package com.ryjs.core.impl;

import com.ryjs.hook.transformer.LoaderAwareClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;


public final class TimeStopRenderArgTransformer implements ClassFileTransformer {

    private static final String ERD = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
    private static final String ERD_DESC =
            "(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    private static final String BERD = "net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher";
    private static final String BERD_DESC =
            "(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V";

    private static final String TSM = "com/ryjs/timestop/TimeStopManager";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || loader == null || classfileBuffer == null) {
            return null;
        }
        try {
            if (ERD.equals(className)) {
                return patch(loader, classfileBuffer, ERD_DESC, "m_114384_", true);
            }
            if (BERD.equals(className)) {
                return patch(loader, classfileBuffer, BERD_DESC, "m_112267_", false);
            }
        } catch (Throwable t) {
            System.err.println("[TimeStopRenderArg] patch failed for " + className + ": " + t);
        }
        return null;
    }

    private static byte[] patch(ClassLoader loader, byte[] buf, String targetDesc, String srgName, boolean entity) {
        ClassNode cn = new ClassNode();
        new ClassReader(buf).accept(cn, 0);
        boolean changed = false;
        for (MethodNode m : cn.methods) {
            if (!m.desc.equals(targetDesc)) {
                continue;
            }
            if (!m.name.equals("render") && !m.name.equals(srgName)) {
                continue;
            }
            InsnList pre = new InsnList();
            if (entity) {
                // partialTicks(槽9) = TimeStopManager.entityRenderPartial(entity(槽1), partialTicks)
                pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
                pre.add(new VarInsnNode(Opcodes.FLOAD, 9));
                pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TSM, "entityRenderPartial",
                        "(Lnet/minecraft/world/entity/Entity;F)F", false));
                pre.add(new VarInsnNode(Opcodes.FSTORE, 9));
            } else {
                // partialTick(槽2) = TimeStopManager.blockEntityRenderPartial(partialTick)
                pre.add(new VarInsnNode(Opcodes.FLOAD, 2));
                pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TSM, "blockEntityRenderPartial", "(F)F", false));
                pre.add(new VarInsnNode(Opcodes.FSTORE, 2));
            }
            m.instructions.insert(pre);
            changed = true;
        }
        if (!changed) {
            return null;
        }
        LoaderAwareClassWriter cw = new LoaderAwareClassWriter(3, loader); // COMPUTE_FRAMES|COMPUTE_MAXS
        cn.accept(cw);
        return cw.toByteArray();
    }
}
