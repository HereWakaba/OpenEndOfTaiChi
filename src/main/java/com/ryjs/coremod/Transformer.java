package com.ryjs.coremod;

import com.ryjs.coremod.ImmediateWindowProvider.EartyLoading;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.HashSet;
import java.util.Set;

public class Transformer implements ITransformer<ClassNode> {

    private static final String LOG_TAG = "[ExecutorTransformer]";

    private static final Set<String> SYSTEM_TARGETS = Set.of(
            "java/lang/System",
            "java/lang/Runtime",
            "java/lang/ProcessBuilder",
            "com/sun/tools/attach/VirtualMachine",
            "java/lang/ClassLoader"
    );

    private static final Set<String> SPECIAL_METHODS = Set.of("<init>", "<clinit>");
    private static final Set<String> OBJECT_METHODS = Set.of("toString", "equals", "hashCode", "clone", "finalize");

    private static final Set<String> DANGEROUS_OWNERS = Set.of(
            "java/lang/System", "java/lang/Runtime", "java/lang/ProcessBuilder",
            "sun/misc/Unsafe", "jdk/internal/misc/Unsafe",
            "java/lang/reflect/Method", "java/lang/reflect/Field",
            "java/lang/reflect/Constructor", "java/lang/reflect/AccessibleObject",
            "java/lang/invoke/MethodHandles", "java/lang/invoke/MethodHandle",
            "java/lang/invoke/VarHandle", "java/lang/ClassLoader",
            "java/security/AccessController"
    );
    private static final Set<String> DANGEROUS_METHODS = Set.of(
            "exit", "exec", "getRuntime", "load", "loadLibrary",
            "setAccessible", "getDeclaredField", "getDeclaredMethod",
            "allocateInstance", "defineClass", "defineAnonymousClass",
            "lookup", "findVirtual", "findStatic", "findSpecial",
            "set", "get", "invoke", "halt"
    );

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        String className = input.name;

        if (SYSTEM_TARGETS.contains(className)) {
            return transformSystemClass(className, input);
        }

