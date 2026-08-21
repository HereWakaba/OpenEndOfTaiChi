package com.ryjs.agent.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;


public class AntiExitTransformer implements ClassFileTransformer {

    private static volatile boolean firstErrorLogged = false;

    private static volatile boolean firstWorkLogged = false;


    public static void preload() {
        try {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "reflectionpreloadprobe/AntiExitProbe",
                    null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "probe", "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
            cw.visitEnd();
            new AntiExitTransformer().transform(AntiExitTransformer.class.getClassLoader(),
                    "reflectionpreloadprobe/AntiExitProbe", null, null, cw.toByteArray());
            System.out.println("[AntiExit] preload 完成");
        } catch (Throwable t) {
            System.err.println("[AntiExit] preload 失败: " + t);
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, final String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }

        if (className.startsWith("java/") || className.startsWith("javax/")
                || className.startsWith("sun/") || className.startsWith("jdk/")
                || className.startsWith("com/sun/")
                || className.startsWith("net/minecraft/") || className.startsWith("net/minecraftforge/")
                || className.startsWith("cpw/mods/") || className.startsWith("com/mojang/")
                || className.startsWith("org/spongepowered/") || className.startsWith("org/objectweb/")
                || className.startsWith("com/ryjs/")) {
            return null;
        }


        if (com.ryjs.agent.CompatWhitelist.isWhitelistedClass(className)) {
            return null;
        }

        if (!firstWorkLogged && !className.startsWith("reflectionpreloadprobe")) {
            firstWorkLogged = true;
            System.out.println("[AntiExit] pipeline active");
        }

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            final boolean[] modified = {false};

            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, final String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        private int removedCount = 0;

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mName,
                                                    String desc, boolean isInterface) {

                            if (opcode == Opcodes.INVOKESTATIC
                                    && "java/lang/System".equals(owner)
                                    && "exit".equals(mName)
                                    && "(I)V".equals(desc)) {
                                super.visitInsn(Opcodes.POP);
                                super.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err",
                                        "Ljava/io/PrintStream;");
                                super.visitLdcInsn("[AntiExit] blocked System.exit() from " + className);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
                                        "println", "(Ljava/lang/String;)V", false);
                                removedCount++;
                                modified[0] = true;
                                return;
                            }

                            if (opcode == Opcodes.INVOKEVIRTUAL
                                    && "java/lang/Runtime".equals(owner)
                                    && "halt".equals(mName)
                                    && "(I)V".equals(desc)) {
                                super.visitInsn(Opcodes.POP);
                                super.visitInsn(Opcodes.POP);
                                super.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err",
                                        "Ljava/io/PrintStream;");
                                super.visitLdcInsn("[AntiExit] blocked Runtime.halt() from " + className);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
                                        "println", "(Ljava/lang/String;)V", false);
                                removedCount++;
                                modified[0] = true;
                                return;
                            }


                            if (opcode == Opcodes.INVOKEVIRTUAL
                                    && "java/lang/ProcessBuilder".equals(owner)
                                    && "start".equals(mName)
                                    && "()Ljava/lang/Process;".equals(desc)) {
                                super.visitInsn(Opcodes.POP); // 弹掉 ProcessBuilder 实例
                                super.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err",
                                        "Ljava/io/PrintStream;");
                                super.visitLdcInsn("[AntiExit] blocked ProcessBuilder.start() from " + className);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
                                        "println", "(Ljava/lang/String;)V", false);
                                super.visitInsn(Opcodes.ACONST_NULL);
                                removedCount++;
                                modified[0] = true;
                                return;
                            }

                            if (opcode == Opcodes.INVOKEVIRTUAL
                                    && "java/lang/Runtime".equals(owner)
                                    && "exec".equals(mName)) {
                                int argSlots = countArgSlots(desc);
                                for (int i = 0; i < argSlots; i++) {
                                    super.visitInsn(Opcodes.POP);
                                }
                                super.visitInsn(Opcodes.POP);
                                super.visitInsn(Opcodes.ACONST_NULL);
                                removedCount++;
                                modified[0] = true;
                                return;
                            }

                            super.visitMethodInsn(opcode, owner, mName, desc, isInterface);
                        }

                        @Override
                        public void visitEnd() {
                            if (removedCount > 0) {
                                System.out.println("[AntiExit] removed " + removedCount
                                        + " dangerous calls in " + className.replace('/', '.') + "." + name);
                            }
                            super.visitEnd();
                        }
                    };
                }
            };

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (modified[0]) {
                return writer.toByteArray();
            }
        } catch (Throwable e) {

            if (!firstErrorLogged) {
                firstErrorLogged = true;
                System.err.println("[AntiExit] 首次 transform 异常 @ " + className
                        + "（后续同类异常将被抑制）：");
                e.printStackTrace();
            }
        }

        return null;
    }


    private static int countArgSlots(String descriptor) {
        int slots = 0;
        int i = 1; // 跳过 '('
        while (i < descriptor.length() && descriptor.charAt(i) != ')') {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int semi = descriptor.indexOf(';', i);
                i = semi + 1;
                slots++;
            } else if (c == '[') {
                while (i < descriptor.length() && descriptor.charAt(i) == '[') {
                    i++;
                }
                if (i < descriptor.length() && descriptor.charAt(i) == 'L') {
                    int semi = descriptor.indexOf(';', i);
                    i = semi + 1;
                } else {
                    i++;
                }
                slots++;
            } else {
                if (c == 'J' || c == 'D') {
                    slots += 2;
                } else {
                    slots++;
                }
                i++;
            }
        }
        return slots;
    }
}
