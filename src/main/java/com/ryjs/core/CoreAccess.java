package com.ryjs.core;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class CoreAccess {

    private CoreAccess() {
    }

    private static final Map<String, MethodHandle> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();


    public static MethodHandle findMethod(Class<?> clazz, String name, Class<?>... argTypes) {
        String key = clazz.getName() + "::" + name + Arrays.toString(argTypes);
        MethodHandle cached = METHOD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int argLen = argTypes.length;
        Method found = null;
        outer:
        for (Class<?> cur = clazz; cur != null; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != argLen) {
                    continue;
                }
                for (int i = 0; i < argLen; i++) {
                    Class<?> provided = argTypes[i];
                    if (provided != null && provided != Object.class && !params[i].isAssignableFrom(provided)) {
                        continue outer;
                    }
                }
                found = m;
                break outer;
            }
        }
        if (found == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + name);
        }
        try {
            MethodHandle mh = com.ryjs.coremod.Agent.AgentUtil.trustedLookup().unreflect(found);
            METHOD_CACHE.put(key, mh);
            return mh;
        } catch (Throwable t) {
            throw new RuntimeException("unreflect " + clazz.getName() + "#" + name, t);
        }
    }


    public static Object invoke(Object target, String name, Object... args) {
        try {
            MethodHandle mh = findMethod(target instanceof Class ? (Class<?>) target : target.getClass(),
                    name, argTypes(args));
            if (target instanceof Class) {
                return mh.invokeWithArguments(args);
            }
            Object[] combined = new Object[args.length + 1];
            combined[0] = target;
            System.arraycopy(args, 0, combined, 1, args.length);
            return mh.invokeWithArguments(combined);
        } catch (Throwable t) {
            return sneakyThrow(t);
        }
    }


    public static Object invokeStatic(Class<?> clazz, String name, Object... args) {
        return invoke(clazz, name, args);
    }


    public static Field findField(Class<?> clazz, String name) {
        String key = clazz.getName() + "::" + name;
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> cur = clazz; cur != null; cur = cur.getSuperclass()) {
            try {
                Field f = cur.getDeclaredField(name);
                FIELD_CACHE.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + name);
    }


    public static Object getField(Object target, String name) {
        try {
            Field f = findField(target instanceof Class ? (Class<?>) target : target.getClass(), name);
            MethodHandle getter = com.ryjs.coremod.Agent.AgentUtil.trustedLookup().unreflectGetter(f);
            return getter.invokeWithArguments(target instanceof Class ? new Object[0] : new Object[] { target });
        } catch (Throwable t) {
            return sneakyThrow(t);
        }
    }


    public static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target instanceof Class ? (Class<?>) target : target.getClass(), name);
            MethodHandle setter = com.ryjs.coremod.Agent.AgentUtil.trustedLookup().unreflectSetter(f);
            if (target instanceof Class) {
                setter.invokeWithArguments(value);
            } else {
                setter.invokeWithArguments(target, value);
            }
        } catch (Throwable t) {
            sneakyThrow(t);
        }
    }


    public static <T> T newInstance(Class<T> clazz, Object... args) {
        try {
            Constructor<?> found = null;
            outer:
            for (Constructor<?> c : clazz.getDeclaredConstructors()) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length != args.length) {
                    continue;
                }
                for (int i = 0; i < args.length; i++) {
                    if (args[i] != null && !params[i].isAssignableFrom(args[i].getClass())) {
                        continue outer;
                    }
                }
                found = c;
                break outer;
            }
            if (found == null) {
                throw new NoSuchMethodError(clazz.getName() + " 无匹配构造器");
            }
            MethodHandle ctor = com.ryjs.coremod.Agent.AgentUtil.trustedLookup().unreflectConstructor(found);
            return (T) ctor.invokeWithArguments(args);
        } catch (Throwable t) {
            return sneakyThrow(t);
        }
    }


    public static void addReads(Module targetModule, Module fromModule) {
        try {
            MethodHandle addReads = com.ryjs.coremod.Agent.AgentUtil.trustedLookup()
                    .unreflect(Module.class.getDeclaredMethod("implAddReads", Module.class));
            addReads.bindTo(targetModule).invoke(fromModule);
        } catch (Throwable t) {
            sneakyThrow(t);
        }
    }

    private static Class<?>[] argTypes(Object[] args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? Object.class : args[i].getClass();
        }
        return types;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
