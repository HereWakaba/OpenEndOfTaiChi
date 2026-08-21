package com.ryjs.agent;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import sun.reflect.ReflectionFactory;

public class DefenseAgent {

    static final Object UNSAFE;
    static final MethodHandles.Lookup LOOKUP;
    static final MethodHandle ClassLoader_defineClass1;
    static final MethodHandle allocateInstanceMH;
    static final MethodHandle putObjectMH;
    static final MethodHandle staticFieldOffsetMH;
    static final MethodHandle staticFieldBaseMH;
    static final MethodHandle objectFieldOffsetMH;
    static final MethodHandle getIntMH;
    static final MethodHandle putIntMH;

    static {
        try {
            Constructor<MethodHandles.Lookup> lookupCtor =
                (Constructor<MethodHandles.Lookup>) ReflectionFactory.getReflectionFactory()
                    .newConstructorForSerialization(
                        MethodHandles.Lookup.class,
                        MethodHandles.Lookup.class.getDeclaredConstructor(
                            Class.class, Class.class, int.class));
            LOOKUP = lookupCtor.newInstance(Object.class, null, -1);

            Class<?> internalUnsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            MethodHandle getUnsafeMH = LOOKUP.findStatic(
                internalUnsafeClass, "getUnsafe",
                MethodType.methodType(internalUnsafeClass));
            UNSAFE = getUnsafeMH.invoke();

            allocateInstanceMH = LOOKUP.findVirtual(internalUnsafeClass, "allocateInstance",
                MethodType.methodType(Object.class, Class.class));
            putObjectMH = LOOKUP.findVirtual(internalUnsafeClass, "putObject",
                MethodType.methodType(void.class, Object.class, long.class, Object.class));
            staticFieldOffsetMH = LOOKUP.findVirtual(internalUnsafeClass, "staticFieldOffset",
                MethodType.methodType(long.class, Field.class));
            staticFieldBaseMH = LOOKUP.findVirtual(internalUnsafeClass, "staticFieldBase",
                MethodType.methodType(Object.class, Field.class));
            objectFieldOffsetMH = LOOKUP.findVirtual(internalUnsafeClass, "objectFieldOffset",
                MethodType.methodType(long.class, Field.class));
            getIntMH = LOOKUP.findVirtual(internalUnsafeClass, "getInt",
                MethodType.methodType(int.class, Object.class, long.class));
            putIntMH = LOOKUP.findVirtual(internalUnsafeClass, "putInt",
                MethodType.methodType(void.class, Object.class, long.class, int.class));

            ClassLoader_defineClass1 = LOOKUP.findStatic(ClassLoader.class, "defineClass1",
                MethodType.methodType(Class.class, ClassLoader.class, String.class,
                    byte[].class, int.class, int.class, ProtectionDomain.class, String.class));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }


    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private static Class<?> defineClass(ClassLoader loader, String name, byte[] buf) {
        try {
            return (Class<?>) ClassLoader_defineClass1.invoke(loader, name, buf, 0, buf.length, null, null);
        } catch (Throwable t) {
            try { return Class.forName(name); } catch (Exception e) { t.addSuppressed(e); return sneakyThrow(t); }
        }
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
                    if (args[i] != null && !params[i].isAssignableFrom(args[i].getClass())) continue outer;
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

    static volatile boolean VERBOSE = false;

    static void log(String msg) {
        if (VERBOSE) System.out.println(msg);
    }

    static void warn(String msg) {
        System.err.println(msg);
    }

    static volatile Instrumentation INST;

    static final String INST_RELAY_KEY = "com.ryjs.agent.DefenseAgent.INST";

    public static synchronized void start() {
        if (INST != null) return;
        if (com.ryjs.agent.DefenseConfig.jvmtiBlast()) {
            log("跳过 attach");
            return;
        }
        try {
            String internalName = AgtCallback.class.getName().replace('.', '/') + ".class";
            InputStream is = DefenseAgent.class.getResourceAsStream("/" + internalName);
            if (is == null) throw new RuntimeException("找不到资源: " + internalName);
            byte[] callbackBytes = readAllBytes(is);
            defineClass(ClassLoader.getSystemClassLoader(), AgtCallback.class.getName(), callbackBytes);

            Manifest manifest = new Manifest();
            Attributes mainAttrs = manifest.getMainAttributes();
            mainAttrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            mainAttrs.put(new Attributes.Name("Launcher-Agent-Class"), AgtCallback.class.getName());
            mainAttrs.put(new Attributes.Name("Can-Redefine-Classes"), "true");
            mainAttrs.put(new Attributes.Name("Can-Retransform-Classes"), "true");
            mainAttrs.put(new Attributes.Name("Can-Set-Native-Method-Prefix"), "true");

            Path jar = Files.createTempFile("defense_agent", ".jar");
            jar.toFile().deleteOnExit();
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()), manifest)) {
                jos.flush();
            }

            reflectInvoke(Class.forName("sun.instrument.InstrumentationImpl"), "loadAgent0",
                    jar.toAbsolutePath().toString());

            long deadline = System.currentTimeMillis() + 1000L;
            while (INST == null) {
                Object relayed = System.getProperties().get(INST_RELAY_KEY);
                if (relayed instanceof Instrumentation) {
                    INST = (Instrumentation) relayed;
                    break;
                }
                if (System.currentTimeMillis() > deadline) throw new RuntimeException("Agent 回调超时");
            }
            log("Instrumentation 已获取");
        } catch (Throwable t) {
            System.err.print("Agent 启动失败: ");
            t.printStackTrace();
            System.exit(0);
        }
    }

    private static volatile int ASM_API = -1;

    private static int asmApi() throws Throwable {
        int cached = ASM_API;
        if (cached != -1) return cached;
        Class<?> opcodesClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.Opcodes");
        int highest = 5 << 16;

        for (int v = 10; v >= 4; v--) {
            try {
                MethodHandle getter = LOOKUP.findStaticGetter(opcodesClass, "ASM" + v, int.class);
                highest = (int) getter.invoke();
                break;
            } catch (NoSuchFieldException | IllegalAccessException ignored) {

            }
        }
        ASM_API = highest;
        return highest;
    }

    private static byte[] patchReflectionFactory(byte[] classBytes) throws Throwable {
        Class<?> crClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.ClassReader");
        Class<?> cwClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.ClassWriter");
        Class<?> cnClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.tree.ClassNode");
        Class<?> mnClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.tree.MethodNode");
        Class<?> insnListClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.tree.InsnList");

        int asmApi = asmApi();

        MethodHandle crCtor = LOOKUP.findConstructor(crClass, MethodType.methodType(void.class, byte[].class));
        Object cr = crCtor.invoke(classBytes);
        MethodHandle cnCtor = LOOKUP.findConstructor(cnClass, MethodType.methodType(void.class, int.class));
        Object cn = cnCtor.invoke(asmApi);
        MethodHandle crAccept = LOOKUP.findVirtual(crClass, "accept",
            MethodType.methodType(void.class, Class.forName("jdk.internal.org.objectweb.asm.ClassVisitor"), int.class));
        crAccept.invoke(cr, cn, 0);

        MethodHandle getMethodsField = LOOKUP.findGetter(cnClass, "methods", List.class);
        @SuppressWarnings("unchecked")
        List<Object> methods = (List<Object>) getMethodsField.invoke(cn);

        MethodHandle mnNameGetter = LOOKUP.findGetter(mnClass, "name", String.class);
        MethodHandle mnDescGetter = LOOKUP.findGetter(mnClass, "desc", String.class);
        MethodHandle mnInstructionsSetter = LOOKUP.findSetter(mnClass, "instructions", insnListClass);

        MethodHandle insnListCtor = LOOKUP.findConstructor(insnListClass, MethodType.methodType(void.class));
        Object emptyInsnList = insnListCtor.invoke();

        MethodHandle visitCode = LOOKUP.findVirtual(mnClass, "visitCode", MethodType.methodType(void.class));
        MethodHandle visitInsn = LOOKUP.findVirtual(mnClass, "visitInsn", MethodType.methodType(void.class, int.class));
        MethodHandle visitMaxs = LOOKUP.findVirtual(mnClass, "visitMaxs", MethodType.methodType(void.class, int.class, int.class));
        MethodHandle visitEnd = LOOKUP.findVirtual(mnClass, "visitEnd", MethodType.methodType(void.class));
        MethodHandle visitTypeInsn = LOOKUP.findVirtual(mnClass, "visitTypeInsn",
            MethodType.methodType(void.class, int.class, String.class));
        MethodHandle visitLdcInsn = LOOKUP.findVirtual(mnClass, "visitLdcInsn",
            MethodType.methodType(void.class, Object.class));
        MethodHandle visitMethodInsn = LOOKUP.findVirtual(mnClass, "visitMethodInsn",
            MethodType.methodType(void.class, int.class, String.class, String.class, String.class, boolean.class));

        for (Object mn : methods) {
            String name = (String) mnNameGetter.invoke(mn);
            String desc = (String) mnDescGetter.invoke(mn);

            if (name.equals("getReflectionFactory") && desc.equals("()Lsun/reflect/ReflectionFactory;")) {
                mnInstructionsSetter.invoke(mn, emptyInsnList);
                visitCode.invoke(mn);
                visitInsn.invoke(mn, 1);  // ACONST_NULL
                visitInsn.invoke(mn, 176); // ARETURN
                visitMaxs.invoke(mn, 1, 1);
                visitEnd.invoke(mn);
            } else if (name.equals("newConstructorForSerialization") && desc.equals("(Ljava/lang/Class;Ljava/lang/reflect/Constructor;)Ljava/lang/reflect/Constructor;")) {
                mnInstructionsSetter.invoke(mn, emptyInsnList);
                visitCode.invoke(mn);
                visitTypeInsn.invoke(mn, 187, "java/lang/SecurityException");
                visitInsn.invoke(mn, 89); // DUP
                visitLdcInsn.invoke(mn, "ReflectionFactory blocked");
                visitMethodInsn.invoke(mn, 183, "java/lang/SecurityException", "<init>", "(Ljava/lang/String;)V", false);
                visitInsn.invoke(mn, 191); // ATHROW
                visitMaxs.invoke(mn, 3, 2);
                visitEnd.invoke(mn);
            }
        }

        MethodHandle cwCtor = LOOKUP.findConstructor(cwClass, MethodType.methodType(void.class, int.class));
        Object cw = cwCtor.invoke(2);
        MethodHandle cnAccept = LOOKUP.findVirtual(cnClass, "accept",
            MethodType.methodType(void.class, Class.forName("jdk.internal.org.objectweb.asm.ClassVisitor")));
        cnAccept.invoke(cn, cw);
        MethodHandle toByteArray = LOOKUP.findVirtual(cwClass, "toByteArray", MethodType.methodType(byte[].class));
        return (byte[]) toByteArray.invoke(cw);
    }

    private static Field[] getAllFields(Class<?> clazz) throws Throwable {
        MethodHandles.Lookup classLookup = LOOKUP.in(Class.class);
        MethodHandle getDeclaredFields0 = classLookup.findSpecial(Class.class, "getDeclaredFields0",
            MethodType.methodType(Field[].class, boolean.class), Class.class);
        return (Field[]) getDeclaredFields0.invoke(clazz, false);
    }

    private static Method[] getAllMethods(Class<?> clazz) throws Throwable {
        MethodHandles.Lookup classLookup = LOOKUP.in(Class.class);
        MethodHandle getDeclaredMethods0 = classLookup.findSpecial(Class.class, "getDeclaredMethods0",
            MethodType.methodType(Method[].class, boolean.class), Class.class);
        return (Method[]) getDeclaredMethods0.invoke(clazz, false);
    }

    private static Constructor<?>[] getAllConstructors(Class<?> clazz) throws Throwable {
        MethodHandles.Lookup classLookup = LOOKUP.in(Class.class);
        MethodHandle getDeclaredConstructors0 = classLookup.findSpecial(Class.class, "getDeclaredConstructors0",
            MethodType.methodType(Constructor[].class, boolean.class), Class.class);
        return (Constructor<?>[]) getDeclaredConstructors0.invoke(clazz, false);
    }

    private static void addAllToFilter(Class<?> clazz) throws Throwable {
        addAllToFilter(clazz, false);
    }

    private static void addAllToFilter(Class<?> clazz, boolean nonPublicOnly) throws Throwable {
        Field[] fields = getAllFields(clazz);
        Method[] methods = getAllMethods(clazz);
        Constructor<?>[] ctors = getAllConstructors(clazz);

        Class<?> reflHelper = Class.forName("jdk.internal.reflect.Reflection");
        MethodHandle regFields = LOOKUP.findStatic(reflHelper, "registerFieldsToFilter",
            MethodType.methodType(void.class, Class.class, Set.class));
        MethodHandle regMethods = LOOKUP.findStatic(reflHelper, "registerMethodsToFilter",
            MethodType.methodType(void.class, Class.class, Set.class));

        for (Field f : fields) {
            if (nonPublicOnly && Modifier.isPublic(f.getModifiers())) continue;
            try { regFields.invoke(clazz, Set.of(f.getName())); } catch (IllegalArgumentException ignored) {}
        }
        for (Method m : methods) {
            if (nonPublicOnly && Modifier.isPublic(m.getModifiers())) continue;
            try { regMethods.invoke(clazz, Set.of(m.getName())); } catch (IllegalArgumentException ignored) {}
        }
        for (Constructor<?> c : ctors) {
            if (nonPublicOnly && Modifier.isPublic(c.getModifiers())) continue;
            try { regMethods.invoke(clazz, Set.of(c.getName())); } catch (IllegalArgumentException ignored) {}
        }
    }

    static final String GUARD_INTERNAL = "com/ryjs/agent/UnsafeGuard";
    static final String GUARD_BINARY = "com.ryjs.agent.UnsafeGuard";

    static final Map<String, String> GUARD_METHODS = new HashMap<>();
    static volatile boolean guardBuilt = false;

    public static void checkCaller() {
        if (!TrustJudge.isCallerTrusted()) {
            throw new SecurityException("Unauthorized Unsafe call");
        }
    }


    static volatile boolean DEBUG_TRANSFORM = false;


    private static boolean isHighRiskCallerTrusted() {
        boolean trusted = TrustJudge.isCallerTrusted();
        if (DEBUG_TRANSFORM) {
            System.out.println("高危调用判定 → " + (trusted ? "可信" : "不可信"));
        }
        return trusted;
    }

    static final Map<String, String[]> HIGH_RISK_REDIRECTS = new HashMap<>();
    static final String HIGH_RISK_GUARD_INTERNAL = "com/ryjs/agent/HighRiskGuard";
    static {
        HIGH_RISK_REDIRECTS.put("java/lang/System#exit(I)V", new String[]{"systemExit", "(I)V"});
        HIGH_RISK_REDIRECTS.put("java/lang/System#load(Ljava/lang/String;)V", new String[]{"systemLoad", "(Ljava/lang/String;)V"});
        HIGH_RISK_REDIRECTS.put("java/lang/System#loadLibrary(Ljava/lang/String;)V", new String[]{"systemLoadLibrary", "(Ljava/lang/String;)V"});
        HIGH_RISK_REDIRECTS.put("java/lang/Runtime#exit(I)V", new String[]{"runtimeExit", "(Ljava/lang/Runtime;I)V"});
        HIGH_RISK_REDIRECTS.put("java/lang/Runtime#halt(I)V", new String[]{"runtimeHalt", "(Ljava/lang/Runtime;I)V"});
        HIGH_RISK_REDIRECTS.put("java/lang/Runtime#load(Ljava/lang/String;)V", new String[]{"runtimeLoad", "(Ljava/lang/Runtime;Ljava/lang/String;)V"});
        HIGH_RISK_REDIRECTS.put("java/lang/Runtime#loadLibrary(Ljava/lang/String;)V", new String[]{"runtimeLoadLibrary", "(Ljava/lang/Runtime;Ljava/lang/String;)V"});
        HIGH_RISK_REDIRECTS.put("java/lang/Runtime#exec(Ljava/lang/String;)Ljava/lang/Process;", new String[]{"runtimeExec", "(Ljava/lang/Runtime;Ljava/lang/String;)Ljava/lang/Process;"});
        HIGH_RISK_REDIRECTS.put("java/lang/Runtime#exec([Ljava/lang/String;)Ljava/lang/Process;", new String[]{"runtimeExecArr", "(Ljava/lang/Runtime;[Ljava/lang/String;)Ljava/lang/Process;"});
        HIGH_RISK_REDIRECTS.put("java/lang/Runtime#exec(Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;", new String[]{"runtimeExecEnv", "(Ljava/lang/Runtime;Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;"});
        HIGH_RISK_REDIRECTS.put("java/lang/Runtime#exec([Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;", new String[]{"runtimeExecArrEnv", "(Ljava/lang/Runtime;[Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;"});
        HIGH_RISK_REDIRECTS.put("java/lang/Runtime#exec(Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;", new String[]{"runtimeExecFull", "(Ljava/lang/Runtime;Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;"});
        HIGH_RISK_REDIRECTS.put("java/lang/Runtime#exec([Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;", new String[]{"runtimeExecArrFull", "(Ljava/lang/Runtime;[Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;"});
        HIGH_RISK_REDIRECTS.put("java/lang/ProcessBuilder#start()Ljava/lang/Process;", new String[]{"processBuilderStart", "(Ljava/lang/ProcessBuilder;)Ljava/lang/Process;"});
    }

    private static synchronized void buildUnsafeGuardClass() throws Throwable {
        if (guardBuilt) return;

        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        String unsafeInternal = "sun/misc/Unsafe";

        Class<?> cwClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.ClassWriter");
        Class<?> cvClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.ClassVisitor");
        Class<?> mvClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.MethodVisitor");
        Class<?> typeClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.Type");

        int V17 = 61;
        int ACC_PUBLIC = 0x0001;
        int ACC_STATIC = 0x0008;
        int ACC_SUPER = 0x0020;
        int COMPUTE_FRAMES = 2;

        MethodHandle cwCtor = LOOKUP.findConstructor(cwClass, MethodType.methodType(void.class, int.class));
        Object cw = cwCtor.invoke(COMPUTE_FRAMES);

        MethodHandle visit = LOOKUP.findVirtual(cwClass, "visit",
            MethodType.methodType(void.class, int.class, int.class, String.class, String.class, String.class, String[].class));
        visit.invoke(cw, V17, ACC_PUBLIC | ACC_SUPER, GUARD_INTERNAL, null, "java/lang/Object", null);

        MethodHandle cwVisitMethod = LOOKUP.findVirtual(cwClass, "visitMethod",
            MethodType.methodType(mvClass, int.class, String.class, String.class, String.class, String[].class));

        MethodHandle typeGetType = LOOKUP.findStatic(typeClass, "getType",
            MethodType.methodType(typeClass, Class.class));
        MethodHandle typeGetOpcode = LOOKUP.findVirtual(typeClass, "getOpcode",
            MethodType.methodType(int.class, int.class));
        MethodHandle typeGetSize = LOOKUP.findVirtual(typeClass, "getSize",
            MethodType.methodType(int.class));
        MethodHandle typeGetDescriptor = LOOKUP.findVirtual(typeClass, "getDescriptor",
            MethodType.methodType(String.class));

        MethodHandle mvVisitCode = LOOKUP.findVirtual(mvClass, "visitCode", MethodType.methodType(void.class));
        MethodHandle mvVisitVarInsn = LOOKUP.findVirtual(mvClass, "visitVarInsn",
            MethodType.methodType(void.class, int.class, int.class));
        MethodHandle mvVisitMethodInsn = LOOKUP.findVirtual(mvClass, "visitMethodInsn",
            MethodType.methodType(void.class, int.class, String.class, String.class, String.class, boolean.class));
        MethodHandle mvVisitInsn = LOOKUP.findVirtual(mvClass, "visitInsn",
            MethodType.methodType(void.class, int.class));
        MethodHandle mvVisitMaxs = LOOKUP.findVirtual(mvClass, "visitMaxs",
            MethodType.methodType(void.class, int.class, int.class));
        MethodHandle mvVisitEnd = LOOKUP.findVirtual(mvClass, "visitEnd", MethodType.methodType(void.class));

        final int ILOAD = 21;
        final int IRETURN = 172;
        final int RETURN = 177;
        final int INVOKESTATIC = 184;
        final int INVOKEVIRTUAL = 182;

        String unsafeTypeDesc = "L" + unsafeInternal + ";";


        for (Method m : unsafeClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getDeclaringClass() == Object.class) continue;

            String name = m.getName();
            Class<?>[] params = m.getParameterTypes();
            Class<?> ret = m.getReturnType();

            StringBuilder origDesc = new StringBuilder("(");
            for (Class<?> p : params) origDesc.append((String) typeGetDescriptor.invoke(typeGetType.invoke(p)));
            origDesc.append(")").append((String) typeGetDescriptor.invoke(typeGetType.invoke(ret)));


            String guardDesc = "(" + unsafeTypeDesc + origDesc.substring(1);
            String key = name + origDesc;
            if (GUARD_METHODS.containsKey(key)) continue;
            GUARD_METHODS.put(key, guardDesc);

            Object mv = cwVisitMethod.invoke(cw, ACC_PUBLIC | ACC_STATIC, name, guardDesc, null, null);
            mvVisitCode.invoke(mv);


            mvVisitMethodInsn.invoke(mv, INVOKESTATIC, "com/ryjs/agent/TrustJudge", "checkCallerOrThrow", "()V", false);


            mvVisitVarInsn.invoke(mv, 25 /*ALOAD*/, 0);


            int slot = 1;
            for (Class<?> p : params) {
                Object pType = typeGetType.invoke(p);
                int loadOp = (int) typeGetOpcode.invoke(pType, ILOAD);
                mvVisitVarInsn.invoke(mv, loadOp, slot);
                slot += (int) typeGetSize.invoke(pType);
            }


            mvVisitMethodInsn.invoke(mv, INVOKEVIRTUAL, unsafeInternal, name, origDesc.toString(), false);


            if (ret == void.class) {
                mvVisitInsn.invoke(mv, RETURN);
            } else {
                Object retType = typeGetType.invoke(ret);
                mvVisitInsn.invoke(mv, (int) typeGetOpcode.invoke(retType, IRETURN));
            }

            mvVisitMaxs.invoke(mv, 0, 0);
            mvVisitEnd.invoke(mv);
        }

        MethodHandle cwVisitEnd = LOOKUP.findVirtual(cwClass, "visitEnd", MethodType.methodType(void.class));
        cwVisitEnd.invoke(cw);
        MethodHandle toByteArray = LOOKUP.findVirtual(cwClass, "toByteArray", MethodType.methodType(byte[].class));
        byte[] guardBytes = (byte[]) toByteArray.invoke(cw);

        installGuardsToBootstrap(guardBytes);
        guardBuilt = true;
        log("[Defense] UnsafeGuard 代理类已生成，代理方法数: " + GUARD_METHODS.size());
    }


    private static volatile boolean guardsDefined = false;
    static synchronized void installGuardsToBootstrap(byte[] unsafeGuardBytes) {
        if (guardsDefined) return;
        if (INST == null) { warn("[Defense] INST 未就绪，无法挂载引导类"); return; }
        try {
            byte[] tjBytes = readAllBytes(DefenseAgent.class.getResourceAsStream("/com/ryjs/agent/TrustJudge.class"));
            byte[] hrBytes = readAllBytes(DefenseAgent.class.getResourceAsStream("/com/ryjs/agent/HighRiskGuard.class"));

            Path jar = Files.createTempFile("defense_guards", ".jar");
            jar.toFile().deleteOnExit();
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
                if (tjBytes != null) writeJarEntry(jos, "com/ryjs/agent/TrustJudge.class", tjBytes);
                if (hrBytes != null) writeJarEntry(jos, "com/ryjs/agent/HighRiskGuard.class", hrBytes);
                if (unsafeGuardBytes != null) writeJarEntry(jos, "com/ryjs/agent/UnsafeGuard.class", unsafeGuardBytes);
            }

            INST.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(jar.toFile()));


            try {
                Class<?> tjBoot = Class.forName("com.ryjs.agent.TrustJudge", true, null);
                injectModsDir(tjBoot);
            } catch (Throwable t) {
                warn("[Defense] 向引导层 TrustJudge 注入 mods 目录失败: " + t.getMessage());
            }

            guardsDefined = true;
            log("[Defense] 防御辅助类已挂到引导类加载器: " + jar);
        } catch (Throwable t) {
            warn("[Defense] 挂载引导类失败: " + t.getMessage());
        }
    }

    private static void writeJarEntry(JarOutputStream jos, String name, byte[] bytes) throws IOException {
        jos.putNextEntry(new java.util.jar.JarEntry(name));
        jos.write(bytes);
        jos.closeEntry();
    }


    private static void injectModsDir(Class<?> trustJudgeClass) {
        try {
            String modsDir = resolveModsDir();
            if (modsDir == null) return;
            Method setter = trustJudgeClass.getMethod("setModsDir", String.class);
            setter.invoke(null, modsDir);
        } catch (Throwable t) {
            warn("[Defense] 注入 mods 目录失败: " + t.getMessage());
        }
    }


    private static String resolveModsDir() {
        try {
            java.security.CodeSource cs = DefenseAgent.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                String p = cs.getLocation().getPath();
                if (p != null && !p.isEmpty()) {
                    if (p.startsWith("union:")) p = p.substring(6);
                    int jarIdx = p.lastIndexOf(".jar");
                    if (jarIdx != -1) p = p.substring(0, jarIdx + 4);
                    Path self = Paths.get(java.net.URLDecoder.decode(p, java.nio.charset.StandardCharsets.UTF_8)).toAbsolutePath().normalize();
                    Path parent = self.getParent();
                    if (parent != null && Files.isDirectory(parent)) return parent.toString();
                }
            }
        } catch (Throwable ignored) {}
        Path fallback = Paths.get("mods").toAbsolutePath();
        return Files.isDirectory(fallback) ? fallback.toString() : null;
    }

    static byte[] neutralizeMethods(byte[] classBytes, String targetMethodName, boolean returnObject) throws Throwable {
        Class<?> crClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.ClassReader");
        Class<?> cwClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.ClassWriter");
        Class<?> cnClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.tree.ClassNode");
        Class<?> mnClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.tree.MethodNode");
        Class<?> insnListClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.tree.InsnList");
        Class<?> typeClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.Type");

        int asmApi = asmApi();
        MethodHandle crCtor = LOOKUP.findConstructor(crClass, MethodType.methodType(void.class, byte[].class));
        Object cr = crCtor.invoke(classBytes);
        MethodHandle cnCtor = LOOKUP.findConstructor(cnClass, MethodType.methodType(void.class, int.class));
        Object cn = cnCtor.invoke(asmApi);
        MethodHandle crAccept = LOOKUP.findVirtual(crClass, "accept",
            MethodType.methodType(void.class, Class.forName("jdk.internal.org.objectweb.asm.ClassVisitor"), int.class));
        crAccept.invoke(cr, cn, 0);

        MethodHandle getMethods = LOOKUP.findGetter(cnClass, "methods", List.class);
        @SuppressWarnings("unchecked")
        List<Object> methods = (List<Object>) getMethods.invoke(cn);

        MethodHandle mnNameGetter = LOOKUP.findGetter(mnClass, "name", String.class);
        MethodHandle mnDescGetter = LOOKUP.findGetter(mnClass, "desc", String.class);
        MethodHandle mnAccessGetter = LOOKUP.findGetter(mnClass, "access", int.class);
        MethodHandle mnInsnSetter = LOOKUP.findSetter(mnClass, "instructions", insnListClass);
        MethodHandle insnListCtor = LOOKUP.findConstructor(insnListClass, MethodType.methodType(void.class));

        MethodHandle visitCode = LOOKUP.findVirtual(mnClass, "visitCode", MethodType.methodType(void.class));
        MethodHandle visitInsn = LOOKUP.findVirtual(mnClass, "visitInsn", MethodType.methodType(void.class, int.class));
        MethodHandle visitMaxs = LOOKUP.findVirtual(mnClass, "visitMaxs", MethodType.methodType(void.class, int.class, int.class));
        MethodHandle visitEnd = LOOKUP.findVirtual(mnClass, "visitEnd", MethodType.methodType(void.class));

        MethodHandle typeGetReturn = LOOKUP.findStatic(typeClass, "getReturnType",
            MethodType.methodType(typeClass, String.class));
        MethodHandle typeGetSort = LOOKUP.findVirtual(typeClass, "getSort", MethodType.methodType(int.class));

        final int ACC_NATIVE = 0x0100, ACC_ABSTRACT = 0x0400;
        final int ACONST_NULL = 1, IRETURN = 172, LRETURN = 173, FRETURN = 174, DRETURN = 175, ARETURN = 176, RETURN = 177;
        final int ICONST_0 = 3, LCONST_0 = 9, FCONST_0 = 11, DCONST_0 = 14;
        boolean any = false;

        for (Object mn : methods) {
            String name = (String) mnNameGetter.invoke(mn);
            if (!targetMethodName.equals(name)) continue;
            int access = (int) mnAccessGetter.invoke(mn);
            if ((access & (ACC_NATIVE | ACC_ABSTRACT)) != 0) continue;

            String desc = (String) mnDescGetter.invoke(mn);
            Object retType = typeGetReturn.invoke(desc);
            int sort = (int) typeGetSort.invoke(retType);

            mnInsnSetter.invoke(mn, insnListCtor.invoke());
            visitCode.invoke(mn);
            if (sort == 0) { // void
                visitInsn.invoke(mn, RETURN);
                visitMaxs.invoke(mn, 0, 0);
            } else if (returnObject || sort == 9 || sort == 10) {
                visitInsn.invoke(mn, ACONST_NULL);
                visitInsn.invoke(mn, ARETURN);
                visitMaxs.invoke(mn, 1, 0);
            } else if (sort == 7) { // long
                visitInsn.invoke(mn, LCONST_0); visitInsn.invoke(mn, LRETURN); visitMaxs.invoke(mn, 2, 0);
            } else if (sort == 8) { // double
                visitInsn.invoke(mn, DCONST_0); visitInsn.invoke(mn, DRETURN); visitMaxs.invoke(mn, 2, 0);
            } else if (sort == 6) { // float
                visitInsn.invoke(mn, FCONST_0); visitInsn.invoke(mn, FRETURN); visitMaxs.invoke(mn, 1, 0);
            } else { // int 类（boolean/char/byte/short/int）
                visitInsn.invoke(mn, ICONST_0); visitInsn.invoke(mn, IRETURN); visitMaxs.invoke(mn, 1, 0);
            }
            visitEnd.invoke(mn);
            any = true;
        }

        if (!any) return null;

        MethodHandle cwCtor = LOOKUP.findConstructor(cwClass, MethodType.methodType(void.class, crClass, int.class));
        Object cw = cwCtor.invoke(cr, 1 /*COMPUTE_MAXS*/);
        MethodHandle cnAccept = LOOKUP.findVirtual(cnClass, "accept",
            MethodType.methodType(void.class, Class.forName("jdk.internal.org.objectweb.asm.ClassVisitor")));
        cnAccept.invoke(cn, cw);
        MethodHandle toByteArray = LOOKUP.findVirtual(cwClass, "toByteArray", MethodType.methodType(byte[].class));
        return (byte[]) toByteArray.invoke(cw);
    }



    static boolean highRiskSubEnabled(String guardMethod) {
        switch (guardMethod) {
            case "systemExit":            return DefenseConfig.hrSystemExit();
            case "runtimeExit":
            case "runtimeHalt":           return DefenseConfig.hrRuntimeExit();
            case "runtimeExec":
            case "runtimeExecArr":
            case "runtimeExecEnv":
            case "runtimeExecArrEnv":
            case "runtimeExecFull":
            case "runtimeExecArrFull":     return DefenseConfig.hrExec();
            case "processBuilderStart":    return DefenseConfig.hrProcessStart();
            case "systemLoad":
            case "systemLoadLibrary":      return DefenseConfig.hrSystemLoad();
            case "runtimeLoad":
            case "runtimeLoadLibrary":     return DefenseConfig.hrRuntimeLoad();
            default:                       return DefenseConfig.interceptHighRisk();
        }
    }

    private static class UnsafeCallTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {

            if (className == null || className.startsWith("com/ryjs/")) return null;

            if (!DefenseConfig.interceptUnsafe() && !DefenseConfig.interceptHighRisk()) return null;

            try {
                if (DefenseConfig.hrAttach() && "com/sun/tools/attach/VirtualMachine".equals(className)) {
                    byte[] r = neutralizeMethods(classfileBuffer, "attach", true);
                    if (r != null) { if (DEBUG_TRANSFORM) System.out.println("[Defense/rewrite] 废掉 VirtualMachine.attach"); return r; }
                }
                if (DefenseConfig.hrLoadAgent()
                        && ("sun/instrument/InstrumentationImpl".equals(className))) {
                    byte[] r = neutralizeMethods(classfileBuffer, "loadAgent0", false);
                    if (r != null) { if (DEBUG_TRANSFORM) System.out.println("[Defense/rewrite] 废掉 InstrumentationImpl.loadAgent0"); return r; }
                }
            } catch (Throwable ignored) {}

            try {
                Class<?> crClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.ClassReader");
                Class<?> cwClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.ClassWriter");
                Class<?> cnClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.tree.ClassNode");
                Class<?> mnClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.tree.MethodNode");
                Class<?> methodInsnNodeClass = LOOKUP.findClass("jdk.internal.org.objectweb.asm.tree.MethodInsnNode");

                int asmApi = asmApi();
                MethodHandle crCtor = LOOKUP.findConstructor(crClass, MethodType.methodType(void.class, byte[].class));
                Object cr = crCtor.invoke(classfileBuffer);
                MethodHandle cnCtor = LOOKUP.findConstructor(cnClass, MethodType.methodType(void.class, int.class));
                Object cn = cnCtor.invoke(asmApi);
                MethodHandle crAccept = LOOKUP.findVirtual(crClass, "accept",
                    MethodType.methodType(void.class, Class.forName("jdk.internal.org.objectweb.asm.ClassVisitor"), int.class));
                crAccept.invoke(cr, cn, 0);

                MethodHandle getMethodsField = LOOKUP.findGetter(cnClass, "methods", List.class);
                @SuppressWarnings("unchecked")
                List<Object> methods = (List<Object>) getMethodsField.invoke(cn);
                MethodHandle miGetOpcode = LOOKUP.findVirtual(methodInsnNodeClass, "getOpcode", MethodType.methodType(int.class));
                MethodHandle miGetOwner = LOOKUP.findGetter(methodInsnNodeClass, "owner", String.class);
                MethodHandle miGetName = LOOKUP.findGetter(methodInsnNodeClass, "name", String.class);
                MethodHandle miGetDesc = LOOKUP.findGetter(methodInsnNodeClass, "desc", String.class);
                MethodHandle miSetOpcode = LOOKUP.findVirtual(methodInsnNodeClass, "setOpcode", MethodType.methodType(void.class, int.class));
                MethodHandle miSetOwner = LOOKUP.findSetter(methodInsnNodeClass, "owner", String.class);
                MethodHandle miSetName = LOOKUP.findSetter(methodInsnNodeClass, "name", String.class);
                MethodHandle miSetDesc = LOOKUP.findSetter(methodInsnNodeClass, "desc", String.class);
                MethodHandle miSetItf = LOOKUP.findSetter(methodInsnNodeClass, "itf", boolean.class);

                final int INVOKEVIRTUAL = 182;
                final int INVOKESTATIC = 184;
                boolean modified = false;

                boolean doUnsafe = guardBuilt && DefenseConfig.interceptUnsafe() && DefenseConfig.unsafeBytecode();
                boolean doHighRisk = DefenseConfig.interceptHighRisk();

                for (Object mn : methods) {
                    MethodHandle getInstructions = LOOKUP.findGetter(mnClass, "instructions",
                        Class.forName("jdk.internal.org.objectweb.asm.tree.InsnList"));
                    Object insns = getInstructions.invoke(mn);
                    MethodHandle getIterator = LOOKUP.findVirtual(insns.getClass(), "iterator", MethodType.methodType(Iterator.class));
                    @SuppressWarnings("unchecked")
                    Iterator<Object> it = (Iterator<Object>) getIterator.invoke(insns);
                    while (it.hasNext()) {
                        Object insn = it.next();
                        if (!methodInsnNodeClass.isInstance(insn)) continue;

                        int opcode = (int) miGetOpcode.invoke(insn);
                        String owner = (String) miGetOwner.invoke(insn);
                        String name = (String) miGetName.invoke(insn);
                        String desc = (String) miGetDesc.invoke(insn);


                        if (doUnsafe && opcode == INVOKEVIRTUAL && "sun/misc/Unsafe".equals(owner)) {
                            String guardDesc = GUARD_METHODS.get(name + desc);

                            if (guardDesc != null) {
                                miSetOwner.invoke(insn, GUARD_INTERNAL);
                                miSetDesc.invoke(insn, guardDesc);
                                miSetOpcode.invoke(insn, INVOKESTATIC);
                                miSetItf.invoke(insn, false);
                                modified = true;
                                if (DEBUG_TRANSFORM) {
                                    System.out.println("[Defense/rewrite] " + className + " 中的 sun/misc/Unsafe."
                                        + name + " → UnsafeGuard");
                                }
                                continue;
                            }
                        }

                        if (doHighRisk) {
                            String[] redirect = HIGH_RISK_REDIRECTS.get(owner + "#" + name + desc);
                            if (redirect != null && highRiskSubEnabled(redirect[0])) {

                                miSetOwner.invoke(insn, HIGH_RISK_GUARD_INTERNAL);
                                miSetName.invoke(insn, redirect[0]);
                                miSetDesc.invoke(insn, redirect[1]);
                                miSetOpcode.invoke(insn, INVOKESTATIC);
                                miSetItf.invoke(insn, false);
                                modified = true;
                                if (DEBUG_TRANSFORM) {
                                    System.out.println("[Defense/rewrite] " + className + " 中的 "
                                        + owner + "." + name + " → HighRiskGuard." + redirect[0]);
                                }
                            }
                        }
                    }
                }


                if (!modified) return null;


                final int COMPUTE_MAXS = 1;
                MethodHandle cwCtor = LOOKUP.findConstructor(cwClass,
                    MethodType.methodType(void.class, crClass, int.class));
                Object cw = cwCtor.invoke(cr, COMPUTE_MAXS);
                MethodHandle cnAccept = LOOKUP.findVirtual(cnClass, "accept",
                    MethodType.methodType(void.class, Class.forName("jdk.internal.org.objectweb.asm.ClassVisitor")));
                cnAccept.invoke(cn, cw);
                MethodHandle toByteArray = LOOKUP.findVirtual(cwClass, "toByteArray", MethodType.methodType(byte[].class));
                return (byte[]) toByteArray.invoke(cw);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    public static void applyDefenses() throws Throwable {
        if (INST == null) start();


        DefenseConfig.load();

        try {
            String modsDir = resolveModsDir();
            if (modsDir != null) {
                TrustJudge.setModsDir(modsDir);
                log("[Defense] mods 目录已定位: " + modsDir);
            } else {
                warn("[Defense] 无法定位 mods 目录，信任判定将保守放行");
            }
        } catch (Throwable t) {
            warn("[Defense] 注入 mods 目录失败: " + t.getMessage());
        }


        Class<?> rfClass = ReflectionFactory.class;
        if (DefenseConfig.interceptReflection()) {
            try {
                byte[] origBytes = readAllBytes(rfClass.getResourceAsStream("/" + rfClass.getName().replace('.', '/') + ".class"));
                byte[] patched = patchReflectionFactory(origBytes);
                if (patched != null) {
                    if (INST != null) {
                        INST.redefineClasses(new ClassDefinition(rfClass, patched));
                        log("[Defense] ReflectionFactory 字节码已修改");
                    } else {
                        log("[Defense] INST 不可用（打爆模式），跳过 ReflectionFactory redefine");
                    }
                }
            } catch (Throwable t) {
                warn("[Defense] 修改 ReflectionFactory 失败: " + t.getMessage());
            }
            addAllToFilter(rfClass);
            Field[] rfFields = getAllFields(rfClass);
            for (Field f : rfFields) {
                if (f.getName().equals("soleInstance")) {
                    long offset = (long) staticFieldOffsetMH.invoke(UNSAFE, f);
                    Object base = staticFieldBaseMH.invoke(UNSAFE, f);
                    putObjectMH.invoke(UNSAFE, base, offset, null);
                    log("[Defense] ReflectionFactory.soleInstance 已置空");
                    break;
                }
            }
        } else {
            log("[Defense] 反射拦截已按配置关闭");
        }


        if (DefenseConfig.interceptUnsafe()) {
            Class<?> sunUnsafe = Class.forName("sun.misc.Unsafe");


            if (DefenseConfig.unsafeBlacklist()) {

                addAllToFilter(sunUnsafe, true);
                Class<?> reflHelper = Class.forName("jdk.internal.reflect.Reflection");
                MethodHandle regFieldsMH = LOOKUP.findStatic(reflHelper, "registerFieldsToFilter",
                    MethodType.methodType(void.class, Class.class, Set.class));
                try { regFieldsMH.invoke(sunUnsafe, Set.of("theUnsafe")); } catch (IllegalArgumentException ignored) {}
            } else {
                log("[Defense] Unsafe 反射黑名单已按子开关关闭");
            }


            if (DefenseConfig.nullifyTheUnsafe()) {
                Field[] unsafeFields = getAllFields(sunUnsafe);
                for (Field f : unsafeFields) {
                    if (f.getName().equals("theUnsafe")) {
                        long offset = (long) staticFieldOffsetMH.invoke(UNSAFE, f);
                        Object base = staticFieldBaseMH.invoke(UNSAFE, f);
                        putObjectMH.invoke(UNSAFE, base, offset, null);
                        warn("[Defense] sun.misc.Unsafe.theUnsafe 已置空（全局高杀伤子开关已开启，WIP）");
                        break;
                    }
                }
            }

            if (DefenseConfig.unsafeBytecode()) {
                try {
                    buildUnsafeGuardClass();
                } catch (Throwable t) {
                    warn("[Defense] 生成 UnsafeGuard 失败: " + t.getMessage());
                }
            } else {
                log("[Defense] Unsafe 字节码重写已按子开关关闭");
            }
        } else {
            log("[Defense] Unsafe 拦截已按配置关闭");
        }

        boolean unsafeTransform = DefenseConfig.interceptUnsafe() && DefenseConfig.unsafeBytecode();
        boolean needTransformer = unsafeTransform || DefenseConfig.interceptHighRisk();
        if (needTransformer) {

            if (!guardsDefined) {
                installGuardsToBootstrap(null);
            }

            if (INST != null) {
                INST.addTransformer(new UnsafeCallTransformer(), true);
            }

            if (DefenseConfig.hrAttach() || DefenseConfig.hrLoadAgent()) {
                String[] targets = { "com.sun.tools.attach.VirtualMachine", "sun.instrument.InstrumentationImpl" };
                for (String tn : targets) {
                    try {
                        Class<?> tc = Class.forName(tn, false, ClassLoader.getSystemClassLoader());
                        if (INST != null && INST.isModifiableClass(tc)) {
                            INST.retransformClasses(tc);
                            log("[Defense] 已针对性 retransform: " + tn);
                        }
                    } catch (Throwable ignored) {

                    }
                }
            }


            if (DefenseConfig.unsafeRetransform()) {
                int transformed = 0;
                for (Class<?> c : (INST != null ? INST.getAllLoadedClasses() : new Class<?>[0])) {
                    if (c.isPrimitive() || c.isArray() || c.isHidden()) continue;
                    if (!INST.isModifiableClass(c)) continue;
                    String bin = c.getName();

                    if (bin.startsWith("com.ryjs.agent.") || bin.equals("sun.misc.Unsafe")) continue;
                    try {
                        INST.retransformClasses(c);
                        transformed++;
                    } catch (Throwable ignored) {

                    }
                }
                log("[Defense] 已对已加载类触发 retransform，成功: " + transformed
                    + "（Unsafe=" + DefenseConfig.interceptUnsafe() + ", 高危=" + DefenseConfig.interceptHighRisk() + "）");
            } else {
                log("[Defense] 已加载类 retransform 已按子开关关闭（仅拦新加载类）");
            }
        } else {
            log("[Defense] 调用拦截 Transformer 已按配置关闭");
        }
    }


    public static class AgtCallback {
        public static void agentmain(String args, Instrumentation inst) {
            System.getProperties().put("com.ryjs.agent.DefenseAgent.INST", inst);
        }
        public static void premain(String args, Instrumentation inst) {
            System.getProperties().put("com.ryjs.agent.DefenseAgent.INST", inst);
        }
    }

    public static void main(String[] args) throws Throwable {
        System.out.println("防御启动");
        applyDefenses();
        System.out.println("防御启动完成");
    }
}