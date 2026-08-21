package com.ryjs.reflection.entity;

import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public final class TaiChiDominion {

    private TaiChiDominion() {}

    private static volatile boolean attacking = false;

    private static final Set<UUID> OWNERS = ConcurrentHashMap.newKeySet();

    public static boolean isAttacking() {
        return attacking;
    }

    public static void setAttacking(boolean value) {
        attacking = value;
    }


    public static synchronized boolean toggleAttacking() {
        attacking = !attacking;
        return attacking;
    }


    public static void activateAttacking() {
        attacking = true;
    }


    public static void registerOwner(UUID id) {
        if (id != null) {
            OWNERS.add(id);
        }
    }


    public static boolean isOwner(Entity entity) {
        return entity != null && OWNERS.contains(entity.getUUID());
    }


    public static boolean visibleUnderDominion(Entity entity) {
        return !attacking || isOwner(entity);
    }
}
