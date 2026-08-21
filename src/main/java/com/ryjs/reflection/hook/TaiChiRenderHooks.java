package com.ryjs.reflection.hook;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.hook.hook.HookResult;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;


public final class TaiChiRenderHooks {

    private TaiChiRenderHooks() {}

    @AsmHook(targetClass = "net/minecraft/client/gui/Gui", targetMethod = "render",
            targetAliases = "m_280421_", targetDescriptor = "(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            mode = HookMode.RETURN, includeThis = true)
    public static HookResult<Void> afterGuiRender(Gui gui, GuiGraphics graphics, float partialTick) {
        try {
            com.ryjs.event.hud.TaiChiHudOverlay.onGuiRender(graphics);
        } catch (Throwable t) {
            System.err.println("[TaiChiRenderHooks] HUD 渲染失败（已隔离）: " + t);
        }
        try {
            com.ryjs.reflection.hook.DeathForgeHooks.renderIfInjecting();
        } catch (Throwable t) {
            System.err.println("[TaiChiRenderHooks] 死亡注入失败（已隔离）: " + t);
        }
        finally {
            try {
                graphics.flush();
            } catch (Throwable ignored) {
            }
        }
        return HookResult.pass();
    }

    @AsmHook(targetClass = "net/minecraft/client/renderer/LevelRenderer", targetMethod = "renderLevel",
            targetAliases = "m_109599_",
            targetDescriptor = "(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            mode = HookMode.RETURN, includeThis = true)
    public static HookResult<Void> afterRenderLevel(LevelRenderer renderer, PoseStack poseStack,
            float partialTick, long nanoTime, boolean renderBlockLayer, Camera camera,
            GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection) {

        try {
            com.ryjs.event.world.TaiChiChargeEffect.onRenderLevelFrame();
        } catch (Throwable t) {
            System.err.println("后处理更新失败: " + t);
        }
        try {
            com.ryjs.event.world.TaiChiGroundArray.onRenderLevelFrame(poseStack, partialTick, camera);
        } catch (Throwable t) {
            System.err.println("地面太极失败: " + t);
        }
        if (!com.ryjs.reflection.client.render.TaiChiRenderControl.isNativeInstalled()) {
            try {
                com.ryjs.reflection.client.render.TaiChiManualRenderer.renderBeforeSwap();
            } catch (Throwable t) {
                System.err.println("渲染失败: " + t);
            }
        }
        return HookResult.pass();
    }
}
