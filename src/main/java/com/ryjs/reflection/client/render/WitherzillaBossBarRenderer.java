package com.ryjs.reflection.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.ryjs.reflection.entity.WitherzillaPhantomManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;


public final class WitherzillaBossBarRenderer {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("reflection", "textures/gui/boss_bar.png");


    private static final int TEX_SIZE = 256;
    private static final int BAR_WIDTH = 208;
    private static final int BAR_HEIGHT = 30;
    private static final int HEALTH_BAR_W = 204;
    private static final int HEALTH_BAR_H = 5;

    private static final String BOSS_NAME = "\u51cb\u7075\u65af\u62c9";

    private static final MultiBufferSource.BufferSource NATIVE_BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(1024));

    private WitherzillaBossBarRenderer() {}

    public static void renderHud(Minecraft mc) {
        if (!WitherzillaPhantomManager.isAlive()) return;
        if (!RenderSystem.isOnRenderThread()) return;
        if (mc.options.hideGui) return;

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        Matrix4f projection = new Matrix4f().setOrtho(0.0F, width, height, 0.0F, 1000.0F, 3000.0F);
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        try {
            GuiGraphics g = new GuiGraphics(mc, NATIVE_BUFFERS);
            g.pose().translate(0.0F, 0.0F, -2000.0F);
            renderBar(g, mc, width);
            g.flush();
        } finally {
            NATIVE_BUFFERS.endBatch();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }


    private static void renderBar(GuiGraphics g, Minecraft mc, int screenWidth) {
        int x = screenWidth / 2 - 104;
        int y = 0;


        blit(g, x, y, 0, 0, BAR_WIDTH, BAR_HEIGHT);


        int healthWidth = HEALTH_BAR_W;


        float hue = (System.currentTimeMillis() % 3600) / 10f;
        int[] rb = hsvToRgb(hue, 1f, 1f);
        RenderSystem.setShaderColor(rb[0] / 255f, rb[1] / 255f, rb[2] / 255f, 1.0f);
        blit(g, x + 2, y + 14, 0, 35, healthWidth, HEALTH_BAR_H);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);


        blit(g, x + 2, y + 14, 0, 30, healthWidth, HEALTH_BAR_H);


        String name = "\u00a7l\u00a7o" + BOSS_NAME;
        int nameWidth = mc.font.width(name);
        int[] nc = hsvToRgb((hue + 180) % 360, 1f, 1f);
        int nameColor = (nc[0] << 16) | (nc[1] << 8) | nc[2];
        g.drawString(mc.font, name, x + 104 - nameWidth / 2, y + 5, nameColor, false);


        String healthText = "Infinity/NaN";
        float hh = (System.currentTimeMillis() % 1800) / 5f;
        int[] hc = hsvToRgb((hh + 90) % 360, 1f, 1f);
        int healthColor = (hc[0] << 16) | (hc[1] << 8) | hc[2];
        int htw = mc.font.width(healthText);
        g.drawString(mc.font, healthText, x + 104 - htw / 2, y + 14, healthColor, false);
    }


    private static void blit(GuiGraphics g, int x, int y, int u, int v, int w, int h) {
        g.blit(TEXTURE, x, y, (float) u, (float) v, w, h, TEX_SIZE, TEX_SIZE);
    }


    private static int[] hsvToRgb(float hue, float saturation, float value) {
        int r, g, b;
        float h = ((hue % 360f) + 360f) % 360f;
        float s = Math.max(0, Math.min(1, saturation));
        float v = Math.max(0, Math.min(1, value));
        int hi = (int) (h / 60f) % 6;
        float f = h / 60f - hi;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        switch (hi) {
            case 0: r = (int) (v * 255); g = (int) (t * 255); b = (int) (p * 255); break;
            case 1: r = (int) (q * 255); g = (int) (v * 255); b = (int) (p * 255); break;
            case 2: r = (int) (p * 255); g = (int) (v * 255); b = (int) (t * 255); break;
            case 3: r = (int) (p * 255); g = (int) (q * 255); b = (int) (v * 255); break;
            case 4: r = (int) (t * 255); g = (int) (p * 255); b = (int) (v * 255); break;
            case 5: r = (int) (v * 255); g = (int) (p * 255); b = (int) (q * 255); break;
            default: r = 0; g = 0; b = 0;
        }
        return new int[] { r, g, b };
    }
}
