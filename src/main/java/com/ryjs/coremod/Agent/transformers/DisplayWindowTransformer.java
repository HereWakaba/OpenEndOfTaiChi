package com.ryjs.coremod.Agent.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;


public class DisplayWindowTransformer implements ClassFileTransformer {

    private static final String TARGET = "net/minecraftforge/fml/earlydisplay/DisplayWindow";
    private static final String RENDERER = "com.ryjs.reflection.client.earlydisplay.ShellEarlyRenderer";
    private static final String HOOK = "ryjs$shellEarlyRender";
    public static void preload() {
        String[] names = {
                "org.objectweb.asm.ClassReader",
                "org.objectweb.asm.ClassWriter",
                "org.objectweb.asm.tree.ClassNode",
                "org.objectweb.asm.tree.MethodNode",
                "org.objectweb.asm.tree.InsnList",
                "org.objectweb.asm.tree.InsnNode",
                "org.objectweb.asm.tree.FrameNode",
                "org.objectweb.asm.tree.LabelNode",
                "org.objectweb.asm.tree.JumpInsnNode",
                "org.objectweb.asm.tree.LdcInsnNode",
                "org.objectweb.asm.tree.TypeInsnNode",
                "org.objectweb.asm.tree.MethodInsnNode",
                "org.objectweb.asm.tree.TryCatchBlockNode",
        };
        ClassLoader cl = DisplayWindowTransformer.class.getClassLoader();
        for (String n : names) {
            try {
                Class.forName(n, true, cl);
            } catch (Throwable ignore) {
            }
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET.equals(className)) {
            return null;
        }
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            for (MethodNode mn : cn.methods) {
                if (HOOK.equals(mn.name)) {
                    return null;
                }
            }

            int injected = 0;
            for (MethodNode mn : cn.methods) {
                if (!"paintFramebuffer".equals(mn.name) || !"()V".equals(mn.desc)) {
                    continue;
                }
                List<AbstractInsnNode> returns = new ArrayList<>();
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.RETURN) {
                        returns.add(insn);
                    }
                }
                for (AbstractInsnNode ret : returns) {
                    // 唯一一条插入：栈中性、无分支 → 不影响该方法原有任何帧
                    mn.instructions.insertBefore(ret,
                            new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, HOOK, "()V", false));
                    injected++;
                }
            }
            if (injected == 0) {
                System.err.println("未找到 DisplayWindow.paintFramebuffer()V，接管失败");
                return null;
            }
            cn.methods.add(buildHookMethod());

            // 只算 maxStack/maxLocals：不重算帧 → 不会调用 getCommonSuperClass → 不会误把异常类型退化成 Object
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("转换 DisplayWindow 失败: " + t);
            return null;
        }
    }

    private static MethodNode buildHookMethod() {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                HOOK, "()V", null, null);
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode after = new LabelNode();

        InsnList il = mn.instructions;
        il.add(tryStart);
        // Class.forName(RENDERER, true, FMLLoader.getGameLayer().findModule("reflection").get().getClassLoader())
        il.add(new LdcInsnNode(RENDERER));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraftforge/fml/loading/FMLLoader",
                "getGameLayer", "()Ljava/lang/ModuleLayer;", false));
        il.add(new LdcInsnNode("reflection"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/ModuleLayer",
                "findModule", "(Ljava/lang/String;)Ljava/util/Optional;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Module"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Module",
                "getClassLoader", "()Ljava/lang/ClassLoader;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class",
                "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false));
        // .getMethod("render", new Class[0]).invoke(null, new Object[0])
        il.add(new LdcInsnNode("render"));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false));
        il.add(new InsnNode(Opcodes.ACONST_NULL));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method",
                "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false));
        il.add(new InsnNode(Opcodes.POP));
        il.add(tryEnd);
        il.add(new JumpInsnNode(Opcodes.GOTO, after));
        il.add(handler);
        il.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"}));
        il.add(new InsnNode(Opcodes.POP)); // 弃掉 Throwable
        // GAME 层未就绪窗口期：glClear 深蓝黑底色（LWJGL 在 boot 层可见——fmlearlydisplay 自身依赖）
        il.add(new LdcInsnNode(0.09f));
        il.add(new LdcInsnNode(0.10f));
        il.add(new LdcInsnNode(0.16f));
        il.add(new LdcInsnNode(1.0f));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11",
                "glClearColor", "(FFFF)V", false));
        il.add(new LdcInsnNode(16384)); // GL_COLOR_BUFFER_BIT = 0x4000
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11",
                "glClear", "(I)V", false));
        il.add(after);
        il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        il.add(new InsnNode(Opcodes.RETURN));

        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, "java/lang/Throwable"));
        mn.maxStack = 8;   // COMPUTE_MAXS 会重算，此处仅兜底
        mn.maxLocals = 1;
        return mn;
    }
}
