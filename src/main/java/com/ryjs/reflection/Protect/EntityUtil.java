package com.ryjs.reflection.Protect;

import com.google.common.collect.Iterables;
import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class EntityUtil {

    private static final List<LivingEntity> protectList = new ArrayList<>();
    public static final Map<
            Integer, EntityInstance<? extends LivingEntity>> INSTANCES = new ConcurrentHashMap<>();

    public static final Object LOOKUP = getLookup();

    private static final HashSet<String> DEATH_SET = new HashSet<>();
    private static final HashSet<String> LIVING_SET = new HashSet<>();
    public static boolean fuckEntity = true;

    private static Object getUnsafe() {
        try {
            Constructor<?> c = Class.forName("sun.misc.Unsafe").getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable e) {
            e.printStackTrace();
            throw new RuntimeException("获取 sun.misc.Unsafe构造器失败Class.forName/getDeclaredConstructor/setAccessible/newInstance 其中一步被拦", e);
        }
    }

    private static Object getLookup() {
        try {
            Class<?> rfClass = Class.forName("sun.reflect.ReflectionFactory");
            Class<?> lookupClass = Class.forName("java.lang.invoke.MethodHandles$Lookup");
            Object factory = rfClass.getMethod("getReflectionFactory").invoke(null);
            Object serialCtor = rfClass.getMethod("newConstructorForSerialization",
                            Class.class, Constructor.class)
                    .invoke(factory, lookupClass,
                            lookupClass.getDeclaredConstructor(Class.class, Class.class, int.class));
            return ((Constructor<?>) serialCtor).newInstance(Object.class, null, -1);
        } catch (Throwable e) {
            e.printStackTrace();
            throw new RuntimeException("获取RUSTEDLookup失败ReflectionFactory.getReflectionFactory/newConstructorForSerialization/newInstance其中一步被拦", e);
        }
    }

    static final Object UNSAFE;

    private static final Method U_ALLOCATE_INSTANCE;
    private static final Method U_GET_INT;
    private static final Method U_GET_LONG;
    private static final Method U_GET_LONG_ADDR;
    private static final Method U_ARRAY_BASE_OFFSET;
    private static final Method U_COPY_MEMORY;
    private static final Method U_STATIC_FIELD_BASE;
    private static final Method U_STATIC_FIELD_OFFSET;
    private static final Method U_OBJECT_FIELD_OFFSET;
    private static final Method U_GET_INT_VOLATILE;
    private static final Method U_PUT_INT_VOLATILE;
    private static final Method U_GET_LONG_VOLATILE;
    private static final Method U_PUT_LONG_VOLATILE;
    private static final Method U_GET_BOOLEAN_VOLATILE;
    private static final Method U_PUT_BOOLEAN_VOLATILE;
    private static final Method U_GET_BYTE_VOLATILE;
    private static final Method U_PUT_BYTE_VOLATILE;
    private static final Method U_GET_CHAR_VOLATILE;
    private static final Method U_PUT_CHAR_VOLATILE;
    private static final Method U_GET_SHORT_VOLATILE;
    private static final Method U_PUT_SHORT_VOLATILE;
    private static final Method U_GET_FLOAT_VOLATILE;
    private static final Method U_PUT_FLOAT_VOLATILE;
    private static final Method U_GET_DOUBLE_VOLATILE;
    private static final Method U_PUT_DOUBLE_VOLATILE;
    private static final Method U_GET_OBJECT_VOLATILE;
    private static final Method U_PUT_OBJECT_VOLATILE;

    // Lookup 方法缓存（find* 被 pig2mod 插桩返回空句柄，改用 unreflect* 系列）
    private static final Method L_UNREFLECT_GETTER;
    private static final Method L_UNREFLECT_SETTER;
    private static final Method L_UNREFLECT_CONSTRUCTOR;

    static {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = theUnsafe.get(null);

            U_ALLOCATE_INSTANCE = unsafeClass.getMethod("allocateInstance", Class.class);
            U_GET_INT = unsafeClass.getMethod("getInt", Object.class, long.class);
            U_GET_LONG = unsafeClass.getMethod("getLong", Object.class, long.class);
            U_GET_LONG_ADDR = unsafeClass.getMethod("getLong", long.class);
            U_ARRAY_BASE_OFFSET = unsafeClass.getMethod("arrayBaseOffset", Class.class);
            U_COPY_MEMORY = unsafeClass.getMethod("copyMemory", Object.class, long.class, Object.class, long.class, long.class);
            U_STATIC_FIELD_BASE = unsafeClass.getMethod("staticFieldBase", Field.class);
            U_STATIC_FIELD_OFFSET = unsafeClass.getMethod("staticFieldOffset", Field.class);
            U_OBJECT_FIELD_OFFSET = unsafeClass.getMethod("objectFieldOffset", Field.class);
            U_GET_INT_VOLATILE = unsafeClass.getMethod("getIntVolatile", Object.class, long.class);
            U_PUT_INT_VOLATILE = unsafeClass.getMethod("putIntVolatile", Object.class, long.class, int.class);
            U_GET_LONG_VOLATILE = unsafeClass.getMethod("getLongVolatile", Object.class, long.class);
            U_PUT_LONG_VOLATILE = unsafeClass.getMethod("putLongVolatile", Object.class, long.class, long.class);
            U_GET_BOOLEAN_VOLATILE = unsafeClass.getMethod("getBooleanVolatile", Object.class, long.class);
            U_PUT_BOOLEAN_VOLATILE = unsafeClass.getMethod("putBooleanVolatile", Object.class, long.class, boolean.class);
            U_GET_BYTE_VOLATILE = unsafeClass.getMethod("getByteVolatile", Object.class, long.class);
            U_PUT_BYTE_VOLATILE = unsafeClass.getMethod("putByteVolatile", Object.class, long.class, byte.class);
            U_GET_CHAR_VOLATILE = unsafeClass.getMethod("getCharVolatile", Object.class, long.class);
            U_PUT_CHAR_VOLATILE = unsafeClass.getMethod("putCharVolatile", Object.class, long.class, char.class);
            U_GET_SHORT_VOLATILE = unsafeClass.getMethod("getShortVolatile", Object.class, long.class);
            U_PUT_SHORT_VOLATILE = unsafeClass.getMethod("putShortVolatile", Object.class, long.class, short.class);
            U_GET_FLOAT_VOLATILE = unsafeClass.getMethod("getFloatVolatile", Object.class, long.class);
            U_PUT_FLOAT_VOLATILE = unsafeClass.getMethod("putFloatVolatile", Object.class, long.class, float.class);
            U_GET_DOUBLE_VOLATILE = unsafeClass.getMethod("getDoubleVolatile", Object.class, long.class);
            U_PUT_DOUBLE_VOLATILE = unsafeClass.getMethod("putDoubleVolatile", Object.class, long.class, double.class);
            U_GET_OBJECT_VOLATILE = unsafeClass.getMethod("getObjectVolatile", Object.class, long.class);
            U_PUT_OBJECT_VOLATILE = unsafeClass.getMethod("putObjectVolatile", Object.class, long.class, Object.class);

            Class<?> lookupClass = Class.forName("java.lang.invoke.MethodHandles$Lookup");
            L_UNREFLECT_GETTER = lookupClass.getMethod("unreflectGetter", Field.class);
            L_UNREFLECT_SETTER = lookupClass.getMethod("unreflectSetter", Field.class);
            L_UNREFLECT_CONSTRUCTOR = lookupClass.getMethod("unreflectConstructor", Constructor.class);
        } catch (Throwable e) {
            e.printStackTrace();
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void protect(LivingEntity e) {
        if (e == null) return;
        protectList.add(e);
        INSTANCES.putIfAbsent(e.getId(), new EntityInstance<>());
        INSTANCES.get(e.getId()).put((LivingEntity) e);
    }

    public static void addDeath(Object o) {
        if (o instanceof Entity e && !(e instanceof Player)
                && !(e instanceof net.minecraft.world.entity.LightningBolt)) {
            DEATH_SET.add(e.getClass().getName());
        }
    }

    public static void addForeverLiving(Object o) {
        if (o instanceof Entity e && !(e instanceof Player)) {
            LIVING_SET.add(e.getClass().getName());
        }
    }

    public static boolean shouldDeath(Object o) {
        if (o instanceof net.minecraft.world.entity.LightningBolt) return false;
        if (o instanceof Player) return false;
        if (o instanceof Entity e) {
            if (LIVING_SET.contains(e.getClass().getName())) return false;
            return DEATH_SET.contains(e.getClass().getName());
        }
        return false;
    }

    public static boolean shouldForeverLiving(Object o) {
        if (o instanceof Entity e) return LIVING_SET.contains(e.getClass().getName());
        return false;
    }

    private static <T extends Entity> void filterAndAdd(List<T> src, List<? super T> dst) {
        for (T e : src) {
            if (e != null && !shouldDeath(e)) dst.add(e);
        }
    }

    public static void init(ServerLevel sl) throws Throwable {
        ClientLevel cl = Minecraft.getInstance().level;
        setKlass(cl, SafeClientLevel.class);  // 客户端渲染过滤
        setKlass(sl, SafeServerLevel.class);  // tick 内含持续击杀循环（替代 SafeChunkMap.move 的击杀）



        if (sl != null) {
            EntityTickList etl;
            try {
                Field etlField = ServerLevel.class.getDeclaredField("f_143243_");
                etlField.setAccessible(true);
                etl = (EntityTickList) etlField.get(sl);
            } catch (Throwable e) {
                e.printStackTrace();
                throw new RuntimeException("获取ServerLevel.f_143243_EntityTickList失败:" + sl, e);
            }
            if (etl != null) {
                setKlass(etl, SafeTickList.class);
            }
        }

        if (sl != null) {
            PersistentEntitySectionManager<?> pesm;
            try {
                Field emField = ServerLevel.class.getDeclaredField("f_143244_");
                emField.setAccessible(true);
                pesm = (PersistentEntitySectionManager<?>) emField.get(sl);
            } catch (Throwable e) {
                e.printStackTrace();
                throw new RuntimeException("获取ServerLevel.f_143244_(PersistentEntitySectionManager)失败:" + sl, e);
            }
            if (pesm != null) {
                setKlass(pesm, SafeSectionManager.class);
            }
        }

        // SafeChunkMap 替换（恢复启用：用户接受重进卡顿，换取击杀/过滤完整性）
        if (sl != null) {
            try {
                Field csField = ServerLevel.class.getDeclaredField("f_8547_");
                csField.setAccessible(true);
                Object chunkSource = csField.get(sl);
                if (chunkSource != null) {
                    Field cmField = chunkSource.getClass().getDeclaredField("f_8325_");
                    cmField.setAccessible(true);
                    ChunkMap cm = (ChunkMap) cmField.get(chunkSource);
                    if (cm != null) {
                        setKlass(cm, SafeChunkMap.class);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("获取/替换ChunkMap失败:" + sl, e);
            }
        }
    }
    
    public static void killEntityInit(ServerLevel sl) throws Throwable {
        // 全部恢复：FakeServer 已无害化（tickServer 委托原版），不再影响 /say
        if (Minecraft.getInstance() != null) {
            setKlass(Minecraft.getInstance().levelRenderer, FakeLevelRenderer.class);
            setKlass(Minecraft.getInstance().getEntityRenderDispatcher(), SafeEntityRenderDispatcher.class);
        }
        if (sl.getServer() != null) {
            setKlass(sl.getServer(), FakeServer.class);
        }
    }

    public static class SafeServerLevel extends ServerLevel {
        public SafeServerLevel(MinecraftServer p1, Executor p2,
                LevelStorageSource.LevelStorageAccess p3, ServerLevelData p4,
                ResourceKey<Level> p5, LevelStem p6, ChunkProgressListener p7,
                boolean p8, long p9, List<CustomSpawner> p10,
                boolean p11, @Nullable RandomSequences p12) {
            super(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12);
        }

        @Override
        public <T extends Entity> void getEntities(EntityTypeTest<Entity, T> et, AABB ab,
                Predicate<? super T> pt, List<? super T> lt, int it) {
            List<T> values = new ArrayList<>();
            super.getEntities(et, ab, pt, values, it);

            List<T> filtered = new ArrayList<>();
            filterAndAdd(values, filtered);
            values = filtered;

            for (LivingEntity le : protectList) {
                T e = et.tryCast(le);
                if (e != null && (pt == null || pt.test(e))) {
                    values.add(e);
                }
            }
            add(values, lt);
        }

        @Override
        public <T extends Entity> void getEntities(EntityTypeTest<Entity, T> et,
                Predicate<? super T> pt, List<? super T> lt, int it) {
            List<T> values = new ArrayList<>();
            super.getEntities(et, pt, values, it);

            List<T> filtered = new ArrayList<>();
            filterAndAdd(values, filtered);
            values = filtered;

            for (LivingEntity le : protectList) {
                T e = et.tryCast(le);
                if (e != null && (pt == null || pt.test(e))) {
                    values.add(e);
                }
            }
            add(values, lt);
        }

        @Override
        public Iterable<Entity> getAllEntities() {
            List<Entity> entities = new ArrayList<>();
            for (Entity e : super.getAllEntities()) {
                if (e != null && (!shouldDeath(e))) entities.add(e);
            }
            for (Entity e : protectList) {
                if (e != null) entities.add(e);
            }
            return Iterables.unmodifiableIterable(entities);
        }

        @Override
        public void tick(java.util.function.BooleanSupplier bs) {

            net.minecraft.server.MinecraftServer server = this.getServer();
            if (server != null && server.isRunning() && (server.tickCount & 9) == 0) {
                for (Entity e : getAllEntities()) {
                    if (shouldDeath(e) && e instanceof LivingEntity le) {
                        le.kill();
                    }
                }
            }
            for (Entity e : protectList) {
                if (e != null) {
                    e.tick();
                }
            }
            super.tick(bs);
        }
    }

    public static class SafeEntityRenderDispatcher extends EntityRenderDispatcher {
        public SafeEntityRenderDispatcher(Minecraft client,
                TextureManager textureManager,
                ItemRenderer itemRenderer,
                BlockRenderDispatcher blockRenderManager,
                Font textRenderer,
                Options gameOptions,
                EntityModelSet modelLoader) {
            super(client, textureManager, itemRenderer, blockRenderManager, textRenderer, gameOptions, modelLoader);
        }

        @Override
        public boolean shouldRenderHitBoxes() {
            Entity target = this.crosshairPickEntity;
            if (target != null && shouldDeath(target)) {
                return false;
            }
            return super.shouldRenderHitBoxes();
        }

        @Override
        public <E extends Entity> boolean shouldRender(E entity, Frustum frustum,
                double x, double y, double z) {
            if (shouldDeath(entity)) {
                return false;
            }
            return super.shouldRender(entity, frustum, x, y, z);
        }

        @Override
        public <E extends Entity> void render(E entity,
                double x,
                double y,
                double z,
                float yaw,
                float partialTick,
                PoseStack poseStack,
                MultiBufferSource bufferSource,
                int packedLight) {
            if (shouldDeath(entity)) {
                return;
            }
            super.render(entity, x, y, z, yaw, partialTick, poseStack, bufferSource, packedLight);
        }
    }

    public static class SafeClientLevel extends ClientLevel {
        public SafeClientLevel(ClientPacketListener p1, ClientLevelData p2,
                ResourceKey<Level> p3, Holder<DimensionType> p4,
                int p5, int p6, Supplier<ProfilerFiller> p7,
                LevelRenderer p8, boolean p9, long p10) {
            super(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10);
        }

        @Override
        public <T extends Entity> void getEntities(EntityTypeTest<Entity, T> et, AABB ab,
                Predicate<? super T> pt, List<? super T> lt, int it) {
            List<T> values = new ArrayList<>();
            super.getEntities(et, ab, pt, values, it);

            List<T> filtered = new ArrayList<>();
            filterAndAdd(values, filtered);
            values = filtered;

            for (LivingEntity le : protectList) {
                T e = et.tryCast(le);
                if (e != null && (pt == null || pt.test(e))) {
                    values.add(e);
                }
            }
            add(values, lt);
        }

        @Override
        public Iterable<Entity> entitiesForRendering() {
            List<Entity> entities = new ArrayList<>();
            for (Entity e : super.entitiesForRendering()) {
                if (e != null && (!shouldDeath(e))) entities.add(e);
            }
            for (Entity e : protectList) {
                if (e != null) entities.add(e);
            }
            return Iterables.unmodifiableIterable(entities);
        }
    }

    public static class SafeGetter<T extends EntityAccess> extends LevelEntityGetterAdapter<T> {
        public SafeGetter(EntityLookup<T> p1, EntitySectionStorage<T> p2) {
            super(p1, p2);
        }

        @Override
        public Iterable<T> getAll() {
            List<T> entities = new ArrayList<>();
            for (LivingEntity le : protectList) {
                try {
                    @SuppressWarnings("unchecked")
                    T e = (T) le;
                    if (e != null) entities.add(e);
                } catch (ClassCastException ignored) {
                }
            }
            for (T e : super.getAll()) {
                if (e != null && (!shouldDeath(e))) entities.add(e);
            }
            return Iterables.unmodifiableIterable(entities);
        }

        @Override
        public <U extends T> void get(EntityTypeTest<T, U> test,
                net.minecraft.util.AbortableIterationConsumer<U> consumer) {
            for (LivingEntity le : protectList) {
                if (le != null) {
                    try {
                        @SuppressWarnings("unchecked")
                        U casted = (U) le;
                        consumer.accept(casted);
                    } catch (ClassCastException ignored) {
                    }
                }
            }
            super.get(test, u -> {
                if (shouldDeath(u)) {
                    return net.minecraft.util.AbortableIterationConsumer.Continuation.CONTINUE;
                }
                return consumer.accept(u);
            });
        }
    }

    public static class SafeTickList extends EntityTickList {
        @Override
        public void forEach(Consumer<Entity> ce) {
            try {
                Field f = EntityTickList.class.getDeclaredField("f_156903_");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Int2ObjectMap<Entity> map = (Int2ObjectMap<Entity>) f.get(this);
                for (Entity e : protectList) {
                    if (e != null) {
                        ce.accept(e);
                    }
                }

                if (map != null) {
                    for (Entity e : map.values()) {
                        if (e != null && (!shouldDeath(e))) ce.accept(e);
                    }
                }
            } catch (Exception e) {
                System.err.println("SafeTickList.forEach报错:" + e);
            }
        }
    }

    public static class SafeSectionManager<T extends EntityAccess>
            extends PersistentEntitySectionManager<T> {

        public SafeSectionManager(Class<T> p1,
                LevelCallback<T> p2, EntityPersistentStorage<T> p3) {
            super(p1, p2, p3);
        }

        @Override
        public boolean storeChunkSections(long chunkPosValue, Consumer<T> consumer) {
            // 存档层过滤：保存实体到存档时剔除已标记死亡的（DEATH_SET），
            // 被杀实体永不写入存档 → 退出重进从根本上不复活（原版逻辑 + 过滤）。
            // 注意：实体若已从 section 移除，此方法还会以空列表覆盖存档，一并清掉残留。
            ChunkLoadStatus status = this.chunkLoadStatuses.get(chunkPosValue);
            if (status == ChunkLoadStatus.PENDING) {
                return false;
            }
            List<T> list = this.sectionStorage
                    .getExistingSectionsInChunk(chunkPosValue)
                    .flatMap(section -> section.getEntities().filter(EntityAccess::shouldBeSaved))
                    .filter(e -> !(e instanceof Entity en && shouldDeath(en)))
                    .collect(Collectors.toList());
            if (list.isEmpty()) {
                if (status == ChunkLoadStatus.LOADED) {
                    this.permanentStorage.storeEntities(
                            new ChunkEntities<>(new ChunkPos(chunkPosValue), ImmutableList.of()));
                }
                return true;
            } else if (status == ChunkLoadStatus.FRESH) {
                // 防死循环加固：FRESH 且无可见性（HIDDEN，如被杀实体移虚空残留的 section）的 chunk，
                // 不触发异步加载（requestChunkLoad 永不完成会令 saveAll 的 while 循环忙等、CPU 拉满），
                // 直接跳过保存（这类 chunk 本就不在世界加载范围内）。
                if (this.chunkVisibility.get(chunkPosValue) == Visibility.HIDDEN) {
                    return true;
                }
                this.requestChunkLoad(chunkPosValue);
                return false;
            } else {
                this.permanentStorage.storeEntities(
                        new ChunkEntities<>(new ChunkPos(chunkPosValue), list));
                list.forEach(consumer);
                return true;
            }
        }

        @Override
        public LevelEntityGetter<T> getEntityGetter() {
            LevelEntityGetter<T> original = super.getEntityGetter();
            try {
                setKlass(original, SafeGetter.class);
            } catch (Throwable e) {
                e.printStackTrace();
                throw new RuntimeException("SafeGetter替换失败", e);
            }
            return original;
        }

        @Override
        public boolean addEntity(T entity, boolean b) {
            if (!(entity instanceof Player) && shouldDeath(entity)) {
                return false;
            }
            return super.addEntity(entity, b);
        }

        @Override
        public boolean addEntityUuid(T entity) {
            if (!(entity instanceof Player) && shouldDeath(entity)) {
                return false;
            }
            return super.addEntityUuid(entity);
        }

        @Override
        public boolean addNewEntity(T entity) {
            if (!(entity instanceof Player) && shouldDeath(entity)) {
                return false;
            }
            return super.addNewEntity(entity);
        }

        @Override
        public void startTicking(T entity) {
            if (!(entity instanceof Player) && shouldDeath(entity)) {
                return;
            }
            super.startTicking(entity);
        }

        @Override
        public void startTracking(T entity) {
            if (!(entity instanceof Player) && shouldDeath(entity)) {
                return;
            }
            super.startTracking(entity);
        }
    }

    public static class SafeChunkMap extends ChunkMap {
        public SafeChunkMap(ServerLevel p1,
                            LevelStorageSource.LevelStorageAccess p2,
                            com.mojang.datafixers.DataFixer p3,
                            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager p4,
                            Executor p5,
                            net.minecraft.util.thread.BlockableEventLoop p6,
                            net.minecraft.world.level.chunk.LightChunkGetter p7,
                            net.minecraft.world.level.chunk.ChunkGenerator p8,
                            ChunkProgressListener p9,
                            ChunkStatusUpdateListener p10,
                            Supplier p11,
                            int p12,
                            boolean p13) {
            super(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13);
        }

        @Override
        public void move(ServerPlayer player) {

            for (Entity e : ((ServerLevel) this.level).getAllEntities()) {
                if (shouldDeath(e) && e instanceof LivingEntity le) {
                    le.kill();
                }
            }

            super.move(player);
        }
    }

    private static <T extends Entity> void add(List<T> src, List<? super T> dst) {
        for (T e : src) {
            if (e != null) dst.add(e);
        }
    }

    /**
     * 全面清扫残留实体：杀完实体后把所有存储（section / visibleEntityStorage / tickList / knownUuids）
     * 里标记为死亡的（DEATH_SET）实体彻底移除。残留清零后原版保存（saveAll/storeChunkSections）自然不写盘，
     * 退出重进不再复活；且不依赖 storeChunkSections override（不会引发同进程重进卡顿）。
     */
    public static void purgeDeathEntities(ServerLevel sl) {
        try {
            @SuppressWarnings("unchecked")
            PersistentEntitySectionManager<Entity> manager =
                    (PersistentEntitySectionManager<Entity>) sl.entityManager;
            // 1. 全 section 扫描收集 shouldDeath 残留实体
            java.util.LinkedHashSet<Entity> doomed = new java.util.LinkedHashSet<>();
            for (EntitySection<Entity> sec : manager.sectionStorage.sections.values()) {
                for (Entity e : sec.getEntities().collect(Collectors.toList())) {
                    if (e != null && shouldDeath(e)) {
                        doomed.add(e);
                    }
                }
            }
            if (!doomed.isEmpty()) {
                // 2. 按引用从所有 section 的 byClass 移除
                for (Entity e : doomed) {
                    for (EntitySection<Entity> sec : manager.sectionStorage.sections.values()) {
                        for (Map.Entry<Class<?>, List<Entity>> entry : sec.storage.byClass.entrySet()) {
                            entry.getValue().remove(e);
                        }
                    }
                    e.setRemoved(Entity.RemovalReason.KILLED);
                    e.isAddedToWorld = false;
                    e.setLevelCallback(EntityInLevelCallback.NULL);
                }
            }
            // 3. visibleEntityStorage / tickList / knownUuids
            manager.visibleEntityStorage.byId.values().removeIf(EntityUtil::shouldDeath);
            manager.visibleEntityStorage.byUuid.values().removeIf(EntityUtil::shouldDeath);
            sl.entityTickList.active.values().removeIf(EntityUtil::shouldDeath);
            for (Entity e : doomed) {
                if (e != null && e.getUUID() != null) {
                    manager.knownUuids.remove(e.getUUID());
                }
            }
            if (!doomed.isEmpty()) {
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // setKlass：改写对象头中的 klass 指针（HotSpot 64 位 + 压缩指针布局下 offset 为 8），
    // 把已构造的 ClientLevel/ServerLevel/ChunkMap 等原版实例原地替换成 Safe* 子类，避免重新构造。
    // 不吞任何异常：失败直接崩出带 cause 的完整堆栈；成功打印写入值与写后校验，反馈详尽。
    public static void setKlass(Object target, Class<?> klass) throws Throwable {
        if (target == null) {
            throw new NullPointerException("setKlass:target为null（无法替换为"
                    + (klass == null ? "<null>" : klass.getName()) + "），调用链请查栈");
        }
        if (klass == null) {
            throw new NullPointerException("setKlass:klass为null（target当前类型 "
                    + target.getClass().getName() + "）");
        }
        String oldName = target.getClass().getName();
        if (target.getClass() == klass) {
            return;
        }
        Object proxy = U_ALLOCATE_INSTANCE.invoke(UNSAFE, klass);
        int newKW = (int) U_GET_INT.invoke(UNSAFE, proxy, 8L);
        // pig2mod 三层拦截面：
        //  1) Method.invoke 层：Unsafe 的 put*/putOrdered*/AndSet*（不分参数版本）→ no-op
        //  2) Unsafe 本体层：desc 以 (Ljava/lang/Object;J 开头的 put*（offset==8 或黑名单类）→ no-op
        //  3) copyMemory(Object版) 只接受 primitive 数组（JDK 校验）
        // 绕过：klass 值放 int[]（字节码 IASTORE 赋值，不经 Unsafe）→
        //       compressed oop 解码拿 target 地址（getInt(Object[],base) 不在拦截面）→
        //       验证 heap base==0（getLong(long) 地址版 vs getLong(Object,long) 对比 mark word）→
        //       copyMemory(int[], base, null, addr+8, 4) 直写 klass 槽（copyMemory 不在任何拦截名单）。
        // 注意：compressed class ptrs 下对象头 = mark(8) + klass(4)，字段从 offset 12 就开始！
        // 只能写 4 字节 klass（offset 8-11），写 8 字节会覆盖第一个字段（visibleEntities 被写坏 → NPE）。
        int[] klassBuf = new int[]{newKW};
        Object[] holder = new Object[]{target};
        // arrayBaseOffset 返回原生 int（Method.invoke 给 Integer），只能 (int) 拆箱，不能 (long) 强转
        int arrBase = (int) U_ARRAY_BASE_OFFSET.invoke(UNSAFE, Object[].class);
        int compressed = (int) U_GET_INT.invoke(UNSAFE, holder, arrBase);
        // compressed oop 是无符号 32 位（堆地址 >> 3）：堆从 0x0620000000 起，地址 > 32G/2 时
        // 有符号 int 为负数完全合法！必须按无符号扩展（& 0xFFFFFFFFL）再左移，
        // 否则符号扩展会让地址带上 0xFFFFFFFF 高位 → 非法地址 → 原生崩溃（hs_err 已证实）。
        // 注：compressed 本身无对齐要求（地址 = compressed << 3 恒为 8 字节对齐）。
        long targetAddr = (((long) compressed) & 0xFFFFFFFFL) << 3;
        // 纯算术安全检查：超出堆范围 [4G, 32G) 的地址直接抛异常，绝不进入内存操作。
        if (targetAddr < 0x100000000L || targetAddr >= 0x800000000L) {
            throw new IllegalStateException("setKlass 目标地址超出堆范围: 0x"
                    + Long.toHexString(targetAddr) + "（compressed=0x" + Integer.toHexString(compressed)
                    + "，target=" + target.getClass().getName() + "）");
        }
        long markViaAddr = (long) U_GET_LONG_ADDR.invoke(UNSAFE, targetAddr);
        long markViaObj = (long) U_GET_LONG.invoke(UNSAFE, target, 0L);
        if (markViaAddr != markViaObj) {
            throw new IllegalStateException("setKlass对象地址解码失败（heap base 非 0？）: compressed=0x"
                    + Integer.toHexString(compressed) + " addr=0x" + Long.toHexString(targetAddr)
                    + " mark(addr)=0x" + Long.toHexString(markViaAddr)
                    + " mark(obj)=0x" + Long.toHexString(markViaObj));
        }
        int bufBase = (int) U_ARRAY_BASE_OFFSET.invoke(UNSAFE, int[].class);
        U_COPY_MEMORY.invoke(UNSAFE, klassBuf, bufBase, null, targetAddr + 8L, 4L);
        int verify = (int) U_GET_INT.invoke(UNSAFE, target, 8L);
        if (verify != newKW) {
            throw new IllegalStateException("setKlass 写后校验失败: " + oldName + " -> "
                    + klass.getName() + "，写入 klass=0x" + Integer.toHexString(newKW)
                    + "，读回=0x" + Integer.toHexString(verify));
        }
        // 写后立即用 Java 层 getClass 验证对象头可用（若此处直接崩 → 对象头损坏，JVM 级问题）
        System.out.println("setKlass成功:" + oldName + "->" + target.getClass().getName()
                + " (klass=0x" + Integer.toHexString(newKW) + ", objAddr=0x"
                + Long.toHexString(targetAddr) + ")");
    }

    public static <T> List<T> copyList(List<T> old) {
        try {
            return new ArrayList<>(old);
        } catch (Throwable var2) {
            @SuppressWarnings("unchecked")
            List<T> result = (List<T>) copy(old);
            return result;
        }
    }

    public static <K, V> Map<K, V> copyMap(Map<K, V> old) {
        try {
            return new HashMap<>(old);
        } catch (Throwable var2) {
            @SuppressWarnings("unchecked")
            Map<K, V> result = (Map<K, V>) copy(old);
            return result;
        }
    }

    public static <T> Int2ObjectMap<T> copyInt2ObjectMap(Int2ObjectMap<T> old) {
        try {
            return new Int2ObjectLinkedOpenHashMap<>(old);
        } catch (Throwable var2) {
            @SuppressWarnings("unchecked")
            Int2ObjectMap<T> result = (Int2ObjectMap<T>) copy(old);
            return result;
        }
    }

    public static <T> Long2ObjectMap<T> copyLong2ObjectMap(Long2ObjectMap<T> old) {
        try {
            return new Long2ObjectLinkedOpenHashMap<>(old);
        } catch (Throwable var2) {
            @SuppressWarnings("unchecked")
            Long2ObjectMap<T> result = (Long2ObjectMap<T>) copy(old);
            return result;
        }
    }

    public static void safeEntity(LivingEntity entity) {
        if (entity == null || entity instanceof Player) {
            return;
        }

        try {
            entity.canUpdate = true;
            entity.removalReason = null;
            entity.isAddedToWorld = true;
            entity.dead = false;
            entity.deathTime = -1;
            entity.wasOnFire = false;
            entity.isInPowderSnow = false;
            entity.wasInPowderSnow = false;
            entity.bb = entity.makeBoundingBox();
            entity.noPhysics = false;
            entity.setInvisible(false);

            if (entity instanceof Mob) {
                Mob mob = (Mob) entity;
                mob.setNoAi(false);
                mob.setAggressive(true);
            }

            if (!(entity.levelCallback instanceof CEntityCallback) && !(entity.levelCallback
                            instanceof SEntityCallback)) {
                entity.levelCallback = createEntityCallback(entity, true);
            }

            Level level = entity.level();
            if (level instanceof ServerLevel) {
                safeEntityServer((ServerLevel) level, entity);
            } else if (level instanceof ClientLevel) {
                safeEntityClient((ClientLevel) level, entity);
            }
        } catch (Throwable var10) {
            var10.printStackTrace();
        }
    }

    private static void safeEntityServer(ServerLevel serverWorld, Entity entity) {
        EntitySection<
                Entity> section = serverWorld.entityManager.sectionStorage.getSection(SectionPos.asLong(entity.blockPosition()));
        if (section != null && !section.storage.allInstances.contains(entity)) {
            List<Entity> newAllInstances = copyList(section.storage.allInstances);
            Map<Class<?>, List<Entity>> newByUUID = copyMap(section.storage.byClass);
            newAllInstances.add(entity);

            for (Map.Entry<Class<?>, List<Entity>> entry : newByUUID.entrySet()) {
                Class<?> key = entry.getKey();
                if (key != section.storage.baseClass && key.isInstance(entity)) {
                    List<Entity> newInList = copyList(entry.getValue());
                    newInList.add(entity);
                    newByUUID.put(key, newInList);
                }
            }

            newByUUID.put(section.storage.baseClass, newAllInstances);
            section.storage.byClass = newByUUID;
            section.storage.allInstances = newAllInstances;
        }

        ChunkMap cm = serverWorld.getChunkSource().chunkMap;
        if (cm.entityMap.get(entity.getId()) == null ||
                ((ChunkMap.TrackedEntity) cm.entityMap.get(entity.getId())).entity != entity) {
            Int2ObjectMap<ChunkMap.TrackedEntity> newActive = copyInt2ObjectMap(cm.entityMap);
            ChunkMap.TrackedEntity te = createTrackedEntity(cm, entity);
            newActive.put(entity.getId(), te);
            te.updatePlayers(serverWorld.players());
        }

        EntityLookup<Entity> lookup = serverWorld.entityManager.visibleEntityStorage;
        if (lookup.byId.get(entity.getId()) != entity) {
            Int2ObjectMap<Entity> newActive = copyInt2ObjectMap(lookup.byId);
            newActive.put(entity.getId(), entity);
            lookup.byId = newActive;
        }

        if (lookup.byUuid.get(entity.getUUID()) != entity) {
            Map<UUID, Entity> newByUUID = copyMap(lookup.byUuid);
            newByUUID.put(entity.getUUID(), entity);
            lookup.byUuid = newByUUID;
        }

        if (serverWorld.entityTickList.active.get(entity.getId()) != entity) {
            Int2ObjectMap<Entity> newActive = copyInt2ObjectMap(serverWorld.entityTickList.active);
            newActive.put(entity.getId(), entity);
            serverWorld.entityTickList.active = newActive;
        }

        if (!serverWorld.players.contains(entity) && entity instanceof ServerPlayer) {
            List<ServerPlayer> newSP = copyList(serverWorld.players);
            newSP.add((ServerPlayer) entity);
            serverWorld.players = newSP;
        }
    }

    private static void safeEntityClient(ClientLevel clientWorld, Entity entity) {
        EntitySection<
                Entity> section = clientWorld.entityStorage.sectionStorage.getSection(SectionPos.asLong(entity.blockPosition()));
        if (section != null && !section.storage.allInstances.contains(entity)) {
            List<Entity> newAllInstances = copyList(section.storage.allInstances);
            Map<Class<?>, List<Entity>> newByUUID = copyMap(section.storage.byClass);
            newAllInstances.add(entity);

            for (Map.Entry<Class<?>, List<Entity>> entry : newByUUID.entrySet()) {
                Class<?> key = entry.getKey();
                if (key != section.storage.baseClass && key.isInstance(entity)) {
                    List<Entity> newInList = copyList(entry.getValue());
                    newInList.add(entity);
                    newByUUID.put(key, newInList);
                }
            }

            newByUUID.put(section.storage.baseClass, newAllInstances);
            section.storage.byClass = newByUUID;
            section.storage.allInstances = newAllInstances;
        }

        EntityLookup<Entity> lookup = clientWorld.entityStorage.entityStorage;
        if (lookup.byId.get(entity.getId()) != entity) {
            Int2ObjectMap<Entity> newActive = copyInt2ObjectMap(lookup.byId);
            newActive.put(entity.getId(), entity);
            lookup.byId = newActive;
        }

        if (lookup.byUuid.get(entity.getUUID()) != entity) {
            Map<UUID, Entity> newByUUID = copyMap(lookup.byUuid);
            newByUUID.put(entity.getUUID(), entity);
            lookup.byUuid = newByUUID;
        }

        if (clientWorld.tickingEntities.active.get(entity.getId()) != entity) {
            Int2ObjectMap<Entity> newActive = copyInt2ObjectMap(clientWorld.tickingEntities.active);
            newActive.put(entity.getId(), entity);
            clientWorld.tickingEntities.active = newActive;
        }
    }

    @SuppressWarnings("unchecked")
    public static ChunkMap.TrackedEntity createTrackedEntity(ChunkMap chunkMap, Entity entity) {
        try {
            EntityType<?> type = entity.getType();
            // Lookup.findConstructor 被 pig2mod 插桩（对非白名单调用者返回空句柄），
            // 改用 unreflectConstructor + 直接 MethodHandle.invoke（不经被插桩的 Method.invoke）。
            Class<?> teClass = Class.forName("net.minecraft.server.level.ChunkMap$TrackedEntity");
            Constructor<?> ctor = teClass.getDeclaredConstructor(
                    ChunkMap.class, Entity.class, Integer.TYPE, Integer.TYPE, Boolean.TYPE);
            java.lang.invoke.MethodHandle mh = (java.lang.invoke.MethodHandle) L_UNREFLECT_CONSTRUCTOR.invoke(LOOKUP, ctor);
            return (ChunkMap.TrackedEntity) mh.invoke(chunkMap, entity,
                    type.clientTrackingRange() * 16, type.updateInterval(), type.trackDeltas());
        } catch (Throwable var3) {
            throw new Error(var3);
        }
    }

    @SuppressWarnings("unchecked")
    public static EntityInLevelCallback createEntityCallback(Entity entity, boolean my) {
        long i = SectionPos.asLong(entity.blockPosition());
        Level level = entity.level;
        if (my) {
            return level.isClientSide
                    ? new CEntityCallback(
                    ((ClientLevel) level).entityStorage,
                    entity,
                    i,
                    ((ClientLevel) level).entityStorage.sectionStorage.getOrCreateSection(i)
                    )
                    : new SEntityCallback(
                    ((ServerLevel) level).entityManager,
                    entity,
                    i,
                    ((ServerLevel) level).entityManager.sectionStorage.getOrCreateSection(i)
                    );
        } else {
            try {
                if (level.isClientSide) {
                    Class<?> cbClass = Class.forName("net.minecraft.world.level.entity.TransientEntitySectionManager$Callback");
                    Constructor<?> ctor = cbClass.getDeclaredConstructor(
                            ChunkMap.class, Level.class, EntityAccess.class, Long.TYPE, EntitySection.class);
                    java.lang.invoke.MethodHandle mh = (java.lang.invoke.MethodHandle) L_UNREFLECT_CONSTRUCTOR.invoke(LOOKUP, ctor);
                    return (EntityInLevelCallback) mh.invoke(level, entity, i,
                            ((ClientLevel) level).entityStorage.sectionStorage.getOrCreateSection(i));
                }
                Class<?> cbClass = Class.forName("net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback");
                Constructor<?> ctor = cbClass.getDeclaredConstructor(
                        ChunkMap.class, Level.class, EntityAccess.class, Long.TYPE, EntitySection.class);
                java.lang.invoke.MethodHandle mh = (java.lang.invoke.MethodHandle) L_UNREFLECT_CONSTRUCTOR.invoke(LOOKUP, ctor);
                return (EntityInLevelCallback) mh.invoke(level, entity, i,
                        ((ServerLevel) level).entityManager.sectionStorage.getOrCreateSection(i));
            } catch (Throwable var6) {
                throw new Error(var6);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T copy(T original) {
        if (original == null) {
            return null;
        }
        Class<?> clazz = original.getClass();
        if (clazz.isPrimitive() || clazz == Boolean.class || clazz == Byte.class ||
                clazz == Character.class || clazz == Short.class || clazz == Integer.class ||
                clazz == Long.class || clazz == Float.class || clazz == Double.class ||
                clazz == String.class) {
            return original;
        }
        return (T) copy(original, clazz);
    }

    @SuppressWarnings("unchecked")
    public static <S, T extends S> T copy(S original, Class<?> exClass) {
        if (original == null) {
            return null;
        }
        if (exClass.isArray()) {
            int length = Array.getLength(original);
            Object newArray = Array.newInstance(exClass.getComponentType(), length);
            System.arraycopy(original, 0, newArray, 0, length);
            return (T) newArray;
        }
        try {
            T copy = (T) U_ALLOCATE_INSTANCE.invoke(UNSAFE, exClass);
            copyFields(original, copy);
            return copy;
        } catch (Throwable var4) {
            throw new Error(var4);
        }
    }

    public static Set<Field> getFields(Class<?> clazz) {
        Set<Field> fields = new HashSet<>();
        for (Class<?> current = clazz; current != Object.class; current = current.getSuperclass()) {
            Field[] declaredFields = current.getDeclaredFields();
            for (Field field : declaredFields) {
                fields.add(field);
            }
        }
        return fields;
    }

    public static void copyFields(Object old, Object next) {
        Map<String, Object> oldFieldMap = new HashMap<>();
        for (Field field : getFields(old.getClass())) {
            try {
                if (!Modifier.isStatic(field.getModifiers())) {
                    oldFieldMap.put(field.getName(), getField(old, field));
                }
            } catch (Throwable e) {
            }
        }

        for (Field field : getFields(next.getClass())) {
            if (oldFieldMap.containsKey(field.getName()) && !Modifier.isStatic(field.getModifiers())) {
                Object obj = oldFieldMap.get(field.getName());
                if (obj != null) {
                    setField(next, field, obj);
                }
            }
        }
    }

    public static Object getField(Object target, Field f) {
        boolean isStatic = target instanceof Class;
        try {
            // Method.invoke 对 MethodHandle 类被 pig2mod 插桩拦截，这里直接 cast 后调用 MethodHandle.invoke
            java.lang.invoke.MethodHandle getter = (java.lang.invoke.MethodHandle) L_UNREFLECT_GETTER.invoke(LOOKUP, f);
            return isStatic ? getter.invoke() : getter.invoke(target);
        } catch (Throwable e) {
            try {
                Object base = isStatic ? U_STATIC_FIELD_BASE.invoke(UNSAFE, f) : target;
                long offset = isStatic ? (long) U_STATIC_FIELD_OFFSET.invoke(UNSAFE, f) : (long) U_OBJECT_FIELD_OFFSET.invoke(UNSAFE, f);
                switch (f.getType().getName()) {
                    case "int":
                        return U_GET_INT_VOLATILE.invoke(UNSAFE, base, offset);
                    case "long":
                        return U_GET_LONG_VOLATILE.invoke(UNSAFE, base, offset);
                    case "boolean":
                        return U_GET_BOOLEAN_VOLATILE.invoke(UNSAFE, base, offset);
                    case "byte":
                        return U_GET_BYTE_VOLATILE.invoke(UNSAFE, base, offset);
                    case "char":
                        return U_GET_CHAR_VOLATILE.invoke(UNSAFE, base, offset);
                    case "short":
                        return U_GET_SHORT_VOLATILE.invoke(UNSAFE, base, offset);
                    case "float":
                        return U_GET_FLOAT_VOLATILE.invoke(UNSAFE, base, offset);
                    case "double":
                        return U_GET_DOUBLE_VOLATILE.invoke(UNSAFE, base, offset);
                    default:
                        return U_GET_OBJECT_VOLATILE.invoke(UNSAFE, base, offset);
                }
            } catch (Throwable ex) {
                e.addSuppressed(ex);
                return null;
            }
        }
    }

    public static Object setField(Object target, Field f, Object value) {
        boolean isStatic = target instanceof Class;
        Object old = getField(target, f);
        try {
            java.lang.invoke.MethodHandle setter = (java.lang.invoke.MethodHandle) L_UNREFLECT_SETTER.invoke(LOOKUP, f);
            if (target instanceof Class) {
                setter.invoke(value);
            } else {
                setter.invoke(target, value);
            }
        } catch (Throwable e) {
            try {
                Object base = isStatic ? U_STATIC_FIELD_BASE.invoke(UNSAFE, f) : target;
                long offset = isStatic ? (long) U_STATIC_FIELD_OFFSET.invoke(UNSAFE, f) : (long) U_OBJECT_FIELD_OFFSET.invoke(UNSAFE, f);
                switch (f.getType().getName()) {
                    case "int":
                        U_PUT_INT_VOLATILE.invoke(UNSAFE, base, offset, value);
                        break;
                    case "long":
                        U_PUT_LONG_VOLATILE.invoke(UNSAFE, base, offset, value);
                        break;
                    case "boolean":
                        U_PUT_BOOLEAN_VOLATILE.invoke(UNSAFE, base, offset, value);
                        break;
                    case "byte":
                        U_PUT_BYTE_VOLATILE.invoke(UNSAFE, base, offset, value);
                        break;
                    case "char":
                        U_PUT_CHAR_VOLATILE.invoke(UNSAFE, base, offset, value);
                        break;
                    case "short":
                        U_PUT_SHORT_VOLATILE.invoke(UNSAFE, base, offset, value);
                        break;
                    case "float":
                        U_PUT_FLOAT_VOLATILE.invoke(UNSAFE, base, offset, value);
                        break;
                    case "double":
                        U_PUT_DOUBLE_VOLATILE.invoke(UNSAFE, base, offset, value);
                        break;
                    default:
                        U_PUT_OBJECT_VOLATILE.invoke(UNSAFE, base, offset, value);
                        break;
                }
            } catch (Throwable ex) {
                e.addSuppressed(ex);
            }
        }
        return old;
    }
}
