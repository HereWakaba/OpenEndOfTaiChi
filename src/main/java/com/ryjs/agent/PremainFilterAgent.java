package com.ryjs.agent;

import com.ryjs.api.org.objectweb.asm.*;
import com.ryjs.api.org.objectweb.asm.commons.GeneratorAdapter;
import com.ryjs.api.org.objectweb.asm.commons.Method;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Iterator;
import java.util.List;


public final class PremainFilterAgent {

    private PremainFilterAgent() {}

    private static final String WHITELIST = "reflection";

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[PremainFilter] premain 启动——注册 candidates 完整过滤 transformer");
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String className,
                                    Class<?> classBeingRedefined, ProtectionDomain domain,
                                    byte[] classfileBuffer) {
                if (className != null && className.equals("net/minecraftforge/fml/loading/ModDirTransformerDiscoverer")) {
                    try {
                        ClassReader cr = new ClassReader(classfileBuffer);
                        ClassWriter cw = new ClassWriter(3);
                        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                            @Override
                            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                             String signature, String[] exceptions) {
                                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                                if (name.equals("candidates")
                                        && descriptor.equals("(Ljava/nio/file/Path;)Ljava/util/List;")) {
                                    System.out.println("[PremainFilter] 命中 candidates(Path)——注入内联过滤代码");
                                    return new GeneratorAdapter(api, mv, access, name, descriptor) {
                                        @Override
                                        public void visitCode() {
                                            super.visitCode();
                                        }

                                        @Override
                                        public void visitMethodInsn(int opcode, String owner, String name,
                                                                    String descriptor, boolean isInterface) {

                                            if (opcode == Opcodes.INVOKESTATIC && name.equals("copyOf")
                                                    && owner.equals("java/util/List")) {
                                                System.out.println("[PremainFilter] 命中 copyOf——在拷贝前注入过滤");
                                                injectFilter(this);
                                            }
                                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                                        }
                                    };
                                }
                                return mv;
                            }
                        };
                        cr.accept(cv, 0);
                        System.out.println("[PremainFilter] ModDirTransformerDiscoverer 处理完成");
                        return cw.toByteArray();
                    } catch (Throwable t) {
                        System.err.println("[PremainFilter] 注入失败: " + t);
                    }
                }

                return null;
            }
        }, true);
    }


    private static void injectFilter(GeneratorAdapter ga) {
        int iterIdx = ga.newLocal(Type.getType(Iterator.class));
        int namedPathIdx = ga.newLocal(Type.getType("Lcpw/mods/modlauncher/api/NamedPath;"));
        int pathArrayIdx = ga.newLocal(Type.getType(Path[].class));
        int pathIdx = ga.newLocal(Type.getType(Path.class));
        int iIdx = ga.newLocal(Type.INT_TYPE);
        int isSelfIdx = ga.newLocal(Type.BOOLEAN_TYPE);

        ga.getStatic(Type.getType("Lnet/minecraftforge/fml/loading/ModDirTransformerDiscoverer;"),
                "found", Type.getType(List.class));
        ga.invokeInterface(Type.getType(List.class), Method.getMethod("java.util.Iterator iterator()"));
        ga.storeLocal(iterIdx);

        Label loopStart = ga.newLabel();
        Label loopEnd = ga.newLabel();
        Label continueLoop = ga.newLabel();
        ga.mark(loopStart);
        ga.loadLocal(iterIdx);
        ga.invokeInterface(Type.getType(Iterator.class), Method.getMethod("boolean hasNext()"));
        ga.visitJumpInsn(Opcodes.IFEQ, loopEnd); // 153 IFEQ

        ga.loadLocal(iterIdx);
        ga.invokeInterface(Type.getType(Iterator.class), Method.getMethod("Object next()"));
        ga.checkCast(Type.getType("Lcpw/mods/modlauncher/api/NamedPath;"));
        ga.storeLocal(namedPathIdx);

        ga.loadLocal(namedPathIdx);
        ga.invokeVirtual(Type.getType("Lcpw/mods/modlauncher/api/NamedPath;"),
                Method.getMethod("java.nio.file.Path[] paths()"));
        ga.storeLocal(pathArrayIdx);

        ga.push(false);
        ga.storeLocal(isSelfIdx);
        ga.push(0);
        ga.storeLocal(iIdx);
        Label forStart = ga.newLabel();
        Label forCheck = ga.newLabel();
        Label foundSelf = ga.newLabel();
        ga.goTo(forCheck);
        ga.mark(forStart);
        ga.loadLocal(pathArrayIdx);
        ga.loadLocal(iIdx);
        ga.arrayLoad(Type.getType(Path[].class).getElementType());
        ga.storeLocal(pathIdx);
        ga.iinc(iIdx, 1);
        ga.loadLocal(pathIdx);
        ga.invokeInterface(Type.getType(Path.class), Method.getMethod("String toString()"));
        int strIdx = ga.newLocal(Type.getType(String.class));
        ga.storeLocal(strIdx);
        // 双关键字检查（保持 pig2 结构——同一关键字两次）
        ga.loadLocal(strIdx);
        ga.push(WHITELIST);
        ga.invokeVirtual(Type.getType(String.class), Method.getMethod("boolean contains(java.lang.CharSequence)"));
        Label labelAcceptable = ga.newLabel();
        ga.visitJumpInsn(Opcodes.IFNE, labelAcceptable); // 154 IFNE
        ga.loadLocal(strIdx);
        ga.push(WHITELIST);
        ga.invokeVirtual(Type.getType(String.class), Method.getMethod("boolean contains(java.lang.CharSequence)"));
        ga.visitJumpInsn(Opcodes.IFEQ, forCheck); // 153 IFEQ
        ga.visitLabel(labelAcceptable);
        ga.push(true);
        ga.storeLocal(isSelfIdx);
        ga.goTo(foundSelf);
        ga.mark(forCheck);
        ga.loadLocal(iIdx);
        ga.loadLocal(pathArrayIdx);
        ga.arrayLength();
        ga.visitJumpInsn(Opcodes.IF_ICMPLT, forStart); // 161 IF_ICMPLT
        ga.mark(foundSelf);
        ga.loadLocal(isSelfIdx);
        ga.visitJumpInsn(Opcodes.IFNE, continueLoop); // 154 IFNE

        int nameIdx = ga.newLocal(Type.getType(String.class));

        ga.loadLocal(namedPathIdx);
        ga.invokeVirtual(Type.getType("Lcpw/mods/modlauncher/api/NamedPath;"),
                Method.getMethod("java.lang.String name()"));
        ga.storeLocal(nameIdx);

        ga.loadLocal(nameIdx);
        ga.push("IModLocator");
        ga.invokeVirtual(Type.getType(String.class),
                Method.getMethod("boolean contains(java.lang.CharSequence)"));
        ga.visitJumpInsn(Opcodes.IFNE, continueLoop); // 154 IFNE

        ga.loadLocal(nameIdx);
        ga.push("IDependencyLocator");
        ga.invokeVirtual(Type.getType(String.class),
                Method.getMethod("boolean contains(java.lang.CharSequence)"));
        ga.visitJumpInsn(Opcodes.IFNE, continueLoop); // 154 IFNE

        ga.getStatic(Type.getType(System.class), "out", Type.getType(java.io.PrintStream.class));
        ga.newInstance(Type.getType(StringBuilder.class));
        ga.dup();
        ga.invokeConstructor(Type.getType(StringBuilder.class), Method.getMethod("void <init>()"));
        ga.push("[PremainFilter] 移除: ");
        ga.invokeVirtual(Type.getType(StringBuilder.class),
                Method.getMethod("java.lang.StringBuilder append(java.lang.String)"));
        ga.loadLocal(namedPathIdx);
        ga.invokeVirtual(Type.getType("Lcpw/mods/modlauncher/api/NamedPath;"),
                Method.getMethod("java.nio.file.Path[] paths()"));
        ga.push(0);
        ga.arrayLoad(Type.getType(Path[].class).getElementType());
        ga.invokeInterface(Type.getType(Path.class), Method.getMethod("String toString()"));
        ga.invokeVirtual(Type.getType(StringBuilder.class),
                Method.getMethod("java.lang.StringBuilder append(java.lang.String)"));
        ga.invokeVirtual(Type.getType(StringBuilder.class), Method.getMethod("String toString()"));
        ga.invokeVirtual(Type.getType(java.io.PrintStream.class), Method.getMethod("void println(java.lang.String)"));
        ga.loadLocal(iterIdx);
        ga.invokeInterface(Type.getType(Iterator.class), Method.getMethod("void remove()"));
        ga.mark(continueLoop);
        ga.goTo(loopStart);
        ga.mark(loopEnd);

        ga.getStatic(Type.getType(System.class), "out", Type.getType(java.io.PrintStream.class));
        ga.push("[PremainFilter] 过滤执行完成（candidates return 前）");
        ga.invokeVirtual(Type.getType(java.io.PrintStream.class),
                Method.getMethod("void println(java.lang.String)"));
    }
}
