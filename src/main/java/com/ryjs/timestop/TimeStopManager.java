package com.ryjs.timestop;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;


public final class TimeStopManager {

    private static volatile boolean active = false;
    private static volatile UUID attacker = null;
    private static volatile int keepAlive = 0;
    private static volatile boolean fullPause = false;

    private TimeStopManager() {}

    public static void sustain(Player player) {
        attacker = player.getUUID();
        active = true;
        fullPause = false;
        keepAlive = 3;
    }


    public static void sustainFullPause(Player player) {
        attacker = player.getUUID();
        active = true;
        fullPause = true;
        keepAlive = 3;
    }

    public static void deactivate() {
        active = false;
        attacker = null;
        fullPause = false;
        keepAlive = 0;
    }

    public static boolean isActive() {
        return active;
    }


    public static boolean isFullPause() {
        return fullPause;
    }


    public static boolean shouldCompletelyFreeze() {
        return active;
    }


    public static boolean shouldFreezeEntity(Entity entity) {
        if (!active || entity == null) {
            return false;
        }
        UUID a = attacker;
        return a == null || !a.equals(entity.getUUID());
    }

    public static void freezePose(Entity e) {
        if (e == null) {
            return;
        }
        e.xOld = e.getX();
        e.yOld = e.getY();
        e.zOld = e.getZ();
        e.yRotO = e.getYRot();
        e.xRotO = e.getXRot();
        if (e instanceof LivingEntity le) {
            le.yBodyRotO = le.yBodyRot;
            le.yHeadRotO = le.yHeadRot;
        }
    }


    public static void tickDown() {
        if (!active) {
            return;
        }
        if (keepAlive > 0) {
            keepAlive--;
        }
        if (keepAlive <= 0) {
            deactivate();
        }
    }


    public static float entityRenderPartial(Entity e, float partialTick) {
        if (shouldFreezeEntity(e)) {
            freezePose(e);
            return 0.0F;
        }
        return partialTick;
    }


    public static float blockEntityRenderPartial(float partialTick) {
        return shouldCompletelyFreeze() ? 0.0F : partialTick;
    }
}
