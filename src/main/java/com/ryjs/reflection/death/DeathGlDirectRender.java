package com.ryjs.reflection.death;

import com.mojang.blaze3d.systems.RenderSystem;
import com.ryjs.reflection.client.render.TaiChiRenderBridge;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;


public final class DeathGlDirectRender {

    private static Font deathFontBase;
    private static boolean enabled = false;

    private DeathGlDirectRender() {
    }


    public static void setEnabled(boolean on) {
        enabled = on;
        try {
            TaiChiRenderBridge.nativeSetDeathGl(on ? 1 : 0);
        } catch (Throwable ignored) {
        }
    }


    public static void render(int width, int height) {
        if (!enabled || width <= 0 || height <= 0) return;
        try {
            if (!RenderSystem.isOnRenderThread()) return;
            if (!net.minecraft.client.Minecraft.getInstance().running) return;
            int[] px = renderFrame(width, height);
            if (px != null) {
                TaiChiRenderBridge.nativeDeathGlFrame(px, width, height);
            }
        } catch (Throwable t) {

            enabled = false;
            System.err.println("帧生成失败，GL 直绘通道已禁用: " + t);
        }
    }


    private static int[] renderFrame(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            long now = System.currentTimeMillis();
            float flow = (now % 6000L) / 6000.0f;
            float flicker = 0.85f + 0.15f * (float) Math.sin(now * 0.012);
            float span = (float) (width + height);

            for (int y = 0; y < height; y++) {
                float hue = (2.0f * y / span + flow) % 1.0f;
                int rgb = Color.HSBtoRGB(hue, 1.0f, flicker);
                g2.setColor(new Color(rgb));
                g2.fillRect(0, y, width, 1);
            }

            Font base = loadFont();
            String name = DeathInjector.playerName();
            String died = "You Died";

            g2.setFont(base.deriveFont(Font.BOLD, Math.max(28f, width / 16f)));
            FontMetrics fm = g2.getFontMetrics();
            int nameY = height / 2 - fm.getHeight() * 2;
            drawText(g2, name, (width - fm.stringWidth(name)) / 2, nameY, Color.WHITE, -1f);

            g2.setFont(base.deriveFont(Font.BOLD, Math.max(44f, width / 10f)));
            fm = g2.getFontMetrics();
            int diedY = height / 2 + fm.getHeight();
            float flash = 0.5f + 0.5f * (float) Math.sin(now * 0.02);
            drawText(g2, died, (width - fm.stringWidth(died)) / 2, diedY, new Color(255, 85, 85), flash);
        } finally {
            g2.dispose();
        }
        return img.getRGB(0, 0, width, height, null, 0, width);
    }

    private static void drawText(Graphics2D g2, String s, int x, int y, Color c, float flash) {
        if (flash >= 0) {
            Color inv = new Color(255 - c.getRed(), 255 - c.getGreen(), 255 - c.getBlue());
            Color outline = new Color(
                    (int) (inv.getRed() + (0 - inv.getRed()) * flash),
                    (int) (inv.getGreen() + (0 - inv.getGreen()) * flash),
                    (int) (inv.getBlue() + (0 - inv.getBlue()) * flash));
            g2.setColor(outline);
            for (int dx = -2; dx <= 2; dx += 2) {
                for (int dy = -2; dy <= 2; dy += 2) {
                    if (dx == 0 && dy == 0) continue;
                    g2.drawString(s, x + dx, y + dy);
                }
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    g2.drawString(s, x + dx, y + dy);
                }
            }
        } else {
            g2.setColor(Color.BLACK);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    g2.drawString(s, x + dx, y + dy);
                }
            }
        }
        g2.setColor(c);
        g2.drawString(s, x, y);
    }

    private static Font loadFont() {
        if (deathFontBase == null) {
            try (InputStream in = DeathGlDirectRender.class.getResourceAsStream("/assets/reflection/font/reflection.ttf")) {
                if (in != null) {
                    deathFontBase = Font.createFont(Font.TRUETYPE_FONT, in);
                }
            } catch (Throwable ignored) {
            }
        }
        return deathFontBase != null ? deathFontBase : new Font("SansSerif", Font.BOLD, 24);
    }
}
