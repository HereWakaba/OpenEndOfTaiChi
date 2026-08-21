package com.ryjs.reflection.hook;

import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.hook.hook.HookResult;
import com.ryjs.timestop.TimeStopManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;


public final class TimeStopHooks {

    private TimeStopHooks() {
    }


    @AsmHook(targetClass = "net/minecraft/world/entity/Entity", targetMethod = "tick",
            targetAliases = "m_8119_", targetDescriptor = "()V",
            mode = HookMode.GUARD, includeThis = true, includeSubclasses = true)
    @AsmHook(targetClass = "net/minecraft/world/entity/Entity", targetMethod = "baseTick",
            targetAliases = "m_6075_", targetDescriptor = "()V",
            mode = HookMode.GUARD, includeThis = true, includeSubclasses = true)
    @AsmHook(targetClass = "net/minecraft/world/entity/Entity", targetMethod = "rideTick",
            targetAliases = "m_6083_", targetDescriptor = "()V",
            mode = HookMode.GUARD, includeThis = true, includeSubclasses = true)
    public static boolean guardEntityFreeze(Entity self) {
        if (TimeStopManager.shouldFreezeEntity(self)) {
            TimeStopManager.freezePose(self); // 对齐插值，避免"拉回"
            return true;
        }
        return false;
    }


    @AsmHook(targetClass = "net/minecraft/world/entity/LivingEntity", targetMethod = "aiStep",
            targetAliases = "m_8107_", targetDescriptor = "()V",
            mode = HookMode.GUARD, includeThis = true, includeSubclasses = true)
    public static boolean guardLivingAiStep(net.minecraft.world.entity.LivingEntity self) {
        if (TimeStopManager.shouldFreezeEntity(self)) {
            TimeStopManager.freezePose(self);
            return true;
        }
        return false;
    }


    @AsmHook(targetClass = "net/minecraft/server/level/ServerLevel", targetMethod = "tickNonPassenger",
            targetAliases = "m_8647_", targetDescriptor = "(Lnet/minecraft/world/entity/Entity;)V", mode = HookMode.GUARD)
    @AsmHook(targetClass = "net/minecraft/client/multiplayer/ClientLevel", targetMethod = "tickNonPassenger",
            targetAliases = "m_104639_", targetDescriptor = "(Lnet/minecraft/world/entity/Entity;)V", mode = HookMode.GUARD)
    public static boolean guardTickNonPassenger(Entity entity) {
        if (TimeStopManager.shouldFreezeEntity(entity)) {
            TimeStopManager.freezePose(entity);
            return true;
        }
        return false;
    }

    @AsmHook(targetClass = "net/minecraft/server/level/ServerLevel", targetMethod = "tickTime",
            targetAliases = "m_8809_", targetDescriptor = "()V", mode = HookMode.GUARD)
    @AsmHook(targetClass = "net/minecraft/server/level/ServerLevel", targetMethod = "advanceWeatherCycle",
            targetAliases = "m_184096_", targetDescriptor = "()V", mode = HookMode.GUARD)
    @AsmHook(targetClass = "net/minecraft/world/level/Level", targetMethod = "tickBlockEntities",
            targetAliases = "m_46463_", targetDescriptor = "()V", mode = HookMode.GUARD)
    @AsmHook(targetClass = "net/minecraft/client/multiplayer/ClientLevel", targetMethod = "tickTime",
            targetAliases = "m_104826_", targetDescriptor = "()V", mode = HookMode.GUARD)
    @AsmHook(targetClass = "net/minecraft/client/renderer/LightTexture", targetMethod = "tick",
            targetAliases = "m_109880_", targetDescriptor = "()V", mode = HookMode.GUARD)
    @AsmHook(targetClass = "net/minecraft/client/renderer/texture/TextureManager", targetMethod = "tick",
            targetAliases = "m_7673_", targetDescriptor = "()V", mode = HookMode.GUARD)
    @AsmHook(targetClass = "net/minecraft/client/particle/Particle", targetMethod = "tick",
            targetAliases = "m_5989_", targetDescriptor = "()V", mode = HookMode.GUARD)
    public static boolean guardCompleteFreeze() {
        return TimeStopManager.shouldCompletelyFreeze();
    }


    @AsmHook(targetClass = "net/minecraft/server/level/ServerLevel", targetMethod = "tickChunk",
            targetAliases = "m_8714_", targetDescriptor = "(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", mode = HookMode.GUARD)
    public static boolean guardTickChunk(LevelChunk chunk, int randomTickSpeed) {
        return TimeStopManager.shouldCompletelyFreeze();
    }

    @AsmHook(targetClass = "net/minecraft/server/level/ServerLevel", targetMethod = "tickCustomSpawners",
            targetAliases = "m_8799_", targetDescriptor = "(ZZ)V", mode = HookMode.GUARD)
    public static boolean guardTickCustomSpawners(boolean spawnEnemies, boolean spawnFriendlies) {
        return TimeStopManager.shouldCompletelyFreeze();
    }


    @AsmHook(targetClass = "net/minecraft/world/level/Level", targetMethod = "shouldTickBlocksAt",
            targetAliases = "m_220393_", targetDescriptor = "(Lnet/minecraft/core/BlockPos;)Z", mode = HookMode.HEAD)
    public static HookResult<Boolean> headShouldTickBlocksAt(BlockPos pos) {
        return TimeStopManager.shouldCompletelyFreeze() ? HookResult.returnValue(Boolean.FALSE) : HookResult.pass();
    }

    @AsmHook(targetClass = "net/minecraft/client/sounds/SoundEngine", targetMethod = "play",
            targetAliases = "m_120312_", targetDescriptor = "(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", mode = HookMode.GUARD)
    public static boolean guardSoundPlay(SoundInstance sound) {
        return TimeStopManager.shouldCompletelyFreeze();
    }


    @AsmHook(targetClass = "net/minecraft/client/Minecraft", targetMethod = "isPaused",
            targetAliases = "m_91104_", targetDescriptor = "()Z", mode = HookMode.HEAD, includeThis = true)
    public static HookResult<Boolean> headIsPaused(Minecraft self) {
        if (TimeStopManager.isFullPause() && self.hasSingleplayerServer()) {
            return HookResult.returnValue(Boolean.TRUE);
        }
        return HookResult.pass();
    }
}
