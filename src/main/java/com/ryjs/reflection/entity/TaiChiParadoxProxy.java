package com.ryjs.reflection.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;


public final class TaiChiParadoxProxy extends PathfinderMob {

    private double avatarX, avatarY, avatarZ;

    public TaiChiParadoxProxy(Level level) {
        super(EntityType.ZOMBIE, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public void applyAvatarState(double x, double y, double z,
                                  float bodyYaw, float headYaw, float pitch, float limbSwing) {
        this.xo = this.avatarX;
        this.yo = this.avatarY;
        this.zo = this.avatarZ;
        this.avatarX = x;
        this.avatarY = y;
        this.avatarZ = z;
        this.yBodyRotO = bodyYaw;
        this.yBodyRot = bodyYaw;
        this.yHeadRotO = headYaw;
        this.yHeadRot = headYaw;
        this.xRotO = pitch;
        this.setXRot(pitch);
        this.walkAnimation.update(limbSwing, 1.0F);
    }

    @Override public void setPos(double x, double y, double z) {
        this.avatarX = x; this.avatarY = y; this.avatarZ = z;
    }
    @Override public double getX() { return avatarX; }
    @Override public double getY() { return avatarY; }
    @Override public double getZ() { return avatarZ; }
    @Override public float getXRot() { return this.xRotO; }
    @Override public boolean isInvisible() { return false; }

    @Override
    public Component getName() {
        return Component.literal("§5太极悖论者");
    }

    @Override
    public Component getDisplayName() {
        return getName();
    }
}
