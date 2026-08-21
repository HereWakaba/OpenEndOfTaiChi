package com.ryjs.reflection.hook;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.hook.hook.HookResult;
import com.ryjs.reflection.guard.RenderProtect;
import com.ryjs.reflection.guard.RenderStageGuard;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.*;
import org.joml.Matrix4f;


public final class RenderStageGuardHooks {

    private RenderStageGuardHooks() {}

    // ============================ 阶段状态机 ============================

    /**
     * renderLevel 入口：标记世界阶段并捕获世界投影；同时抢回事件总线（渲染帧的总线必须是
     * 我们的——对抗 Omni-Mobs 类 mod 在 ServerTickEvent 里抢走总线，其 post 内联渲染会绕过阻断）。
     * <p>注意：<b>出口不恢复</b>——手持物品渲染（renderItemInHand）在 renderLevel 之后、GUI 之前，
     * 注入点（物品附魔）恰在此区间；阶段持续到 HUD/GUI 渲染入口（Gui.render / Screen.render）才切换。
     */
    @AsmHook(targetClass = "net/minecraft/client/renderer/LevelRenderer", targetMethod = "renderLevel",
            targetAliases = "m_109599_",
            targetDescriptor = "(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            mode = HookMode.HEAD, includeThis = true)
    public static HookResult<Void> enterRenderLevel(LevelRenderer renderer, PoseStack poseStack,
            float partialTick, long nanoTime, boolean renderBlockLayer, Camera camera,
            GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection) {
        com.ryjs.reflection.guard.ForgeRenderEventGuard.maintain();
        com.ryjs.reflection.guard.WindowGuard.maintain();
        com.ryjs.reflection.guard.MouseGuard.maintain();
        RenderStageGuard.enterWorld();
        return HookResult.pass();
    }

    /** HUD 渲染入口：世界阶段结束，进入 GUI 阶段（正交投影合法）。 */
    @AsmHook(targetClass = "net/minecraft/client/gui/Gui", targetMethod = "render",
            targetAliases = "m_280421_", targetDescriptor = "(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            mode = HookMode.HEAD, includeThis = true)
    public static HookResult<Void> enterGui(Gui gui, GuiGraphics graphics, float partialTick) {
        RenderStageGuard.exitWorld();
        return HookResult.pass();
    }

    // ============================ 投影守卫 ============================

    @AsmHook(targetClass = "com/mojang/blaze3d/systems/RenderSystem", targetMethod = "setProjectionMatrix",
            targetDescriptor = "(Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/vertex/VertexSorting;)V",
            mode = HookMode.GUARD)
    public static boolean guardProjection(Matrix4f projection, VertexSorting sorting) {
        return RenderStageGuard.guardProjection(projection, sorting);
    }

    // ============================ 深度守卫 ============================

    @AsmHook(targetClass = "com/mojang/blaze3d/systems/RenderSystem", targetMethod = "disableDepthTest",
            targetDescriptor = "()V", mode = HookMode.GUARD)
    public static boolean guardDisableDepthTest() {
        return RenderStageGuard.guardDisableDepthTest();
    }

    @AsmHook(targetClass = "com/mojang/blaze3d/systems/RenderSystem", targetMethod = "depthMask",
            targetDescriptor = "(Z)V", mode = HookMode.GUARD)
    public static boolean guardDepthMask(boolean mask) {
        return RenderStageGuard.guardDepthMask(mask);
    }

    // ============================ 附魔光泽屏蔽（glint） ============================

    /** glint 层（附魔光泽）在防御开启时跳过 endBatch（光泽不绘制；物品本体正常）。 */
    @AsmHook(targetClass = "net/minecraft/client/renderer/MultiBufferSource$BufferSource", targetMethod = "endBatch",
            targetAliases = "m_109912_",
            targetDescriptor = "(Lnet/minecraft/client/renderer/RenderType;)V",
            mode = HookMode.GUARD, includeThis = true)
    public static boolean guardEndBatch(MultiBufferSource.BufferSource source, RenderType renderType) {
        if (RenderProtect.isProtectEnabled() && isGlint(renderType)) {
            return true;
        }
        return false;
    }

    /** 判定是否为附魔光泽层（7 条 glint 路径：物品/直接/半透明/实体/实体直接/盔甲/盔甲实体）。 */
    private static boolean isGlint(RenderType type) {
        if (type == null) {
            return false;
        }
        return type == RenderType.glint()
                || type == RenderType.glintDirect()
                || type == RenderType.glintTranslucent()
                || type == RenderType.entityGlint()
                || type == RenderType.entityGlintDirect()
                || type == RenderType.armorGlint()
                || type == RenderType.armorEntityGlint();
    }
}
