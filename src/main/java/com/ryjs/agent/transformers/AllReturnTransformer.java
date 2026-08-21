package com.ryjs.agent.transformers;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


public class AllReturnTransformer implements ClassFileTransformer {

    private static volatile boolean firstErrorLogged = false;
    private static volatile boolean firstWorkLogged = false;
    private static volatile boolean firstGateSkipLogged = false;

    private static final Set<String> DANGEROUS_CLASSES = new HashSet<>(Arrays.asList(
            "java/lang/System", "java/lang/Runtime", "java/lang/ProcessBuilder", "java/lang/Process",
            "java/lang/reflect/Method", "java/lang/reflect/Field", "java/lang/reflect/Constructor",
            "java/lang/reflect/Array", "java/lang/reflect/Proxy", "java/lang/reflect/AccessibleObject",
            "java/lang/invoke/MethodHandles", "java/lang/invoke/MethodHandle", "java/lang/invoke/VarHandle",
            "java/lang/invoke/CallSite", "java/lang/invoke/MethodType", "java/lang/invoke/MethodHandles$Lookup",
            "sun/misc/Unsafe", "jdk/internal/misc/Unsafe", "java/security/AccessController",
            "java/security/PrivilegedAction", "java/security/PrivilegedExceptionAction",
            "java/lang/ClassLoader", "java/net/URLClassLoader", "java/io/File",
            "java/io/FileInputStream", "java/io/FileOutputStream", "java/io/RandomAccessFile",
            "java/nio/file/Paths", "java/nio/file/Files", "java/lang/Thread", "java/lang/ThreadGroup",
            "java/lang/Shutdown", "java/net/Socket", "java/net/ServerSocket", "java/net/URL",
            "java/net/URI", "java/util/ServiceLoader", "javax/tools/ToolProvider",
            "javax/tools/JavaCompiler", "com/sun/tools/attach/VirtualMachine",
            "sun/tools/attach/HotSpotVirtualMachine"
    ));

    private static final Set<String> DANGEROUS_METHODS = new HashSet<>(Arrays.asList(
            "exit", "load", "loadLibrary", "exec", "getRuntime", "gc", "runFinalization",
            "invoke", "get", "set", "newInstance", "getDeclaredField", "getDeclaredMethod",
            "getDeclaredConstructor", "setAccessible", "getField", "getMethod", "getConstructor",
            "getDeclaredFields", "getDeclaredMethods", "getDeclaredConstructors",
            "lookup", "findVirtual", "findStatic", "findSpecial", "findGetter", "findSetter",
            "findVarHandle", "findConstructor", "invoke", "invokeExact", "invokeWithArguments",
            "unreflect", "unreflectSpecial", "unreflectConstructor", "unreflectField",
            "allocateInstance", "objectFieldOffset", "staticFieldOffset", "compareAndSwapObject",
            "compareAndSwapInt", "compareAndSwapLong", "getObject", "putObject", "getInt", "putInt",
            "getLong", "putLong", "getObjectVolatile", "putObjectVolatile", "arrayBaseOffset",
            "arrayIndexScale", "defineClass", "defineAnonymousClass", "ensureClassInitialized",
            "allocateMemory", "reallocateMemory", "freeMemory", "putAddress", "getAddress",
            "loadClass", "defineClass", "findClass", "findLoadedClass", "definePackage",
            "delete", "deleteOnExit", "renameTo", "createNewFile", "mkdir", "mkdirs",
            "setExecutable", "setReadable", "setWritable",
            "stop", "suspend", "resume", "setContextClassLoader", "setDaemon",
            "destroy", "destroyForcibly", "waitFor",
            "attach", "detach", "loadAgent", "loadAgentLibrary", "loadAgentPath",
            "redefineClasses", "retransformClasses", "setNativeMethodPrefix"
    ));

    private static final Set<String> DANGEROUS_DESCRIPTORS = new HashSet<>(Arrays.asList(
            "Ljava/lang/reflect/Method;", "Ljava/lang/reflect/Field;", "Ljava/lang/reflect/Constructor;",
            "Ljava/lang/invoke/MethodHandle;", "Ljava/lang/invoke/VarHandle;", "Ljava/lang/invoke/MethodHandles$Lookup;",
            "Lsun/misc/Unsafe;", "Ljdk/internal/misc/Unsafe;", "Ljava/lang/ClassLoader;",
            "Ljava/lang/Process;", "Ljava/lang/Runtime;"
    ));

