package com.ryjs.reflection.hook;

import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.hook.hook.HookResult;
import com.ryjs.reflection.entity.PhantomRegistry;
import com.ryjs.reflection.entity.TaiChiDominion;
import com.ryjs.reflection.entity.TaiChiPresenceEntity;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

@SuppressWarnings({"unchecked", "rawtypes"})
public final class TaiChiPresenceEntityHooks {

    private TaiChiPresenceEntityHooks() {
    }

    private static boolean isPhantom(Object entity) {
        return entity instanceof Entity e && PhantomRegistry.contains(e);
    }

    private static void feedTyped(EntityTypeTest typeTest, AABB bounds, AbortableIterationConsumer consumer) {
        for (Entity inst : PhantomRegistry.all()) {
            if (bounds != null && !inst.getBoundingBox().intersects(bounds)) {
                continue;
            }
            Object cast = typeTest.tryCast(inst);
            if (cast != null && consumer.accept(cast).shouldAbort()) {
                return;
            }
        }
    }


    private static boolean keptUnderDominion(Object e) {
        return !TaiChiDominion.isAttacking() || (e instanceof Entity ent && TaiChiDominion.isOwner(ent));
    }

    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/EntityLookup",
        targetMethod = "getAllEntities",
        targetAliases = "m_156811_",
        targetDescriptor = "()Ljava/lang/Iterable;",
        mode = HookMode.RETURN
    )
    public static HookResult<Iterable> wrapAllEntities(Iterable result) {
        java.util.List<Entity> phantoms = PhantomRegistry.all();
        boolean attacking = TaiChiDominion.isAttacking();
        if (phantoms.isEmpty() && !attacking) {
            return HookResult.pass();
        }
        Set<Object> merged = Collections.newSetFromMap(new IdentityHashMap<>());
        if (result != null) {
            result.forEach(e -> {
                if (keptUnderDominion(e)) {
                    merged.add(e);
                }
            });
        }
        merged.addAll(phantoms);
        return HookResult.returnValue((Iterable) Collections.unmodifiableSet(merged));
    }


    @AsmHook(
        targetClass = "net/minecraft/server/level/ServerLevel",
        targetMethod = "getAllEntities",
        targetAliases = "m_8583_",
        targetDescriptor = "()Ljava/lang/Iterable;",
        mode = HookMode.RETURN
    )
    @AsmHook(
        targetClass = "net/minecraft/client/multiplayer/ClientLevel",
        targetMethod = "entitiesForRendering",
        targetAliases = "m_104735_",
        targetDescriptor = "()Ljava/lang/Iterable;",
        mode = HookMode.RETURN
    )
    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/LevelEntityGetterAdapter",
        targetMethod = "getAll",
        targetAliases = "m_142273_",
        targetDescriptor = "()Ljava/lang/Iterable;",
        mode = HookMode.RETURN
    )
    public static HookResult<Iterable> wrapFacadeAll(Iterable result) {
        java.util.List<Entity> phantoms = PhantomRegistry.all();
        boolean attacking = TaiChiDominion.isAttacking();
        if (phantoms.isEmpty() && !attacking) {
            return HookResult.pass();
        }
        Set<Object> merged = Collections.newSetFromMap(new IdentityHashMap<>());
        if (result != null) {
            result.forEach(e -> {
                if (keptUnderDominion(e)) {
                    merged.add(e);
                }
            });
        }
        merged.addAll(phantoms);
        return HookResult.returnValue((Iterable) Collections.unmodifiableSet(merged));
    }

    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/EntityLookup",
        targetMethod = "count",
        targetAliases = "m_156821_",
        targetDescriptor = "()I",
        mode = HookMode.RETURN
    )
    public static HookResult<Integer> bumpCount(int result) {
        return HookResult.returnValue(result + PhantomRegistry.size());
    }


    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/EntityLookup",
        targetMethod = "getEntity",
        targetAliases = "m_156812_",
        targetDescriptor = "(I)Lnet/minecraft/world/level/entity/EntityAccess;",
        mode = HookMode.HEAD
    )
    public static HookResult<EntityAccess> resolveById(int id) {
        for (Entity inst : PhantomRegistry.all()) {
            if (inst.getId() == id) {
                return HookResult.returnValue((EntityAccess) inst);
            }
        }
        return HookResult.pass();
    }

    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/EntityLookup",
        targetMethod = "getEntity",
        targetAliases = "m_156819_",
        targetDescriptor = "(Ljava/util/UUID;)Lnet/minecraft/world/level/entity/EntityAccess;",
        mode = HookMode.HEAD
    )
    public static HookResult<EntityAccess> resolveByUuid(UUID uuid) {
        for (Entity inst : PhantomRegistry.all()) {
            if (inst.getUUID().equals(uuid)) {
                return HookResult.returnValue((EntityAccess) inst);
            }
        }
        return HookResult.pass();
    }


    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/EntityLookup",
        targetMethod = "getEntities",
        targetAliases = "m_260822_",
        targetDescriptor = "(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/util/AbortableIterationConsumer;)V",
        mode = HookMode.HEAD
    )
    public static void feedLookupTyped(EntityTypeTest typeTest, AbortableIterationConsumer consumer) {
        feedTyped(typeTest, null, consumer);
    }

    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/LevelEntityGetterAdapter",
        targetMethod = "get",
        targetAliases = "m_142690_",
        targetDescriptor = "(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/util/AbortableIterationConsumer;)V",
        mode = HookMode.HEAD
    )
    public static void feedAdapterTyped(EntityTypeTest typeTest, AbortableIterationConsumer consumer) {
        feedTyped(typeTest, null, consumer);
    }

    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/LevelEntityGetterAdapter",
        targetMethod = "get",
        targetAliases = "m_142137_",
        targetDescriptor = "(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
        mode = HookMode.HEAD
    )
    public static void feedAdapterTypedInBounds(EntityTypeTest typeTest, AABB bounds, AbortableIterationConsumer consumer) {
        feedTyped(typeTest, bounds, consumer);
    }

    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/LevelEntityGetterAdapter",
        targetMethod = "get",
        targetAliases = "m_142232_",
        targetDescriptor = "(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V",
        mode = HookMode.HEAD
    )
    public static void feedAdapterInBounds(AABB bounds, Consumer consumer) {
        for (Entity inst : PhantomRegistry.all()) {
            if (bounds == null || inst.getBoundingBox().intersects(bounds)) {
                consumer.accept(inst);
            }
        }
    }


    @AsmHook(
        targetClass = "net/minecraft/server/level/ServerLevel",
        targetMethod = "getEntities",
        targetAliases = "m_261178_",
        targetDescriptor = "(Lnet/minecraft/world/level/entity/EntityTypeTest;Ljava/util/function/Predicate;Ljava/util/List;I)V",
        mode = HookMode.RETURN
    )
    public static void appendTypedInto(EntityTypeTest typeTest, Predicate predicate, List output, int limit) {
        addPhantomToList(typeTest, null, predicate, output, limit);
    }

    @AsmHook(
        targetClass = "net/minecraft/world/level/Level",
        targetMethod = "getEntities",
        targetAliases = "m_260826_",
        targetDescriptor = "(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V",
        mode = HookMode.RETURN
    )
    public static void appendTypedInBoundsInto(EntityTypeTest typeTest, AABB bounds, Predicate predicate, List output, int limit) {
        addPhantomToList(typeTest, bounds, predicate, output, limit);
    }

    private static void addPhantomToList(EntityTypeTest typeTest, AABB bounds, Predicate predicate, List output, int limit) {
        if (output == null) {
            return;
        }
        if (TaiChiDominion.isAttacking()) {
            output.removeIf(o -> !keptUnderDominion(o));
        }
        for (Entity inst : PhantomRegistry.all()) {
            if (output.size() >= limit) {
                return;
            }
            if (bounds != null && !inst.getBoundingBox().intersects(bounds)) {
                continue;
            }
            Object cast = typeTest.tryCast(inst);
            if (cast == null) {
                continue;
            }
            if (predicate != null && !predicate.test(cast)) {
                continue;
            }
            if (!output.contains(cast)) {
                output.add(cast);
            }
        }
    }


    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/EntityTickList",
        targetMethod = "contains",
        targetAliases = "m_156914_",
        targetDescriptor = "(Lnet/minecraft/world/entity/Entity;)Z",
        mode = HookMode.HEAD
    )
    public static HookResult<Boolean> tickContains(Entity entity) {
        return isPhantom(entity) ? HookResult.returnValue(Boolean.TRUE) : HookResult.pass();
    }


    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/EntityTickList",
        targetMethod = "remove",
        targetAliases = "m_156912_",
        targetDescriptor = "(Lnet/minecraft/world/entity/Entity;)V",
        mode = HookMode.GUARD
    )
    public static boolean guardTickRemove(Entity entity) {
        return isPhantom(entity);
    }

    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/EntityTickList",
        targetMethod = "add",
        targetAliases = "m_156908_",
        targetDescriptor = "(Lnet/minecraft/world/entity/Entity;)V",
        mode = HookMode.GUARD
    )
    public static boolean guardTickAdd(Entity entity) {
        return isPhantom(entity);
    }

    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/EntityLookup",
        targetMethod = "remove",
        targetAliases = "m_156822_",
        targetDescriptor = "(Lnet/minecraft/world/level/entity/EntityAccess;)V",
        mode = HookMode.GUARD
    )
    public static boolean guardLookupRemove(EntityAccess entity) {
        return isPhantom(entity);
    }

    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/EntityLookup",
        targetMethod = "add",
        targetAliases = "m_156814_",
        targetDescriptor = "(Lnet/minecraft/world/level/entity/EntityAccess;)V",
        mode = HookMode.GUARD
    )
    public static boolean guardLookupAdd(EntityAccess entity) {
        return isPhantom(entity);
    }

    @AsmHook(
        targetClass = "net/minecraft/server/level/ChunkMap",
        targetMethod = "removeEntity",
        targetAliases = "m_140331_",
        targetDescriptor = "(Lnet/minecraft/world/entity/Entity;)V",
        mode = HookMode.GUARD
    )
    public static boolean guardChunkRemove(Entity entity) {
        return isPhantom(entity);
    }

    @AsmHook(
        targetClass = "net/minecraft/server/level/ChunkMap",
        targetMethod = "addEntity",
        targetAliases = "m_140199_",
        targetDescriptor = "(Lnet/minecraft/world/entity/Entity;)V",
        mode = HookMode.GUARD
    )
    public static boolean guardChunkAdd(Entity entity) {
        return isPhantom(entity);
    }

    @AsmHook(
        targetClass = "net/minecraft/world/level/entity/PersistentEntitySectionManager",
        targetMethod = "unloadEntity",
        targetAliases = "m_157585_",
        targetDescriptor = "(Lnet/minecraft/world/level/entity/EntityAccess;)V",
        mode = HookMode.GUARD
    )
    public static boolean guardUnload(EntityAccess entity) {
        return isPhantom(entity);
    }

    @AsmHook(
        targetClass = "net/minecraft/server/level/ServerLevel",
        targetMethod = "getEntities",
        targetAliases = "m_143280_",
        targetDescriptor = "(Lnet/minecraft/world/level/entity/EntityTypeTest;Ljava/util/function/Predicate;)Ljava/util/List;",
        mode = HookMode.RETURN
    )
    @AsmHook(
        targetClass = "net/minecraft/server/level/ServerLevel",
        targetMethod = "getEntities",
        targetAliases = "m_260813_",
        targetDescriptor = "(Lnet/minecraft/world/level/entity/EntityTypeTest;Ljava/util/function/Predicate;Ljava/util/List;)V",
        mode = HookMode.RETURN
    )
    public static void filterTypedInto(EntityTypeTest typeTest, Predicate predicate, List list) {
        addPhantomToList(typeTest, null, predicate, list, Integer.MAX_VALUE);
    }


    @AsmHook(
        targetClass = "net/minecraft/world/level/Level",
        targetMethod = "getEntities",
        targetAliases = "m_142425_",
        targetDescriptor = "(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
        mode = HookMode.RETURN
    )
    @AsmHook(
        targetClass = "net/minecraft/world/level/Level",
        targetMethod = "getEntities",
        targetAliases = "m_261153_",
        targetDescriptor = "(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;)V",
        mode = HookMode.RETURN
    )
    public static void filterTypedBoundsInto(EntityTypeTest typeTest, AABB bounds, Predicate predicate, List list) {
        addPhantomToList(typeTest, bounds, predicate, list, Integer.MAX_VALUE);
    }

    @AsmHook(
        targetClass = "net/minecraft/world/level/Level",
        targetMethod = "getEntities",
        targetAliases = "m_6249_",
        targetDescriptor = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
        mode = HookMode.RETURN
    )
    public static void filterEntitiesInBox(Entity excluded, AABB bounds, Predicate predicate, List list) {
        if (list == null) {
            return;
        }
        if (TaiChiDominion.isAttacking()) {
            list.removeIf(o -> !keptUnderDominion(o));
        }
        for (Entity inst : PhantomRegistry.all()) {
            if (inst == excluded) {
                continue;
            }
            if (bounds != null && !inst.getBoundingBox().intersects(bounds)) {
                continue;
            }
            if (predicate != null && !predicate.test(inst)) {
                continue;
            }
            if (!list.contains(inst)) {
                list.add(inst);
            }
        }
    }

    @AsmHook(
        targetClass = "net/minecraft/world/entity/Entity",
        targetMethod = "tick",
        targetAliases = "m_8119_",
        targetDescriptor = "()V",
        mode = HookMode.GUARD,
        includeThis = true,
        includeSubclasses = true
    )
    public static boolean guardTick(Entity self) {
        return TaiChiDominion.isAttacking() && !isPhantom(self) && !TaiChiDominion.isOwner(self);
    }


    @AsmHook(
        targetClass = "net/minecraft/server/level/ServerLevel",
        targetMethod = "getEntities",
        targetAliases = "m_142646_",
        targetDescriptor = "()Lnet/minecraft/world/level/entity/LevelEntityGetter;",
        mode = HookMode.RETURN
    )
    @AsmHook(
        targetClass = "net/minecraft/client/multiplayer/ClientLevel",
        targetMethod = "getEntities",
        targetAliases = "m_142646_",
        targetDescriptor = "()Lnet/minecraft/world/level/entity/LevelEntityGetter;",
        mode = HookMode.RETURN
    )
    public static HookResult<LevelEntityGetter> wrapGetter(LevelEntityGetter getter) {
        if (PhantomRegistry.isEmpty() || getter == null || getter instanceof PhantomLevelEntityGetter) {
            return HookResult.pass();
        }
        return HookResult.returnValue((LevelEntityGetter) new PhantomLevelEntityGetter((LevelEntityGetter<Entity>) getter));
    }


    private static final class PhantomLevelEntityGetter implements LevelEntityGetter<Entity> {
        private final LevelEntityGetter<Entity> delegate;

        private PhantomLevelEntityGetter(LevelEntityGetter<Entity> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Entity get(int id) {
            for (Entity inst : PhantomRegistry.all()) {
                if (inst.getId() == id) {
                    return inst;
                }
            }
            Entity found = delegate.get(id);
            return TaiChiDominion.visibleUnderDominion(found) ? found : null;
        }

        @Override
        public Entity get(UUID uuid) {
            for (Entity inst : PhantomRegistry.all()) {
                if (inst.getUUID().equals(uuid)) {
                    return inst;
                }
            }
            Entity found = delegate.get(uuid);
            return TaiChiDominion.visibleUnderDominion(found) ? found : null;
        }

        @Override
        public Iterable<Entity> getAll() {
            Iterable<Entity> base = delegate.getAll();
            java.util.List<Entity> phantoms = PhantomRegistry.all();
            if (phantoms.isEmpty() && !TaiChiDominion.isAttacking()) {
                return base;
            }
            Set<Entity> merged = Collections.newSetFromMap(new IdentityHashMap<>());
            if (base != null) {
                base.forEach(entity -> {
                    if (TaiChiDominion.visibleUnderDominion(entity)) {
                        merged.add(entity);
                    }
                });
            }
            merged.addAll(phantoms);
            return Collections.unmodifiableSet(merged);
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> typeTest, AbortableIterationConsumer<U> consumer) {
            Set<Entity> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Entity inst : PhantomRegistry.all()) {
                U cast = typeTest.tryCast(inst);
                if (cast != null && emitted.add(inst) && consumer.accept(cast).shouldAbort()) {
                    return;
                }
            }
            delegate.get(typeTest, entity -> (TaiChiDominion.visibleUnderDominion(entity) && emitted.add(entity))
                ? consumer.accept(entity)
                : AbortableIterationConsumer.Continuation.CONTINUE);
        }

        @Override
        public void get(AABB bounds, Consumer<Entity> consumer) {
            Set<Entity> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Entity inst : PhantomRegistry.all()) {
                if (inst.getBoundingBox().intersects(bounds) && emitted.add(inst)) {
                    consumer.accept(inst);
                }
            }
            delegate.get(bounds, entity -> {
                if (TaiChiDominion.visibleUnderDominion(entity) && emitted.add(entity)) {
                    consumer.accept(entity);
                }
            });
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> typeTest, AABB bounds, AbortableIterationConsumer<U> consumer) {
            Set<Entity> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Entity inst : PhantomRegistry.all()) {
                if (inst.getBoundingBox().intersects(bounds)) {
                    U cast = typeTest.tryCast(inst);
                    if (cast != null && emitted.add(inst) && consumer.accept(cast).shouldAbort()) {
                        return;
                    }
                }
            }
            delegate.get(typeTest, bounds, entity -> (TaiChiDominion.visibleUnderDominion(entity) && emitted.add(entity))
                ? consumer.accept(entity)
                : AbortableIterationConsumer.Continuation.CONTINUE);
        }
    }
}