        return transformModClass(input);
    }

    // ═══════════════════════════════════════════════════════════
    //  JDK 系统类保护
    // ═══════════════════════════════════════════════════════════

    private ClassNode transformSystemClass(String className, ClassNode node) {
        boolean modified = false;

        switch (className) {
            case "java/lang/System":
                modified = handleSystemExit(node);
                break;
            case "java/lang/Runtime":
                modified = handleRuntimeExit(node);
                break;
            case "java/lang/ProcessBuilder":
                modified = handleProcessBuilder(node);
                break;
            case "com/sun/tools/attach/VirtualMachine":
                modified = handleVirtualMachineAttach(node);
                break;
            case "java/lang/ClassLoader":
                modified = handleClassLoaderLoadLibrary(node);
                break;
        }

        if (modified) {
            System.out.println(LOG_TAG + " Protected system class: " + className);
        }
        return node;
    }

    private boolean handleSystemExit(ClassNode node) {
        boolean modified = false;
        for (MethodNode m : node.methods) {
            if (!"exit".equals(m.name) || !"(I)V".equals(m.desc)) continue;
            m.instructions.clear();
            m.tryCatchBlocks.clear();
            InsnList insns = new InsnList();
            insns.add(new InsnNode(Opcodes.RETURN));
            m.instructions = insns;
            m.maxStack = 0;
            m.maxLocals = 1;
            System.out.println(LOG_TAG + " System.exit() neutralized");
            modified = true;
        }
        return modified;
    }

    private boolean handleRuntimeExit(ClassNode node) {
        boolean modified = false;
        for (MethodNode m : node.methods) {
            if ((!"halt".equals(m.name) && !"exit".equals(m.name)) || !"(I)V".equals(m.desc)) continue;
            m.instructions.clear();
            m.tryCatchBlocks.clear();
            InsnList insns = new InsnList();
            insns.add(new InsnNode(Opcodes.RETURN));
            m.instructions = insns;
            m.maxStack = 0;
            m.maxLocals = 1;
            System.out.println(LOG_TAG + " Runtime." + m.name + "() neutralized");
            modified = true;
        }
        return modified;
    }

    private boolean handleProcessBuilder(ClassNode node) {
        boolean modified = false;
        for (MethodNode m : node.methods) {
            if (!"start".equals(m.name) || !"()Ljava/lang/Process;".equals(m.desc)) continue;

            InsnList check = new InsnList();
            LabelNode allowed = new LabelNode();
            LabelNode loopStart = new LabelNode();
            LabelNode loopEnd = new LabelNode();

            check.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Throwable"));
            check.add(new InsnNode(Opcodes.DUP));
            check.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Throwable", "<init>", "()V", false));
            check.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable", "getStackTrace",
                    "()[Ljava/lang/StackTraceElement;", false));
            check.add(new VarInsnNode(Opcodes.ASTORE, 1));

            check.add(new InsnNode(Opcodes.ICONST_0));
            check.add(new VarInsnNode(Opcodes.ISTORE, 2));
            check.add(new InsnNode(Opcodes.ICONST_0));
            check.add(new VarInsnNode(Opcodes.ISTORE, 3));

            check.add(loopStart);
            check.add(new VarInsnNode(Opcodes.ILOAD, 3));
            check.add(new VarInsnNode(Opcodes.ALOAD, 1));
            check.add(new InsnNode(Opcodes.ARRAYLENGTH));
            check.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

            check.add(new VarInsnNode(Opcodes.ALOAD, 1));
            check.add(new VarInsnNode(Opcodes.ILOAD, 3));
            check.add(new InsnNode(Opcodes.AALOAD));
            check.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StackTraceElement", "getClassName",
                    "()Ljava/lang/String;", false));
            check.add(new LdcInsnNode("net.minecraft.executordragon"));
            check.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith",
                    "(Ljava/lang/String;)Z", false));
            check.add(new JumpInsnNode(Opcodes.IFNE, allowed));

            check.add(new IincInsnNode(3, 1));
            check.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

            check.add(allowed);
            check.add(new InsnNode(Opcodes.ICONST_1));
            check.add(new VarInsnNode(Opcodes.ISTORE, 2));

            check.add(loopEnd);
            check.add(new VarInsnNode(Opcodes.ILOAD, 2));
            LabelNode proceed = new LabelNode();
            check.add(new JumpInsnNode(Opcodes.IFNE, proceed));
            check.add(new TypeInsnNode(Opcodes.NEW, "java/lang/SecurityException"));
            check.add(new InsnNode(Opcodes.DUP));
            check.add(new LdcInsnNode("ProcessBuilder.start() blocked by ExecutorTransformer"));
            check.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/SecurityException", "<init>",
                    "(Ljava/lang/String;)V", false));
            check.add(new InsnNode(Opcodes.ATHROW));
            check.add(proceed);

            AbstractInsnNode first = m.instructions.getFirst();
            while (first != null && (first instanceof LabelNode || first instanceof LineNumberNode))
                first = first.getNext();
            if (first != null) m.instructions.insertBefore(first, check);
            else m.instructions.add(check);

            if (m.maxLocals < 4) m.maxLocals = 4;
            if (m.maxStack < 5) m.maxStack = 5;
            System.out.println(LOG_TAG + " ProcessBuilder.start() guarded");
            modified = true;
        }
        return modified;
    }

    private boolean handleVirtualMachineAttach(ClassNode node) {
        boolean modified = false;
        for (MethodNode m : node.methods) {
            if (!"attach".equals(m.name)) continue;
            if (!m.desc.startsWith("(Ljava/lang/String;")) continue;
            m.instructions.clear();
            m.tryCatchBlocks.clear();
            InsnList insns = new InsnList();
            insns.add(new InsnNode(Opcodes.ACONST_NULL));
            insns.add(new InsnNode(Opcodes.ARETURN));
            m.instructions = insns;
            m.maxStack = 1;
            m.maxLocals = 1;
            System.out.println(LOG_TAG + " VirtualMachine.attach() neutralized");
            modified = true;
        }
        return modified;
    }

    private boolean handleClassLoaderLoadLibrary(ClassNode node) {
        boolean modified = false;
        for (MethodNode m : node.methods) {
            if (!"loadLibrary".equals(m.name)) continue;
            if (!"(Ljava/lang/Class;Ljava/lang/String;)V".equals(m.desc)) continue;

            InsnList hijack = new InsnList();
            hijack.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hijack.add(new LdcInsnNode(".dll"));
            hijack.add(new LdcInsnNode(".d11"));
            hijack.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replace",
                    "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;", false));
            hijack.add(new VarInsnNode(Opcodes.ASTORE, 1));

            AbstractInsnNode first = m.instructions.getFirst();
            while (first != null && (first instanceof LabelNode || first instanceof LineNumberNode))
                first = first.getNext();
            if (first != null) m.instructions.insertBefore(first, hijack);
            else m.instructions.add(hijack);

            if (m.maxStack < 3) m.maxStack = 3;
            System.out.println(LOG_TAG + " ClassLoader.loadLibrary() hijacked (.dll -> .d11)");
            modified = true;
        }
        return modified;
    }

    // ═══════════════════════════════════════════════════════════
    //  Mod 类 AllReturn 注入
    // ═══════════════════════════════════════════════════════════

    // AllReturn skip targets — 与 ModClassScanner.PACKAGE_WHITELIST 对齐
    // 这里做二次兜底，防止扫描遗漏或运行时动态加载的类
    private static final Set<String> SKIP_PACKAGES = Set.of(
        "com/ryjs/",
        "cpw/mods/modlauncher/MyCore/",
        "net/minecraft/",
        "com/mojang/",
        "net/minecraftforge/",
        "org/apache/",
        "org/slf4j/",
        "org/objectweb/asm/",
        "it/unimi/dsi/fastutil/",
        "com/google/"
    );

    private ClassNode transformModClass(ClassNode input) {
        String className = input.name;

        // Skip whitelisted packages
        for (String pkg : SKIP_PACKAGES) {
            if (className.startsWith(pkg)) return input;
        }

        doAllReturn(input);
        return input;
    }

    /** Shared AllReturn injection logic */
    private boolean doAllReturn(ClassNode input) {
        String className = input.name;
        boolean modified = false;

        if ((input.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM | Opcodes.ACC_ANNOTATION)) != 0) {
            return false;
        }

        for (MethodNode method : input.methods) {
            if (SPECIAL_METHODS.contains(method.name)) continue;
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            if ((method.access & Opcodes.ACC_PRIVATE) != 0) continue;

            Type returnType = Type.getReturnType(method.desc);
            boolean isVoid = returnType.getSort() == Type.VOID;

            if (containsDangerousCalls(method)) {
                clearMethodBody(method, returnType);
                modified = true;
                System.out.println(LOG_TAG + " Cleared dangerous method: " + className + "." + method.name);
                continue;
            }

            boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
            if (isStatic) {
                if (!isVoid) continue;
                modified |= injectReturnCheck(method, returnType);
                continue;
            }

            boolean isBoolean = returnType.getSort() == Type.BOOLEAN;
            if (!isVoid && !isBoolean) continue;
            if (OBJECT_METHODS.contains(method.name)) continue;

            modified |= injectReturnCheck(method, returnType);
        }

        if (modified) {
            System.out.println(LOG_TAG + " Modified mod class: " + className);
        }

        return modified;
    }

    private boolean injectReturnCheck(MethodNode method, Type returnType) {
        if (method.instructions == null || method.instructions.size() == 0) return false;
        InsnList insns = new InsnList();
        LabelNode cont = new LabelNode();
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "net/minecraft/executordragon/power/TransformerToggle",
                "shouldReturn", "()Z", false));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, cont));
        addDefaultReturn(insns, returnType);
        insns.add(cont);
        method.instructions.insert(insns);
        return true;
    }

    private void clearMethodBody(MethodNode method, Type returnType) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        InsnList insns = new InsnList();
        addDefaultReturn(insns, returnType);
        method.instructions = insns;
        method.maxStack = 2;
        method.maxLocals = 1;
    }

    private void addDefaultReturn(InsnList insns, Type returnType) {
        switch (returnType.getSort()) {
            case Type.VOID -> insns.add(new InsnNode(Opcodes.RETURN));
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.LONG -> {
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.FLOAT -> {
                insns.add(new InsnNode(Opcodes.FCONST_0));
                insns.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.DOUBLE -> {
                insns.add(new InsnNode(Opcodes.DCONST_0));
                insns.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new InsnNode(Opcodes.ARETURN));
            }
        }
    }

    private boolean containsDangerousCalls(MethodNode method) {
        if (method.instructions == null) return false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode m) {
                if (DANGEROUS_OWNERS.contains(m.owner) && DANGEROUS_METHODS.contains(m.name))
                    return true;
                if (m.owner.contains("Unsafe")) return true;
            }
            if (insn instanceof FieldInsnNode f) {
                if (f.owner.contains("Unsafe") && ("theUnsafe".equals(f.name) || "THE_ONE".equals(f.name)))
                    return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    //  ITransformer 接口
    // ═══════════════════════════════════════════════════════════

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        Set<String> modClasses = EartyLoading.targetClasses;
        int total = SYSTEM_TARGETS.size() + modClasses.size();
        Set<Target> targets = new HashSet<>(total);
        for (String cls : SYSTEM_TARGETS) {
            targets.add(Target.targetClass(cls));
        }
        for (String cls : modClasses) {
            targets.add(Target.targetClass(cls));
        }
        return targets;
    }
}