    private static final Set<String> PROTECTED_PATTERNS = new HashSet<>(Arrays.asList(
            "EntityInit", "ItemInit", "BlockInit", "Registry", "DeferredRegister",
            "ModEventBus", "FMLCommonSetupEvent", "FMLClientSetupEvent",
            "EntityAttributeCreationEvent", "PacketHandler", "Config"
            /*"ModEntities", "ModItems", "ModBlocks", "ModBiomes", "ModEffects",
            "ModParticles", "ModSounds", "ModTileEntities", "ModBlockEntities",
            "ModMenuTypes", "ModRecipes", "ModEnchantments",
            "Register", "Setup", "Init", "ModMain", "EventBusSubscriber",
            "AttributeSupplier", "DefaultAttributes", "AttributeCreation",
            "EntitySpawn", "SpawnPlacement", "EntityRenderers",
            "NetworkInit", "PacketInit", "ChannelInit"*/
    ));

    private static final Set<String> SKIP_PREFIXES = new HashSet<>(Arrays.asList(
            "java/", "javax/", "sun/", "com/sun/", "jdk/", "org/openjdk/",
            "org/xml/", "org/ietf/", "org/w3c/", "org/jcp/",
            "org/objectweb/asm/", "org/spongepowered/", "io/netty/",
            "com/google/", "org/apache/", "org/slf4j/", "org/lwjgl/",
            "joptsimple/", "net/minecrell/", "org/jline/",
            "com/ryjs"
    ));

    private static final Set<String> OBJECT_METHODS = new HashSet<>(Arrays.asList(
            "toString", "equals", "hashCode", "clone", "finalize"
    ));

    private static final Map<String, String> SUPER_CLASS_CACHE = new ConcurrentHashMap<>();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;

        if (loader == null) return null;

        if (shouldSkipClass(className)) return null;

        if (com.ryjs.agent.CompatWhitelist.isWhitelistedClass(className)) return null;

        if (!firstWorkLogged) {
            firstWorkLogged = true;
            System.out.println("[AllReturn] pipeline active — 开始检查已加载类");
        }

        try {
            Class.forName("com.ryjs.agent.AllReturnUtil", false, loader);
        } catch (Throwable e) {
            if (!firstGateSkipLogged) {
                firstGateSkipLogged = true;
                System.out.println("[AllReturn] 目标类加载器看不到 AllReturnUtil，该类跳过: " + className);
            }
            return null;
        }

        if (className.startsWith("net/minecraft/world/entity/")) {
            return null;
        }


        if (className.startsWith("com/ryjs/")) {
            return null;
        }

