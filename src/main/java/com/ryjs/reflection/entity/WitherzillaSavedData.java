package com.ryjs.reflection.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;


public class WitherzillaSavedData extends SavedData {


    public static final String NAME = "reflection_witherzilla";


    public volatile boolean active;
    public volatile double x;
    public volatile double y;
    public volatile double z;
    public volatile int count;
    public volatile String dim = "minecraft:overworld";

    public WitherzillaSavedData() {
    }


    public static WitherzillaSavedData load(CompoundTag tag) {
        WitherzillaSavedData d = new WitherzillaSavedData();
        d.active = tag.getBoolean("active");
        d.x = tag.getDouble("x");
        d.y = tag.getDouble("y");
        d.z = tag.getDouble("z");
        d.count = tag.getInt("count");
        if (tag.contains("dim")) {
            d.dim = tag.getString("dim");
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("active", active);
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putInt("count", count);
        tag.putString("dim", dim);
        return tag;
    }


    public static WitherzillaSavedData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();

        return overworld.getDataStorage().computeIfAbsent(
                WitherzillaSavedData::load,
                WitherzillaSavedData::new,
                NAME);
    }
}
