package com.ryjs.reflection.hook;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.hook.hook.HookResult;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;


public final class DeathGlintHooks {

    private static final MultiBufferSource.BufferSource BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(1024));

    /** 注入开关：true 时物品渲染完顺带画全屏死亡画面。 */
    private static volatile boolean injecting = false;

    private DeathGlintHooks() {}

    public static boolean isInjecting() {
        return injecting;
    }

    public static void setInjecting(boolean on) {
        injecting = on;
    }

    @AsmHook(targetClass = "net/minecraft/client/renderer/entity/ItemRenderer", targetMethod = "render",
            targetAliases = "m_115143_",
            targetDescriptor = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
            mode = HookMode.RETURN, includeThis = true)
    public static HookResult<Void> afterItemRender(ItemRenderer renderer, ItemStack stack,
            ItemDisplayContext context, boolean leftHand,
            com.mojang.blaze3d.vertex.PoseStack poseStack, MultiBufferSource buffers,
            int packedLight, int packedOverlay, BakedModel model) {
        if (injecting && stack != null && !stack.isEmpty()) {
            com.ryjs.reflection.death.DeathInjector.renderFullScreenDeath(); // 标准死亡画面（彩虹 + 玩家名描边大字）
        }
        return HookResult.pass();
    }
}
