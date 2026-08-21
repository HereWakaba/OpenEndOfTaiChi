package com.ryjs.coremod.Agent;

import com.ryjs.reflection.Registration;

import java.io.ByteArrayOutputStream;

import com.ryjs.coremod.Agent.transformers.AllReturn;
import com.ryjs.coremod.Agent.transformers.RenderBlockerTransformer;

import java.nio.file.Path;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import sun.misc.Unsafe;
import sun.reflect.ReflectionFactory;

public class AgentUtil {

 static final Unsafe UNSAFE;


    static final MethodHandles.Lookup LOOKUP;

 static final MethodHandle ClassLoader_defineClass1;

    static {
        try {
            // --- Unsafe via private constructor ---
            Constructor<Unsafe> unsafeCtor = Unsafe.class.getDeclaredConstructor();
            unsafeCtor.setAccessible(true);
            UNSAFE = unsafeCtor.newInstance();

            // --- Trusted Lookup via ReflectionFactory serialisation trick ---
            Constructor<MethodHandles.Lookup> lookupCtor =
                    (Constructor<MethodHandles.Lookup>) ReflectionFactory.getReflectionFactory()
                            .newConstructorForSerialization(
                                    MethodHandles.Lookup.class,
                                    MethodHandles.Lookup.class.getDeclaredConstructor(
                                            Class.class, Class.class, int.class));
            LOOKUP = lookupCtor.newInstance(Object.class, null, -1);

            // --- ClassLoader#defineClass1 method handle ---
            ClassLoader_defineClass1 = LOOKUP.findStatic(
                    ClassLoader.class,
                    "defineClass1",
                    MethodType.methodType(
                            Class.class,
                            ClassLoader.class, String.class,
                            byte[].class, int.class, int.class,
                            ProtectionDomain.class, String.class));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    // ---- Byte I/O -----------------------------------------------------------

    private static byte[] readAllBytes(InputStream is) {
        if (is == null) return null;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = is.read(chunk, 0, chunk.length)) != -1) buf.write(chunk, 0, n);
            buf.flush();
            return buf.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }


