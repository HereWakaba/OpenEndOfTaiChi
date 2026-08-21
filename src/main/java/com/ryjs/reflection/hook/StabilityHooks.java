package com.ryjs.reflection.hook;

import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;

import java.util.ConcurrentModificationException;


public final class StabilityHooks {

    private StabilityHooks() {}

    @AsmHook(targetClass = "net/minecraft/world/level/entity/EntitySection", targetMethod = "getEntities",
            targetAliases = "m_260830_",
            targetDescriptor = "(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
            mode = HookMode.OVERRIDE, includeThis = true)
    public static AbortableIterationConsumer.Continuation getEntities(EntitySection section, AABB box,
            AbortableIterationConsumer consumer) {
        try {
            for (Object entity : section.storage) {
                EntityAccess access = (EntityAccess) entity;
                if (access.getBoundingBox().intersects(box) && consumer.accept(access).shouldAbort()) {
                    return AbortableIterationConsumer.Continuation.ABORT;
                }
            }
        } catch (ConcurrentModificationException ex) {
            return AbortableIterationConsumer.Continuation.CONTINUE;
        }
        return AbortableIterationConsumer.Continuation.CONTINUE;
    }
}
