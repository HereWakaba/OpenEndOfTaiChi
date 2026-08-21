package com.ryjs.reflection.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import com.ryjs.reflection.Reflection;
import com.ryjs.reflection.client.model.ModelWitherzilla;
import com.ryjs.reflection.entity.EntityWitherzilla;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.entity.Pose;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;


@OnlyIn(Dist.CLIENT)
public class RenderWitherzilla extends MobRenderer<EntityWitherzilla, ModelWitherzilla> {

    private static final ResourceLocation WITHERZILLA_TEXTURES =
            new ResourceLocation(Reflection.MODID, "textures/entity/witherzilla.png");

    private static final ResourceLocation WITHERZILLA_OMEGA_TEXTURES =
            new ResourceLocation(Reflection.MODID, "textures/entity/witherzilla_omega.png");

    private static final ResourceLocation WITHERZILLA_ARMORED_TEXTURES =
            new ResourceLocation(Reflection.MODID, "textures/entity/witherzilla_armored.png");

    private static final ResourceLocation WITHERZILLA_SHIELD =
            new ResourceLocation(Reflection.MODID, "textures/entity/wither_aura.png");

    public RenderWitherzilla(EntityRendererProvider.Context context) {
        super(context, new ModelWitherzilla(context.bakeLayer(ModelWitherzilla.LAYER_LOCATION)), 1.0F);
        this.addLayer(new WitherzillaShieldLayer(this));
        this.addLayer(new WitherzillaEffectLayer(this));
    }

    @Override
    public boolean shouldRender(EntityWitherzilla entity, Frustum frustum, double x, double y, double z) {
        return false; // witherzilla 恒为幻象，由 native 手动渲染器绘制，原版管线永不绘制（与 taichi 一致）
    }

    @Override
    public ResourceLocation getTextureLocation(EntityWitherzilla entity) {
        if (entity.isInOmegaForm()) {
            return WITHERZILLA_OMEGA_TEXTURES;
        } else if (entity.isArmored()) {
            return WITHERZILLA_ARMORED_TEXTURES;
        }
        return WITHERZILLA_TEXTURES;
    }

    @Override
    public void scale(EntityWitherzilla entity, PoseStack poseStack, float partialTick) {
        float scale = entity.getSizeMultiplier() * 2.0F;
        poseStack.scale(scale * 4, scale * 4, scale * 4);
    }

    @Override
    public void setupRotations(EntityWitherzilla entity, PoseStack poseStack,
                               float ageInTicks, float rotationYaw, float partialTick) {
        if (entity.deathTime > 0) {
            entity.deathTime = 0;
        }
        entity.setPose(Pose.STANDING);
        super.setupRotations(entity, poseStack, ageInTicks, rotationYaw, partialTick);
    }

