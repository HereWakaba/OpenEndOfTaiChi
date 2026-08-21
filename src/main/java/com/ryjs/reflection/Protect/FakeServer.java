package com.ryjs.reflection.Protect;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

public class FakeServer extends IntegratedServer {
    public FakeServer(Thread p1, Minecraft p2, LevelStorageSource.LevelStorageAccess p3, PackRepository p4, WorldStem p5, Services p6, ChunkProgressListenerFactory p7){
        super(p1, p2, p3, p4, p5, p6, p7);
    }
    
    // 完全委托原版 tickServer：手抄版本缺 Forge 钩子（onPreServerTick/onPostServerTick）且自行处理
    // 暂停/视野同步，与 Forge 1.20.1 的原版实现差异过大，会导致 /say 等指令无响应（已定位）。
    // 委托原版后 FakeServer 零副作用，可安全 setKlass 替换。
    public void tickServer(@NotNull BooleanSupplier p_129871_) {
        super.tickServer(p_129871_);
    }
}