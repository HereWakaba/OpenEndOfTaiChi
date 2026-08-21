package com.ryjs.event.tooltip;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;


public final class TaiChiCubeRenderer {

    private TaiChiCubeRenderer() {}

    private static final long START = System.currentTimeMillis();

    private static final class Face {
        final float[] sx = new float[4];
        final float[] sy = new float[4];
        float depth;
    }


    private static final double[][] CORNERS = {
            {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
            {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}
    };

    private static final int[][] FACES = {
            {0, 1, 2, 3}, // -z
            {5, 4, 7, 6}, // +z
            {4, 0, 3, 7}, // -x
            {1, 5, 6, 2}, // +x
            {4, 5, 1, 0}, // -y
            {3, 2, 6, 7}  // +y
    };

    public static void render(GuiGraphics graphics, int screenWidth, int screenHeight) {
        double t = (System.currentTimeMillis() - START) / 1000.0;

        double orbit = t * 0.6 + Math.sin(t * 0.7) * 0.55;

        float cx = screenWidth / 2.0f;
        float cy = screenHeight / 2.0f;
        float scale = Math.min(screenWidth, screenHeight) / 7.0f;
        double tilt = Math.toRadians(62.0);

        List<Face> faces = new ArrayList<>();
        addRing(faces, 12, 2.0, 0.26, 0.0, orbit, tilt, cx, cy, scale);
        addRing(faces, 8, 3.1, 0.30, 0.7, -orbit * 0.8, tilt, cx, cy, scale);

        faces.sort((a, b) -> Float.compare(a.depth, b.depth));

        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(ReflectionRenderTypes.WAKABA_CUBE);
        Matrix4f mat = graphics.pose().last().pose();
        float z = 1950.0f;
        for (Face f : faces) {
            vc.vertex(mat, f.sx[0], f.sy[0], z).uv(0, 0).color(255, 255, 255, 255).endVertex();
            vc.vertex(mat, f.sx[1], f.sy[1], z).uv(1, 0).color(255, 255, 255, 255).endVertex();
            vc.vertex(mat, f.sx[2], f.sy[2], z).uv(1, 1).color(255, 255, 255, 255).endVertex();
            vc.vertex(mat, f.sx[3], f.sy[3], z).uv(0, 1).color(255, 255, 255, 255).endVertex();
        }
        buffer.endBatch(ReflectionRenderTypes.WAKABA_CUBE);
    }

    private static void addRing(List<Face> faces, int count, double radius, double half,
                                double phase, double orbit, double tilt,
                                float cx, float cy, float scale) {
        double sinT = Math.sin(tilt), cosT = Math.cos(tilt);
        for (int c = 0; c < count; c++) {
            double a = orbit + phase + c * (Math.PI * 2.0 / count);
            double ringX = Math.cos(a) * radius;
            double ringZ = Math.sin(a) * radius;
            double spin = a * 1.7;
            double sinS = Math.sin(spin), cosS = Math.cos(spin);
            double sinX = Math.sin(spin * 0.6), cosX = Math.cos(spin * 0.6);

            double[][] world = new double[8][3];
            for (int i = 0; i < 8; i++) {
                double x = CORNERS[i][0] * half;
                double y = CORNERS[i][1] * half;
                double zz = CORNERS[i][2] * half;

                double x1 = x * cosS - zz * sinS;
                double z1 = x * sinS + zz * cosS;

                double y2 = y * cosX - z1 * sinX;
                double z2 = y * sinX + z1 * cosX;

                double px = x1 + ringX;
                double pz = z2 + ringZ;

                double y3 = y2 * cosT - pz * sinT;
                double z3 = y2 * sinT + pz * cosT;
                world[i][0] = px;
                world[i][1] = y3;
                world[i][2] = z3;
            }

            for (int[] fi : FACES) {
                Face f = new Face();
                double dsum = 0;
                for (int j = 0; j < 4; j++) {
                    double[] w = world[fi[j]];
                    f.sx[j] = cx + (float) (w[0] * scale);
                    f.sy[j] = cy - (float) (w[1] * scale);
                    dsum += w[2];
                }
                f.depth = (float) (dsum / 4.0);
                faces.add(f);
            }
        }
    }
}
