package com.ryjs.reflection.entity;

import net.minecraft.util.Mth;


public final class TaiChiParadoxAvatar {

    private double x, y, z;
    private float bodyYaw;
    private float headYaw;
    private float headPitch;
    private float age;
    private long lastUpdateNanos;

    public TaiChiParadoxAvatar(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void update() {
        long now = System.nanoTime();
        if (lastUpdateNanos == 0L) {
            lastUpdateNanos = now;
            return;
        }
        double delta = Math.min((now - lastUpdateNanos) / 1.0E9, 0.05);
        lastUpdateNanos = now;

        age += (float) (delta * 20.0);


        headYaw = bodyYaw + Mth.sin(age * 0.035F) * 8.0F;
        headPitch = Mth.sin(age * 0.027F) * 3.0F;
    }

    public void apply(TaiChiParadoxProxy proxy, float partialTick) {

        double renderY = y + Mth.sin(age * 0.08F) * 0.02;
        proxy.applyAvatarState(x, renderY, z, bodyYaw, headYaw, headPitch, 0.0F);
    }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
}
