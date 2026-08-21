package com.ryjs.reflection.entity;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;


public final class TaiChiParadoxManager {

    private static volatile TaiChiParadoxAvatar avatar;
    private static volatile TaiChiParadoxProxy proxy;

    private static volatile double spawnX, spawnY, spawnZ;

    private TaiChiParadoxManager() {}

    public static void spawn(double x, double y, double z) {
        synchronized (TaiChiParadoxManager.class) {
            avatar = new TaiChiParadoxAvatar(x, y, z);
            proxy = null;
            spawnX = x;
            spawnY = y;
            spawnZ = z;
            System.out.println("TaiChiParadox Spawned at " + x + ", " + y + ", " + z);
        }
    }


    public static void spawnPresence(ServerLevel level, double x, double y, double z) {
        TaiChiPresenceEntity presence = new TaiChiPresenceEntity(level);
        presence.setPos(x, y, z);
        TaiChiPresenceEntity.setInstance(presence);
        System.out.println(" TaiChiParadox Presence entity created");
    }


    public static void removePresence() {
        TaiChiPresenceEntity.clearInstance();
    }


    public static void syncProxyFromAvatar() {
        TaiChiParadoxAvatar av = avatar;
        TaiChiParadoxProxy pr = proxy;
        if (av == null || pr == null) return;
        if (pr.isRemoved()) {
            pr.unsetRemoved();
        }

        pr.setPos(spawnX, spawnY, spawnZ);
        pr.setOldPosAndRot();
        pr.applyAvatarState(spawnX, spawnY, spawnZ, pr.yBodyRot, pr.yHeadRot, pr.xRot, 0.0F);
    }


    public static double[] spawnPos() {
        return new double[]{spawnX, spawnY, spawnZ};
    }


    public static TaiChiParadoxProxy update(float partialTick) {
        TaiChiParadoxAvatar current = avatar;
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;

        if (current == null || level == null) return null;

        TaiChiParadoxProxy currentProxy = proxy;
        if (currentProxy == null || currentProxy.level() != level) {
            currentProxy = new TaiChiParadoxProxy(level);
            proxy = currentProxy;
        }

        current.update();
        current.apply(currentProxy, partialTick);
        return currentProxy;
    }


    public static void destroy() {
        avatar = null;
        proxy = null;
        removePresence();
    }

    public static boolean isAlive() {
        return avatar != null;
    }
}
