package com.ryjs.reflection.entity;

import com.ryjs.reflection.Registration;

import com.ryjs.reflection.Reflection;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;


@Mod.EventBusSubscriber(modid = Reflection.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WitherzillaReconciler {

    private WitherzillaReconciler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            reconcile(server);
        }
    }


    public static void summon(ServerLevel level, double x, double y, double z, int count) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        WitherzillaSavedData truth = WitherzillaSavedData.get(server);
        truth.active = true;
        truth.x = x;
        truth.y = y;
        truth.z = z;
        truth.count = Math.max(1, count);
        truth.dim = level.dimension().location().toString();
        truth.setDirty();
        reconcile(server);
    }


    public static void despawn(MinecraftServer server) {
        if (server == null) {
            return;
        }
        WitherzillaSavedData truth = WitherzillaSavedData.get(server);
        truth.active = false;
        truth.setDirty();
        reconcile(server);
    }


    public static void reconcile(MinecraftServer server) {
        WitherzillaSavedData truth = WitherzillaSavedData.get(server);


        List<EntityWitherzilla> mine = new ArrayList<>();
        for (Entity e : PhantomRegistry.all()) {
            if (e instanceof EntityWitherzilla w) {
                mine.add(w);
            }
        }

        if (!truth.active) {
            for (EntityWitherzilla w : mine) {
                PhantomRegistry.unregister(w);
            }
            WitherzillaPhantomManager.clearRenderHint();
            return;
        }

        ServerLevel level = resolveLevel(server, truth.dim);
        if (level == null) {
            level = server.overworld();
        }

        int want = Math.max(1, truth.count);
    

        List<EntityWitherzilla> survivors = new ArrayList<>();
        for (EntityWitherzilla w : mine) {
            w.unsetRemoved();
            try {
                w.setHealth(w.getMaxHealth());
            } catch (Throwable ignored) {
            }
            if (w.isRemoved() || !w.isAlive()) {
                PhantomRegistry.unregister(w);
                continue;
            }
            w.setUUID(java.util.UUID.randomUUID());
            survivors.add(w);
        }
    

        while (survivors.size() > want) {
            PhantomRegistry.unregister(survivors.remove(survivors.size() - 1));
        }

        while (survivors.size() < want) {
            EntityWitherzilla w = newPhantom(level, truth);
            PhantomRegistry.register(w);
            survivors.add(w);
        }

        for (EntityWitherzilla w : survivors) {
            w.setPos(truth.x, truth.y, truth.z);
        }
    

        WitherzillaPhantomManager.setRenderHint(truth.x, truth.y, truth.z);
    }
    

    private static EntityWitherzilla newPhantom(ServerLevel level, WitherzillaSavedData truth) {
        EntityWitherzilla w = new EntityWitherzilla(Registration.WITHERZILLA.get(), level);
        w.setPos(truth.x, truth.y, truth.z);
        w.setCustomName(Component.literal("Witherzilla"));
        w.setCustomNameVisible(true);
        w.setUUID(java.util.UUID.randomUUID());
        return w;
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dim) {
        try {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dim));
            return server.getLevel(key);
        } catch (Exception e) {
            return null;
        }
    }
}
