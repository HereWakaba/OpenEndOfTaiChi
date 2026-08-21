package com.ryjs.reflection.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import org.apache.commons.lang3.mutable.MutableObject;
import sun.misc.Unsafe;
import java.lang.invoke.*;
import java.lang.reflect.Field;
import java.util.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import static net.minecraft.server.level.ChunkMap.isChunkInRange;


public class AdvancedKillUtils {
    /**
     * 方法句柄查找器，用于访问私有字段和方法
     */
    private static final MethodHandles.Lookup lookup = MethodHandles.lookup();
    /**
     * Unsafe实例，用于直接内存操作
     */
    private static Unsafe unsafe;
    /**
     * 生命值字段的内存偏移量
     */
    private static long healthOffset = -1;
    /**
     * 实体等级字段的变量句柄
     */
    private static final VarHandle entityLevelVar;
    /**
     * 实体管理器字段的变量句柄
     */
    private static final VarHandle entityManagerVar;
    /**
     * 移除原因字段的变量句柄
     */
    private static final VarHandle removalReasonVar;
    /**
     * 生命值字段的变量句柄
     */
    private static final VarHandle healthVar;
    /**
     * 日志记录器
     */
    public static final Logger log = LogManager.getLogger();
    /**
     * 生命值数据访问器
     */
    private static final EntityDataAccessor<Float> DATA_HEALTH_ACCESSOR = LivingEntity.DATA_HEALTH_ID;
    
