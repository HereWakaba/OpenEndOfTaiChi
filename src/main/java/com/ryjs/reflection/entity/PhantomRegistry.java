package com.ryjs.reflection.entity;

import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;


public final class PhantomRegistry {

    private static final Set<Entity> PHANTOMS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private PhantomRegistry() {}


    public static void register(Entity entity) {
        if (entity != null) {
            PHANTOMS.add(entity);
        }
    }


    public static void unregister(Entity entity) {
        if (entity != null) {
            PHANTOMS.remove(entity);
        }
    }

    public static boolean contains(Entity entity) {
        return entity != null && PHANTOMS.contains(entity);
    }

    public static boolean isEmpty() {
        return PHANTOMS.isEmpty();
    }

    public static int size() {
        return PHANTOMS.size();
    }


    public static List<Entity> all() {
        synchronized (PHANTOMS) {
            return new ArrayList<>(PHANTOMS);
        }
    }
}
