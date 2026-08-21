package com.ryjs.reflection.entity;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;


public class EntityTitan extends PathfinderMob implements Enemy {

    protected float titanSizeMultiplier = 48.0F;
    protected int threashHold = 850;

    public EntityTitan(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setMaxUpStep(4.0F);
        this.xpReward = 5000;
        this.setNoAi(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 100.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.ARMOR, 100.0)
                .add(Attributes.ARMOR_TOUGHNESS, 10.0);
    }

    @Override
    public void registerGoals() {
        // 幻象无 AI
    }

    public float getTitanSizeMultiplier() {
        return this.titanSizeMultiplier;
    }

    public int getThreashHold() {
        return this.threashHold;
    }

    @Override
    public void die(DamageSource source) {}

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public void knockback(double strength, double x, double z) {
        super.knockback(0.0, x, z);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    public boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }
}
