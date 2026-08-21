package com.ryjs.reflection.hook;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.hook.hook.HookResult;
import com.ryjs.reflection.death.DeathInjector;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;


public final class DeathWorldHooks {

    /** 注入开关：true 时世界渲染完画一帧全屏死亡画面。 */
    private static volatile boolean injecting = false;

    private DeathWorldHooks() {}

    public static boolean isInjecting() {
        return injecting;
    }

    public static void setInjecting(boolean on) {
        injecting = on;
    }

    @AsmHook(targetClass = "net/minecraft/client/renderer/LevelRenderer", targetMethod = "renderLevel",
            targetAliases = "m_109599_",
            targetDescriptor = "(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            mode = HookMode.RETURN, includeThis = true)
    public static HookResult<Void> afterRenderLevel(LevelRenderer renderer, PoseStack poseStack,
            float partialTick, long nanoTime, boolean renderBlockLayer, Camera camera,
            GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection) {
        if (injecting) {
            DeathInjector.renderFullScreenDeath(); // 标准死亡画面（彩虹 + 玩家名描边大字）
        }
        return HookResult.pass();
    }
}
