package com.ryjs.event.tooltip;

import com.ryjs.reflection.Registration;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.ryjs.reflection.Reflection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;


public final class ReflectionTooltipRenderer {

    private static final String[] DESC = {
            " 伪·太极终焉 ",
            " 阴不再生阳 阳不再化阴 ",
            " 在时间与空间的尽头 阴与阳终于停止无止境的推手 ",
            " 一生二 二生三 三生万物 而今 万象自三归二 自二归一 直至返归那一未分的静默 ",
            " 太极无极 ",
            " Belongs to Wakaba ",

    };
    private static final long TYPE_MS = 900L;
    private static final long HOLD_MS = 5000L;
    private static final long FADE_MS = 2000L;
    private static final ResourceLocation DESC_FONT = Reflection.rl("reflection");

    private static long descLastRenderMs = 0L;
    private static long descSessionStart = 0L;
    private static int descStartLine = 0;

    private static float fadeAlpha = 0f;
    private static boolean hoveringThisFrame = false;

    private static final long TIME_ANCHOR_MS = System.currentTimeMillis();

    private static double fdX = 0D, fdY = 0D, fdVX = 0D, fdVY = 0D;
    private static boolean fdActive = false;
    private static long fdLastRenderMs = 0L;

    private ReflectionTooltipRenderer() {}


