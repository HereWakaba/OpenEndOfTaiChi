package com.ryjs.event.world;

import com.ryjs.reflection.Registration;

import com.ryjs.reflection.Reflection;
import com.ryjs.timestop.TimeStopManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;


public final class TaiChiChargeEffect {

    private static final float MAX_CHARGE_TICKS = 200f; // 10 秒
    private static final ResourceLocation EFFECT = Reflection.rl("shaders/post/taichi_charge.json");
    private static final String EFFECT_NAME = EFFECT.toString();

    private static float chargeI = 0f;  // 蓄力扭曲强度 0..1
    private static float invertI = 0f;  // 全反强度 0..1
    private static float effectTime = 0f;
    private static long lastMs = 0L;

    private TaiChiChargeEffect() {}

    public static void onRenderLevelFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (currentOurs(mc) != null) mc.gameRenderer.shutdownEffect();
            chargeI = 0f;
            invertI = 0f;
            lastMs = 0L;
            return;
        }

        boolean fullPause = TimeStopManager.isFullPause();
        float charge = chargeProgress(mc.player);

        float chargeTarget = fullPause ? 0f : charge;
        if (chargeTarget >= chargeI) {
            chargeI = chargeTarget;
        } else {
            chargeI += (chargeTarget - chargeI) * 0.1f;
            if (chargeI < 0.01f) chargeI = 0f;
        }

        float invertTarget = fullPause ? 1f : 0f;
        invertI += (invertTarget - invertI) * 0.1f;
        if (invertTarget == 0f && invertI < 0.01f) invertI = 0f;

        long now = System.currentTimeMillis();
        float dt = lastMs == 0L ? 0f : Math.min(0.1f, (now - lastMs) / 1000.0f);
        lastMs = now;
        effectTime += dt * (1.0f + randSpeed(now) * 4.0f * chargeI);

        boolean active = Math.max(chargeI, invertI) > 0.01f;
        try {
            if (active) {
                PostChain pe = currentOurs(mc);
                if (pe == null && mc.gameRenderer.postEffect == null) {
                    mc.gameRenderer.loadEffect(EFFECT);
                    pe = currentOurs(mc);
                }
                if (pe != null) {
                    for (PostPass pass : pe.passes) {
                        pass.getEffect().safeGetUniform("ChargeProgress").set(chargeI);
                        pass.getEffect().safeGetUniform("InvertAmount").set(invertI);
                        pass.getEffect().safeGetUniform("EffectTime").set(effectTime);
                    }
                }
            } else if (currentOurs(mc) != null) {
                mc.gameRenderer.shutdownEffect();
            }
        } catch (Exception ignored) {
        }
    }

    private static PostChain currentOurs(Minecraft mc) {
        PostChain pe = mc.gameRenderer.postEffect;
        return (pe != null && EFFECT_NAME.equals(pe.getName())) ? pe : null;
    }

    private static float randSpeed(long ms) {
        long q = ms / 150L;
        long h = q * 6364136223846793005L + 1442695040888963407L;
        h ^= (h >>> 33);
        return (h & 0xFFFF) / 65535.0f;
    }

    private static float chargeProgress(Player p) {
        if (p != null && p.isUsingItem()
                && p.getUseItem().getItem() == Registration.END_OF_TAI_CHI.get()) {
            return Math.min(1f, p.getTicksUsingItem() / MAX_CHARGE_TICKS);
        }
        return 0f;
    }
}
