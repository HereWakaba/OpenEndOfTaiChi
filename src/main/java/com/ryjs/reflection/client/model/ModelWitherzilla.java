package com.ryjs.reflection.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ryjs.reflection.Reflection;
import com.ryjs.reflection.entity.EntityWitherzilla;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;


@OnlyIn(Dist.CLIENT)
public class ModelWitherzilla extends EntityModel<EntityWitherzilla> {

    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(new ResourceLocation(Reflection.MODID, "witherzilla"), "main");

    private final ModelPart[] spine;
    private final ModelPart[] heads;
    private final ModelPart root;

    public ModelWitherzilla(ModelPart root) {
        this.root = root;
        this.spine = new ModelPart[3];
        this.heads = new ModelPart[3];

        this.spine[0] = root.getChild("spine_0");
        this.spine[1] = root.getChild("spine_1");
        this.spine[2] = root.getChild("spine_2");

        this.heads[0] = root.getChild("head_0");
        this.heads[1] = root.getChild("head_1");
        this.heads[2] = root.getChild("head_2");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshDefinition = new MeshDefinition();
        PartDefinition partDefinition = meshDefinition.getRoot();

        partDefinition.addOrReplaceChild("spine_0",
                CubeListBuilder.create()
                        .texOffs(0, 16)
                        .addBox(-10.0F, 3.9F, -0.5F, 20, 3, 3),
                PartPose.offset(0.0F, 0.0F, 0.0F));

        PartDefinition spine1 = partDefinition.addOrReplaceChild("spine_1",
                CubeListBuilder.create()
                        .texOffs(0, 22)
                        .addBox(0.0F, 0.0F, 0.0F, 3, 10, 3)
                        .texOffs(24, 22)
                        .addBox(-4.0F, 1.5F, 0.5F, 11, 2, 2)
                        .texOffs(24, 22)
                        .addBox(-4.0F, 4.0F, 0.5F, 11, 2, 2)
                        .texOffs(24, 22)
                        .addBox(-4.0F, 6.5F, 0.5F, 11, 2, 2),
                PartPose.offset(-2.0F, 9.9F, -0.5F));

        partDefinition.addOrReplaceChild("spine_2",
                CubeListBuilder.create()
                        .texOffs(12, 22)
                        .addBox(0.0F, 0.0F, 0.0F, 3, 6, 3),
                PartPose.offset(-2.0F, 18.0F, 0.0F));

        partDefinition.addOrReplaceChild("head_0",
                CubeListBuilder.create()
                        .texOffs(0, 0)
                        .addBox(-4.0F, -4.0F, -4.0F, 8, 8, 8),
                PartPose.offset(0.0F, 3.0F, 0.0F));

        partDefinition.addOrReplaceChild("head_1",
                CubeListBuilder.create()
                        .texOffs(32, 0)
                        .addBox(-3.0F, -3.0F, -3.0F, 6, 6, 6),
                PartPose.offset(-10.0F, 7.0F, 0.0F));

        partDefinition.addOrReplaceChild("head_2",
                CubeListBuilder.create()
                        .texOffs(32, 0)
                        .addBox(-3.0F, -3.0F, -3.0F, 6, 6, 6),
                PartPose.offset(10.0F, 7.0F, 0.0F));

        return LayerDefinition.create(meshDefinition, 64, 64);
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer,
                               int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {

        for (ModelPart head : heads) {
            head.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        }
        for (ModelPart segment : spine) {
            segment.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        }
    }

    @Override
    public void setupAnim(EntityWitherzilla entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {

        heads[0].setPos(0.0F, 3.0F, 0.0F);
        heads[1].setPos(-10.0F, 7.0F, 0.0F);
        heads[2].setPos(10.0F, 7.0F, 0.0F);
        spine[0].setPos(0.0F, 3.0F, 0.0F);
        spine[1].setPos(-2.0F, 9.9F, -0.5F);

        float spineWave = Mth.cos(ageInTicks * 0.025F);
        spine[1].xRot = (0.065F + 0.025F * spineWave) * Mth.PI;

        float spine1RotX = spine[1].xRot;
        spine[2].setPos(
                -2.0F,
                9.9F + Mth.cos(spine1RotX) * 10.0F,
                -0.5F + Mth.sin(spine1RotX) * 10.0F
        );

        float spineWave2 = Mth.cos(ageInTicks * 0.025F - 1.0F);
        spine[2].xRot = (0.265F + 0.1F * spineWave2) * Mth.PI;

        heads[0].yRot = netHeadYaw * Mth.DEG_TO_RAD;
        heads[0].xRot = headPitch * Mth.DEG_TO_RAD;

        heads[1].yRot = (entity.getHeadYRotation(0) - entity.yBodyRot) * Mth.DEG_TO_RAD;
        heads[1].xRot = entity.getHeadXRotation(0) * Mth.DEG_TO_RAD;

        heads[2].yRot = (entity.getHeadYRotation(1) - entity.yBodyRot) * Mth.DEG_TO_RAD;
        heads[2].xRot = entity.getHeadXRotation(1) * Mth.DEG_TO_RAD;
    }
}