    public static String getJarPath(Class<?> clazz) {
        try {
            String resourcePath = clazz.getName().replace('.', '/') + ".class";
            ClassLoader[] loaders = {
                    clazz.getClassLoader(),
                    Thread.currentThread().getContextClassLoader(),
                    ClassLoader.getSystemClassLoader(),
                    ClassLoader.getPlatformClassLoader()
            };
            for (ClassLoader loader : loaders) {
                if (loader == null) continue;
                URL url = loader.getResource(resourcePath);
                if (url != null) {
                    String decoded = URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8);
                    return new File(decoded.substring(0, decoded.lastIndexOf(".jar") + 4))
                            .getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {}
        try {
            String decoded = URLDecoder.decode(
                    clazz.getProtectionDomain().getCodeSource().getLocation().getPath(),
                    StandardCharsets.UTF_8);
            return new File(decoded.substring(0, decoded.lastIndexOf(".jar") + 4))
                    .getAbsolutePath();
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] getClassBytes(String jarPath, String className) {
        try {
            JarFile jarFile = new JarFile(jarPath);
            try {
                String entry = className.replace('.', '/') + ".class";
                InputStream is = jarFile.getInputStream(jarFile.getJarEntry(entry));
                try {
                    return readAllBytes(is);
                } finally {
                    if (is != null) is.close();
                }
            } finally {
                jarFile.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }


    public static MethodHandles.Lookup trustedLookup() {
        return LOOKUP;
    }

    private static volatile com.ryjs.core.RyjsCore hiddenCore;

    public static com.ryjs.core.RyjsCore hiddenCore() {
        return hiddenCore;
    }

    private static void bootstrapHiddenCore() {
        try {

            String jarPath = jarPathOf(AgentUtil.class);
            if (jarPath == null || jarPath.isEmpty()) {
                System.out.println("引导跳过：无法定位自身 jar（dev classes 环境预期内）");
                return;
            }
            byte[] bytes = com.ryjs.core.RyjsClassLoader.instance(jarPath).readClassBytes("com.ryjs.core.RyjsCoreImpl");
            if (bytes == null) {
                System.out.println("引导跳过：读取实现字节码失败: " + jarPath);
                return;
            }

            Class<?> hidden = defineHiddenDirect(bytes, com.ryjs.core.RyjsClassLoader.instance(jarPath));
            if (hidden == null) {
                System.out.println("隐藏核心不可用（两条定义路径均失败，需官方原版 JDK 或兼容内部 API 的环境）");
                return;
            }
            MethodHandle ctor = LOOKUP.findConstructor(hidden, MethodType.methodType(void.class));
            hiddenCore = (com.ryjs.core.RyjsCore) ctor.invoke();
            System.out.println("隐藏核心就绪: " + hiddenCore.describe());
            com.ryjs.core.CoreBridge.init(jarPath);
            try {
                Class<?> probe = com.ryjs.core.RyjsClassLoader.instance(jarPath)
                        .loadClass("com.ryjs.core.impl.CoreProbe");
                Object inst = probe.getDeclaredConstructor().newInstance();
                System.out.println("自定义加载器就绪: " + probe.getMethod("describe").invoke(inst));
            } catch (Throwable t) {
                System.out.println("自定义加载器验证失败（不影响启动）: " + t);
            }
        } catch (Throwable t) {
            hiddenCore = null;
            System.out.println("引导失败（核心逻辑降级不可用）: " + t);
        }
    }

    private static Class<?> defineHiddenDirect(byte[] bytes, ClassLoader loader) {
        try {
            MethodHandle def0 = LOOKUP.findStatic(ClassLoader.class, "defineClass0",
                    MethodType.methodType(Class.class, ClassLoader.class, Class.class, String.class,
                            byte[].class, int.class, int.class, ProtectionDomain.class, boolean.class, int.class, Object.class));
            return (Class<?>) def0.invoke(
                    loader,
                    com.ryjs.core.RyjsCoreHost.class,
                    "com.ryjs.core.RyjsCoreImpl",
                    bytes, 0, bytes.length,
                    (ProtectionDomain) null,
                    true, // initialize
                    3,    // flags: HIDDEN(2) | NESTMATE(1)
                    (Object) null);
        } catch (Throwable t1) {
            System.out.println("defineClass0 路径失败: " + t1);
        }
        try {
            Class<?> optionClass = Class.forName("java.lang.invoke.MethodHandles$Lookup$ClassOption");
            Method defineHidden = MethodHandles.Lookup.class.getMethod("defineHiddenClass",
                    byte[].class, boolean.class, java.lang.reflect.Array.newInstance(optionClass, 0).getClass());
            if (!Class.class.equals(defineHidden.getReturnType())) {
                return null;
            }
            MethodHandles.Lookup hostLookup = LOOKUP.in(com.ryjs.core.RyjsCoreHost.class);
            Object nestmate = Enum.valueOf((Class) optionClass, "NESTMATE");
            return (Class<?>) defineHidden.invoke(hostLookup, bytes, false, new Object[] { nestmate });
        } catch (Throwable t2) {
            System.out.println("defineHiddenClass 路径失败: " + t2);
            return null;
        }
    }

    private static Class<?> defineClass(ClassLoader loader, String name, byte[] buf) {
        try {
            return (Class<?>) ClassLoader_defineClass1
                    .invoke(loader, name, buf, 0, buf.length, (Object) null, (Object) null);
        } catch (Throwable t) {
            try {
                return Class.forName(name);
            } catch (Exception e) {
                t.addSuppressed(e);
                return sneakyThrow(t);
            }
        }
    }

    private static void defineClassInPackage(ClassLoader loader, Class<?> lookup, String name) {
        defineClass(loader, name, getClassBytes(getJarPath(lookup), name));
    }

    private static Object reflectInvoke(Object target, String methodName, Object... args) {
        Class<?> clazz = target instanceof Class ? (Class<?>) target : target.getClass();
        int argLen = args.length;
        Method found = null;
        outer:
        for (Class<?> cur = clazz; cur != null; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != argLen) continue;
                for (int i = 0; i < argLen; i++) {
                    if (args[i] != null && !params[i].isAssignableFrom(args[i].getClass()))
                        continue outer;
                }
                found = m;
                break outer;
            }
        }
        if (found == null) sneakyThrow(new NoSuchMethodException(clazz.getName() + "#" + methodName));
        try {
            MethodHandle mh = LOOKUP.unreflect(found);
            if (target instanceof Class) {
                return mh.invokeWithArguments(args);
            } else {
                Object[] combined = new Object[argLen + 1];
                combined[0] = target;
                System.arraycopy(args, 0, combined, 1, argLen);
                return mh.invokeWithArguments(combined);
            }
        } catch (Throwable t) {
            return sneakyThrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }


    public static volatile Instrumentation INST;
    private static volatile boolean spiFiltered = false;
    private static volatile boolean launchPluginsFiltered = false;
    private static void addTransformer(java.lang.instrument.ClassFileTransformer t, boolean canRetransform) {
        System.out.println("addTransformer: " + t.getClass().getName()
                + " (jvmti=" + com.ryjs.core.JvmtiBridge.isAvailable() + ")");
        if (com.ryjs.core.JvmtiBridge.isAvailable()) {
            com.ryjs.core.JvmtiBridge.addTransformer(t);
        } else {
            java.lang.instrument.Instrumentation inst = INST;
            if (inst != null) {
                inst.addTransformer(t, canRetransform);
            }
        }
    }

    private static void retransformClasses(Class<?>... classes) throws java.lang.instrument.UnmodifiableClassException {
        if (com.ryjs.core.JvmtiBridge.isAvailable()) {
            com.ryjs.core.JvmtiBridge.retransform(classes);
        } else {
            java.lang.instrument.Instrumentation inst = INST;
            if (inst != null && classes.length > 0) {
                inst.retransformClasses(classes);
            }
        }
    }

    private static Class<?>[] loadedClasses() {
        java.util.Map<String, Class<?>> observed = com.ryjs.core.JvmtiBridge.observedSnapshot();
        if (!observed.isEmpty()) {
            return observed.values().toArray(new Class<?>[0]);
        }
        java.lang.instrument.Instrumentation inst = INST;
        return inst != null ? inst.getAllLoadedClasses() : new Class<?>[0];
    }

    private static boolean isModifiableClass(Class<?> c) {
        if (com.ryjs.core.JvmtiBridge.isAvailable()) {
            return com.ryjs.core.JvmtiBridge.isModifiable(c);
        }
        java.lang.instrument.Instrumentation inst = INST;
        return inst != null && inst.isModifiableClass(c);
    }

    public static synchronized void start() {
        if (INST != null) return;
        boolean skipAttach = com.ryjs.agent.DefenseConfig.jvmtiBlast();
        if (skipAttach) {
            System.out.println("跳过attach");
        }
        try {

            bootstrapHiddenCore();
            if (!skipAttach) {
            Class<?> agentClass = AgtCallback.class;
            // 1. Build a minimal agent JAR
            Manifest manifest = new Manifest();
            Attributes mainAttrs = manifest.getMainAttributes();
            mainAttrs.put(Name.MANIFEST_VERSION,                       "1.0");
            mainAttrs.put(new Name("Launcher-Agent-Class"), agentClass.getName());
            mainAttrs.put(new Name("Can-Redefine-Classes"),    "true");
            mainAttrs.put(new Name("Can-Retransform-Classes"), "true");
            mainAttrs.put(new Name("Can-Set-Native-Method-Prefix"), "true");
            Path jar = Files.createTempFile("agentutil", ".jar");
            jar.toFile().deleteOnExit();
            JarOutputStream jos = new JarOutputStream(
                    new FileOutputStream(jar.toAbsolutePath().toString()), manifest);
            try {
                jos.flush();
            } finally {
                jos.close();
            }
            defineClassInPackage(
                    ClassLoader.getSystemClassLoader(),
                    AgentUtil.class,
                    agentClass.getName());
            reflectInvoke(
                    Class.forName("sun.instrument.InstrumentationImpl"),
                    "loadAgent0",
                    jar.toAbsolutePath().toString());

            long deadline = System.currentTimeMillis() + 1000L;
            while (INST == null) {
                if (System.currentTimeMillis() > deadline)
                    throw new TimeoutException("AgentUtil: timed out waiting for agent callback");
            }
            }
            
            AllReturn.preload();

            if (com.ryjs.agent.DefenseConfig.interceptAllReturn()) {
                addTransformer(new AllReturn(), true);
            }

            if (!com.ryjs.agent.DefenseConfig.compatMode()) {
                addTransformer(new RenderBlockerTransformer(), true);
            }

            try {
                if (com.ryjs.agent.DefenseConfig.proxyShell() || com.ryjs.agent.DefenseConfig.earlyDisplay()) {
                    com.ryjs.coremod.Agent.transformers.DisplayWindowTransformer.preload();
                    com.ryjs.reflection.client.earlydisplay.ShellEarlyRenderer.preload();
                    addTransformer(new com.ryjs.coremod.Agent.transformers.DisplayWindowTransformer(), true);
                    retransformIfLoaded("net.minecraftforge.fml.earlydisplay.DisplayWindow");
                    com.ryjs.coremod.Agent.EarlyWindowBridge.start();
                }
            } catch (Throwable t) {
                System.err.println("[ShellEarly] 注册 DisplayWindowTransformer 失败: " + t);
            }

            installPhantomHooks();

            try {
                if (com.ryjs.agent.DefenseConfig.interceptCoremod() || com.ryjs.agent.DefenseConfig.proxyShell()) {
                    com.ryjs.coremod.Agent.transformers.CoremodNeuterTransformer.preload();
                    addTransformer(new com.ryjs.coremod.Agent.transformers.CoremodNeuterTransformer(), true);
                    System.out.println("已注册 CoremodNeuterTransformer");
                }
            } catch (Throwable t) {
                System.err.println("注册 CoremodNeuterTransformer 失败: " + t);
            }
            // 全部拦截模式（保留 init/clinit）：薅自 Diamond 的精细中和。CleanMethod 清空 /mods/ 非白名单 static void/float/boolean；
            // AllReturnTransformer 强制清空危险方法(exit/反射/Unsafe/Thread 等，正好卸掉 pig2 的 killOtherXform/stopBadThreads) + static/非static 条件返回。
            try {
                if (com.ryjs.agent.DefenseConfig.fullCleanMethod()) {
                    com.ryjs.agent.transformers.CleanMethodClassFileTransformer.preload();
                    addTransformer(new com.ryjs.agent.transformers.CleanMethodClassFileTransformer(), true);
                    System.out.println("已注册 CleanMethodClassFileTransformer");
                }
                if (com.ryjs.agent.DefenseConfig.fullCoexistAllReturn()) {
                    com.ryjs.agent.transformers.AllReturnTransformer.preload();
                    addTransformer(new com.ryjs.agent.transformers.AllReturnTransformer(), true);
                    System.out.println("已注册 AllReturnTransformer");
                }
                if (com.ryjs.agent.DefenseConfig.fullAntiExit()) {
                    com.ryjs.agent.transformers.AntiExitTransformer.preload();
                    addTransformer(new com.ryjs.agent.transformers.AntiExitTransformer(), true);
                    System.out.println("已注册 AntiExitTransformer");
                }
            } catch (Throwable t) {
                System.err.println("注册全部拦截模式transformer失败: " + t);
            }

            System.out.println("Transformers registered. AllReturn CL=" + AllReturn.class.getClassLoader()
                    + ", RenderBlocker CL=" + RenderBlockerTransformer.class.getClassLoader());

            // Uncomment to enable the global watch/redefine transformer pipeline:
           // INST.addTransformer(new Tsf(), true);
        } catch (Throwable t) {
            System.err.print("Agent load failed: ");
            t.printStackTrace();
            System.exit(0);
        }
    }

    public static Instrumentation getInst() {
        if (INST == null) start();
        return INST;
    }

    public static void filterTransformationServices() {
        if (!com.ryjs.agent.DefenseConfig.interceptCoremod() && !com.ryjs.agent.DefenseConfig.proxyShell()) {
            return;
        }
        if (spiFiltered) {
            return; // 已成功过滤过，幂等返回
        }
        try {
            Class<?> launcherClass = Class.forName("cpw.mods.modlauncher.Launcher");
            java.lang.reflect.Field instanceField = launcherClass.getDeclaredField("INSTANCE");
            Object launcher = LOOKUP.unreflectGetter(instanceField).invokeWithArguments();
            if (launcher == null) {
                System.out.println("Launcher.INSTANCE 为空，跳过服务过滤");
                return;
            }
            java.lang.reflect.Field handlerField = launcherClass.getDeclaredField("transformationServicesHandler");
            Object handler = LOOKUP.unreflectGetter(handlerField).invokeWithArguments(launcher);
            java.lang.reflect.Field serviceLookupField = handler.getClass().getDeclaredField("serviceLookup");
            @SuppressWarnings("unchecked")
            Map<String, Object> serviceLookup = (Map<String, Object>) LOOKUP.unreflectGetter(serviceLookupField).invokeWithArguments(handler);
            if (serviceLookup == null || serviceLookup.isEmpty()) {
                System.out.println("serviceLookup 此刻为空（IWP 时序过早？），安全跳过");
                return;
            }
            System.out.println("过滤前服务: " + serviceLookup.keySet());
            java.util.LinkedHashMap<String, Object> filtered = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> e : serviceLookup.entrySet()) {
                if (isWhitelistedService(e.getKey(), e.getValue())) {
                    filtered.put(e.getKey(), e.getValue());
                } else {
                    System.out.println("泛杀服务: " + e.getKey());
                }
            }
            if (!filtered.containsKey("fml")) {
                System.out.println("警告：过滤后缺少 fml，放弃过滤以防崩溃");
                return;
            }
            LOOKUP.unreflectSetter(serviceLookupField).invokeWithArguments(handler, filtered);
            spiFiltered = true;
            System.out.println("过滤后服务: " + filtered.keySet());
        } catch (Throwable t) {
            System.err.println("服务过滤失败（已忽略，不影响启动）: " + t);
        }
    }


    public static void filterLaunchPlugins() {
        if (!com.ryjs.agent.DefenseConfig.interceptCoremod() && !com.ryjs.agent.DefenseConfig.proxyShell()) {
            return;
        }
        if (launchPluginsFiltered) {
            return;
        }
        try {
            Class<?> launcherClass = Class.forName("cpw.mods.modlauncher.Launcher");
            java.lang.reflect.Field instanceField = launcherClass.getDeclaredField("INSTANCE");
            Object launcher = LOOKUP.unreflectGetter(instanceField).invokeWithArguments();
            if (launcher == null) {
                System.out.println("Launcher.INSTANCE 为空，跳过 LaunchPlugins 过滤");
                return;
            }
            java.lang.reflect.Field lpField = launcherClass.getDeclaredField("launchPlugins");
            Object handler = LOOKUP.unreflectGetter(lpField).invokeWithArguments(launcher); // LaunchPluginHandler
            java.lang.reflect.Field pluginsField = handler.getClass().getDeclaredField("plugins");
            @SuppressWarnings("unchecked")
            Map<String, Object> plugins = (Map<String, Object>) LOOKUP.unreflectGetter(pluginsField).invokeWithArguments(handler);
            if (plugins == null || plugins.isEmpty()) {
                System.out.println("launchPlugins 为空，跳过");
                return;
            }
            System.out.println("LaunchPlugins 过滤前: " + plugins.keySet());
            java.util.LinkedHashMap<String, Object> kept = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> e : plugins.entrySet()) {
                if (isWhitelistedLaunchPlugin(e.getKey(), e.getValue())) {
                    kept.put(e.getKey(), e.getValue());
                } else {
                    Object p = e.getValue();
                    System.out.println("泛杀 LaunchPlugin: " + e.getKey() + " (" + (p == null ? "null" : p.getClass().getName()) + ")");
                }
            }
            LOOKUP.unreflectSetter(pluginsField).invokeWithArguments(handler, kept);
            launchPluginsFiltered = true;
            System.out.println("LaunchPlugins 过滤后: " + kept.keySet());
        } catch (Throwable t) {
            System.err.println("LaunchPlugins 过滤失败（已忽略）: " + t);
        }
    }

private static boolean isWhitelistedLaunchPlugin(String name, Object plugin) {
        if (plugin == null) {
            return true;
        }
        String cn = plugin.getClass().getName();
        if ("mixin".equals(name) || cn.startsWith("org.spongepowered.asm.")) {
            return true;
            //这个有问题没修一去掉就炸所以删注解
        }
        if (cn.startsWith("com.ryjs.")) {
            return true;
            // 自身
        }
        if (com.ryjs.agent.CompatWhitelist.isWhitelistedClass(cn)
                || com.ryjs.agent.CompatWhitelist.isWhitelistedJar(jarPathOf(plugin.getClass()))) {
            return true; // 兼容白名单
        }
        return !isFromModsFolder(plugin.getClass());
    }

    private static boolean isWhitelistedService(String name, Object decorator) {
        if ("fml".equals(name)) {
            return true;
        }
        if ("mixin".equals(name)) {
            if (com.ryjs.agent.DefenseConfig.interceptMixin()) {
                System.out.println("mixin为基础设施，强制保留（移除会导致 MixinLaunchPluginLegacy NPE）");
            }
            return true;
        }
        Object svc = unwrapService(decorator);
        if (svc == null) {
            return true;
            // 取不到实现类、无法核验 → 保守保留，避免误杀基础设施导致崩溃
        }
        Class<?> cls = svc.getClass();
        if (cls.getName().startsWith("com.ryjs.")) {
            return true;
            // 我们自己
        }
        if (com.ryjs.agent.CompatWhitelist.isWhitelistedClass(cls.getName())
                || com.ryjs.agent.CompatWhitelist.isWhitelistedJar(jarPathOf(cls))) {
            return true;
            // 兼容白名单：良性 mod 的服务保留
        }
        boolean fromMods = isFromModsFolder(cls);
        boolean hasClinit = hasStaticBlock(cls);
        if (fromMods || hasClinit) {
            System.out.println("命中移除: " + name + " (fromMods=" + fromMods + ", 含静态块=" + hasClinit + ", 实现类=" + cls.getName() + ")");
            return false;
        }
        return true;
    }

    private static boolean isFromModsFolder(Class<?> c) {
        String srcJar = jarPathOf(c);
        if (srcJar == null) {
            return false;
        }
        String p = srcJar.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return p.contains("/mods/") && !p.contains("/libraries/");
    }

    private static boolean hasStaticBlock(Class<?> c) {
        try {
            String res = c.getName().replace('.', '/') + ".class";
            java.io.InputStream in = (c.getClassLoader() != null)
                    ? c.getClassLoader().getResourceAsStream(res)
                    : ClassLoader.getSystemResourceAsStream(res);
            if (in == null) {
                return false; // 读不到字节码 → 交由 /mods/ 判据兜底
            }
            byte[] bytes;
            try (java.io.InputStream is = in) {
                bytes = is.readAllBytes();
            }
            org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
            final boolean[] found = {false};
            cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int a, String mName, String d, String s, String[] ex) {
                    if ("<clinit>".equals(mName)) {
                        found[0] = true;
                    }
                    return null;
                }
            }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
            return found[0];
        } catch (Throwable t) {
            return false; // 出错 → 交由 /mods/ 判据兜底
        }
    }