    @Override
    public void render(EntityWitherzilla entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {

        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @OnlyIn(Dist.CLIENT)
    public static class WitherzillaShieldLayer extends RenderLayer<EntityWitherzilla, ModelWitherzilla> {

        public WitherzillaShieldLayer(RenderLayerParent<EntityWitherzilla, ModelWitherzilla> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                           EntityWitherzilla entity, float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {

            if (entity.isArmored() || entity.isInOmegaForm()) {
                float time = entity.tickCount + partialTick;

                VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.energySwirl(
                        WITHERZILLA_SHIELD,
                        time * 0.015F % 1.0F,
                        time * 0.01F % 1.0F));

                this.getParentModel().renderToBuffer(poseStack, vertexConsumer,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                        0.5F, 0.5F, 0.5F, 0.5F);
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static class WitherzillaEffectLayer extends RenderLayer<EntityWitherzilla, ModelWitherzilla> {

        public WitherzillaEffectLayer(RenderLayerParent<EntityWitherzilla, ModelWitherzilla> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                           EntityWitherzilla entity, float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {

            if (entity.affectTicks > 0) {
                renderLightningEffect(poseStack, buffer, entity, partialTick);
            } else if (entity.deathTime > 0) {
                renderDeathEffect(poseStack, buffer, entity, partialTick);
            }
        }

        private void renderLightningEffect(PoseStack poseStack, MultiBufferSource buffer,
                                           EntityWitherzilla entity, float partialTick) {

            RandomSource rand = RandomSource.create(432L);
            float progress = (entity.affectTicks + partialTick) / 1000.0F;
            float fade = 0.0F;
            if (progress > 0.8F) {
                fade = (progress - 0.8F) / 0.2F;
            }

            poseStack.pushPose();

            RenderSystem.enableBlend();
            RenderSystem.blendFunc(770, 1);
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);

            int particleCount = (int) ((progress + progress * progress) / 2.0F * 100.0F);

            for (int i = 0; i < particleCount; i++) {
                poseStack.pushPose();

                poseStack.mulPose(Axis.XP.rotationDegrees(rand.nextFloat() * 360.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(rand.nextFloat() * 360.0F));
                poseStack.mulPose(Axis.ZP.rotationDegrees(rand.nextFloat() * 360.0F));
                poseStack.mulPose(Axis.XP.rotationDegrees(rand.nextFloat() * 360.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(rand.nextFloat() * 360.0F));
                poseStack.mulPose(Axis.ZP.rotationDegrees(rand.nextFloat() * 360.0F + progress * 90.0F));

                float height = rand.nextFloat() * 10.0F + 5.0F + fade * 20.0F;
                float width = rand.nextFloat() * 2.0F + 1.0F + fade * 2.0F;
                int alpha = (int) (255.0F * (1.0F - fade));

                VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.lightning());

                vertex(vertexConsumer, poseStack, 0.0F, 0.0F, 0.0F, alpha);
                vertex(vertexConsumer, poseStack, -0.866F * width, height, -0.5F * width, 0);
                vertex(vertexConsumer, poseStack, 0.866F * width, height, -0.5F * width, 0);
                vertex(vertexConsumer, poseStack, 0.0F, height, 1.0F * width, alpha);
                vertex(vertexConsumer, poseStack, -0.866F * width, height, -0.5F * width, 0);

                poseStack.popPose();
            }

            poseStack.popPose();

            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        private void renderDeathEffect(PoseStack poseStack, MultiBufferSource buffer,
                                       EntityWitherzilla entity, float partialTick) {

            RandomSource rand = RandomSource.create(432L);
            float progress = (entity.deathTime + partialTick) / 300.0F;
            float fade = 0.0F;
            if (progress > 0.8F) {
                fade = (progress - 0.8F) / 0.2F;
            }

            poseStack.pushPose();

            RenderSystem.enableBlend();
            RenderSystem.blendFunc(770, 1);
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);

            int particleCount = (int) ((progress + progress * progress) / 2.0F * 800.0F);

            for (int i = 0; i < particleCount; i++) {
                poseStack.pushPose();

                poseStack.mulPose(Axis.XP.rotationDegrees(rand.nextFloat() * 360.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(rand.nextFloat() * 360.0F));
                poseStack.mulPose(Axis.ZP.rotationDegrees(rand.nextFloat() * 360.0F));
                poseStack.mulPose(Axis.XP.rotationDegrees(rand.nextFloat() * 360.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(rand.nextFloat() * 360.0F));
                poseStack.mulPose(Axis.ZP.rotationDegrees(rand.nextFloat() * 360.0F + progress * 90.0F));

                float height = rand.nextFloat() * 2.0F + 1.0F + fade * 20.0F;
                float width = rand.nextFloat() * 4.0F + 2.0F + fade * 4.0F;
                int alpha = (int) (255.0F * (1.0F - fade));

                VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.lightning());

                vertex(vertexConsumer, poseStack, 0.0F, 0.0F, 0.0F, alpha);
                vertex(vertexConsumer, poseStack, -0.866F * width, height, -0.5F * width, 0);
                vertex(vertexConsumer, poseStack, 0.866F * width, height, -0.5F * width, 0);
                vertex(vertexConsumer, poseStack, 0.0F, height, 1.0F * width, alpha);
                vertex(vertexConsumer, poseStack, -0.866F * width, height, -0.5F * width, 0);

                poseStack.popPose();
            }

            poseStack.popPose();

            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        private void vertex(VertexConsumer consumer, PoseStack poseStack,
                            float x, float y, float z, int alpha) {
            consumer.vertex(poseStack.last().pose(), x, y, z)
                    .color(0.9F, 0.5F, 0.1F, alpha / 255.0F)
                    .endVertex();
        }
    }
}
