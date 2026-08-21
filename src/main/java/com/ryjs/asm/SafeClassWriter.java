package com.ryjs.asm;

import com.ryjs.coremod.Agent.AgentUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SafeClassWriter extends ClassWriter {
    private static volatile Instrumentation globalInstrumentation;
    private final Instrumentation instrumentation;
    private volatile ClassLoader targetLoader;
    private static volatile MethodHandle findLoadedClassHandle;
    private static volatile boolean findLoadedClassUnavailable;
    private Map<String, Class<?>> loadedClassesCache;
    private boolean rescannedThisCall;

    public SafeClassWriter(ClassReader reader, int flags) {
        this(reader, flags, null);
    }

    public SafeClassWriter(int flags) {
        this(flags, null);
    }

    public SafeClassWriter(ClassReader reader, int flags, Instrumentation inst) {
        super(reader, flags);
        this.instrumentation = inst;
    }

    public SafeClassWriter(int flags, Instrumentation inst) {
        super(flags);
        this.instrumentation = inst;
    }

    public static void setInstrumentation(Instrumentation inst) {
        if (inst != null) {
            globalInstrumentation = inst;
        }
    }

    public void setTargetLoader(ClassLoader loader) {
        this.targetLoader = loader;
    }

    private Instrumentation instrumentation() {
        Instrumentation local = this.instrumentation;
        if (local != null) {
            return local;
        }

        Instrumentation global = globalInstrumentation;
        if (global != null) {
            return global;
        }

        try {
            Instrumentation fromProp = AgentUtil.getInst();
            if (fromProp != null) {
                globalInstrumentation = fromProp;
                return fromProp;
            }
        } catch (Throwable var4) {
        }

        return null;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        if (type1 == null || type2 == null) {
            return "java/lang/Object";
        }

        if (type1.equals(type2)) {
            return type1;
        }

        if (!"java/lang/Object".equals(type1) && !"java/lang/Object".equals(type2)) {
            this.rescannedThisCall = false;

            try {
                SafeClassWriter.TypeInfo info1 = this.read(type1);
                SafeClassWriter.TypeInfo info2 = this.read(type2);
                if (info1 == null || info2 == null) {
                    return "java/lang/Object";
                }

                if (info1.isInterface || info2.isInterface) {
                    if (this.isAssignable(type1, type2)) {
                        return type2;
                    }

                    if (this.isAssignable(type2, type1)) {
                        return type1;
                    }

                    return "java/lang/Object";
                }

                Set<String> supers1 = new HashSet<>();
                String cur = type1;

                while (cur != null && !"java/lang/Object".equals(cur)) {
                    supers1.add(cur);
                    SafeClassWriter.TypeInfo info = this.read(cur);
                    if (info == null) {
                        break;
                    }

                    cur = info.superName;
                }

                cur = type2;

                while (cur != null && !"java/lang/Object".equals(cur)) {
                    if (supers1.contains(cur)) {
                        return cur;
                    }

                    SafeClassWriter.TypeInfo info = this.read(cur);
                    if (info == null) {
                        break;
                    }

                    cur = info.superName;
                }
            } catch (Throwable var8) {
            }

            return "java/lang/Object";
        } else {
            return "java/lang/Object";
        }
    }

    private boolean isAssignable(String type, String superType) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.push(type);

        while (!queue.isEmpty()) {
            String c = queue.pop();
            if (c != null && seen.add(c)) {
                if (c.equals(superType)) {
                    return true;
                }

                SafeClassWriter.TypeInfo info = this.read(c);
                if (info != null) {
                    if (info.superName != null) {
                        queue.push(info.superName);
                    }

                    if (info.interfaces != null) {
                        for (String itf : info.interfaces) {
                            queue.push(itf);
                        }
                    }
                }
            }
        }

        return false;
    }

    private SafeClassWriter.TypeInfo read(String internalName) {
        ClassLoader[] loaders = this.loaders();

        for (ClassLoader loader : loaders) {
            if (loader != null) {
                InputStream is = null;

                try {
                    is = loader.getResourceAsStream(internalName + ".class");
                    if (is != null) {
                        ClassReader cr = new ClassReader(is);
                        boolean isInterface = (cr.getAccess() & 512) != 0;
                        return new SafeClassWriter.TypeInfo(cr.getSuperName(), cr.getInterfaces(), isInterface);
                    }
                } catch (Throwable var21) {
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (Throwable var20) {
                        }
                    }
                }
            }
        }

        String binaryName = internalName.replace('/', '.');

        for (ClassLoader loader : loaders) {
            if (loader != null) {
                Class<?> c = findLoadedClass(loader, binaryName);
                if (c != null) {
                    SafeClassWriter.TypeInfo info = fromClass(c);
                    if (info != null) {
                        return info;
                    }
                }
            }
        }

        Class<?> c = this.findInLoadedClassesMap(binaryName);
        if (c != null) {
            SafeClassWriter.TypeInfo info = fromClass(c);
            if (info != null) {
                return info;
            }
        }

        return null;
    }

    private static SafeClassWriter.TypeInfo fromClass(Class<?> c) {
        try {
            String superName = c.getSuperclass() == null ? null : c.getSuperclass().getName().replace('.', '/');
            Class<?>[] itfs = c.getInterfaces();
            String[] interfaces = new String[itfs.length];

            for (int i = 0; i < itfs.length; i++) {
                interfaces[i] = itfs[i].getName().replace('.', '/');
            }

            return new SafeClassWriter.TypeInfo(superName, interfaces, c.isInterface());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> findLoadedClass(ClassLoader loader, String binaryName) {
        if (findLoadedClassUnavailable) {
            return null;
        }

        MethodHandle mh = findLoadedClassHandle;
        if (mh == null) {
            try {
                Method m = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
                mh = AgentUtil.trustedLookup().unreflect(m);
                findLoadedClassHandle = mh;
            } catch (Throwable t) {
                findLoadedClassUnavailable = true;
                return null;
            }
        }

        try {
            return (Class<?>) mh.invoke(loader, binaryName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Class<?> findInLoadedClassesMap(String binaryName) {
        Instrumentation inst = this.instrumentation();
        if (inst == null) {
            return null;
        }

        if (this.loadedClassesCache == null) {
            this.rebuildLoadedClassesCache(inst);
        }

        Map<String, Class<?>> cache = this.loadedClassesCache;
        if (cache != null) {
            Class<?> c = cache.get(binaryName);
            if (c != null) {
                return c;
            }
        }

        if (!this.rescannedThisCall) {
            this.rescannedThisCall = true;
            this.rebuildLoadedClassesCache(inst);
            cache = this.loadedClassesCache;
            if (cache != null) {
                Class<?> c = cache.get(binaryName);
                if (c != null) {
                    return c;
                }
            }
        }

        return null;
    }

    private void rebuildLoadedClassesCache(Instrumentation inst) {
        try {
            Class<?>[] all = inst.getAllLoadedClasses();
            Map<String, Class<?>> map = new HashMap<>(all.length * 2);

            for (Class<?> c : all) {
                if (c != null && !c.isArray() && !c.isPrimitive()) {
                    map.putIfAbsent(c.getName(), c);
                }
            }

            this.loadedClassesCache = map;
        } catch (Throwable ignored) {
            if (this.loadedClassesCache == null) {
                this.loadedClassesCache = new HashMap<>();
            }
        }
    }

    private ClassLoader[] loaders() {
        List<ClassLoader> list = new ArrayList<>(4);
        ClassLoader target = this.targetLoader;
        if (target != null) {
            list.add(target);
        }
        list.add(Thread.currentThread().getContextClassLoader());
        list.add(SafeClassWriter.class.getClassLoader());

        try {
            list.add(ClassLoader.getSystemClassLoader());
        } catch (Throwable var3) {
        }

        return list.toArray(new ClassLoader[0]);
    }

    private static final class TypeInfo {
        final String superName;
        final String[] interfaces;
        final boolean isInterface;

        TypeInfo(String superName, String[] interfaces, boolean isInterface) {
            this.superName = superName;
            this.interfaces = interfaces;
            this.isInterface = isInterface;
        }
    }
}