    private static String jarPathOf(Class<?> c) {
        try {
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            String path = cs.getLocation().toString();
            if (path.startsWith("union:")) {
                path = path.substring(6);
            } else if (path.startsWith("jar:file:")) {
                path = path.substring(9);
            } else if (path.startsWith("file:")) {
                path = path.substring(5);
            }
            try {
                path = java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }
            int bang = path.indexOf("!/");
            if (bang != -1) {
                path = path.substring(0, bang);
            }
            int hash = path.indexOf('#');
            if (hash != -1) {
                path = path.substring(0, hash);
            }
            return path;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object unwrapService(Object decorator) {
        if (decorator == null) {
            return null;
        }
        try {
            Class<?> its = Class.forName("cpw.mods.modlauncher.api.ITransformationService");
            for (java.lang.reflect.Field f : decorator.getClass().getDeclaredFields()) {
                if (its.isAssignableFrom(f.getType())) {
                    return LOOKUP.unreflectGetter(f).invokeWithArguments(decorator);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final String PHANTOM_HOOKS_CLASS = "com/ryjs/reflection/hook/TaiChiPresenceEntityHooks";

    private static void installPhantomHooks() {
        try {
            prewarmZipFs();
            String[] callbackClasses = {
                PHANTOM_HOOKS_CLASS,
                "com/ryjs/reflection/hook/EarlyMainHooks", // 主类阶段信号
                "com/ryjs/reflection/hook/TimeStopHooks",
                "com/ryjs/reflection/hook/ItemRenderHooks",
                "com/ryjs/reflection/hook/PlayerGuardHooks",
                "com/ryjs/reflection/hook/RenderProtectHooks",
                "com/ryjs/reflection/hook/StabilityHooks",
                "com/ryjs/reflection/hook/DeathGlintHooks",
                "com/ryjs/reflection/hook/DeathWorldHooks",
                "com/ryjs/reflection/hook/TaiChiRenderHooks",
                "com/ryjs/reflection/hook/TooltipRenderHooks",
                "com/ryjs/reflection/hook/RenderStageGuardHooks",
                "com/ryjs/reflection/hook/MouseGuardHooks",
                "com/ryjs/reflection/hook/PhantomRedirectHooks",
                "com/ryjs/reflection/hook/RegistrationHooks",
            };
            int registered = 0;
            String jarPathForEnc = jarPathOf(AgentUtil.class);
            com.ryjs.core.RyjsClassLoader encLoader = com.ryjs.core.RyjsClassLoader.instance(jarPathForEnc);
            for (String callbackClass : callbackClasses) {
                // 字节来源：RyjsClassLoader 读 .mcmod（密文）→ 解密——加密后 jar 内不再有回调类 .class 资源
                try {
                    byte[] cbBytes = encLoader.readClassBytes(callbackClass.replace('/', '.'));
                    if (cbBytes == null) {
                        System.out.println("Hook 回调类字节码不可读，跳过: " + callbackClass);
                        continue;
                    }
                    com.ryjs.core.CoreBridge.hookRegistryRegister(cbBytes);
                    registered++;
                } catch (Throwable t) {
                    // 单个 hook 类注册失败（如签名校验不过）不应拖垮其它类与 transformer 挂载
                    System.out.println("Hook 回调类注册失败，跳过: " + callbackClass + " -> " + t);
                }
            }
            if (registered == 0) {
                // 不 return：transformer 必须继续挂载——主类阶段触发（首次 MC transform → 业务类预定义）
                // 挂在 transformer 链上，若这里 return 则触发点死掉 → 加密业务类永不定义 → 主类构造 CNFE
                // （2026-08-15 实测：早注册全部失败时连锁崩溃）。无目标时 transformer 空转无害。
                System.out.println("无任何 Hook 回调类可注册——继续挂载 transformer（主类阶段触发仍需活性）");
            } else {
                System.out.println("Hook 回调类注册成功 " + registered + "/" + callbackClasses.length
                        + "，targets=" + com.ryjs.core.CoreBridge.hookRegistryTargets());
            }
            // ===== 诊断（2026-08-18 注册链排查）：RegistrationHooks 的 ForgeMod 目标是否进表 =====
            try {
                System.out.println("DIAG hasTarget(ForgeMod)="
                        + com.ryjs.core.CoreBridge.hookRegistryHasTarget("net/minecraftforge/common/ForgeMod"));
            } catch (Throwable t) {
                System.out.println("DIAG hasTarget(ForgeMod) 调用失败: " + t);
            }
            fixModuleReads();
            preloadHookFramework();
            System.out.println("preloadHookFramework 完成，挂载 jvmti transformer...");
            addTransformer(com.ryjs.core.CoreBridge.newPresenceHookTransformer(), true);
            addTransformer(com.ryjs.core.CoreBridge.newTimeStopRenderArgTransformer(), true);
            System.out.println("jvmti transformer 已挂载（presence/timeStop）");
            retransformPhantomTargets();
            System.out.println("retransformPhantomTargets 完成");
            try {
                if (com.ryjs.agent.DefenseConfig.compatMode()) {
                    com.ryjs.core.CoreBridge.reinjectGuardStart(INST);
                    System.out.println("兼容模式ReinjectGuard重注入");
                }
            } catch (Throwable ignored) {
            }
            try {
                if (com.ryjs.agent.DefenseConfig.restoreGuard()) {
                    System.out.println("类还原启动: channel=" + com.ryjs.agent.DefenseConfig.restoreGuardChannel()
                            + " jvmtiAvail=" + com.ryjs.core.JvmtiBridge.isAvailable() + " inst=" + (INST != null));
                    com.ryjs.core.CoreBridge.classRestoreGuardStart(INST);
                    System.out.println("类还原守卫已启用（链尾仲裁 + 高频重注入 + redefine 兜底）");
                }
            } catch (Throwable t) {
                System.err.println("类还原守卫注册失败: " + t);
            }
            if (com.ryjs.core.CoreBridge.presenceDiagnostic()) {
                System.out.println("Hook早注册完成，目标类=" + com.ryjs.core.CoreBridge.hookRegistryTargets());
            }
            com.ryjs.hook.DiagLog.log("调用点重定向注册数=" + com.ryjs.core.CoreBridge.hookRegistryRedirectCount()
                    + "，目标=" + com.ryjs.core.CoreBridge.hookRegistryRedirectMethodNames());
        } catch (Throwable t) {
            System.err.println("Hook 早注册失败: " + t);
            t.printStackTrace();
        }
    }

    private static void prewarmZipFs() {
        try {
            try (java.io.InputStream in = AgentUtil.class.getResourceAsStream("/com/ryjs/coremod/Agent/AgentUtil.class")) {
                if (in != null) {
                    byte[] buf = new byte[512];
                    while (in.read(buf) >= 0) {
                        // 读完即弃——触发底层 zipfs 初始化
                    }
                }
            }
            java.util.concurrent.locks.ReentrantReadWriteLock warm =
                    new java.util.concurrent.locks.ReentrantReadWriteLock();
            warm.writeLock().lock();
            warm.writeLock().unlock();
            warm.readLock().lock();
            warm.readLock().unlock();
            try {
                Class.forName("java.util.concurrent.locks.AbstractQueuedSynchronizer$SharedNode",
                        true, null);
            } catch (Throwable ignored) {
            }
            System.out.println("zipfs/AQS 预热完成（首次初始化已在安静期完成）");
        } catch (Throwable t) {
            System.err.println("zipfs 预热失败（不影响启动）: " + t);
        }
    }


    private static void preloadHookFramework() {
        String[] names = {
            "com.ryjs.hook.hook.HookResult",
            "com.ryjs.hook.hook.HookMode",
            "com.ryjs.hook.DiagLog",
            "com.ryjs.hook.transformer.LoaderAwareClassWriter",
            "com.ryjs.hook.transformer.BytecodeHierarchy",
            "com.ryjs.hook.transformer.BytecodeHierarchy$ClassInfo",
            "com.ryjs.hook.transformer.ClassByteSource",
            "com.ryjs.reflection.hook.TaiChiPresenceEntityHooks",
            "com.ryjs.reflection.hook.TimeStopHooks",
            "com.ryjs.reflection.hook.ItemRenderHooks",
            "com.ryjs.reflection.hook.PlayerGuardHooks",
            "com.ryjs.reflection.hook.RenderProtectHooks",
            "com.ryjs.reflection.hook.StabilityHooks",
            "com.ryjs.reflection.hook.DeathGlintHooks",
            "com.ryjs.reflection.hook.DeathWorldHooks",
            "com.ryjs.reflection.hook.TaiChiRenderHooks",
            "com.ryjs.reflection.hook.TooltipRenderHooks",
            "com.ryjs.reflection.hook.RenderStageGuardHooks",
            "com.ryjs.reflection.hook.MouseGuardHooks",
            "com.ryjs.reflection.hook.PhantomRedirectHooks",
        };
        ClassLoader cl = AgentUtil.class.getClassLoader();
        int okPreload = 0;
        for (String name : names) {
            try {
                Class.forName(name, false, cl);
                okPreload++;
            } catch (Throwable t) {
                System.out.println("  预加载 hook 框架类失败: " + name + " -> " + t);
            }
        }
        System.out.println("  hook 框架类预加载完成: " + okPreload + "/" + names.length);
    }


    public static synchronized void defineEncryptedBusiness() {
        if (businessDefined) {
            return;
        }
        int failed = defineAllEncrypted();
        if (failed == 0) {
            businessDefined = true;
        }
    }

    private static volatile boolean businessDefined = false;


    private static void fixModuleReads() {
        try {
            Module self = AgentUtil.class.getModule();
            if (self == null || !self.isNamed()) {
                return;
            }

            java.util.Set<ModuleLayer> layers = new java.util.HashSet<>();
            layers.add(ModuleLayer.boot());

            if (com.ryjs.agent.DefenseConfig.jvmtiBlast()) {
                for (Class<?> c : com.ryjs.core.JvmtiBridge.observedSnapshot().values()) {
                    if (c != null && c.getModule() != null && c.getModule().getLayer() != null) {
                        layers.add(c.getModule().getLayer());
                    }
                }
            } else if (INST != null) {
                for (Class<?> c : loadedClasses()) {
                    if (c != null && c.getModule() != null && c.getModule().getLayer() != null) {
                        layers.add(c.getModule().getLayer());
                    }
                }
            }
            int fixed = 0;
            for (ModuleLayer layer : layers) {
                for (Module m : layer.modules()) {
                    if (m != self && m.isNamed()) {
                        com.ryjs.core.CoreAccess.addReads(m, self);
                        fixed++;
                    }
                }
            }
            System.out.println("模块 reads 修复完成: " + fixed + " 个模块已 reads reflection（层数=" + layers.size() + "）");
        } catch (Throwable t) {
            System.err.println("模块 reads 修复失败: " + t);
        }
    }

    private static int defineAllEncrypted() {
        try {

            String jarPath = com.ryjs.core.RyjsClassLoader.instance(jarPathOf(AgentUtil.class)).jarPath();
            if (jarPath == null || jarPath.isEmpty()) {
                return Integer.MAX_VALUE; // 视为全部失败，不置幂等（后续重试）
            }
            com.ryjs.core.RyjsClassLoader encLoader = com.ryjs.core.RyjsClassLoader.instance(jarPath);
            java.util.List<String> remaining = new java.util.ArrayList<>();
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
                java.util.Enumeration<java.util.jar.JarEntry> en = jar.entries();
                while (en.hasMoreElements()) {
                    java.util.jar.JarEntry e = en.nextElement();
                    String name = e.getName();
                    if (!name.endsWith(".mcmod") || !name.startsWith("com/ryjs/")
                            || name.startsWith("com/ryjs/core/impl/") || name.equals("com/ryjs/core/RyjsCoreImpl.mcmod")) {
                        continue;
                    }
                    remaining.add(name.substring(0, name.length() - ".mcmod".length()).replace('/', '.'));
                }
            }
            int defined = 0;
            int rounds = 0;
            while (!remaining.isEmpty() && rounds++ < 12) {
                boolean progress = false;
                java.util.Iterator<String> it = remaining.iterator();
                while (it.hasNext()) {
                    String className = it.next();
                    try {
                        byte[] bytes = encLoader.readClassBytes(className);
                        if (bytes == null) {
                            it.remove();
                            continue;
                        }
                        defineClass(AgentUtil.class.getClassLoader(), className, bytes);
                        defined++;
                        it.remove();
                        progress = true;
                    } catch (NoClassDefFoundError e) {
                        // 依赖未就绪（内部类/同包/MC 父类链）——下轮重试
                    } catch (LinkageError e) {
                        // 重复定义（其它触发源已定义）——视为成功
                        defined++;
                        it.remove();
                        progress = true;
                    } catch (Throwable t) {
                        it.remove();
                    }
                }
                if (!progress) {
                    break;
                }
            }
            return remaining.size();
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }
    
    public static void defineSingleEncrypted(String className) {
        try {
            String jarPath = com.ryjs.core.RyjsClassLoader.instance(jarPathOf(AgentUtil.class)).jarPath();
            if (jarPath == null || jarPath.isEmpty()) return;
            com.ryjs.core.RyjsClassLoader encLoader = com.ryjs.core.RyjsClassLoader.instance(jarPath);
            byte[] bytes = encLoader.readClassBytes(className);
            if (bytes == null) return;
            defineClass(AgentUtil.class.getClassLoader(), className, bytes);
        } catch (NoClassDefFoundError ignored) {
            // 依赖未就绪——静默（守护线程下轮再试）
        } catch (LinkageError ignored) {
            // 已定义（其它触发源）——视为成功
        } catch (Throwable ignored) {
        }
    }
    
    private static void retransformIfLoaded(String binaryName) {
        try {
            for (Class<?> loaded : loadedClasses()) {
                if (loaded != null && binaryName.equals(loaded.getName()) && isModifiableClass(loaded)) {
                    com.ryjs.core.CoreBridge.setInternalRetransform(true); // 我方主动
                    try {
                        retransformClasses(loaded);
                    } finally {
                        com.ryjs.core.CoreBridge.setInternalRetransform(false);
                    }
                    System.out.println("retransform 已加载类: " + binaryName);
                    return;
                }
            }
        } catch (Throwable t) {
            System.err.println("retransformIfLoaded 失败 " + binaryName + ": " + t);
        }
    }

    private static void retransformPhantomTargets() {
        try {
            java.util.List<Class<?>> targets = new java.util.ArrayList<>();
            for (Class<?> loaded : loadedClasses()) {
                if (loaded == null || loaded.isArray() || loaded.isPrimitive()) continue;
                if (com.ryjs.core.CoreBridge.hookRegistryHasTarget(loaded.getName()) && isModifiableClass(loaded)) {
                    targets.add(loaded);
                }
            }
            if (!targets.isEmpty()) {
                com.ryjs.core.CoreBridge.setInternalRetransform(true); // 我方主动 retransform：正常处理
                try {
                    retransformClasses(targets.toArray(new Class[0]));
                } finally {
                    com.ryjs.core.CoreBridge.setInternalRetransform(false);
                }
                if (com.ryjs.core.CoreBridge.presenceDiagnostic()) {
                    System.out.println("  幻象 Hook retransform 已加载目标: " + targets.size());
                }
            }
        } catch (Throwable t) {
            System.err.println("Hook retransform 失败: " + t);
        }
    }

    public static synchronized boolean redefine(Object obj, EZTsf tsf, boolean once) {
        if (INST == null) return true;

        Class<?> clazz;
        if (obj instanceof Class) {
            clazz = (Class<?>) obj;
        } else if (obj != null) {
            clazz = obj.getClass();
        } else {
            return false;
        }

        if (tsf == null
                || clazz.isPrimitive()
                || clazz.isArray()
                || clazz.getName().contains("$Lambda$$")) {
            return false;
        }

        if (once && Tsf.TSFD_CLASSES.contains(clazz)) return true;

        String internalName = clazz.getName().replace("/", "+").replace(".", "/");
        Tsf.SUPPLIER.put(internalName, tsf);
        // Hidden classes need their access flags patched before retransform
        long klass = 0L;
        int accessflags = 0;
        if (clazz.isHidden()) {
            klass = UNSAFE.getLong(clazz, 16L);
            accessflags = UNSAFE.getInt(klass + 164L);
            UNSAFE.putInt(klass + 164L, accessflags & -67108865); // clear bit 26 (hidden flag)
        }

        try {
            retransformClasses(clazz);
            if (once) Tsf.TSFD_CLASSES.add(clazz);
        } catch (Throwable t) {
            System.err.print("retransform " + clazz.getName() + " error: ");
            t.printStackTrace();
        }

        if (klass != 0L) UNSAFE.putInt(klass + 164L, accessflags); // restore flags

        Tsf.SUPPLIER.remove(internalName);
        return true;
    }
    
    public static void redefineHidden(Class<?> clazz) {
        long klass = 0L;
        int accessflags = 0;
        if (clazz.isHidden()) {
            klass = UNSAFE.getLong(clazz, 16L);
            accessflags = UNSAFE.getInt(klass + 164L);
            UNSAFE.putInt(klass + 164L, accessflags & -67108865); // clear bit 26 (hidden flag)
        }
        try {
            retransformClasses(clazz);
        } catch (Throwable t) {
            System.err.print("retransform " + clazz.getName() + " error: ");
            t.printStackTrace();
        }
        
        if (klass != 0L) UNSAFE.putInt(klass + 164L, accessflags);
    }

    public static int watch(ClassFileTransformer tsf) {
        Tsf.TRANSFORMERS.add(tsf);
        return Tsf.TRANSFORMERS.indexOf(tsf);
    }


    public static ClassFileTransformer unwatch(int id) {
        return Tsf.TRANSFORMERS.remove(id);
    }


    private static final class Tsf implements ClassFileTransformer {

        static final Set<Class<?>> TSFD_CLASSES = new HashSet<>();

        static final Map<String, EZTsf> SUPPLIER = new ConcurrentHashMap<>();

        static final List<ClassFileTransformer> TRANSFORMERS = new CopyOnWriteArrayList<>();

        @Override
        public byte[] transform(ClassLoader loader,
                                String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) throws IllegalClassFormatException {

            EZTsf tsf = SUPPLIER.get(className);
            if (tsf != null) {
                return tsf.transform(loader, classBeingRedefined, protectionDomain, classfileBuffer);
            }

            byte[] result = null;
            Iterator<ClassFileTransformer> it = TRANSFORMERS.iterator();
            while (it.hasNext()) {
                ClassFileTransformer er = it.next();
                byte[] transformed = er.transform(
                        loader, className, classBeingRedefined, protectionDomain,
                        result == null ? classfileBuffer : result);
                if (transformed != null) result = transformed;
            }
            return result;
        }
    }


    public interface EZTsf {
        byte[] transform(ClassLoader loader, Class<?> clazz, ProtectionDomain domain, byte[] bytes);
    }
}
