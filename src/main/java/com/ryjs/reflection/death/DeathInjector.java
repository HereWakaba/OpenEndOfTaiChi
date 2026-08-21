package com.ryjs.reflection.death;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

public final class DeathInjector {

    private static final MultiBufferSource.BufferSource BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(1024));

    private DeathInjector() {}


    public static String playerName() {
        try {
            net.minecraft.world.entity.player.Player p =
                    Minecraft.getInstance().player;
            if (p != null) {
                return p.getName().getString();
            }
        } catch (Throwable ignored) {
        }
        return "玩家";
    }


    private static final String LINE_MAIN = "";
    private static final String LINE_SUB = "You Died";


    private static final net.minecraft.resources.ResourceLocation DEATH_FONT =
            com.ryjs.reflection.Reflection.rl("reflection");


    public static void renderRainbowBackground(GuiGraphics graphics, int width, int height) {
        if (width <= 0 || height <= 0) return;
        long now = System.currentTimeMillis();
        float flow = (now % 6000L) / 6000.0f;
        float flicker = 0.85f + 0.15f * (float) Math.sin(now * 0.012);
        float span = (float) (width + height);
        int row = 0;
        for (int y = 0; y < height; y += 2) {

            float hue = ((y + (float) y) / span + flow) % 1.0f;
            int rgb = Mth.hsvToRgb(hue, 1.0F, flicker);
            graphics.fill(0, y, width, Math.min(y + 2, height), 0xFF000000 | rgb);
            if (++row >= 15) {
                graphics.flush();
                row = 0;
            }
        }
        if (row > 0) graphics.flush();
    }


    public static void renderOutlinedCentered(GuiGraphics graphics, String text,
                                              int width, int height, float scale,
                                              int yOffset, int color) {
        renderOutlinedCentered(graphics, text, width, height, scale, yOffset, color, false);
    }


    public static void renderOutlinedCentered(GuiGraphics graphics, String text,
                                              int width, int height, float scale,
                                              int yOffset, int color, boolean thickFlicker) {
        Minecraft mc = Minecraft.getInstance();

        Component comp = Component.literal(text)
                .withStyle(ChatFormatting.BOLD)
                .withStyle(s -> s.withFont(DEATH_FONT));
        float fontW = mc.font.width(comp);
        float fontH = mc.font.lineHeight;

        float drawX = (width / scale - fontW) / 2.0F;
        float drawY = (height / scale - fontH) / 2.0F + yOffset / scale;
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1.0F);
        int ox = Math.round(drawX);
        int oy = Math.round(drawY);
        int outline = 0xFF000000;
        if (thickFlicker) {

            long now = System.currentTimeMillis();
            int textRgb = color & 0xFFFFFF;
            int invRgb = (~textRgb) & 0xFFFFFF;
            float flash = 0.5f + 0.5f * (float) Math.sin(now * 0.02);
            int or = (invRgb >> 16) & 0xFF, og = (invRgb >> 8) & 0xFF, ob = invRgb & 0xFF;
            outline = 0xFF000000
                    | ((int) (or * (1.0f - flash)) << 16)
                    | ((int) (og * (1.0f - flash)) << 8)
                    | (int) (ob * (1.0f - flash));

            for (int dx = -2; dx <= 2; dx += 2) {
                for (int dy = -2; dy <= 2; dy += 2) {
                    if (dx == 0 && dy == 0) continue;
                    graphics.drawString(mc.font, comp, ox + dx, oy + dy, outline);
                }
            }
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                graphics.drawString(mc.font, comp, ox + dx, oy + dy, outline);
            }
        }
        graphics.drawString(mc.font, comp, ox, oy, color);
        graphics.pose().popPose();
    }


    public static void renderDeathText(GuiGraphics graphics, int width, int height) {
        String name = playerName();

        renderOutlinedCentered(graphics, name, width, height, 2.2F, -36, 0xFFFFFFFF);

        renderOutlinedCentered(graphics, LINE_SUB, width, height, 2.0F, 40, 0xFFFF5555, true);
    }




    public static void renderFullScreenDeath() {
        Minecraft mc = Minecraft.getInstance();
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        Matrix4f projection = new Matrix4f().setOrtho(0.0F, width, height, 0.0F, 1000.0F, 3000.0F);
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        try {
            GuiGraphics graphics = new GuiGraphics(mc, BUFFERS);
            graphics.pose().translate(0.0F, 0.0F, -2000.0F);
            renderRainbowBackground(graphics, width, height);
            renderDeathText(graphics, width, height);
            graphics.flush();

            DeathGlDirectRender.render(width, height);
        } finally {
            BUFFERS.endBatch();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
        }
    }
}
