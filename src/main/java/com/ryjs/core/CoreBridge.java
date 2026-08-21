package com.ryjs.core;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class CoreBridge {

    private static final String IMPL = "com.ryjs.core.impl.";

    private static ClassLoader loader;
    private static volatile boolean ready;

    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    private CoreBridge() {
    }

    public static synchronized void init(String jarPath) {
        if (ready) {
            return;
        }
        loader = RyjsClassLoader.instance(jarPath);
        ready = true;
    }

    public static boolean isReady() {
        return ready;
    }




    public static Object hookRegistryRegister(byte[] callbackClassBytes) {
        return invokeStatic("HookRegistry", "register", new Class<?>[] { byte[].class }, callbackClassBytes);
    }

    public static boolean hookRegistryHasTarget(String className) {
        return (Boolean) invokeStatic("HookRegistry", "hasTarget", new Class<?>[] { String.class }, className);
    }

    public static String hookRegistryTargets() {
        return String.valueOf(invokeStatic("HookRegistry", "targets", new Class<?>[0]));
    }

    public static int hookRegistryRedirectCount() {
        return (Integer) invokeStatic("HookRegistry", "redirectCount", new Class<?>[0]);
    }

    public static String hookRegistryRedirectMethodNames() {
        return String.valueOf(invokeStatic("HookRegistry", "redirectMethodNames", new Class<?>[0]));
    }


    public static String hookRegistryAuditDiagnostic() {
        Object audit = invokeStatic("HookRegistry", "audit", new Class<?>[0]);
        try {
            Method diag = audit.getClass().getDeclaredMethod("diagnostic");
            diag.setAccessible(true);
            return String.valueOf(diag.invoke(audit));
        } catch (Throwable t) {
            throw new RuntimeException("HookAudit.diagnostic 调用失败", t);
        }
    }


    public static void setInternalRetransform(boolean v) {
        fieldSet("HookTransformer", "internalRetransform", v);
    }


    public static boolean presenceDiagnostic() {
        return (Boolean) fieldGet("PresenceHookTransformer", "DIAGNOSTIC");
    }

    public static ClassFileTransformer newPresenceHookTransformer() {
        return (ClassFileTransformer) newInstance("PresenceHookTransformer");
    }


    public static ClassFileTransformer newTimeStopRenderArgTransformer() {
        return (ClassFileTransformer) newInstance("TimeStopRenderArgTransformer");
    }


    public static void reinjectGuardStart(Instrumentation inst) {
        invokeStatic("ReinjectGuard", "start", new Class<?>[] { Instrumentation.class }, inst);
    }

    public static void classRestoreGuardStart(Instrumentation inst) {
        invokeStatic("ClassRestoreGuard", "start", new Class<?>[] { Instrumentation.class }, inst);
    }


    private static Class<?> loadClass(String simple) throws ClassNotFoundException {
        return Class.forName(IMPL + simple, true, loader);
    }

    private static Method method(String simple, String name, Class<?>[] types) {
        String key = simple + "." + name;
        Method m = METHOD_CACHE.get(key);
        if (m == null) {
            try {
                m = loadClass(simple).getDeclaredMethod(name, types);
                m.setAccessible(true);
                METHOD_CACHE.put(key, m);
            } catch (Throwable t) {
                throw new RuntimeException("方法查找失败: " + key, t);
            }
        }
        return m;
    }

    private static Object invokeStatic(String simple, String name, Class<?>[] types, Object... args) {
        try {
            return method(simple, name, types).invoke(null, args);
        } catch (Throwable t) {
            throw new RuntimeException("调用失败: " + simple + "." + name, t);
        }
    }

    private static Field field(String simple, String name) {
        String key = simple + "." + name;
        Field f = FIELD_CACHE.get(key);
        if (f == null) {
            try {
                f = loadClass(simple).getDeclaredField(name);
                f.setAccessible(true);
                FIELD_CACHE.put(key, f);
            } catch (Throwable t) {
                throw new RuntimeException("字段查找失败: " + key, t);
            }
        }
        return f;
    }

    private static Object fieldGet(String simple, String name) {
        try {
            return field(simple, name).get(null);
        } catch (Throwable t) {
            throw new RuntimeException("读字段失败: " + simple + "." + name, t);
        }
    }

    private static void fieldSet(String simple, String name, Object v) {
        try {
            field(simple, name).set(null, v);
        } catch (Throwable t) {
            throw new RuntimeException("写字段失败: " + simple + "." + name, t);
        }
    }

    private static Object newInstance(String simple) {
        try {
            return loadClass(simple).getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            throw new RuntimeException("实例化失败: " + simple, t);
        }
    }
}
