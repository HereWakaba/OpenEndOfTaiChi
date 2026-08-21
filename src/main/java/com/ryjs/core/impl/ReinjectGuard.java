package com.ryjs.core.impl;


import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public final class ReinjectGuard {

    private static volatile boolean running = false;
    private static volatile Instrumentation inst = null;


    private static final long INITIAL_DELAY_MS = 60000L;

    private static final long PERIOD_MS = 120000L;

    private static boolean useJvmti() {
        return com.ryjs.core.JvmtiBridge.isAvailable();
    }

    private static boolean isModifiable(Class<?> c) {
        if (useJvmti()) {
            return com.ryjs.core.JvmtiBridge.isModifiable(c);
        }
        return inst != null && inst.isModifiableClass(c);
    }

    private ReinjectGuard() {}

    public static void start(Instrumentation instrumentation) {
        if (running) {
            return;
        }
        inst = instrumentation;

        if (inst == null && !com.ryjs.core.JvmtiBridge.isAvailable()) {
            return;
        }
        running = true;
        Thread t = new Thread(ReinjectGuard::loop, "ReinjectGuard");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        try {
            Thread.sleep(INITIAL_DELAY_MS);
        } catch (InterruptedException e) {
            return;
        }
        reinjectAll("");
        while (running) {
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
            reinjectAll("");
        }
    }


    private static void reinjectAll(String why) {
        try {
            Map<String, ClassLoader> injected = HookRegistry.injectedClasses();
            if (injected.isEmpty()) {
                return;
            }

            Map<String, Class<?>> loaded = com.ryjs.core.JvmtiBridge.observedSnapshot();
            if (loaded.isEmpty() && inst != null) {
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    if (c != null && !c.isArray() && !c.isPrimitive()) {
                        loaded.put(c.getName().replace('.', '/'), c);
                    }
                }
            }
            boolean zeroChannel = com.ryjs.core.JvmtiBridge.zeroChannelAvailable();
            List<Class<?>> classes = new ArrayList<>();
            for (String className : injected.keySet()) {
                Class<?> c = loaded.get(className);
                if (c == null || (!zeroChannel && !isModifiable(c))) {
                    continue;
                }
                if (!ancestryLoaded(c, loaded)) {
                    continue;
                }
                classes.add(c);
            }
            if (classes.isEmpty()) {
                return;
            }
            HookTransformer.internalRetransform = true;
            try {
                if (useJvmti()) {
                    com.ryjs.core.JvmtiBridge.retransform(classes.toArray(new Class<?>[0]));
                } else {
                    inst.retransformClasses(classes.toArray(new Class<?>[0]));
                }
                System.out.println("重注入 " + classes.size() + "个目标类");
            } finally {
                HookTransformer.internalRetransform = false;
            }
        } catch (Throwable t) {
            System.err.println("重注入失败: " + t);
        }
    }

    private static boolean ancestryLoaded(Class<?> c, Map<String, Class<?>> loaded) {
        Class<?> cur = c.getSuperclass();
        while (cur != null) {
            if (!loaded.containsKey(cur.getName().replace('.', '/'))) {
                return false;
            }
            cur = cur.getSuperclass();
        }
        for (Class<?> itf : c.getInterfaces()) {
            if (!loaded.containsKey(itf.getName().replace('.', '/'))) {
                return false;
            }
        }
        return true;
    }
}
