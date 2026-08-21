package com.ryjs.core;

import java.lang.instrument.ClassFileTransformer;


public final class JvmtiBridge {


    private static volatile ClassFileTransformer[] TRANSFORMERS = new ClassFileTransformer[0];
    private static volatile boolean available;
    private static volatile String LAST_TRANSFORM_ERROR;


    private static final long NATIVE_TOKEN = 0x52A9C0DE5EC0DE11L;


    private static volatile long LAST_DISPATCH_NANO = 0L;
    private static volatile boolean exportTransformOn = false;
    private static final long HEARTBEAT_INITIAL_DELAY_MS = 60000L;
    private static final long HEARTBEAT_PERIOD_MS = 5000L;
    private static final int HEARTBEAT_MISS_THRESHOLD = 2;

    private static void startTransformHeartbeat() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(HEARTBEAT_INITIAL_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
            int misses = 0;
            Class<?> sentinel = null;
            while (true) {
                try {
                    if (!exportTransformOn) {
                        if (sentinel == null) {
                            sentinel = Class.forName("com.ryjs.core.TransformHeartbeat", true,
                                    JvmtiBridge.class.getClassLoader());
                        }
                        long before = LAST_DISPATCH_NANO;
                        int r = nativeRetransform(new Class<?>[] { sentinel });
                        if (r == 0) {
                            Thread.sleep(200);
                            if (LAST_DISPATCH_NANO == before) {
                                misses++;
                                if (misses >= HEARTBEAT_MISS_THRESHOLD) {
                                    nativeSetExportTransform(true);
                                    exportTransformOn = true;
                                    System.err.println("cb 失联transform 通道已自动接管");
                                }
                            } else {
                                misses = 0;
                            }
                        }

                    }
                } catch (Throwable ignored) {

                }
                try {
                    Thread.sleep(HEARTBEAT_PERIOD_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "JvmtiTransformHeartbeat");
        t.setDaemon(true);
        t.start();
    }

    private static final java.util.Map<String, Class<?>> OBSERVED = new java.util.concurrent.ConcurrentHashMap<>();

    static {

        try {
            loadZeroJvmtiSim("first");
        } catch (Throwable t) {
            System.err.println("DevMode sim failed: " + t);
        }
        try {
            java.io.InputStream in = JvmtiBridge.class.getResourceAsStream("/RyjsAgent.dll");
            if (in != null) {
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ryjs_agent", ".dll");
                tmp.toFile().deleteOnExit();
                java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                in.close();
                System.load(tmp.toAbsolutePath().toString());
            } else {
                System.loadLibrary("RyjsAgent");
            }
            available = nativeBootstrap();
            if (available) {
                nativeSetBridgeClass(JvmtiBridge.class);

                try {
                    nativeSetClassObserver(true);
                } catch (Throwable ignored) {

                }

                startTransformHeartbeat();

                try {
                    nativeSetOsBlock(com.ryjs.agent.DefenseConfig.hrSystemExit(),
                            com.ryjs.agent.DefenseConfig.hrExec(),
                            com.ryjs.agent.DefenseConfig.hrSystemLoad());
                } catch (Throwable ignored) {

                }

                try {
                    nativeSetFullSeal(com.ryjs.agent.DefenseConfig.fullBlock(), NATIVE_TOKEN);
                } catch (Throwable ignored) {

                }

                try {
                    nativeSetBreak(com.ryjs.agent.DefenseConfig.jvmtiBreak(), NATIVE_TOKEN);
                } catch (Throwable ignored) {

                }

                try {
                    if (com.ryjs.agent.DefenseConfig.jvmtiBlast()) {
                        nativeBlastEnv(true, NATIVE_TOKEN);
                    }
                } catch (Throwable ignored) {
                }
            }
            System.out.println("RyjsAgent.dll 已加载，jvmti 可用=" + available);
            try {
                loadZeroJvmtiSim("late");
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            System.err.println("RyjsAgent.dll 加载失败: " + t);
        }
    }

    private JvmtiBridge() {
    }

    private static void loadZeroJvmtiSim(String mode) {
        try {
            boolean first = com.ryjs.agent.DefenseConfig.zeroJvmtiSim();
            boolean late = com.ryjs.agent.DefenseConfig.zeroJvmtiLate();
            if (("first".equals(mode) && !first) || ("late".equals(mode) && !late)) {
                return;
            }
            java.io.InputStream in = JvmtiBridge.class.getResourceAsStream("/ZeroJvmti.dll");
            if (in == null) {
                System.err.println("DevMode: /ZeroJvmti.dll missing in jar - sim skipped");
                return;
            }
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ryjs_zerojvmti", ".dll");
            tmp.toFile().deleteOnExit();
            java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            in.close();
            int r = com.ryjs.core.ZeroJvmtiSim.runSim(tmp.toAbsolutePath().toString(),
                    com.ryjs.agent.DefenseConfig.zeroJvmtiKill());
            System.out.println("DevMode: ZeroJvmti " + mode + "-mover sim init=" + r
                    + " (armed-before=" + first + " armed-after=" + late + ")");
        } catch (Throwable ignored) {
            // DevMode 失败不影响正常启动
        }
    }

    // ---- native（RyjsAgent.dll） ----
    private static native boolean nativeBootstrap();
    private static native void nativeSetBridgeClass(Class<?> bridge);
    private static native void nativeSetFullSeal(boolean on, long token);
    private static native void nativeSetBreak(boolean on, long token);
    private static native void nativeBlastEnv(boolean on, long token);
    private static native void nativeRestoreEnv(long token);
    private static native Class<?>[] nativeLoadedClasses();
    private static native String[] nativeLoadedNames();
    private static native int[] nativeProbeRetransform(String[] names);
    private static native int nativeRedefineByName(String name, byte[] bytes);
    private static native int nativeRedefineClass(Class<?> target, byte[] bytes);
    private static native int nativeRetransform(Class<?>[] classes);
    private static native boolean nativeIsModifiable(Class<?> target);
    private static native Class<?> nativeDefineClass(String name, ClassLoader loader, byte[] bytes);
    private static native boolean nativeZeroChannel();

    private static native Class<?>[] nativeToolGetLoadedClasses();
    private static native boolean nativeToolIsModifiableClass(Class<?> target);
    private static native String nativeToolGetClassInternalName(Class<?> target);
    private static native Class<?>[] nativeToolGetImplementedInterfaces(Class<?> target);
    private static native Class<?>[] nativeToolGetClassLoaderClasses(ClassLoader loader);
    private static native int nativeToolRetransformClasses(Class<?>[] classes);
    private static native int nativeToolRedefineClasses(Class<?> target, byte[] bytes);
    private static native String nativeToolGetErrorName(int errCode);
    private static native void nativeSetClassObserver(boolean on);
    private static native void nativeSetExportTransform(boolean on);
    private static native void nativeSetOsBlock(boolean exitBlock, boolean spawnBlock, boolean loadBlock);


    public static boolean zeroChannelAvailable() {
        return available && nativeZeroChannel();
    }


    @SuppressWarnings("unused")
    public static void classLoaded(String name, Class<?> clazz) {
        if (name != null && clazz != null) {
            OBSERVED.put(name, clazz);
        }
    }


    public static Class<?> observedClass(String internalName) {
        return OBSERVED.get(internalName);
    }


    public static java.util.Map<String, Class<?>> observedSnapshot() {
        return new java.util.HashMap<>(OBSERVED);
    }

    private static final String RESTORE_TRANSFORMER_NAME = "com.ryjs.core.impl.ClassRestoreGuard$RestoreTransformer";

    @SuppressWarnings("unused") // called from native
    public static byte[] dispatchTransform(ClassLoader loader, String name, byte[] data) {
        LAST_DISPATCH_NANO = System.nanoTime(); // 心跳信号：cb/导出通道任一触发 transform 即刷新
        byte[] current = data;
        ClassFileTransformer[] ts = TRANSFORMERS;
        boolean ryjsOnly = name != null && name.startsWith("com/ryjs/");
        for (int i = 0; i < ts.length; i++) {
            if (ryjsOnly && !RESTORE_TRANSFORMER_NAME.equals(ts[i].getClass().getName())) {
                continue;
            }
            try {
                byte[] out = ts[i].transform(loader, name, null, null, current);
                if (out != null) {
                    current = out;
                }
            } catch (Throwable t) {
                if (ryjsOnly) {

                    continue;
                }

                if (t != null) {
                    String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
                    if (msg != null && !msg.equals(LAST_TRANSFORM_ERROR)) {
                        LAST_TRANSFORM_ERROR = msg;
                        System.err.println("transformer 异常 [" + (name == null ? "?" : name) + "] " + msg);
                    }
                }
            }
        }
        return current == data ? null : current;
    }



    public static void addTransformer(ClassFileTransformer t) {
        ClassFileTransformer[] cur = TRANSFORMERS;
        ClassFileTransformer[] next = new ClassFileTransformer[cur.length + 1];
        System.arraycopy(cur, 0, next, 0, cur.length);
        next[cur.length] = t;
        TRANSFORMERS = next;
    }

    public static boolean isAvailable() {
        return available;
    }


    public static Class<?>[] loadedClasses() {
        Class<?>[] r = toolLoadedClasses();
        if (r != null) return r;
        return nativeLoadedClasses();
    }


    public static String[] loadedNames() {
        Class<?>[] all = toolLoadedClasses();
        if (all != null) {
            java.util.ArrayList<String> acc = new java.util.ArrayList<>(all.length);
            for (Class<?> c : all) {
                if (c == null) continue;
                String n = c.getName().replace('.', '/');
                if (n.length() > 0 && n.charAt(0) != '[') acc.add(n);
            }
            return acc.toArray(new String[0]);
        }
        String[] raw = nativeLoadedNames();
        if (raw == null) {
            return new String[0];
        }
        int n = 0;
        for (String s : raw) {
            if (s != null) n++;
        }
        if (n == raw.length) {
            return raw;
        }
        String[] out = new String[n];
        int i = 0;
        for (String s : raw) {
            if (s != null) out[i++] = s;
        }
        return out;
    }

    public static int[] probeRetransform(String[] names) {
        Class<?>[] all = toolLoadedClasses();
        if (all != null && names != null) {
            java.util.HashMap<String, Class<?>> byName = new java.util.HashMap<>();
            for (Class<?> c : all) {
                if (c != null) byName.put(c.getName().replace('.', '/'), c);
            }
            int ok = 0, skipped = 0;
            java.util.ArrayList<Class<?>> targets = new java.util.ArrayList<>();
            for (String n : names) {
                if (n == null) { skipped++; continue; }
                Class<?> c = byName.get(n);
                if (c == null || !toolIsModifiable(c)) { skipped++; continue; }
                targets.add(c);
            }
            if (!targets.isEmpty()) {
                int r = toolRetransform(targets.toArray(new Class<?>[0]));
                if (r == 0) ok = targets.size();
                else skipped += targets.size();
            }
            return new int[] { ok, skipped };
        }
        int[] r = nativeProbeRetransform(names);
        return r != null ? r : new int[] { 0, 0 };
    }

    public static int redefineByName(String name, byte[] bytes) {
        if (name == null || bytes == null) return -1;
        Class<?> c = observedClass(name);
        if (c == null) {
            Class<?>[] all = toolLoadedClasses();
            if (all != null) {
                for (Class<?> x : all) {
                    if (x != null && name.equals(x.getName().replace('.', '/'))) { c = x; break; }
                }
            }
        }
        if (c == null) return -2;
        if (!toolIsModifiable(c)) return -5;
        int r = toolRedefine(c, bytes);
        if (r != -1) return r;
        return nativeRedefineByName(name, bytes);
    }

    public static void restoreEnv() {
        nativeRestoreEnv(NATIVE_TOKEN);
        if (exportTransformOn) {
            try {
                nativeSetExportTransform(false);
            } catch (Throwable ignored) {
            }
            exportTransformOn = false;
        }
        if (available) return;
        try {
            available = nativeBootstrap();
        } catch (Throwable ignored) {
        }
    }

    public static int redefineClass(Class<?> target, byte[] bytes) {
        int r = toolRedefine(target, bytes);
        if (r != -1) return r;
        return nativeRedefineClass(target, bytes);
    }


    public static int retransform(Class<?>... classes) {
        int r = toolRetransform(classes);
        if (r != -1) return r;
        return nativeRetransform(classes);
    }


    public static boolean isModifiable(Class<?> target) {
        return toolIsModifiable(target);
    }

    public static Class<?> defineClass(String name, ClassLoader loader, byte[] bytes) {
        return nativeDefineClass(name, loader, bytes);
    }


    public static Class<?>[] toolLoadedClasses() {
        try {
            return nativeToolGetLoadedClasses();
        } catch (Throwable t) {
            return null;
        }
    }


    public static boolean toolIsModifiable(Class<?> target) {
        try {
            return nativeToolIsModifiableClass(target);
        } catch (Throwable t) {
        }
        try {
            return nativeIsModifiable(target);
        } catch (Throwable t) {
            return false;
        }
    }


    public static String toolClassInternalName(Class<?> target) {
        try {
            String s = nativeToolGetClassInternalName(target);
            if (s != null) return s;
        } catch (Throwable t) {
            /* fall */
        }
        return target != null ? target.getName().replace('.', '/') : null;
    }

    public static Class<?>[] toolImplementedInterfaces(Class<?> target) {
        try {
            Class<?>[] r = nativeToolGetImplementedInterfaces(target);
            if (r != null) return r;
        } catch (Throwable t) {
            /* fall */
        }
        return target != null ? target.getInterfaces() : null;
    }


    public static Class<?>[] toolLoaderClasses(ClassLoader loader) {
        try {
            Class<?>[] r = nativeToolGetClassLoaderClasses(loader);
            if (r != null) return r;
        } catch (Throwable t) {
            /* fall */
        }
        if (loader == null) return new Class<?>[0];
        java.util.ArrayList<Class<?>> acc = new java.util.ArrayList<>();
        for (Class<?> c : OBSERVED.values()) {
            if (c != null && c.getClassLoader() == loader) acc.add(c);
        }
        return acc.toArray(new Class<?>[0]);
    }


    public static int toolRetransform(Class<?>... classes) {
        try {
            return nativeToolRetransformClasses(classes);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int toolRedefine(Class<?> target, byte[] bytes) {
        try {
            return nativeToolRedefineClasses(target, bytes);
        } catch (Throwable t) {
            return -1;
        }
    }


    public static String toolErrorName(int errCode) {
        try {
            return nativeToolGetErrorName(errCode);
        } catch (Throwable t) {
            return null;
        }
    }
}
