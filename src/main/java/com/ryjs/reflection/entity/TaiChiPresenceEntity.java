package com.ryjs.reflection.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;


public class TaiChiPresenceEntity extends PathfinderMob {

    private static volatile TaiChiPresenceEntity INSTANCE;

    public TaiChiPresenceEntity(Level level) {
        super(com.ryjs.reflection.Registration.TAICHI_PARADOX.get(), level);
        this.setCustomName(Component.literal("\u00a75太极悖论者"));
        this.setCustomNameVisible(true);
    }


    public static TaiChiPresenceEntity instance() {
        return INSTANCE;
    }

    public static void setInstance(TaiChiPresenceEntity entity) {
        TaiChiPresenceEntity old = INSTANCE;
        if (old != null && old != entity) {
            PhantomRegistry.unregister(old);
        }
        INSTANCE = entity;
        if (entity != null) {
            PhantomRegistry.register(entity);
        }
    }

    public static void clearInstance() {
        TaiChiPresenceEntity old = INSTANCE;
        INSTANCE = null;
        if (old != null) {
            PhantomRegistry.unregister(old);
        }
    }


    @SuppressWarnings("unchecked")
    public static Object injectIntoResult(Object result) {
        TaiChiPresenceEntity inst = INSTANCE;
        if (inst == null) return result;

        if (result instanceof java.util.List<?> list) {
            java.util.ArrayList<Object> wrapped = new java.util.ArrayList<>(list);
            if (!wrapped.contains(inst)) wrapped.add(inst);
            return wrapped;
        } else if (result instanceof Iterable<?> iter) {
            Set<Object> wrapped = Collections.newSetFromMap(new IdentityHashMap<>());
            iter.forEach(wrapped::add);
            wrapped.add(inst);
            return Collections.unmodifiableSet(wrapped);
        }
        return result;
    }


    @SuppressWarnings("unchecked")
    public static void injectIntoList(Object list) {
        TaiChiPresenceEntity inst = INSTANCE;
        if (inst == null) return;
        if (list instanceof java.util.List<?> l) {
            java.util.List<Object> target = (java.util.List<Object>) l;
            if (!target.contains(inst)) {
                target.add(inst);
            }
        }
    }


    @Override public float getHealth() { return 20.0F; }
    @Override public float getMaxHealth() { return 20.0F; }
    @Override public boolean isDeadOrDying() { return false; }
    @Override public boolean isAlive() { return true; }
    @Override public boolean isInvulnerableTo(DamageSource source) { return true; }
    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public boolean removeWhenFarAway(double dist) { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean isNoAi() { return true; }
    @Override public boolean isNoGravity() { return true; }
    @Override public boolean isInvisible() { return true; }
    @Override public boolean shouldRender(double x, double y, double z) { return false; }
    @Override public boolean shouldRenderAtSqrDistance(double dist) { return false; }
    @Override public boolean broadcastToPlayer(ServerPlayer player) { return true; }
    @Override public void push(double x, double y, double z) {}
    @Override public void kill() {}
    @Override public void remove(RemovalReason reason) {}

    @Override
    public Component getName() {
        return Component.literal("\u00a75太极悖论者");
    }

    @Override
    public Component getDisplayName() {
        return getName();
    }
}
