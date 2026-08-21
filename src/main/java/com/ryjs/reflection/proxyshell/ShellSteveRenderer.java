package com.ryjs.reflection.proxyshell;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

public class ShellSteveRenderer extends EntityRenderer<ShellBillboardEntity> {

    private final HumanoidModel<LivingEntity> model;

    public ShellSteveRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.model = new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER));
    }

    @Override
    public ResourceLocation getTextureLocation(ShellBillboardEntity entity) {
        ResourceLocation rl = ProxyShellEntities.textureFor(entity.getType()); // 被拦 mod 自己的实体贴图
        return rl != null ? rl : DefaultPlayerSkin.getDefaultSkin();           // 找不到才退回史蒂夫皮肤
    }

    @Override
    public void render(ShellBillboardEntity entity, float entityYaw, float partialTicks,
                       PoseStack ps, MultiBufferSource buffer, int packedLight) {
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - entityYaw)); // 按实体朝向转身
        ps.scale(-1.0F, -1.0F, 1.0F);                            // 人形模型在模型空间是"倒置"的
        ps.translate(0.0D, -1.501D, 0.0D);                       // 脚底对齐实体原点
        this.model.young = false;
        this.model.attackTime = 0.0F;
        this.model.riding = false;
        this.model.crouching = false;
        VertexConsumer vc = buffer.getBuffer(this.model.renderType(getTextureLocation(entity)));
        this.model.renderToBuffer(ps, vc, packedLight, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        ps.popPose();
        super.render(entity, entityYaw, partialTicks, ps, buffer, packedLight);
    }
}
