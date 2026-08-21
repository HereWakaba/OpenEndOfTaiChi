package com.ryjs.reflection.client.render;

import com.ryjs.reflection.entity.TaiChiParadoxProxy;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;


public final class TaiChiParadoxRenderer
        extends HumanoidMobRenderer<TaiChiParadoxProxy, HumanoidModel<TaiChiParadoxProxy>> {

    private static volatile TaiChiParadoxRenderer instance;

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("reflection", "textures/entity/taichi_paradox.png");

    public TaiChiParadoxRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.0F);
        instance = this;
    }

    public static TaiChiParadoxRenderer instance() {
        return instance;
    }

    @Override
    public ResourceLocation getTextureLocation(TaiChiParadoxProxy entity) {
        return TEXTURE;
    }

    @Override
    public void render(TaiChiParadoxProxy entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
    }

    @Override
    protected void scale(TaiChiParadoxProxy entity, PoseStack poseStack, float partialTick) {
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    protected boolean shouldShowName(TaiChiParadoxProxy entity) {
        return true;
    }

    @Override
    protected void renderNameTag(TaiChiParadoxProxy entity, Component name, PoseStack poseStack,
                                 MultiBufferSource buffers, int packedLight) {
        float phase = (float) (System.currentTimeMillis() % 4000L) / 4000.0F;
        poseStack.pushPose();
        poseStack.translate(0.0, Mth.sin(phase * (float) (Math.PI * 2)) * 0.12F, 0.0);
        super.renderNameTag(entity, entity.getName(), poseStack, buffers, packedLight);
        poseStack.popPose();
    }
}
