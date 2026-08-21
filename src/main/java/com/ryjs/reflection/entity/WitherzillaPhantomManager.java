package com.ryjs.reflection.entity;

import com.ryjs.reflection.Registration;

import com.ryjs.reflection.Reflection;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;


public final class WitherzillaPhantomManager {

    private static volatile EntityWitherzilla renderProxy;
    private static volatile double px, py, pz;
    private static volatile boolean hasPos;

    private WitherzillaPhantomManager() {
    }


    public static void setRenderHint(double x, double y, double z) {
        px = x;
        py = y;
        pz = z;
        hasPos = true;
    }


    public static void clearRenderHint() {
        hasPos = false;
        renderProxy = null;
    }


    public static EntityWitherzilla update(float partialTick) {
        if (!hasPos) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return null;
        }
        EntityWitherzilla proxy = renderProxy;
        if (proxy == null || proxy.level() != level) {
            proxy = new EntityWitherzilla(Registration.WITHERZILLA.get(), level);
            renderProxy = proxy;
        }

        if (proxy.isRemoved()) {
            proxy.unsetRemoved();
        }
        proxy.setPos(px, py, pz);

        proxy.tickCount++;
        return proxy;
    }


    public static void destroy() {
        renderProxy = null;
        hasPos = false;
        for (Entity e : PhantomRegistry.all()) {
            if (e instanceof EntityWitherzilla w) {
                PhantomRegistry.unregister(w);
            }
        }
    }


    public static boolean isAlive() {
        return hasPos;
    }


    public static String diagState() {
        return hasPos ? (renderProxy != null && !renderProxy.isRemoved() ? "alive" : "removed") : "gone";
    }
}
