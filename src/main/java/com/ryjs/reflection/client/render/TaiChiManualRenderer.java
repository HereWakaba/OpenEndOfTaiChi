package com.ryjs.reflection.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import com.ryjs.reflection.command.RyjsCommand;
import com.ryjs.reflection.entity.TaiChiParadoxManager;
import com.ryjs.reflection.entity.TaiChiParadoxProxy;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


public final class TaiChiManualRenderer {

    private static final MultiBufferSource.BufferSource BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(256));
    private static Method GET_FOV;

    static {
        try {
            GET_FOV = GameRenderer.class.getDeclaredMethod("m_109141_",
                    Camera.class, float.class, boolean.class);
            GET_FOV.setAccessible(true);
        } catch (NoSuchMethodException e) {
            try {
                GET_FOV = GameRenderer.class.getDeclaredMethod("getFov",
                        Camera.class, float.class, boolean.class);
                GET_FOV.setAccessible(true);
            } catch (NoSuchMethodException ex) {
                System.err.println("Cannot find getFov method!");
            }
        }
    }

    private TaiChiManualRenderer() {}

    private static int fboId = 0;
    private static int colorTex = 0;
    private static int depthRbo = 0;
    private static int fboWidth = 0;
    private static int fboHeight = 0;

    public static void renderBeforeSwap() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) return;
        if (!RenderSystem.isOnRenderThread()) return;

        long now = System.currentTimeMillis();
        if (now - LAST_SWAP_DIAG > 1000L) {
            LAST_SWAP_DIAG = now;
            System.out.println("swap: pureC=" + RyjsCommand.isPureCLayer()
                    + " fbo=" + fboId + " native=" + com.ryjs.reflection.client.render.TaiChiRenderControl.isNativeInstalled());
        }

        if (RyjsCommand.isPureCLayer()) {
            renderToOffscreenAndPush(mc);
        } else {
            renderIntoScene(mc, 0); // targetFbo=0 → MC 默认 framebuffer
        }

        WitherzillaBossBarRenderer.renderHud(mc);
    }

    private static void renderToOffscreenAndPush(Minecraft mc) {
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        if (w <= 0 || h <= 0) return;

        ensureFbo(w, h);
        if (fboId == 0) return;

        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, false);

        renderIntoScene(mc, fboId);


        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);

        long handle = mc.getWindow().getWindow();
        int[] px = new int[1];
        int[] py = new int[1];
        GLFW.glfwGetWindowPos(handle, px, py);
        int winX = px[0];
        int winY = py[0];

        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int srcRow = (h - 1 - y) * w;
            int dstRow = y * w;
            for (int x = 0; x < w; x++) {
                int i = (srcRow + x) * 4;
                int r = buf.get(i) & 0xFF;
                int g = buf.get(i + 1) & 0xFF;
                int b = buf.get(i + 2) & 0xFF;
                int a = buf.get(i + 3) & 0xFF;
                pixels[dstRow + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        com.ryjs.reflection.client.render.TaiChiRenderControl.pushOverlayFrame(pixels, w, h, winX, winY);
    }

    private static void ensureFbo(int w, int h) {
        if (fboId != 0 && w == fboWidth && h == fboHeight) return;
        deleteFbo();
        try {
            fboId = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);

            colorTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, colorTex, 0);

            depthRbo = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRbo);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, w, h);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_RENDERBUFFER, depthRbo);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                System.err.println("离屏 FBO 不完整: 0x" + Integer.toHexString(status));
                deleteFbo();
            } else {
                fboWidth = w;
                fboHeight = h;
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        } catch (Throwable t) {
            System.err.println("创建离屏 FBO 失败: " + t);
            deleteFbo();
        }
    }

    private static void deleteFbo() {
        if (colorTex != 0) { GL11.glDeleteTextures(colorTex); colorTex = 0; }
        if (depthRbo != 0) { GL30.glDeleteRenderbuffers(depthRbo); depthRbo = 0; }
        if (fboId != 0) { GL30.glDeleteFramebuffers(fboId); fboId = 0; }
        fboWidth = fboHeight = 0;
    }

    private static long LAST_SWAP_DIAG = 0L;
    private static long LAST_DIAG_MS = 0L;
    private static long LAST_ENTITY_DIAG = 0L;

    private static void renderIntoScene(Minecraft mc, int targetFbo) {
        float partialTick = mc.getFrameTime();

        long now = System.currentTimeMillis();
        if (now - LAST_DIAG_MS > 1000L) {
            LAST_DIAG_MS = now;
            System.out.println("diag: taichi=" + (TaiChiParadoxManager.isAlive() ? "alive" : "gone")
                    + " native=" + com.ryjs.reflection.client.render.TaiChiRenderControl.isNativeInstalled());
        }

        TaiChiParadoxProxy taichi = TaiChiParadoxManager.update(partialTick);
        if (taichi != null) {
            TaiChiParadoxManager.syncProxyFromAvatar();
            TaiChiParadoxRenderer renderer = TaiChiParadoxRenderer.instance();
            if (renderer == null) {
                renderer = createTaiChiRenderer(mc);
            }
            if (renderer != null) {
                renderEntityAt(mc, taichi, renderer, taichi.yBodyRot, targetFbo);
            }
        }

        com.ryjs.reflection.entity.EntityWitherzilla witherzilla =
                com.ryjs.reflection.entity.WitherzillaPhantomManager.update(partialTick);
        if (witherzilla != null) {
            net.minecraft.client.renderer.entity.EntityRenderer<?> wzRenderer =
                    mc.getEntityRenderDispatcher().getRenderer(witherzilla);
            if (wzRenderer != null) {
                renderEntityAt(mc, witherzilla, wzRenderer, witherzilla.yBodyRot, targetFbo);
            }
        }
    }

    private static TaiChiParadoxRenderer createTaiChiRenderer(Minecraft mc) {
        try {
            EntityRenderDispatcher erd = mc.getEntityRenderDispatcher();
            EntityRendererProvider.Context ctx = new EntityRendererProvider.Context(
                    erd, mc.getItemRenderer(), mc.getBlockRenderer(),
                    mc.gameRenderer.itemInHandRenderer, mc.getResourceManager(),
                    mc.getEntityModels(), mc.font);
            TaiChiParadoxRenderer renderer = new TaiChiParadoxRenderer(ctx);
            System.out.println("Renderer created OK");
            return renderer;
        } catch (Throwable e) {
            System.err.println("Renderer init FAILED: " + e);
            e.printStackTrace();
            return null;
        }
    }


    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderEntityAt(Minecraft mc, net.minecraft.world.entity.Entity entity,
                                       net.minecraft.client.renderer.entity.EntityRenderer renderer,
                                       float yaw, int targetFbo) {
        if (entity == null || renderer == null) return;
        if (!RenderSystem.isOnRenderThread()) return;

        boolean pureC = targetFbo != 0;
        Vec3 position;
        if (entity instanceof com.ryjs.reflection.entity.TaiChiParadoxProxy) {
            double[] sp = com.ryjs.reflection.entity.TaiChiParadoxManager.spawnPos();
            position = new Vec3(sp[0], sp[1], sp[2]);
        } else {
            position = new Vec3(entity.getX(), entity.getY(), entity.getZ());
        }
        Camera mainCamera = mc.gameRenderer.getMainCamera();
        Vec3 camera = mainCamera.getPosition();

        long now = System.currentTimeMillis();
        if (now - LAST_ENTITY_DIAG > 1000L) {
            LAST_ENTITY_DIAG = now;
            System.out.println("draw: fboBind=" + GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
                    + " entity=" + entity.getClass().getSimpleName()
                    + " removed=" + entity.isRemoved()
                    + " renderPos=(" + (int) position.x + "," + (int) position.y + "," + (int) position.z + ")"
                    + " fieldPos=(" + (int) entity.getX() + "," + (int) entity.getY() + "," + (int) entity.getZ() + ")"
                    + " cam=(" + (int) camera.x + "," + (int) camera.y + "," + (int) camera.z + ")");
        }

        Matrix4f worldProjection;
        try {
            if (GET_FOV == null) return;
            double fov = (Double) GET_FOV.invoke(mc.gameRenderer, mainCamera, mc.getFrameTime(), true);
            worldProjection = mc.gameRenderer.getProjectionMatrix(fov);
        } catch (Exception e) {
            return;
        }

        VertexSorting worldSorting = RenderSystem.getVertexSorting();


        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(Axis.XP.rotationDegrees(mainCamera.getXRot()));
        poseStack.mulPose(Axis.YP.rotationDegrees(mainCamera.getYRot() + 180.0F));


        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(worldProjection, worldSorting);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);

        if ((com.ryjs.reflection.client.render.TaiChiRenderControl.isNativeInstalled()
                && !RyjsCommand.isForceForgeRenderer())
                || pureC) {
            RenderSystem.clear(256, false); // GL_DEPTH_BUFFER_BIT
            RenderSystem.disableDepthTest();
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();

        try {
            poseStack.translate(
                    position.x - camera.x,
                    position.y - camera.y,
                    position.z - camera.z);
            renderer.render(entity, yaw, 1.0F, poseStack, BUFFERS, 15728880);
        } catch (Throwable e) {
            System.err.println("Render FAILED: " + e);
            e.printStackTrace();
        } finally {
            BUFFERS.endBatch();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
        }
    }
}
