package com.ryjs.reflection.hook;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.reflection.client.model.CosmicBakeModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;


public final class ItemRenderHooks {

    private ItemRenderHooks() {
    }

    @AsmHook(targetClass = "net/minecraft/client/renderer/entity/ItemRenderer", targetMethod = "render",
            targetAliases = "m_115143_",
            targetDescriptor = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
            mode = HookMode.GUARD)
    public static boolean guardItemRender(ItemStack stack, ItemDisplayContext context, boolean leftHand,
                                          PoseStack mStack, MultiBufferSource buffers,
                                          int packedLight, int packedOverlay, BakedModel modelIn) {
        // 战斗模式：防御开启时物品渲染全屏蔽（手持/掉落物图标/GUI 图标——攻击者没有物品渲染出口可伪装注入）
        if (com.ryjs.reflection.guard.RenderProtect.isProtectEnabled()) {
            return true;
        }
        if (modelIn instanceof CosmicBakeModel cosmic) {
            try {
                mStack.pushPose();
                CosmicBakeModel renderer = (CosmicBakeModel) ForgeHooksClient.handleCameraTransforms(mStack, cosmic, context, leftHand);
                mStack.translate(-0.5D, -0.5D, -0.5D);
                renderer.renderItem(stack, context, mStack, buffers, packedLight, packedOverlay);
                mStack.popPose();
            } catch (Throwable t) {
                // 自定义渲染异常不得从 hook 注入点穿出（否则打断原版渲染流程 → 脏 buffer 后续越界）
                try { mStack.popPose(); } catch (Throwable ignored) {}
                System.err.println("[ItemRenderHooks] 宇宙渲染失败（已隔离，回退原版渲染）: " + t);
                return false; // 回退原版 ItemRenderer.render
            }
            return true; // 跳过原版 ItemRenderer.render
        }
        return false;
    }
}
