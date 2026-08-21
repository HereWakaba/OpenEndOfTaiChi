package com.ryjs.reflection.proxyshell;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;


public class ShellBillboardRenderer extends EntityRenderer<ShellBillboardEntity> {

    private static final ResourceLocation FALLBACK =
            new ResourceLocation("minecraft", "textures/misc/unknown_pack.png");

    public ShellBillboardRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public ResourceLocation getTextureLocation(ShellBillboardEntity entity) {
        ResourceLocation rl = ProxyShellEntities.textureFor(entity.getType());
        return rl != null ? rl : FALLBACK;
    }

    @Override
    public void render(ShellBillboardEntity entity, float entityYaw, float partialTicks,
                       PoseStack ps, MultiBufferSource buffer, int packedLight) {
        ResourceLocation tex = getTextureLocation(entity);
        ps.pushPose();
        ps.translate(0.0D, entity.getBbHeight() * 0.5D, 0.0D); // 抬到实体中心
        ps.mulPose(this.entityRenderDispatcher.cameraOrientation());  // 面向摄像机
        ps.mulPose(Axis.YP.rotationDegrees(180.0F));
        Matrix4f pose = ps.last().pose();
        Matrix3f normal = ps.last().normal();
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutout(tex));
        float h = 0.5F;
        vertex(vc, pose, normal, -h, -h, 0.0F, 1.0F, packedLight);
        vertex(vc, pose, normal, h, -h, 1.0F, 1.0F, packedLight);
        vertex(vc, pose, normal, h, h, 1.0F, 0.0F, packedLight);
        vertex(vc, pose, normal, -h, h, 0.0F, 0.0F, packedLight);
        ps.popPose();
        super.render(entity, entityYaw, partialTicks, ps, buffer, packedLight);
    }

    private static void vertex(VertexConsumer vc, Matrix4f pose, Matrix3f normal,
                               float x, float y, float u, float v, int light) {
        vc.vertex(pose, x, y, 0.0F)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normal, 0.0F, 1.0F, 0.0F)
                .endVertex();
    }
}
