package com.ryjs.reflection.entity;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;


public class EntityWitherzilla extends EntityTitan {

    private final float[] headYRotations = new float[2];
    private final float[] headXRotations = new float[2];

    public int affectTicks = 0;

    public EntityWitherzilla(EntityType<? extends EntityTitan> type, Level level) {
        super(type, level);
        this.setNoAi(true);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.xpReward = 5000000;
        this.threashHold = 210;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return EntityTitan.createAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.ATTACK_DAMAGE, Double.MAX_VALUE)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 256.0);
    }



    @Override
    public void setPose(Pose pose) {
        if (pose == Pose.DYING || pose == Pose.SLEEPING) {
            return;
        }
        super.setPose(pose);
    }

    @Override
    public Pose getPose() {
        return Pose.STANDING;
    }

    public float getHeadYRotation(int index) {
        return index < this.headYRotations.length ? this.headYRotations[index] : 0.0F;
    }

    public float getHeadXRotation(int index) {
        return index < this.headXRotations.length ? this.headXRotations[index] : 0.0F;
    }


    public float getSizeMultiplier() {
        return 64.0F;
    }


    public boolean isArmored() {
        return this.getHealth() <= this.getMaxHealth() / 2.0F;
    }


    public boolean isInOmegaForm() {
        return true;
    }



    @Override public void remove(RemovalReason reason) {}
    @Override public void setRemoved(RemovalReason removalReason) {}
    @Override public void die(DamageSource source) {}
    @Override public boolean isAlive() { return true; }
    @Override public boolean isDeadOrDying() { return false; }
    @Override public float getHealth() { return 10000000; }
    @Override public float getMaxHealth() { return 10000000; }
    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public boolean isInvulnerableTo(DamageSource source) { return true; }
    @Override public boolean shouldDespawnInPeaceful() { return false; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return true; }
    @Override public boolean shouldRender(double x, double y, double z) { return true; }

    @Override
    public void setTarget(@Nullable LivingEntity target) {

    }

    @Override
    public net.minecraft.sounds.SoundEvent getAmbientSound() {
        return net.minecraft.sounds.SoundEvents.WITHER_AMBIENT;
    }

    @Override
    public float getSoundVolume() {
        return 10.0F;
    }
}
