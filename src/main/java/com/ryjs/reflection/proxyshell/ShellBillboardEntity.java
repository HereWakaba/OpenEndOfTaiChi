package com.ryjs.reflection.proxyshell;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;


public class ShellBillboardEntity extends Entity {

    public ShellBillboardEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noCulling = true;
    }

    @Override
    public void defineSynchedData() {
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (!this.level().isClientSide) {
            this.discard();
        }
        return true;
    }
}
