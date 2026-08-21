package com.ryjs.coremod.Agent.transformers;

import java.lang.instrument.ClassFileTransformer;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Locale;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;


public final class CoremodNeuterTransformer implements ClassFileTransformer {
    
    private static final Set<String> BLOCKED_SPI = Set.of(
            "cpw/mods/modlauncher/api/ITransformationService",
            "cpw/mods/modlauncher/serviceapi/ILaunchPluginService",
            "cpw/mods/modlauncher/api/ITransformer",
            "cpw/mods/modlauncher/serviceapi/ITransformerDiscoveryService",
            "net/minecraftforge/fml/loading/ImmediateWindowProvider",
            "java/lang/instrument/ClassFileTransformer"
    );
    
    private static final String OUR_JAR = computeOwnJar();

    private static String computeOwnJar() {
        try {
            CodeSource cs = CoremodNeuterTransformer.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                return normalizeLocation(cs.getLocation().toString());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (classfileBuffer == null || className == null) {
            return null;
        }
        if (!com.ryjs.agent.DefenseConfig.interceptCoremod() && !com.ryjs.agent.DefenseConfig.proxyShell()) {
            return null; 
        }

        if (className.startsWith("com/ryjs/")) {
            return null;
        }

        if (com.ryjs.agent.CompatWhitelist.isWhitelistedClass(className)) {
            return null;
        }

        String srcJar = jarPathOf(protectionDomain);
        if (srcJar == null) {
            return null; // 拿不到来源，保守放行
        }

        if (!srcJar.contains("/mods/") || srcJar.contains("/libraries/")) {
            return null;
        }

        if (OUR_JAR != null && OUR_JAR.equals(srcJar)) {
            return null;
        }
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            boolean isSpi = false;
            for (String itf : cr.getInterfaces()) {
                if (BLOCKED_SPI.contains(itf)) {
                    isSpi = true;
                    break;
                }
            }
            if (!isSpi) {
                return null;
            }
            final String internalName = className;
            ObjectFallbackClassWriter cw = new ObjectFallbackClassWriter(ClassWriter.COMPUTE_FRAMES);
            NeuterClassVisitor cv = new NeuterClassVisitor(cw, internalName, cr.getSuperName());
            cr.accept(cv, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            System.out.println("已中和第三方SPI类: " + className);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("中和失败，放行原类: " + className + " -> " + t);
            return null; // 失败绝不产出非法字节码
        }
    }

    private static String jarPathOf(ProtectionDomain pd) {
        try {
            if (pd == null) {
                return null;
            }
            CodeSource cs = pd.getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            return normalizeLocation(cs.getLocation().toString());
        } catch (Throwable t) {
            return null;
        }
    }


    private static String normalizeLocation(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String path = raw;
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
        int jarIdx = path.lastIndexOf(".jar");
        if (jarIdx != -1) {
            path = path.substring(0, jarIdx + 4);
        }
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

   public static void preload() {
        Class<?>[] touch = {
                ObjectFallbackClassWriter.class,
                NeuterClassVisitor.class,
                BodyGutVisitor.class,
                CtorGutVisitor.class
        };
        if (touch.length != 4) {
            throw new IllegalStateException("unreachable");
        }
    }

    private static final class ObjectFallbackClassWriter extends ClassWriter {
        ObjectFallbackClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }

    private static final class NeuterClassVisitor extends ClassVisitor {
        private final String ownerInternalName;
        private final String superName;

        NeuterClassVisitor(ClassVisitor cv, String ownerInternalName, String superName) {
            super(Opcodes.ASM9, cv);
            this.ownerInternalName = ownerInternalName;
            this.superName = superName;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return mv; // 抽象/native 无方法体
            }
            if ("<init>".equals(name)) {
                return new CtorGutVisitor(mv, ownerInternalName, superName);
            }
            return new BodyGutVisitor(mv, descriptor, ownerInternalName);
        }
    }

    private static final class BodyGutVisitor extends MethodVisitor {
        private final Type returnType;
        private final String ownerInternalName;

        BodyGutVisitor(MethodVisitor mv, String descriptor, String ownerInternalName) {
            super(Opcodes.ASM9, mv);
            this.returnType = Type.getReturnType(descriptor);
            this.ownerInternalName = ownerInternalName;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            switch (returnType.getSort()) {
                case Type.VOID:
                    mv.visitInsn(Opcodes.RETURN);
                    break;
                case Type.FLOAT:
                    mv.visitInsn(Opcodes.FCONST_0);
                    mv.visitInsn(Opcodes.FRETURN);
                    break;
                case Type.LONG:
                    mv.visitInsn(Opcodes.LCONST_0);
                    mv.visitInsn(Opcodes.LRETURN);
                    break;
                case Type.DOUBLE:
                    mv.visitInsn(Opcodes.DCONST_0);
                    mv.visitInsn(Opcodes.DRETURN);
                    break;
                case Type.ARRAY:
                case Type.OBJECT:
                    if ("java/lang/String".equals(returnType.getInternalName())) {
                        // name() 等 String 方法：返回类名，非 null 且唯一，避免 toMap 空键崩溃。
                        mv.visitLdcInsn(ownerInternalName.replace('/', '.'));
                    } else {
                        mv.visitInsn(Opcodes.ACONST_NULL);
                    }
                    mv.visitInsn(Opcodes.ARETURN);
                    break;
                default: // boolean/char/byte/short/int
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                    break;
            }
            mv.visitMaxs(0, 0); // COMPUTE_FRAMES 会重算
        }

        @Override public void visitInsn(int opcode) { }
        @Override public void visitIntInsn(int opcode, int operand) { }
        @Override public void visitVarInsn(int opcode, int var) { }
        @Override public void visitTypeInsn(int opcode, String type) { }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) { }
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) { }
        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) { }
        @Override public void visitJumpInsn(int opcode, Label label) { }
        @Override public void visitLabel(Label label) { }
        @Override public void visitLdcInsn(Object value) { }
        @Override public void visitIincInsn(int var, int increment) { }
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) { }
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) { }
        @Override public void visitMultiANewArrayInsn(String descriptor, int numDimensions) { }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { }
        @Override public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) { }
        @Override public void visitLineNumber(int line, Label start) { }
        @Override public void visitLocalVariable(String name, String desc, String sig, Label start, Label end, int index) { }
        @Override public void visitMaxs(int maxStack, int maxLocals) { } // 忽略原 maxs（已在 visitCode emit）
    }

    private static final class CtorGutVisitor extends MethodVisitor {
        private final String ownName;
        private final String superName;
        private boolean superDone = false;
        private int pendingNew = 0;
    
        CtorGutVisitor(MethodVisitor mv, String ownName, String superName) {
            super(Opcodes.ASM9, mv);
            this.ownName = ownName;
            this.superName = superName;
        }
    
        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (superDone) {
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) {
                if (pendingNew > 0) {
                    pendingNew--; // 初始化的是 new 出来的实参对象，继续透传，不当终点
                } else {
                    superDone = true;
                    super.visitInsn(Opcodes.RETURN);
                }
            }
        }

        @Override public void visitInsn(int opcode) { if (!superDone) super.visitInsn(opcode); }
        @Override public void visitIntInsn(int opcode, int operand) { if (!superDone) super.visitIntInsn(opcode, operand); }
        @Override public void visitVarInsn(int opcode, int var) { if (!superDone) super.visitVarInsn(opcode, var); }
        @Override public void visitTypeInsn(int opcode, String type) { if (!superDone) { if (opcode == Opcodes.NEW) pendingNew++; super.visitTypeInsn(opcode, type); } }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) { if (!superDone) super.visitFieldInsn(opcode, owner, name, descriptor); }
        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) { if (!superDone) super.visitInvokeDynamicInsn(name, descriptor, bsm, bsmArgs); }
        @Override public void visitJumpInsn(int opcode, Label label) { if (!superDone) super.visitJumpInsn(opcode, label); }
        @Override public void visitLabel(Label label) { if (!superDone) super.visitLabel(label); }
        @Override public void visitLdcInsn(Object value) { if (!superDone) super.visitLdcInsn(value); }
        @Override public void visitIincInsn(int var, int increment) { if (!superDone) super.visitIincInsn(var, increment); }
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) { if (!superDone) super.visitTableSwitchInsn(min, max, dflt, labels); }
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) { if (!superDone) super.visitLookupSwitchInsn(dflt, keys, labels); }
        @Override public void visitMultiANewArrayInsn(String descriptor, int numDimensions) { if (!superDone) super.visitMultiANewArrayInsn(descriptor, numDimensions); }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { } // 砍掉 try/catch，保持线性
        @Override public void visitLineNumber(int line, Label start) { }
        @Override public void visitLocalVariable(String name, String desc, String sig, Label start, Label end, int index) { }
        @Override public void visitMaxs(int maxStack, int maxLocals) { super.visitMaxs(maxStack, maxLocals); } // 必须委托以触发 COMPUTE_FRAMES 重算 maxStack（否则 maxStack=0→aload_0 栈溢出 VerifyError）
    }
}
