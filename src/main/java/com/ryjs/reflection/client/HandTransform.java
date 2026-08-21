package com.ryjs.reflection.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

@OnlyIn(Dist.CLIENT)
public final class HandTransform {

    private HandTransform() {
    }

    public static final IClientItemExtensions INSTANCE = new IClientItemExtensions() {
        @Override
        public boolean applyForgeHandTransform(PoseStack poseStack, LocalPlayer player,
                                                HumanoidArm arm, ItemStack itemInHand,
                                                float partialTick, float equipProcess,
                                                float swingProcess) {
            if (player.isUsingItem() && player.getUseItem() == itemInHand) {
                int i = arm == HumanoidArm.RIGHT ? 1 : -1;
                poseStack.translate((float) i * 0.56F, -0.52F, -0.72F);
                int horizontal = arm == HumanoidArm.RIGHT ? 1 : -1;
                poseStack.translate((float) horizontal * -0.14142136F, 0.08F, 0.14142136F);
                poseStack.mulPose(Axis.XP.rotationDegrees(-102.25F));
                poseStack.mulPose(Axis.YP.rotationDegrees((float) horizontal * 13.365F));
                poseStack.mulPose(Axis.ZP.rotationDegrees((float) horizontal * 78.05F));
                return true;
            }
            return false;
        }

        @Override
        public HumanoidModel.ArmPose getArmPose(LivingEntity entityLiving, InteractionHand hand, ItemStack itemStack) {
            if (entityLiving.isUsingItem() && entityLiving.getUseItem() == itemStack) {
                return HumanoidModel.ArmPose.BLOCK;
            }
            return HumanoidModel.ArmPose.ITEM;
        }
    };

    public static void register(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(INSTANCE);
    }
}