    /**
     * 静态初始化块，初始化反射相关的字段和方法句柄
     * 在类加载时执行一次，设置所有必要的反射访问权限
     */
    static {
        try {
            // 获取Unsafe实例，用于直接内存操作
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);  // 设置可访问私有字段
            unsafe = (Unsafe) f.get(null);  // 获取Unsafe单例实例
            
            // 获取LivingEntity中生命值字段的内存偏移量
            Field healthField = LivingEntity.class.getDeclaredField("f_20883_");
            healthOffset = unsafe.objectFieldOffset(healthField);  // 计算字段在对象中的内存偏移
            
            // 创建各种字段的变量句柄，用于高效访问
            // f_19853_ 是 Entity 类中的 level 字段（混淆后的名称）
            entityLevelVar = MethodHandles.privateLookupIn(Entity.class, lookup).findVarHandle(Entity.class, "f_19853_", Level.class);
            
            // f_143244_ 是 ServerLevel 类中的 entityManager 字段
            entityManagerVar = MethodHandles.privateLookupIn(ServerLevel.class, lookup).findVarHandle(ServerLevel.class, "f_143244_", PersistentEntitySectionManager.class);
            
            // f_146795_ 是 Entity 类中的 removalReason 字段
            removalReasonVar = MethodHandles.privateLookupIn(Entity.class, lookup).findVarHandle(Entity.class, "f_146795_", Entity.RemovalReason.class);
            
            // f_20883_ 是 LivingEntity 类中的 health 字段
            healthVar = MethodHandles.privateLookupIn(LivingEntity.class, lookup).findVarHandle(LivingEntity.class, "f_20883_", float.class);
        } catch (Exception e) { 
            log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage());
            throw new RuntimeException("Failed to initialize", e);  // 初始化失败则抛出异常
        }
    }
    
    /**
     * 获取实体所在的等级（世界）
     * @param entity 要查询的实体
     * @return 实体所在的等级
     * @param <T> 等级类型
     */
    @SuppressWarnings("unchecked")
    public static <T extends Level> T getLevel(Entity entity) { return (T) entityLevelVar.get(entity); }
    
    /**
     * 获取服务器等级的实体管理器
     * @param level 服务器等级
     * @return 持久化实体部分管理器
     */
    public static PersistentEntitySectionManager<Entity> getManager(ServerLevel level) { return (PersistentEntitySectionManager<Entity>) entityManagerVar.get(level); }
    
    /**
     * 设置实体的移除原因
     * @param entity 要设置的实体
     * @param reason 移除原因
     */
    public static void setRemovalReason(Entity entity, Entity.RemovalReason reason) { removalReasonVar.set(entity, reason); }
    
    /**
     * 直接设置生物实体的生命值（绕过常规API）
     * @param entity 要设置的生物实体
     * @param health 新的生命值
     */
    public static void setHealthDirect(LivingEntity entity, float health) { healthVar.set(entity, health); }

    /**
     * 检查是否应该跳过某个实体的处理
     * @param entity 要检查的实体
     * @return 如果应该跳过则返回true
     */
    private static boolean shouldSkip(Entity entity) {
        if (entity == null) return true;
        // 跳过你的 mod 里所有实体（包名过滤）
        if (entity.getClass().getName().startsWith("com.ryjs.test")) return true;
        if (entity.getClass().getName().startsWith("com.ryjs.byd")) return true;
        if (entity.getClass().getName().startsWith("net.mcreator.ultimateskeletons")) return true;
        // 跳过原版视觉闪电
        if (entity instanceof net.minecraft.world.entity.LightningBolt) return true;
        return false;
    }

    /**
     * 确保活动实体列表不在迭代过程中被修改
     * 通过交换活跃和被动列表来避免并发修改异常
     * Minecraft的实体刻列表在迭代时不能直接修改，此方法通过切换列表来解决这个问题
     * @param list 实体刻列表
     */
    private static void ensureActiveIsNotIterated(EntityTickList list) {
        try {
            // 通过反射获取EntityTickList的内部字段
            // f_156905_ 是 iterated（当前正在迭代的列表）
            Field iteratedField = EntityTickList.class.getDeclaredField("f_156905_");
            // f_156903_ 是 active（活跃的实体列表）
            Field activeField = EntityTickList.class.getDeclaredField("f_156903_");
            // f_156904_ 是 passive（备用的实体列表）
            Field passiveField = EntityTickList.class.getDeclaredField("f_156904_");
            
            // 设置字段可访问
            iteratedField.setAccessible(true); 
            activeField.setAccessible(true); 
            passiveField.setAccessible(true);
            
            // 获取三个列表的引用
            Int2ObjectMap<Entity> iterated = (Int2ObjectMap<Entity>) iteratedField.get(list);
            Int2ObjectMap<Entity> active = (Int2ObjectMap<Entity>) activeField.get(list);
            Int2ObjectMap<Entity> passive = (Int2ObjectMap<Entity>) passiveField.get(list);
            
            // 如果当前迭代的列表就是活跃列表，说明正在遍历活跃列表
            if (iterated == active) {
                // 清空被动列表
                passive.clear();
                // 将活跃列表中的所有实体复制到被动列表
                for (Int2ObjectMap.Entry<Entity> entry : Int2ObjectMaps.fastIterable(active)) {
                    passive.put(entry.getIntKey(), entry.getValue());
                }
                // 交换活跃和被动列表的引用
                // 这样后续对active的修改不会影响当前正在进行的迭代
                activeField.set(list, passive); 
                passiveField.set(list, active);
            }
        } catch (Exception e) { 
            log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); 
            e.printStackTrace(); 
        }
    }
    
    /**
     * 在服务器端完全移除实体
     * 从所有相关的数据结构中彻底删除实体引用
     * 这是最彻底的服务器端实体删除方法，处理了所有可能的引用
     * @param entity 要移除的实体
     * @return 如果成功移除则返回true
     */
    public static boolean serverCompleteRemove(Entity entity) {
        // 获取实体所在的等级（世界）
        Level level = getLevel(entity);
        // 如果不是服务器等级，无法执行服务器端删除
        if (!(level instanceof ServerLevel serverLevel)) return false;
        
        try {
            // 计算实体当前所在的区块部分键值
            long currentSectionKey = SectionPos.asLong(entity.blockPosition());
            
            // 获取服务器等级的实体管理器
            PersistentEntitySectionManager<Entity> manager = getManager(serverLevel);
            
            // 获取实体所在的区块部分
            EntitySection<Entity> currentSection = manager.sectionStorage.getSection(currentSectionKey);
            
            // 如果找到了对应的区块部分，从其中移除实体
            if (currentSection != null) {
                removeFromEntitySection(entity, currentSection);
            } else {
                // 位置可能已被改成虚空（setEntityToVoid），按实体引用扫描所有 section 兜底移除
                // （否则实体留在 section 里，saveChunk 仍会写入存档 → 退出重进复活）
                boolean removed = false;
                for (EntitySection<Entity> sec : manager.sectionStorage.sections.values()) {
                    for (Map.Entry<Class<?>, List<Entity>> entry : sec.storage.byClass.entrySet()) {
                        if (entry.getValue().contains(entity)) {
                            removeFromEntitySection(entity, sec);
                            removed = true;
                            break;
                        }
                    }
                    if (removed) break;
                }
            }
            
            // 确保在移除实体时不会干扰正在进行的迭代
            ensureActiveIsNotIterated(serverLevel.entityTickList);
            
            // 从活跃实体刻列表中移除该实体
            serverLevel.entityTickList.active.remove(entity.getId());
            
            // 高级区块映射移除，处理玩家追踪和客户端同步
            advancedChunkMapRemoval(entity, serverLevel);
            
            // 处理特殊实体类型的移除逻辑（如玩家、怪物等）
            handleSpecialEntityRemoval(entity, serverLevel);
            
            // 更新动态游戏事件监听器，将其移除
            entity.updateDynamicGameEventListener(DynamicGameEventListener::remove);
            
            // 标记实体不再存在于世界中
            entity.isAddedToWorld = false;
            
            // 从可见实体存储中按UUID和ID移除
            manager.visibleEntityStorage.byUuid.remove(entity.getUUID());
            manager.visibleEntityStorage.byId.remove(entity.getId());
            
            // 从计分板中移除实体
            serverLevel.getScoreboard().entityRemoved(entity);
            
            // 从实体管理器的可见实体存储中移除
            serverLevel.entityManager.visibleEntityStorage.remove(entity);
            
            // 从实体刻列表中完全移除
            serverLevel.entityTickList.remove(entity);
            
            // 从已知的UUID集合中移除
            manager.knownUuids.remove(entity.getUUID());
            
            // 设置实体的等级回调为空，断开与世界的联系
            entity.setLevelCallback(EntityInLevelCallback.NULL);
            
            // 如果当前区块部分为空，清理空的区块部分
            if (currentSection != null && currentSection.isEmpty()) {
                manager.sectionStorage.sections.remove(currentSectionKey);
                manager.sectionStorage.sectionIds.remove(currentSectionKey);
            }
            
            return true;  // 成功移除
        } catch (Exception e) { 
            log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); 
            e.printStackTrace(); 
            return false;  // 移除失败
        }
    }
    
    /**
     * 从实体部分中移除指定实体
     * 实体部分是Minecraft用于组织和管理实体的数据结构，按区块划分
     * @param entity 要移除的实体
     * @param section 实体所在的部分
     */
    private static void removeFromEntitySection(Entity entity, EntitySection<Entity> section) {
        try {
            // 通过反射获取EntitySection中的storage字段（f_156827_）
            // storage是一个ClassInstanceMultiMap，按类型存储实体
            Field storageField = EntitySection.class.getDeclaredField("f_156827_");
            storageField.setAccessible(true);  // 设置可访问
            
            // 获取存储结构
            ClassInstanceMultiMap<Entity> storage = (ClassInstanceMultiMap<Entity>) storageField.get(section);
            
            // 遍历所有类型的实体列表
            for (Map.Entry<Class<?>, List<Entity>> entry : storage.byClass.entrySet()) {
                // 如果该类型可以实例化为当前实体，则从列表中移除
                if (entry.getKey().isInstance(entity)) {
                    entry.getValue().remove(entity);
                }
            }
        } catch (Exception e) { 
            log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); 
            e.printStackTrace(); 
        }
    }
    
    /**
     * 高级区块映射移除，处理玩家追踪和客户端同步
     * ChunkMap负责管理哪些实体对哪些玩家可见，此方法确保客户端正确收到实体移除通知
     * @param entity 要移除的实体
     * @param serverLevel 服务器等级
     */
    private static void advancedChunkMapRemoval(Entity entity, ServerLevel serverLevel) {
        try {
            // 获取服务器等级的区块映射
            ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;
            
            // 如果要移除的是服务器玩家，需要特殊处理
            if (entity instanceof ServerPlayer serverPlayer) {
                // 更新玩家在区块映射中的状态（设为移除）
                chunkMapUpdatePlayerStatus(chunkMap, serverPlayer, false);
                
                // 遍历所有被追踪的实体
                for (ChunkMap.TrackedEntity trackedEntity : chunkMap.entityMap.values()) {
                    // 如果该玩家的连接在实体的可见列表中
                    if (trackedEntity.seenBy.remove(serverPlayer.connection)) {
                        // 停止实体被该玩家看见
                        entity.stopSeenByPlayer(serverPlayer);
                        
                        // 创建移除实体的网络数据包
                        MethodHandle constructor = lookup.findConstructor(
                            ClientboundRemoveEntitiesPacket.class, 
                            MethodType.methodType(void.class, int[].class)
                        );
                        
                        // 发送移除数据包给玩家客户端
                        serverPlayer.connection.send((Packet<?>) constructor.invoke(new int[]{trackedEntity.entity.getId()}));
                    }
                }
            }
            
            // 从区块映射的实体追踪表中移除该实体
            ChunkMap.TrackedEntity trackedEntity = chunkMap.entityMap.remove(entity.getId());
            
            // 如果找到了对应的追踪实体
            if (trackedEntity != null) {
                // 创建移除实体的网络数据包
                MethodHandle constructor = lookup.findConstructor(
                    ClientboundRemoveEntitiesPacket.class, 
                    MethodType.methodType(void.class, int[].class)
                );
                
                // 向所有能看到该实体的玩家发送移除通知
                for (ServerPlayerConnection connection : trackedEntity.seenBy) {
                    entity.stopSeenByPlayer(connection.getPlayer());
                    connection.send((Packet<?>) constructor.invoke(new int[]{entity.getId()}));
                }
            }
        } catch (Throwable e) { 
            e.printStackTrace(); 
        }
    }
    
    /**
     * 处理特殊实体的移除逻辑
     * 如玩家、怪物、多部分实体等需要特殊处理的实体类型
     * 这些实体在Minecraft中有特殊的注册表或列表，需要单独清理
     * @param entity 要处理的实体
     * @param serverLevel 服务器等级
     */
    private static void handleSpecialEntityRemoval(Entity entity, ServerLevel serverLevel) {
        // 如果是服务器玩家，需要从玩家列表中移除并更新睡眠玩家列表
        if (entity instanceof ServerPlayer serverPlayer) {
            serverLevel.players().remove(serverPlayer);  // 从玩家列表移除
            serverLevel.updateSleepingPlayerList();      // 更新睡眠状态
        }
        
        // 如果是怪物且不在更新导航过程中，从导航怪物列表中移除
        if (entity instanceof Mob mob && !serverLevel.isUpdatingNavigations) {
            serverLevel.navigatingMobs.remove(mob);
        }
        
        // 如果是多部分实体（如末影龙），需要移除所有部分
        if (entity.isMultipartEntity()) {
            for (PartEntity<?> part : entity.getParts()) {
                serverLevel.dragonParts.remove(part.getId());
            }
        }
    }
    
    /**
     * 更新区块映射中的玩家状态
     * @param map 区块映射
     * @param player 玩家
     * @param add 是否为添加操作
     */
    private static void chunkMapUpdatePlayerStatus(ChunkMap map, ServerPlayer player, boolean add) {
        boolean skipPlayer = player.isSpectator() && !map.level.getGameRules().getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS);
        boolean wasIgnored = map.playerMap.ignoredOrUnknown(player);
        int sectionX = SectionPos.blockToSectionCoord(player.getBlockX());
        int sectionZ = SectionPos.blockToSectionCoord(player.getBlockZ());
        if (add) {
            map.playerMap.addPlayer(ChunkPos.asLong(sectionX, sectionZ), player, skipPlayer);
            SectionPos sectionPos = SectionPos.of(player);
            player.setLastSectionPos(sectionPos);
            player.connection.send(new ClientboundSetChunkCacheCenterPacket(sectionPos.x(), sectionPos.z()));
            if (!skipPlayer) addPlayerToDistanceManager(map.distanceManager, sectionPos, player);
        } else {
            SectionPos sectionPos = player.getLastSectionPos();
            map.playerMap.removePlayer(sectionPos.chunk().toLong(), player);
            if (!wasIgnored) removePlayerFromDistanceManager(map.distanceManager, sectionPos, player);
        }
        for (int x = sectionX - map.viewDistance - 1; x <= sectionX + map.viewDistance + 1; x++) {
            for (int z = sectionZ - map.viewDistance - 1; z <= sectionZ + map.viewDistance + 1; z++) {
                if (isChunkInRange(x, z, sectionX, sectionZ, map.viewDistance)) {
                    updateChunkTracking(map, player, new ChunkPos(x, z), new MutableObject<>(), !add, add);
                }
            }
        }
    }
    
    /**
     * 向距离管理器中添加玩家
     * @param manager 距离管理器
     * @param pos 位置
     * @param player 玩家
     */
    private static void addPlayerToDistanceManager(DistanceManager manager, SectionPos pos, ServerPlayer player) {
        try {
            ChunkPos chunkPos = pos.chunk();
            long chunkKey = chunkPos.toLong();
            Field playersPerChunkField = DistanceManager.class.getDeclaredField("f_140760_");
            playersPerChunkField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Long, ObjectSet<ServerPlayer>> playersPerChunk = (Map<Long, ObjectSet<ServerPlayer>>) playersPerChunkField.get(manager);
            playersPerChunk.computeIfAbsent(chunkKey, k -> new ObjectOpenHashSet<>()).add(player);
            Field naturalSpawnField = DistanceManager.class.getDeclaredField("f_140763_");
            naturalSpawnField.setAccessible(true);
            DynamicGraphMinFixedPoint naturalSpawn = (DynamicGraphMinFixedPoint) naturalSpawnField.get(manager);
            checkEdge(naturalSpawn, ChunkPos.INVALID_CHUNK_POS, chunkKey, 0, true);
            Field playerTicketField = DistanceManager.class.getDeclaredField("f_140764_");
            playerTicketField.setAccessible(true);
            DynamicGraphMinFixedPoint playerTicket = (DynamicGraphMinFixedPoint) playerTicketField.get(manager);
            checkEdge(playerTicket, ChunkPos.INVALID_CHUNK_POS, chunkKey, 0, true);
            manager.addTicket(TicketType.PLAYER, chunkPos, Math.max(0, ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING) - manager.simulationDistance), chunkPos);
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); }
    }
    
    /**
     * 从距离管理器中移除玩家
     * @param manager 距离管理器
     * @param pos 位置
     * @param player 玩家
     */
    private static void removePlayerFromDistanceManager(DistanceManager manager, SectionPos pos, ServerPlayer player) {
        try {
            ChunkPos chunkPos = pos.chunk();
            long chunkKey = chunkPos.toLong();
            Field playersPerChunkField = DistanceManager.class.getDeclaredField("f_140760_");
            playersPerChunkField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Long, ObjectSet<ServerPlayer>> playersPerChunk = (Map<Long, ObjectSet<ServerPlayer>>) playersPerChunkField.get(manager);
            ObjectSet<ServerPlayer> players = playersPerChunk.get(chunkKey);
            if (players != null) {
                players.remove(player);
                if (players.isEmpty()) {
                    playersPerChunk.remove(chunkKey);
                    Field naturalSpawnField = DistanceManager.class.getDeclaredField("f_140763_");
                    naturalSpawnField.setAccessible(true);
                    DynamicGraphMinFixedPoint naturalSpawn = (DynamicGraphMinFixedPoint) naturalSpawnField.get(manager);
                    checkEdge(naturalSpawn, ChunkPos.INVALID_CHUNK_POS, chunkKey, Integer.MAX_VALUE, false);
                    Field playerTicketField = DistanceManager.class.getDeclaredField("f_140764_");
                    playerTicketField.setAccessible(true);
                    DynamicGraphMinFixedPoint playerTicket = (DynamicGraphMinFixedPoint) playerTicketField.get(manager);
                    checkEdge(playerTicket, ChunkPos.INVALID_CHUNK_POS, chunkKey, Integer.MAX_VALUE, false);
                    manager.removeTicket(TicketType.PLAYER, chunkPos, Math.max(0, ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING) - manager.simulationDistance), chunkPos);
                }
            }
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); }
    }
    
    /**
     * 检查动态图最小固定点的边
     * （此方法已被注释掉，暂时不使用）
     * @param graph 动态图最小固定点
     * @param fromPos 起始位置
     * @param toPos 目标位置
     * @param level 层级
     * @param isAdd 是否为添加操作
     */
    private static void checkEdge(DynamicGraphMinFixedPoint graph, long fromPos, long toPos, int level, boolean isAdd) {
/*        try {
            int currentLevel = graph.getLevel(toPos);
            Field computedLevelsField = DynamicGraphMinFixedPoint.class.getDeclaredField("f_75539_");
            computedLevelsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Long, Byte> computedLevels = (Map<Long, Byte>) computedLevelsField.get(graph);
            int computedLevel = computedLevels.getOrDefault(toPos, (byte) 255) & 255;
            checkEdge(graph, fromPos, toPos, level, currentLevel, computedLevel, isAdd);
            Field hasWorkField = DynamicGraphMinFixedPoint.class.getDeclaredField("f_75541_");
            hasWorkField.setAccessible(true);
            Field priorityQueueField = DynamicGraphMinFixedPoint.class.getDeclaredField("f_278118_");
            priorityQueueField.setAccessible(true);
            Object priorityQueue = priorityQueueField.get(graph);
            boolean hasWork = !((Collection<?>) priorityQueue.getClass().getMethod("isEmpty").invoke(priorityQueue));
            hasWorkField.set(graph, hasWork);
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); }
        */
    }
    
    /**
     * 检查动态图最小固定点的边（重载版本）
     * @param graph 动态图最小固定点
     * @param fromPos 起始位置
     * @param toPos 目标位置
     * @param level 层级
     * @param currentLevel 当前层级
     * @param computedLevel 计算层级
     * @param isAdd 是否为添加操作
     */
    private static void checkEdge(DynamicGraphMinFixedPoint graph, long fromPos, long toPos, int level, int currentLevel, int computedLevel, boolean isAdd) {
        try {
            Field levelCountField = DynamicGraphMinFixedPoint.class.getDeclaredField("f_75537_");
            levelCountField.setAccessible(true);
            int levelCount = levelCountField.getInt(graph);
            boolean isSource = graph.isSource(toPos);
            if (isSource) return;
            level = Mth.clamp(level, 0, levelCount - 1);
            currentLevel = Mth.clamp(currentLevel, 0, levelCount - 1);
            boolean wasUnknown = computedLevel == 255;
            if (wasUnknown) computedLevel = currentLevel;
            int newComputedLevel = isAdd ? Math.min(computedLevel, level) : Mth.clamp(graph.getComputedLevel(toPos, fromPos, level), 0, levelCount - 1);
            int oldPriority = graph.calculatePriority(currentLevel, computedLevel);
            if (currentLevel != newComputedLevel) {
                int newPriority = graph.calculatePriority(currentLevel, newComputedLevel);
                Field priorityQueueField = DynamicGraphMinFixedPoint.class.getDeclaredField("f_278118_");
                priorityQueueField.setAccessible(true);
                Object priorityQueue = priorityQueueField.get(graph);
                Field computedLevelsField = DynamicGraphMinFixedPoint.class.getDeclaredField("f_75539_");
                computedLevelsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Long, Byte> computedLevels = (Map<Long, Byte>) computedLevelsField.get(graph);
                if (oldPriority != newPriority && !wasUnknown) priorityQueue.getClass().getMethod("dequeue", long.class, int.class, int.class).invoke(priorityQueue, toPos, oldPriority, newPriority);
                priorityQueue.getClass().getMethod("enqueue", long.class, int.class).invoke(priorityQueue, toPos, newPriority);
                computedLevels.put(toPos, (byte) newComputedLevel);
            } else if (!wasUnknown) {
                Field priorityQueueField = DynamicGraphMinFixedPoint.class.getDeclaredField("f_278118_");
                priorityQueueField.setAccessible(true);
                Object priorityQueue = priorityQueueField.get(graph);
                Field computedLevelsField = DynamicGraphMinFixedPoint.class.getDeclaredField("f_75539_");
                computedLevelsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Long, Byte> computedLevels = (Map<Long, Byte>) computedLevelsField.get(graph);
                priorityQueue.getClass().getMethod("dequeue", long.class, int.class, int.class).invoke(priorityQueue, toPos, oldPriority, levelCount);
                computedLevels.remove(toPos);
            }
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); }
    }
    
    /**
     * 更新区块追踪
     * （此方法已被注释掉，暂时不使用）
     * @param map 区块映射
     * @param player 玩家
     * @param chunkPos 区块位置
     * @param packetHolder 数据包持有者
     * @param wasAccessible 之前是否可访问
     * @param isAccessible 现在是否可访问
     */
    private static void updateChunkTracking(ChunkMap map, ServerPlayer player, ChunkPos chunkPos, MutableObject<ClientboundLevelChunkWithLightPacket> packetHolder, boolean wasAccessible, boolean isAccessible) {
 /*       if (getLevel(player) != map.level) return;
        try {
            if (isAccessible && !wasAccessible) {
                Field visibleChunkMapField = ChunkMap.class.getDeclaredField("f_140130_");
                visibleChunkMapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Long, ChunkHolder> visibleChunkMap = (Map<Long, ChunkHolder>) visibleChunkMapField.get(map);
                ChunkHolder chunkHolder = visibleChunkMap.get(chunkPos.toLong());
                if (chunkHolder != null) {
                    LevelChunk chunk = chunkHolder.getTickingChunk();
                    if (chunk != null) playerLoadedChunk(map, player, packetHolder, chunk);
                    DebugPackets.sendPoiPacketsForChunk(map.level, chunkPos);
                }
            }
            if (!isAccessible && wasAccessible && player.isAlive()) player.connection.send(new ClientboundForgetLevelChunkPacket(chunkPos));
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); }
        */
    }
    
    /**
     * 玩家加载区块时的处理
     * @param map 区块映射
     * @param player 玩家
     * @param packetHolder 数据包持有者
     * @param chunk 区块
     */
    private static void playerLoadedChunk(ChunkMap map, ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetHolder, LevelChunk chunk) {
        try {
            if (packetHolder.getValue() == null) packetHolder.setValue(new ClientboundLevelChunkWithLightPacket(chunk, map.lightEngine, null, null));
            player.connection.send(packetHolder.getValue());
            DebugPackets.sendPoiPacketsForChunk(map.level, chunk.getPos());
            List<Entity> leashList = new ArrayList<>();
            List<Entity> passengerList = new ArrayList<>();
            for (ChunkMap.TrackedEntity trackedEntity : map.entityMap.values()) {
                Entity entity = trackedEntity.entity;
                if (entity != player && entity.chunkPosition().equals(chunk.getPos())) {
                    trackedEntity.updatePlayer(player);
                    if (entity instanceof Mob mob && mob.getLeashHolder() != null) leashList.add(entity);
                    if (!entity.getPassengers().isEmpty()) passengerList.add(entity);
                }
            }
            for (Entity entity : leashList) player.connection.send(new ClientboundSetEntityLinkPacket(entity, ((Mob) entity).getLeashHolder()));
            for (Entity entity : passengerList) player.connection.send(new ClientboundSetPassengersPacket(entity));
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); }
    }
    
    /**
     * 终极删除V2版本 - 彻底删除单个实体
     * 结合多种删除方法确保实体被完全清除
     * 这是最常用的实体删除方法，平衡了效率和安全性
     * @param entity 要删除的实体
     */
    public static void ultimateKillV2(Entity entity) {
        // 空值检查
        if (entity == null) return;
        
        // 检查是否应该跳过此实体（如mod自己的实体）
        if (shouldSkip(entity)) return;
        
        // 获取实体所在的等级
        Level level = getLevel(entity);
        
        // 设置移除原因为“被击杀”
        setRemovalReason(entity, Entity.RemovalReason.KILLED);
        
        // 如果是生物实体，执行生物特定的死亡处理
        if (entity instanceof LivingEntity le) {
            // 直接设置生命值为负数，确保实体死亡
            setHealthDirect(le, -999999.0F);
            
            // 触发实体的死亡逻辑，使用通用击杀伤害源
            le.die(le.damageSources().genericKill());
            
            // 设置死亡时间为20刻（1秒），这是Minecraft标准的死亡动画时间
            le.deathTime = 20;
            
            // 标记实体为已死亡状态
            le.dead = true;
            
            // 触发实体死亡的游戏事件，通知周围的游戏机制
            le.gameEvent(GameEvent.ENTITY_DIE);
        }
        
        // 如果是服务器等级，执行完整的服务器端移除
        if (level instanceof ServerLevel) {
            serverCompleteRemove(entity);
        }
        
        // 调用Minecraft标准的移除方法
        entity.remove(Entity.RemovalReason.KILLED);
        
        // 丢弃实体，标记为待垃圾回收
        entity.discard();
        
        // 如果实体有能力系统，使其失效
        if (entity.getCapabilities() != null) {
            entity.invalidateCaps();
        }
        
        // 发布Forge事件，通知其他mod该实体离开了世界
        MinecraftForge.EVENT_BUS.post(new EntityLeaveLevelEvent(entity, level));
        
        // 最后调用kill方法确保实体被杀死
        entity.kill();
    }
    

    public static void ultimateKillAllV2(Level level, double x, double y, double z, double radius, boolean includePlayer) {

        level.getEntities(null, new net.minecraft.world.phys.AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius)).forEach(entity -> {
            if (shouldSkip(entity)) return;
            if (!includePlayer && entity instanceof Player) return;
            ultimateKillV2(entity);
        });
    }
    

    public static boolean completeRemove(Entity entity, Level level) {
        // 空值检查
        if (entity == null || level == null) return false;
        
        try {
            // 如果移除原因未设置，设置为“被击杀”
            if (entity.getRemovalReason() == null) {
                entity.removalReason = Entity.RemovalReason.KILLED;
            }
            
            // 调用标准移除方法
            entity.remove(Entity.RemovalReason.KILLED);
            
            // 如果移除原因要求销毁，则丢弃实体
            if (entity.getRemovalReason().shouldDestroy()) {
                entity.discard();
            }
            
            // 丢弃所有乘客（骑乘该实体的其他实体）
            entity.getPassengers().forEach(Entity::discard);
            
            // 如果是生物实体，执行深度击杀
            if (entity instanceof LivingEntity le) {

                deepEntityKill(le);  // 执行深度击杀
            }
            
            // 如果是服务器等级，执行服务器端的清理工作（必须在移虚空之前！
            // deleteFromPersistentManager 用 entity.blockPosition() 找 section，
            // 先移虚空会导致 section==null、从 section/chunk 的移除全部跳过 → 存档残留 → 退出重进复活）
            if (level instanceof ServerLevel serverLevel) {
                clearBossEvents(entity, serverLevel);              // 清除Boss事件
                removeFromChunkMap(entity, serverLevel);           // 从区块映射中移除
                deleteFromPersistentManager(entity, serverLevel);  // 从持久化管理器中删除
                handleSpecialEntityTypes(entity, serverLevel);     // 处理特殊实体类型
                
                // 更新动态游戏事件监听器
                entity.updateDynamicGameEventListener(DynamicGameEventListener::remove);
                
                // 调用实体从世界移除的回调
                entity.onRemovedFromWorld();
                
                // 设置等级回调为空
                entity.setLevelCallback(EntityInLevelCallback.NULL);
                
                // 发布Forge事件
                MinecraftForge.EVENT_BUS.post(new EntityLeaveLevelEvent(entity, serverLevel));
                
                // 强制从服务器实体列表中移除
                forceRemoveFromServerEntityList(entity, serverLevel);
            }
            
            // 服务器清理完成后，非玩家实体移动到虚空位置
            if (!(entity instanceof Player)) {
                setEntityToVoid(entity);
            }
            
            // 如果是客户端，执行客户端侧的移除
            if (level.isClientSide) {
                handleClientSideRemoval(entity, level);
            }
            
            // 使实体能力失效
            if (entity.getCapabilities() != null) {
                entity.invalidateCaps();
            }
            
            // 从Forge注册表中清除
            clearFromForgeRegistries(entity, level);
            
            // 再次调用移除方法，确保万无一失
            entity.remove(Entity.RemovalReason.KILLED);
            entity.removalReason = Entity.RemovalReason.KILLED;
            
            // 再次丢弃所有乘客
            entity.getPassengers().forEach(Entity::discard);
            
            return true;  // 成功
        } catch (Exception e) { 
            log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); 
            e.printStackTrace(); 
            return false;  // 失败
        }
    }
    

    public static boolean deepEntityKill(LivingEntity entity) {
        try {
            
            // 使用标准API设置生命值为负数
            entity.setHealth(-999999.0F);
            
            // 对实体造成最大伤害，使用通用击杀伤害源
            entity.hurt(entity.damageSources().genericKill(), Float.MAX_VALUE);
            
            // 触发实体死亡逻辑
            entity.die(entity.damageSources().genericKill());
            
            // 设置死亡时间为20刻（1秒）
            entity.deathTime = 20;
            
            // 标记为已死亡
            entity.dead = true;
            
            // 禁用无重力效果，让实体正常下落
            entity.setNoGravity(false);
            
            // 重置掉落距离
            entity.fallDistance = 0;
            
            // 先取消无敌状态，确保可以受到伤害
            entity.setInvulnerable(false);
            
            // 清除火焰效果
            entity.clearFire();
            entity.setRemainingFireTicks(0);
            
            // 触发实体死亡的游戏事件
            entity.gameEvent(GameEvent.ENTITY_DIE);
            
            // 清除自定义名称
            entity.setCustomName(null);
            
            // 重新设置为无敌状态（防止其他伤害干扰）
            entity.setInvulnerable(true);
            
            // 调用kill方法
            entity.kill();
            
            return true;  // 成功
        } catch (Exception e) { 
            log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); 
            e.printStackTrace(); 
            return false;  // 失败
        }
    }

    public static boolean unsafeDeepKill(LivingEntity entity) {
        // 检查Unsafe和偏移量是否已初始化
        if (unsafe == null || healthOffset == -1) return false;
        
        try {
            // 通过实体数据同步器设置生命值为极小的负数
            entity.getEntityData().set(DATA_HEALTH_ACCESSOR, -99999999.0F);
            
            // 通过反射获取deathTime字段（f_20919_）
            Field deathTimeField = LivingEntity.class.getDeclaredField("f_20919_");
            long deathTimeOffset = unsafe.objectFieldOffset(deathTimeField);  // 计算内存偏移
            unsafe.putInt(entity, deathTimeOffset, 20);  // 直接写入内存，设置死亡时间为20
            
            // 通过反射获取dead字段（f_20890_）
            Field deadField = LivingEntity.class.getDeclaredField("f_20890_");
            long deadOffset = unsafe.objectFieldOffset(deadField);  // 计算内存偏移
            unsafe.putBoolean(entity, deadOffset, true);  // 直接写入内存，设置dead为true
            
            // 调用kill方法
            entity.kill();
            
            return true;  // 成功
        } catch (Exception e) { 
            log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); 
            e.printStackTrace(); 
            return false;  // 失败
        }
    }
    

    public static boolean forceRemoveFromServerEntityList(Entity entity, ServerLevel serverLevel) {
        try {
            for (ServerLevel level : serverLevel.getServer().getAllLevels()) clearEntityFromAllServerLevelCollections(entity, level);
            serverLevel.getServer().execute(() -> {
                if (entity.level() instanceof ServerLevel sl) sl.entityManager.sectionStorage.remove(entity.getId());
            });
            return true;
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); return false; }
    }
    

    private static boolean clearEntityFromAllServerLevelCollections(Entity entity, ServerLevel level) {
        try {
            level.getEntities().get(entity.getId());
            level.players().remove(entity);
            if (entity instanceof Mob mob) level.navigatingMobs.remove(mob);
            if (entity.isMultipartEntity()) {
                for (PartEntity<?> part : entity.getParts()) level.dragonParts.remove(part.getId());
            }
            for (Field field : ServerLevel.class.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(level);
                    if (value instanceof Map<?, ?> map) {
                        map.remove(entity.getId());
                        map.remove(entity.getUUID());
                        map.values().remove(entity);
                    } else if (value instanceof Collection<?> collection) collection.remove(entity);
                } catch (Exception ignored) {}
            }
            return true;
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); return false; }
    }
    

    private static boolean clearFromForgeRegistries(Entity entity, Level level) {
        try {
            for (Field field : Level.class.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(level);
                    if (value instanceof Map<?, ?> map) {
                        map.remove(entity.getId());
                        map.remove(entity.getUUID());
                        map.values().remove(entity);
                    } else if (value instanceof Collection<?> collection) collection.remove(entity);
                } catch (Exception ignored) {}
            }
            if (level instanceof ServerLevel serverLevel) clearServerLevelForgeData(entity, serverLevel);
            return true;
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); return false; }
    }
    

    private static boolean clearServerLevelForgeData(Entity entity, ServerLevel serverLevel) {
        try {
            for (Field field : ServerLevel.class.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(serverLevel);
                    if (value instanceof Map<?, ?> map) {
                        map.remove(entity.getId());
                        map.remove(entity.getUUID());
                        map.values().remove(entity);
                    } else if (value instanceof Collection<?> collection) collection.remove(entity);
                } catch (Exception ignored) {}
            }
            PersistentEntitySectionManager<Entity> manager = serverLevel
            .entityManager;
            clearEntityManagerCompletely(entity, manager);
            return true;
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); return false; }
    }
    

    private static boolean clearEntityManagerCompletely(Entity entity, PersistentEntitySectionManager<Entity> manager) {
        try {
            for (Field field : PersistentEntitySectionManager.class.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(manager);
                    if (value instanceof Map<?, ?> map) {
                        map.remove(entity.getId());
                        map.remove(entity.getUUID());
                        map.values().remove(entity);
                    } else if (value instanceof Collection<?> collection) collection.remove(entity);
                } catch (Exception ignored) {}
            }
            return true;
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); return false; }
    }
    

    private static boolean removeFromChunkMap(Entity entity, ServerLevel serverLevel) {
        try {
            ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;
            if (entity instanceof ServerPlayer serverPlayer) {
                for (ChunkMap.TrackedEntity trackedEntity : chunkMap.entityMap.values()) {
                    if (trackedEntity.seenBy.remove(serverPlayer.connection)) {
                        try {
                            serverPlayer.connection.send((Packet<?>) lookup.findConstructor(ClientboundRemoveEntitiesPacket.class, MethodType.methodType(void.class, int[].class)).invoke(trackedEntity.serverEntity.entity.getId()));
                        } catch (Throwable ignored) {}
                    }
                }
            }
            ChunkMap.TrackedEntity trackedEntity = chunkMap.entityMap.remove(entity.getId());
            if (trackedEntity != null) {
                ClientboundRemoveEntitiesPacket removePacket = new ClientboundRemoveEntitiesPacket(trackedEntity.entity.getId());
                for (ServerPlayerConnection connection : trackedEntity.seenBy) connection.getPlayer().connection.send(removePacket);
            }
            return true;
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); return false; }
    }
    

    private static boolean clearBossEvents(Entity entity, ServerLevel serverLevel) {
        try {
            for (Field field : getAllFieldsInHierarchy(entity.getClass())) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(entity);
                    if (value instanceof ServerBossEvent bossEvent) {
                        ClientboundBossEventPacket removePacket = ClientboundBossEventPacket.createRemovePacket(bossEvent.getId());
                        serverLevel.players().forEach(player -> player.connection.send(removePacket));
                        bossEvent.removeAllPlayers();
                        if (canSafelySetToNull(entity)) field.set(entity, null);
                    }
                } catch (Exception ignored) {}
            }
            return true;
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); return false; }
    }
    

    private static boolean deleteFromPersistentManager(Entity entity, ServerLevel serverLevel) {
        try {
            PersistentEntitySectionManager<Entity> manager = serverLevel.entityManager;
            EntitySection<Entity> section = manager.sectionStorage.getSection(SectionPos.asLong(entity.blockPosition()));
            if (section == null) return false;
            removeEntityFromSection(entity, section.storage);
            entity.levelCallback.onRemove(Entity.RemovalReason.KILLED);
            manager.sectionStorage.remove(entity.getId());
            manager.knownUuids.remove(entity.getUUID());
            serverLevel.getChunkSource().removeEntity(entity);
            entity.setLevelCallback(EntityInLevelCallback.NULL);
            manager.updateChunkStatus(new ChunkPos(entity.blockPosition()), section.chunkStatus);
            return true;
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); return false; }
    }

    public static <T> boolean removeEntityFromSection(Entity entity, ClassInstanceMultiMap<T> multiMap) {
        try {
            for (Map.Entry<Class<?>, List<T>> entry : multiMap.byClass.entrySet()) {
                if (entry.getKey().isInstance(entity)) entry.getValue().remove(entity);
            }
            return true;
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); return false; }
    }
    

    private static boolean handleSpecialEntityTypes(Entity entity, ServerLevel serverLevel) {
        try {
            if (entity instanceof ServerPlayer serverPlayer) {
                serverLevel.players().remove(serverPlayer);
                serverLevel.updateSleepingPlayerList();
            }
            if (entity instanceof Mob mob) serverLevel.navigatingMobs.remove(mob);
            if (entity.isMultipartEntity()) {
                for (PartEntity<?> part : entity.getParts()) serverLevel.dragonParts.remove(part.getId());
            }
            return true;
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); return false; }
    }
    

    private static void handleClientSideRemoval(Entity entity, Level level) {
        try {
            entity.remove(Entity.RemovalReason.KILLED);
            entity.setRemoved(Entity.RemovalReason.KILLED);
            if (entity instanceof LivingEntity le) le.deathTime = 20;
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); }
    }
    

    private static void setEntityToVoid(Entity entity) {
        try {
            if (entity.blockPosition == null || entity.position == null) return;
            entity.setPos(0, -999999, 0);
            entity.teleportTo(0, -999999, 0);
            entity.moveTo(0, -999999, 0);
        } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); e.printStackTrace(); }
    }
    

    private static Field[] getAllFieldsInHierarchy(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            try {
                fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
                clazz = clazz.getSuperclass();
            } catch (Exception e) { log.error("[AdvancedKillUtils]出现了报错" + e + e.getMessage()); break; }
        }
        return fields.toArray(new Field[0]);
    }
    

    private static boolean canSafelySetToNull(Entity entity) {
        return entity.isRemoved() || !entity.isAlive();
    }
    

    public static void ultimateKill(Entity entity) {
        // 空值检查
        if (entity == null) return;
        
        // 检查是否应该跳过此实体
        if (shouldSkip(entity)) return;
        
        // 获取实体所在的等级
        Level level = entity.level();
        
        // 如果是生物实体，执行深度击杀和不安全击杀
        if (entity instanceof LivingEntity le) {
            deepEntityKill(le);      // 深度击杀：使用标准API
            unsafeDeepKill(le);      // 不安全击杀：直接操作内存
        }

        // 执行完整的移除流程
        completeRemove(entity, level);
        
        // 执行V2版本的终极删除
        ultimateKillV2(entity);
        
        // 清除实体渲染器（来自EntityMaker工具类）
        EntityMaker.fuckEntityRenderer(entity);
        
        // 清除所有Boss血条覆盖层
        EntityMaker.fuckAllBossOverlay();
        
        // 完全清除实体（来自EntityMaker工具类）
        EntityMaker.fuckEntityCompletely(entity);

        
        // 如果实体仍未被移除，再次尝试标准方法
        if (!entity.isRemoved()) {
            entity.remove(Entity.RemovalReason.KILLED);
            entity.discard();
            entity.kill();
        }
    }


    public static void ultimateKillAll(Level level, double x, double y, double z, double radius, boolean includePlayer) {
        level.getEntities(null, new net.minecraft.world.phys.AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius)).forEach(entity -> {
            if (entity instanceof LivingEntity living) {
                if (shouldSkip(entity)) return;
                if (!includePlayer && entity instanceof Player) return;
                ultimateKill(entity);
            }
        });
    }
}