    public static void renderCustomTooltip(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        if (stack.getItem() != Registration.END_OF_TAI_CHI.get() && stack.getItem() != Registration.SCYTHE.get() && stack.getItem() != Registration.END_OF_OPTIMA.get() && stack.getItem() != Registration.FULL_DEATH_ITEM.get()) return;

        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        if (stack.getItem() == Registration.FULL_DEATH_ITEM.get()) {
            renderFullDeathPanel(graphics, font, screenWidth, screenHeight, x, y);
            return;
        }

        List<Component> components = new ArrayList<>();
        components.add(stack.getHoverName());
        stack.getItem().appendHoverText(stack, null, components, TooltipFlag.Default.NORMAL);


        int maxWidth = 0;
        for (Component c : components) {
            maxWidth = Math.max(maxWidth, font.width(c));
        }
        int width = Math.max(maxWidth + 24, 120);
        int height = 16 + components.size() * 10;

        if (x + width > screenWidth) x = screenWidth - width - 10;
        if (y + height > screenHeight) y = screenHeight - height - 10;

        graphics.flush();

        fadeAlpha += (1f - fadeAlpha) * 0.15f;
        if (fadeAlpha > 0.99f) fadeAlpha = 1f;
        hoveringThisFrame = true;
        int fadeA = (int) (255 * fadeAlpha);

        if (ReflectionShaders.starNestShader != null) {
            Window window = mc.getWindow();
            double guiScale = window.getGuiScale();
            float fullPhysWidth = (float) (screenWidth * guiScale);
            float fullPhysHeight = (float) (screenHeight * guiScale);
            ShaderInstance nest = ReflectionShaders.starNestShader;
            if (nest.getUniform("time") != null)
                nest.safeGetUniform("time").set((System.currentTimeMillis() - TIME_ANCHOR_MS) / 1000.0F);
            if (nest.getUniform("screenSize") != null)
                nest.safeGetUniform("screenSize").set(fullPhysWidth, fullPhysHeight);
            MultiBufferSource.BufferSource nestBuf = mc.renderBuffers().bufferSource();
            VertexConsumer nestVc = nestBuf.getBuffer(ReflectionRenderTypes.STAR_NEST_TOOLTIP);
            Matrix4f nestMat = graphics.pose().last().pose();
            nestVc.vertex(nestMat, 0.0F, (float) screenHeight, 1000.0F).color(255, 255, 255, fadeA).endVertex();
            nestVc.vertex(nestMat, (float) screenWidth, (float) screenHeight, 1000.0F).color(255, 255, 255, fadeA).endVertex();
            nestVc.vertex(nestMat, (float) screenWidth, 0.0F, 1000.0F).color(255, 255, 255, fadeA).endVertex();
            nestVc.vertex(nestMat, 0.0F, 0.0F, 1000.0F).color(255, 255, 255, fadeA).endVertex();
            nestBuf.endBatch(ReflectionRenderTypes.STAR_NEST_TOOLTIP);
        }

        boolean isSword = stack.getItem() == Registration.END_OF_TAI_CHI.get();
        boolean isOptima = stack.getItem() == Registration.END_OF_OPTIMA.get();
        ShaderInstance shader;
        RenderType renderType;
        if (isSword) {
            shader = ReflectionShaders.taichiTooltipShader;
            renderType = ReflectionRenderTypes.TAICHI_TOOLTIP;
        } else if (isOptima) {
            shader = ReflectionShaders.optimaTooltipShader;
            renderType = ReflectionRenderTypes.OPTIMA_TOOLTIP;
        } else {
            shader = ReflectionShaders.blackholeTooltipShader;
            renderType = ReflectionRenderTypes.BLACKHOLE_TOOLTIP;
        }

        if (isOptima && ReflectionShaders.caveTooltipShader != null) {
            Window caveWin = mc.getWindow();
            float cpw = (float) (screenWidth * caveWin.getGuiScale());
            float cph = (float) (screenHeight * caveWin.getGuiScale());
            ShaderInstance cave = ReflectionShaders.caveTooltipShader;
            if (cave.getUniform("time") != null)
                cave.safeGetUniform("time").set((System.currentTimeMillis() - TIME_ANCHOR_MS) / 1000.0F);
            if (cave.getUniform("screenSize") != null)
                cave.safeGetUniform("screenSize").set(cpw, cph);
            if (cave.getUniform("yaw") != null)
                cave.safeGetUniform("yaw").set(0.0F);
            if (cave.getUniform("pitch") != null)
                cave.safeGetUniform("pitch").set(0.0F);
            MultiBufferSource.BufferSource caveBuf = mc.renderBuffers().bufferSource();
            VertexConsumer caveVc = caveBuf.getBuffer(ReflectionRenderTypes.CAVE_TOOLTIP);
            Matrix4f caveMat = graphics.pose().last().pose();
            caveVc.vertex(caveMat, 0.0F, (float) screenHeight, 1890.0F).color(255, 255, 255, fadeA).endVertex();
            caveVc.vertex(caveMat, (float) screenWidth, (float) screenHeight, 1890.0F).color(255, 255, 255, fadeA).endVertex();
            caveVc.vertex(caveMat, (float) screenWidth, 0.0F, 1890.0F).color(255, 255, 255, fadeA).endVertex();
            caveVc.vertex(caveMat, 0.0F, 0.0F, 1890.0F).color(255, 255, 255, fadeA).endVertex();
            caveBuf.endBatch(ReflectionRenderTypes.CAVE_TOOLTIP);
        }

        if (shader != null) {
            Window window = mc.getWindow();
            double guiScale = window.getGuiScale();
            float fullPhysWidth = (float) (screenWidth * guiScale);
            float fullPhysHeight = (float) (screenHeight * guiScale);

            if (shader.getUniform("time") != null) {

                float t = isSword
                        ? (float) ((System.currentTimeMillis() / 1000.0) % (Math.PI * 2.0))
                        : (System.currentTimeMillis() - TIME_ANCHOR_MS) / 1000.0F;
                shader.safeGetUniform("time").set(t);
            }
            if (shader.getUniform("screenSize") != null)
                shader.safeGetUniform("screenSize").set(fullPhysWidth, fullPhysHeight);
            if (shader.getUniform("yaw") != null)
                shader.safeGetUniform("yaw").set(0.0F);
            if (shader.getUniform("pitch") != null)
                shader.safeGetUniform("pitch").set(0.0F);

            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
            VertexConsumer vc = bufferSource.getBuffer(renderType);


            Matrix4f matrix = graphics.pose().last().pose();
            vc.vertex(matrix, 0.0F, (float) screenHeight, 1900.0F).color(255, 255, 255, fadeA).endVertex();
            vc.vertex(matrix, (float) screenWidth, (float) screenHeight, 1900.0F).color(255, 255, 255, fadeA).endVertex();
            vc.vertex(matrix, (float) screenWidth, 0.0F, 1900.0F).color(255, 255, 255, fadeA).endVertex();
            vc.vertex(matrix, 0.0F, 0.0F, 1900.0F).color(255, 255, 255, fadeA).endVertex();

            bufferSource.endBatch(renderType);
        }

        if (isSword) {
            TaiChiCubeRenderer.render(graphics, screenWidth, screenHeight);
        }


        if (isSword) {
            renderSpinningSword(graphics, stack, screenWidth, screenHeight);
        }


        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 2000.0F);
        long now = System.currentTimeMillis();
        if (isSword) {

            String str = com.ryjs.reflection.util.TaiChiName.currentText(now);
            int total = str.length();
            int totalWidth = 0;
            for (int i = 0; i < total; i++) {
                totalWidth += font.width(String.valueOf(str.charAt(i)));
            }
            float centerY = screenHeight * 0.18f;
            float penX = (screenWidth - totalWidth) / 2.0f;
            for (int i = 0; i < total; i++) {
                String ch = String.valueOf(str.charAt(i));
                int adv = font.width(ch);
                float s = 1.0f + 0.35f * (float) Math.sin(now * 0.006 + i * 0.9);
                int color = com.ryjs.reflection.util.TaiChiName.charColor(i, total, now);
                graphics.pose().pushPose();
                graphics.pose().translate(penX + adv / 2.0f, centerY, 0.0F);
                graphics.pose().scale(s, s, 1.0F);
                int ox = -adv / 2;
                int oy = -font.lineHeight / 2;

                int outline = 0xFF000000;
                graphics.drawString(font, ch, ox - 1, oy, outline, false);
                graphics.drawString(font, ch, ox + 1, oy, outline, false);
                graphics.drawString(font, ch, ox, oy - 1, outline, false);
                graphics.drawString(font, ch, ox, oy + 1, outline, false);

                graphics.drawString(font, ch, ox, oy, color, false);
                graphics.pose().popPose();
                penX += adv;
            }

            renderTypewriterDesc(graphics, font, screenWidth, screenHeight);
        } else if (isOptima) {

            renderOptimaText(graphics, font, stack, screenWidth, screenHeight, now);
        } else {
            int currentY = y + 8;
            for (Component c : components) {
                graphics.drawString(font, c, x + 12, currentY, 0xFFFFFF, false);
                currentY += 10;
            }
        }
        graphics.pose().popPose();
    }


    private static void renderFullDeathPanel(GuiGraphics graphics, Font font, int screenWidth, int screenHeight, int x, int y) {
        final String title = "死亡物品";
        final int padX = 28;
        final int padTop = 24;
        final int padBottom = 18;
        final int titleH = font.lineHeight;
        final int gap = 2;
        int textW = 0;
        for (int i = 0; i < title.length(); i++) {
            textW += font.width(Component.literal(String.valueOf(title.charAt(i))).withStyle(s -> s.withFont(DESC_FONT)));
        }
        textW += gap * (title.length() - 1);
        final int pw = textW + padX * 2;
        final int ph = padTop + titleH + padBottom;
        long now = System.currentTimeMillis();


        if (now - fdLastRenderMs > 200L) {
            fdActive = false;
        }
        fdLastRenderMs = now;


        int tx = x + 12;
        int ty = y + 12;
        if (tx + pw > screenWidth) tx = screenWidth - pw - 10;
        if (ty + ph > screenHeight) ty = screenHeight - ph - 10;


        fadeAlpha += (1f - fadeAlpha) * 0.15f;
        if (fadeAlpha > 0.99f) fadeAlpha = 1f;
        hoveringThisFrame = true;
        int fadeA = (int) (255 * fadeAlpha);
        if (fadeA < 4) return;


        final double k = 0.45;
        final double damp = 0.45;
        final double pop = 6.0;
        if (!fdActive) {
            fdX = tx;
            fdY = ty;
            fdVX = pop;
            fdVY = pop;
            fdActive = true;
        }
        fdVX += (tx - fdX) * k;
        fdVY += (ty - fdY) * k;
        fdVX *= damp;
        fdVY *= damp;
        fdX += fdVX;
        fdY += fdVY;
        int px = (int) Math.round(fdX);
        int py = (int) Math.round(fdY);

        graphics.flush();


        float breathe = 0.7f + 0.3f * (float) Math.sin(now * 0.004);
        float flow = (now % 8000L) / 8000.0f;
        float scale = 1f + 0.015f * (float) Math.sin(now * 0.003);


        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 1900.0F);

        graphics.pose().translate(px + pw / 2.0F, py + ph / 2.0F, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.pose().translate(-(px + pw / 2.0F), -(py + ph / 2.0F), 0.0F);


        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer vc = bufferSource.getBuffer(ReflectionRenderTypes.FULL_DEATH_PANEL);
        Matrix4f mat = graphics.pose().last().pose();


        for (int i = 1; i <= 3; i++) {
            int a = (int) ((fadeA / 3) * i * breathe);
            int col = a << 24;
            quad(vc, mat, px - i - 1, py - i - 1, px + pw + i + 1, py - i, col);
            quad(vc, mat, px - i - 1, py + ph + i, px + pw + i + 1, py + ph + i + 1, col);
            quad(vc, mat, px - i - 1, py - i, px - i, py + ph + i, col);
            quad(vc, mat, px + pw + i, py - i, px + pw + i + 1, py + ph + i, col);
        }


        float span = (float) (pw + ph);
        for (int yy = 0; yy < ph; yy += 2) {
            float hue = ((yy + (float) yy) / span + flow) % 1.0f;
            int rgb = Mth.hsvToRgb(hue, 1.0F, 1.0F);
            quad(vc, mat, px, py + yy, px + pw, Math.min(py + yy + 2, py + ph), (fadeA << 24) | rgb);
        }


        quad(vc, mat, px, py, px + pw, py + 2, fadeA << 24);
        quad(vc, mat, px, py + ph - 2, px + pw, py + ph, fadeA << 24);
        quad(vc, mat, px, py, px + 2, py + ph, fadeA << 24);
        quad(vc, mat, px + pw - 2, py, px + pw, py + ph, fadeA << 24);
        int hl = (int) (fadeA * 0.15f) << 24 | 0x00FFFFFF;
        quad(vc, mat, px + 2, py + 2, px + pw - 2, py + 3, hl);
        quad(vc, mat, px + 2, py + ph - 3, px + pw - 2, py + ph - 2, hl);
        quad(vc, mat, px + 2, py + 2, px + 3, py + ph - 2, hl);
        quad(vc, mat, px + pw - 3, py + 2, px + pw - 2, py + ph - 2, hl);


        int corner = (int) (fadeA * (0.6f + 0.4f * breathe)) << 24;
        quad(vc, mat, px - 3, py - 3, px + 3, py, corner);
        quad(vc, mat, px + pw - 3, py - 3, px + pw + 3, py, corner);
        quad(vc, mat, px - 3, py + ph, px + 3, py + ph + 3, corner);
        quad(vc, mat, px + pw - 3, py + ph, px + pw + 3, py + ph + 3, corner);


        int sepY = py + padTop + 2;
        quad(vc, mat, px + 6, sepY, px + pw - 6, sepY + 1, (int) (fadeA * 0.45f) << 24 | 0x00FFFFFF);

        bufferSource.endBatch(ReflectionRenderTypes.FULL_DEATH_PANEL);


        int titleX = px + (pw - textW) / 2;
        int baseY = py + (padTop - titleH) / 2;
        float tSec = (now - TIME_ANCHOR_MS) / 1000.0f;
        int penX = titleX;
        for (int i = 0; i < title.length(); i++) {
            String ch = String.valueOf(title.charAt(i));
            Component glyph = Component.literal(ch).withStyle(s -> s.withFont(DESC_FONT));
            int adv = font.width(glyph);

            float rnd = (float) Math.sin(i * 12.9898 + 78.233) * 43758.5453f;
            rnd = rnd - (float) Math.floor(rnd);
            float freq = 2.5f + 3.0f * rnd;
            float phase = rnd * 6.2831f;
            float s = 1f + 0.12f * (float) Math.sin(tSec * freq + phase);
            float wave = (float) Math.sin(tSec * 2.0f + phase) * 1.5f;

            float cx = penX + adv / 2.0f;
            float cy = baseY + titleH / 2.0f + wave;
            graphics.pose().pushPose();
            graphics.pose().translate(cx, cy, 0.0F);
            graphics.pose().scale(s, s, 1.0F);
            int ox = -adv / 2;
            int oy = -titleH / 2;

            int chRgb = Mth.hsvToRgb((flow + rnd * 0.5f) % 1.0f, 0.85f, 1.0f);
            int chCol = (fadeA << 24) | chRgb;
            int outCol = (fadeA << 24);
            graphics.drawString(font, glyph, ox - 1, oy, outCol, false);
            graphics.drawString(font, glyph, ox + 1, oy, outCol, false);
            graphics.drawString(font, glyph, ox, oy - 1, outCol, false);
            graphics.drawString(font, glyph, ox, oy + 1, outCol, false);
            graphics.drawString(font, glyph, ox, oy, chCol, false);
            graphics.drawString(font, glyph, ox + 1, oy, chCol, false);
            graphics.pose().popPose();
            penX += adv + gap;
        }

        graphics.pose().popPose();
    }


    private static void quad(VertexConsumer vc, Matrix4f mat, float x1, float y1, float x2, float y2, int color) {
        vc.vertex(mat, x1, y1, 0.0F).color(color).endVertex();
        vc.vertex(mat, x1, y2, 0.0F).color(color).endVertex();
        vc.vertex(mat, x2, y2, 0.0F).color(color).endVertex();
        vc.vertex(mat, x2, y1, 0.0F).color(color).endVertex();
    }


    private static void renderTypewriterDesc(GuiGraphics graphics, Font font, int screenWidth, int screenHeight) {
        if (DESC.length == 0) return;
        long now = System.currentTimeMillis();


        if (now - descLastRenderMs > 200L) {
            descSessionStart = now;
            descStartLine = (int) (Math.random() * DESC.length);
        }
        descLastRenderMs = now;

        long cycle = TYPE_MS + HOLD_MS + FADE_MS;
        long elapsed = now - descSessionStart;
        int lineIndex = (int) ((descStartLine + elapsed / cycle) % DESC.length);
        long t = elapsed % cycle;
        String line = DESC[lineIndex];

        float reveal;
        float alpha;
        if (t < TYPE_MS) {
            reveal = t / (float) TYPE_MS;
            alpha = reveal;
        } else if (t < TYPE_MS + HOLD_MS) {
            reveal = 1f;
            alpha = 1f;
        } else {
            reveal = 1f;
            alpha = 1f - (t - TYPE_MS - HOLD_MS) / (float) FADE_MS;
        }
        if (alpha <= 0.02f) return;

        int len = line.length();
        float center = (len - 1) / 2.0f;
        float maxDist = Math.max(center, 0.0001f);


        Component[] glyphs = new Component[len];
        int[] adv = new int[len];
        int totalWidth = 0;
        for (int i = 0; i < len; i++) {
            glyphs[i] = Component.literal(String.valueOf(line.charAt(i)))
                    .setStyle(Style.EMPTY.withFont(DESC_FONT));
            adv[i] = font.width(glyphs[i]);
            totalWidth += adv[i];
        }

        float penX = (screenWidth - totalWidth) / 2.0f;
        float baseY = screenHeight - 42f;
        final float band = 0.28f;
        float front = reveal * (1f + band);
        float ts = elapsed / 1000.0f;

        for (int i = 0; i < len; i++) {

            float dist = Math.abs(i - center) / maxDist;
            float charReveal = Math.max(0f, Math.min(1f, (front - dist) / band));
            float ca = alpha * charReveal;
            int a8 = (int) (ca * 255f);
            if (a8 >= 6) {
                float hue = (((ts * 50f) + i * 20f) % 360f) / 360f;
                int rgb = Color.HSBtoRGB(hue, 0.55f, 1.0f) & 0xFFFFFF;
                int col = (a8 << 24) | rgb;
                int outline = a8 << 24;

                float wave = (float) (Math.sin(ts * 4.0 + i * 0.6) * 1.6);
                graphics.pose().pushPose();
                graphics.pose().translate(penX, baseY + wave, 0f);

                graphics.drawString(font, glyphs[i], -1, 0, outline, false);
                graphics.drawString(font, glyphs[i], 1, 0, outline, false);
                graphics.drawString(font, glyphs[i], 0, -1, outline, false);
                graphics.drawString(font, glyphs[i], 0, 1, outline, false);

                graphics.drawString(font, glyphs[i], 0, 0, col, false);
                graphics.drawString(font, glyphs[i], 1, 0, col, false);
                graphics.pose().popPose();
            }
            penX += adv[i];
        }
    }

    private static final ResourceLocation OPTIMA_FONT = Reflection.rl("endofoptima");


    private static float[] optimaCharOffsets;
    private static float[] optimaCharVel;
    private static long optimaLastTick = 0L;
    private static int optimaCycleDir = 1;
    private static float optimaCycleHue = 0f;

    private static final String OPTIMA_DESC = "Finale";
    private static final float DESC_APPEAR_MS = 1200f;
    private static final float DESC_HOLD_MS = 2000f;
    private static final float DESC_DISAPPEAR_MS = 1200f;
    private static final float DESC_PAUSE_MS = 2000f;
    private static final float DESC_CYCLE_MS = DESC_APPEAR_MS + DESC_HOLD_MS + DESC_DISAPPEAR_MS + DESC_PAUSE_MS;

    private static void renderOptimaText(GuiGraphics graphics, Font font, ItemStack stack, int screenWidth, int screenHeight, long now) {

        if (now - descLastRenderMs > 200L) {
            descSessionStart = now;
            optimaCycleDir = (int)(Math.random() * 2) * 2 - 1;
            optimaCycleHue = (float)(Math.random());
            optimaCharOffsets = null;
        }
        descLastRenderMs = now;

        int dLen = OPTIMA_DESC.length();

        if (optimaCharOffsets == null || optimaCharOffsets.length != dLen) {
            optimaCharOffsets = new float[dLen];
            optimaCharVel = new float[dLen];
        }

        float dt = Math.min(0.05f, (now - optimaLastTick) / 1000.0f);
        optimaLastTick = now;
        float maxDrift = 2.5f;
        for (int i = 0; i < dLen; i++) {

            float force = (float)(Math.sin(now * 0.003 + i * 1.7) * 8.0 + Math.cos(now * 0.005 + i * 2.3) * 5.0);
            optimaCharVel[i] += force * dt;
            optimaCharVel[i] *= 0.9f;
            optimaCharOffsets[i] += optimaCharVel[i] * dt;

            if (optimaCharOffsets[i] > maxDrift) { optimaCharOffsets[i] = maxDrift; optimaCharVel[i] = -Math.abs(optimaCharVel[i]) * 0.6f; }
            if (optimaCharOffsets[i] < -maxDrift) { optimaCharOffsets[i] = -maxDrift; optimaCharVel[i] = Math.abs(optimaCharVel[i]) * 0.6f; }
        }


        float elapsed = (now - descSessionStart);
        float cycleTime = elapsed % DESC_CYCLE_MS;

        int cycleCount = (int)(elapsed / DESC_CYCLE_MS);
        long cycleSeed = descSessionStart + cycleCount * 9973L;
        int dir = ((int)(((cycleSeed * 2654435761L) >>> 33) % 2)) * 2 - 1;
        float hue = ((cycleSeed * 1234567L) >>> 33) % 360 / 360.0f;


        float reveal;
        boolean appearing;
        if (cycleTime < DESC_APPEAR_MS) {
            reveal = cycleTime / DESC_APPEAR_MS;
            appearing = true;
        } else if (cycleTime < DESC_APPEAR_MS + DESC_HOLD_MS) {
            reveal = 1f;
            appearing = true;
        } else if (cycleTime < DESC_APPEAR_MS + DESC_HOLD_MS + DESC_DISAPPEAR_MS) {
            reveal = 1f - (cycleTime - DESC_APPEAR_MS - DESC_HOLD_MS) / DESC_DISAPPEAR_MS;
            appearing = false;
        } else {
            reveal = 0f;
            appearing = false;
        }
        if (reveal <= 0.01f) return;


        int dTotalW = 0;
        for (int i = 0; i < dLen; i++) {
            dTotalW += font.width(Component.literal(String.valueOf(OPTIMA_DESC.charAt(i))).withStyle(s -> s.withFont(OPTIMA_FONT)));
        }
        float dPenX = (screenWidth - dTotalW) / 2.0f;
        float dBaseY = screenHeight * 0.78f;
        float waveSpeed = (float) dLen;

        int rgb = java.awt.Color.HSBtoRGB(hue, 0.65f, 1.0f) & 0xFFFFFF;
        int invRgb = (~rgb) & 0xFFFFFF;

        for (int i = 0; i < dLen; i++) {
            Component dGlyph = Component.literal(String.valueOf(OPTIMA_DESC.charAt(i)))
                    .withStyle(s -> s.withFont(OPTIMA_FONT));
            int dAdv = font.width(dGlyph);


            float charPos = (dir > 0) ? (float) i / dLen : (float) (dLen - 1 - i) / dLen;

            float effectivePos = appearing ? charPos : (1f - charPos);
            float charReveal = Math.min(1f, Math.max(0f, (reveal * 1.3f - effectivePos * 0.3f) / 1.0f));

            if (charReveal > 0.02f) {
                int ca = (int) (charReveal * 255);
                float drift = optimaCharOffsets[i] * charReveal;

                graphics.pose().pushPose();
                graphics.pose().translate(dPenX, dBaseY + drift, 0f);

                int outlineCol = (ca << 24) | invRgb;
                int dCol = (ca << 24) | rgb;

                graphics.drawString(font, dGlyph, -1, 0, outlineCol, false);
                graphics.drawString(font, dGlyph, 1, 0, outlineCol, false);
                graphics.drawString(font, dGlyph, 0, -1, outlineCol, false);
                graphics.drawString(font, dGlyph, 0, 1, outlineCol, false);

                graphics.drawString(font, dGlyph, 0, 0, dCol, false);
                graphics.drawString(font, dGlyph, 1, 0, dCol, false);

                graphics.pose().popPose();
            }
            dPenX += dAdv;
        }
    }


    private static void renderSpinningSword(GuiGraphics graphics, ItemStack stack, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        float angle = (now % 4000L) / 4000.0f * 360.0f;
        float centerX = screenWidth / 2.0f;
        float centerY = screenHeight / 2.0f;
        float size = Math.min(screenWidth, screenHeight) * 0.55f;

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 2460.0F);
        pose.scale(size, -size, size);
        pose.mulPose(Axis.YP.rotationDegrees(angle));

        Lighting.setupFor3DItems();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        com.ryjs.reflection.client.model.CosmicBakeModel.SUPPRESS_BACK_TAICHI = true;
        try {
            mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, 0xF000F0,
                    OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, 0);
            buffers.endBatch();
        } finally {
            com.ryjs.reflection.client.model.CosmicBakeModel.SUPPRESS_BACK_TAICHI = false;
        }
        Lighting.setupForFlatItems();
        pose.popPose();
    }
}