        String simpleName = className.substring(className.lastIndexOf('/') + 1);
        for (String pattern : PROTECTED_PATTERNS) {
            if (simpleName.contains(pattern)) return null;
        }

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);


            if (isSkipType(classNode.access)) return null;

            ClassInfo classInfo = new ClassInfo(classNode.access, classNode.name,
                    classNode.superName,
                    classNode.interfaces != null ? classNode.interfaces : Collections.emptyList());

            boolean modified = false;
            for (MethodNode method : classNode.methods) {
                if (isSpecialMethod(method)) continue;

                if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
                    continue;
                }

                boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
                boolean isPrivate = (method.access & Opcodes.ACC_PRIVATE) != 0;
                if (isPrivate) continue;

                Type returnType = Type.getReturnType(method.desc);
                boolean isVoid = returnType.getSort() == Type.VOID;

                if (containsDangerousCalls(method)) {
                    clearMethodBodyWithCheck(method, returnType);
                    modified = true;
                    System.out.println("[AllReturn] Cleared dangerous method: " + className + "." + method.name);
                    continue;
                }

                if (isStatic) {
                    if (!isVoid) continue;
                    modified |= injectStaticVoidCheck(method);
                    continue;
                }

                boolean isBoolean = returnType.getSort() == Type.BOOLEAN;
                if (!isVoid && !isBoolean) continue;

                if (OBJECT_METHODS.contains(method.name)) continue;

                OverrideCheckResult override = checkOverrideSingleLevel(classInfo, method.name, method.desc, loader);
                if (override.isOverriding) {
                    modified |= injectSuperFallback(method, returnType, override);
                } else {

                    modified |= injectAllReturnCheck(method, isBoolean);
                }
            }

            if (modified) {

                try {
                    ClassWriter writer = new SafeClassWriter(reader,
                            ClassWriter.COMPUTE_FRAMES, loader);
                    classNode.accept(writer);
                    return writer.toByteArray();
                } catch (Throwable e) {
                    System.err.println("[AllReturn] COMPUTE_FRAMES failed for " + className +
                            ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    return null;
                }
            }
        } catch (Throwable e) {
            if (!firstErrorLogged) {
                firstErrorLogged = true;
                System.err.println("[AllReturn] 首次 transform 异常 @ " + className
                        + "（后续同类异常将被抑制）：");
                e.printStackTrace();
            }
        }

        return null;
    }

    private boolean shouldSkipClass(String className) {
        for (String prefix : SKIP_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    private boolean isSkipType(int access) {
        return (access & Opcodes.ACC_INTERFACE) != 0 ||
               (access & Opcodes.ACC_ENUM) != 0 ||
               (access & Opcodes.ACC_ANNOTATION) != 0 ||
               (access & Opcodes.ACC_MODULE) != 0 ||
               (access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    private boolean containsDangerousCalls(MethodNode method) {
        if (method.instructions == null) return false;

        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode minsn = (MethodInsnNode) insn;

                if (DANGEROUS_CLASSES.contains(minsn.owner)) {
                    if (DANGEROUS_METHODS.contains(minsn.name)) {
                        return true;
                    }
                }

                if (minsn.owner.startsWith("java/lang/reflect/")) {
                    if (minsn.name.equals("invoke") ||
                            minsn.name.equals("newInstance") ||
                            minsn.name.equals("set") ||
                            minsn.name.equals("setAccessible")) {
                        return true;
                    }
                }

                if (minsn.owner.startsWith("java/lang/invoke/")) {
                    if (DANGEROUS_METHODS.contains(minsn.name)) {
                        return true;
                    }
                }

                if (minsn.owner.contains("Unsafe")) {
                    return true;
                }

                if ("java/lang/System".equals(minsn.owner) && "exit".equals(minsn.name)) {
                    return true;
                }

                if ("java/lang/Runtime".equals(minsn.owner) && "exec".equals(minsn.name)) {
                    return true;
                }

                if ("java/lang/ProcessBuilder".equals(minsn.owner)) {
                    return true;
                }

                if (minsn.owner.contains("attach") || minsn.owner.contains("VirtualMachine")) {
                    return true;
                }

            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode finsn = (FieldInsnNode) insn;

                if (finsn.owner.contains("Unsafe") &&
                        ("theUnsafe".equals(finsn.name) || "THE_ONE".equals(finsn.name))) {
                    return true;
                }

                if (DANGEROUS_DESCRIPTORS.contains(finsn.desc)) {
                    return true;
                }

            } else if (insn instanceof LdcInsnNode) {
                LdcInsnNode ldc = (LdcInsnNode) insn;
                if (ldc.cst instanceof Type) {
                    Type type = (Type) ldc.cst;
                    String desc = type.getDescriptor();
                    if (DANGEROUS_DESCRIPTORS.contains(desc) ||
                            desc.contains("reflect") ||
                            desc.contains("invoke") ||
                            desc.contains("Unsafe")) {
                        return true;
                    }
                }

            } else if (insn instanceof TypeInsnNode) {
                TypeInsnNode tinsn = (TypeInsnNode) insn;
                String desc = tinsn.desc;

                if (desc.startsWith("java/lang/reflect/") ||
                        desc.contains("invoke/") ||
                        desc.contains("Unsafe") ||
                        desc.contains("Process") ||
                        desc.contains("ClassLoader")) {
                    return true;
                }

            } else if (insn instanceof MultiANewArrayInsnNode) {
                MultiANewArrayInsnNode mainn = (MultiANewArrayInsnNode) insn;
                if (mainn.desc.contains("reflect") || mainn.desc.contains("Unsafe")) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isSpecialMethod(MethodNode method) {
        String name = method.name;
        int access = method.access;
        return "<init>".equals(name) ||
               "<clinit>".equals(name) ||
               (access & Opcodes.ACC_ABSTRACT) != 0 ||
               (access & Opcodes.ACC_NATIVE) != 0 ||
               (access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    private boolean injectStaticVoidCheck(MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) return false;

        InsnList check = new InsnList();
        LabelNode continueLabel = new LabelNode();

        check.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/ryjs/agent/AllReturnUtil",
                "shouldAR",
                "()Z",
                false
        ));
        check.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));
        check.add(new InsnNode(Opcodes.RETURN));
        check.add(continueLabel);

        method.instructions.insert(check);
        return true;
    }


    private boolean injectAllReturnCheck(MethodNode method, boolean isBoolean) {
        if (method.instructions == null || method.instructions.size() == 0) return false;

        InsnList check = new InsnList();
        LabelNode continueLabel = new LabelNode();

        check.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/ryjs/agent/AllReturnUtil",
                "shouldAR",
                "()Z",
                false
        ));
        check.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        if (isBoolean) {
            check.add(new InsnNode(Opcodes.ICONST_0));
            check.add(new InsnNode(Opcodes.IRETURN));
        } else {
            check.add(new InsnNode(Opcodes.RETURN));
        }

        check.add(continueLabel);
        method.instructions.insert(check);
        return true;
    }


    private boolean injectSuperFallback(MethodNode method, Type returnType, OverrideCheckResult overrideCheckResult) {
        if (method.instructions == null || method.instructions.size() == 0) return false;


        if (overrideCheckResult.isInterface) {
            boolean isBoolean = returnType.getSort() == Type.BOOLEAN;
            return injectAllReturnCheck(method, isBoolean);
        }

        InsnList check = new InsnList();
        LabelNode continueLabel = new LabelNode();

        check.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/ryjs/agent/AllReturnUtil",
                "shouldAR",
                "()Z",
                false
        ));
        check.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));


        check.add(new VarInsnNode(Opcodes.ALOAD, 0));


        Type[] argTypes = Type.getArgumentTypes(method.desc);
        int index = 1;
        for (Type arg : argTypes) {
            int sort = arg.getSort();
            int opcode;
            if (sort == Type.INT || sort == Type.BYTE || sort == Type.CHAR || sort == Type.SHORT || sort == Type.BOOLEAN) {
                opcode = Opcodes.ILOAD;
            } else if (sort == Type.LONG) {
                opcode = Opcodes.LLOAD;
            } else if (sort == Type.FLOAT) {
                opcode = Opcodes.FLOAD;
            } else if (sort == Type.DOUBLE) {
                opcode = Opcodes.DLOAD;
            } else {
                opcode = Opcodes.ALOAD;
            }
            check.add(new VarInsnNode(opcode, index));
            index += arg.getSize();
        }


        String superClass = overrideCheckResult.targetSuperClass;
        check.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                superClass,
                method.name,
                method.desc,
                false
        ));


        int sort = returnType.getSort();
        if (sort == Type.VOID) {
            check.add(new InsnNode(Opcodes.RETURN));
        } else if (sort == Type.INT || sort == Type.BYTE || sort == Type.CHAR || sort == Type.SHORT || sort == Type.BOOLEAN) {
            check.add(new InsnNode(Opcodes.IRETURN));
        } else if (sort == Type.LONG) {
            check.add(new InsnNode(Opcodes.LRETURN));
        } else if (sort == Type.FLOAT) {
            check.add(new InsnNode(Opcodes.FRETURN));
        } else if (sort == Type.DOUBLE) {
            check.add(new InsnNode(Opcodes.DRETURN));
        } else {
            check.add(new InsnNode(Opcodes.ARETURN));
        }

        check.add(continueLabel);
        method.instructions.insert(check);
        return true;
    }


    private void clearMethodBodyWithCheck(MethodNode method, Type returnType) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;

        InsnList newInsns = new InsnList();
        // 直接返回默认值，不注入 shouldAR() 检查
        addDefaultReturn(newInsns, returnType);

        method.instructions = newInsns;
        method.maxStack = 2;
        method.maxLocals = 1;
    }


    private void addDefaultReturn(InsnList insns, Type returnType) {
        int sort = returnType.getSort();
        switch (sort) {
            case Type.VOID:
                insns.add(new InsnNode(Opcodes.RETURN));
                break;
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new InsnNode(Opcodes.IRETURN));
                break;
            case Type.LONG:
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new InsnNode(Opcodes.LRETURN));
                break;
            case Type.FLOAT:
                insns.add(new InsnNode(Opcodes.FCONST_0));
                insns.add(new InsnNode(Opcodes.FRETURN));
                break;
            case Type.DOUBLE:
                insns.add(new InsnNode(Opcodes.DCONST_0));
                insns.add(new InsnNode(Opcodes.DRETURN));
                break;
            case Type.ARRAY:
            case Type.OBJECT:
            default:
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new InsnNode(Opcodes.ARETURN));
                break;
        }
    }


    private OverrideCheckResult checkOverrideSingleLevel(ClassInfo classInfo, String methodName, String descriptor, ClassLoader loader) {
        OverrideCheckResult result = new OverrideCheckResult();


        if (classInfo.superName != null && !"java/lang/Object".equals(classInfo.superName)) {
            checkInClass(classInfo.superName, methodName, descriptor, loader, result);
            if (result.isOverriding) return result;
        }

        for (String iface : classInfo.interfaces) {
            checkInClass(iface, methodName, descriptor, loader, result);
            if (result.isOverriding) {
                result.isParentAbstract = true;
                result.isInterface = true;
                return result;
            }
        }

        return result;
    }

    private void checkInClass(String className, String methodName, String descriptor,
                              ClassLoader loader, OverrideCheckResult result) {
        if (className == null || className.equals("java/lang/Object") ||
            className.startsWith("java/") || className.startsWith("javax/") ||
            className.startsWith("sun/") || className.startsWith("jdk/")) {
            return;
        }

        InputStream is = null;
        try {
            is = loader.getResourceAsStream(className + ".class");
            if (is == null) return;

            ClassReader reader = new ClassReader(is);
            reader.accept(new MethodProbeVisitor(methodName, descriptor, className, result),
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException ignored) {
        } finally {
            if (is != null) {
                try { is.close(); } catch (IOException ignored) {}
            }
        }
    }


    private static class SafeClassWriter extends ClassWriter {
        private final ClassLoader loader;

        SafeClassWriter(ClassReader reader, int flags, ClassLoader loader) {
            super(reader, flags);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {

            if (type1.startsWith("[") || type2.startsWith("[")) return "java/lang/Object";
            if (type1.equals(type2)) return type1;
            if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object")) return "java/lang/Object";

            if (type1.length() <= 1 || type2.length() <= 1) return "java/lang/Object";

            boolean t1Std = type1.startsWith("java/") || type1.startsWith("javax/");
            boolean t2Std = type2.startsWith("java/") || type2.startsWith("javax/");

            if (t1Std && t2Std) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (Throwable e) {
                    return "java/lang/Object";
                }
            }

            try {
                Set<String> ancestors = new HashSet<>();
                String current = type1;
                while (current != null && !current.equals("java/lang/Object")) {
                    ancestors.add(current);
                    current = getSuperClassName(current, loader);
                }

                current = type2;
                while (current != null && !current.equals("java/lang/Object")) {
                    if (ancestors.contains(current)) {
                        return current;
                    }
                    current = getSuperClassName(current, loader);
                }
            } catch (Throwable ignored) {
            }

            return "java/lang/Object";
        }
    }


    private static String getSuperClassName(String className, ClassLoader loader) {
        String cached = SUPER_CLASS_CACHE.get(className);
        if (cached != null) return cached;

        if (className.startsWith("java/") || className.startsWith("javax/")) {
            try {
                Class<?> clazz = Class.forName(className.replace('/', '.'), false, loader);
                cached = clazz.getSuperclass() != null ?
                        clazz.getSuperclass().getName().replace('.', '/') :
                        "java/lang/Object";
            } catch (Throwable e) {
                cached = "java/lang/Object";
            }
        } else {

            try (InputStream is = loader.getResourceAsStream(className + ".class")) {
                if (is != null) {
                    ClassReader reader = new ClassReader(is);
                    cached = reader.getSuperName();
                }
            } catch (IOException ignored) {
            }
            if (cached == null) {
                cached = "java/lang/Object";
            }
        }

        SUPER_CLASS_CACHE.put(className, cached);
        return cached;
    }

    private static class ClassInfo {
        final int access;
        final String name;
        final String superName;
        final java.util.List<String> interfaces;

        ClassInfo(int access, String name, String superName, java.util.List<String> interfaces) {
            this.access = access;
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces;
        }
    }

    private static class OverrideCheckResult {
        boolean isOverriding = false;
        boolean isParentAbstract = false;
        boolean isInterface = false;
        String targetSuperClass = null;
    }


    private static class MethodProbeVisitor extends ClassVisitor {
        private final String methodName;
        private final String descriptor;
        private final String owner;
        private final OverrideCheckResult result;

        MethodProbeVisitor(String methodName, String descriptor, String owner, OverrideCheckResult result) {
            super(Opcodes.ASM9, null);
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.owner = owner;
            this.result = result;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            if (name.equals(methodName) && desc.equals(descriptor)) {
                result.isOverriding = true;
                result.targetSuperClass = owner;
            }
            return null;
        }
    }


    public static void preload() {
        Class<?>[] touch = {
                SafeClassWriter.class, ClassInfo.class, OverrideCheckResult.class, MethodProbeVisitor.class
        };
        if (touch.length != 4) {
            throw new IllegalStateException("unreachable");
        }
        System.out.println("[AllReturn] preload 完成");
    }
}
