package com.ryjs.reflection.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;


public class EntityMaker {
    
    private static final Logger LOGGER = LogManager.getLogger();
    
    // ==================== 原有标记集合 ====================
    private static final Set<Entity> DEATH_MARKED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Entity> REMOVED_MARKED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Entity> NO_HEALTH_MARKED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Entity> DATA_HEALTH_MARKED = Collections.newSetFromMap(new WeakHashMap<>());
    
    // ==================== 渲染拦截标记集合 ====================
    /** 标记为禁止渲染的实体 */
    private static final Set<Entity> RENDER_BLOCKED = Collections.newSetFromMap(new WeakHashMap<>());
    
    /** 全局渲染开关 - 当为true时，拦截所有实体渲染 */
    private static volatile boolean BLOCK_ALL_RENDERING = false;
    
    /** 全局渲染拦截 - 拦截所有渲染阶段事件 */
    private static volatile boolean BLOCK_ALL_RENDER_STAGES = false;
    
    private static volatile boolean BLOCK_ALL_RENDER_STAGES_EVENT = false;
    
    /** 自动拦截被标记实体的渲染 - 如果实体被死亡/移除标记，自动阻止其渲染 */
    private static volatile boolean AUTO_BLOCK_MARKED_ENTITIES = true;
    
    // ==================== Boss事件拦截标记 ====================
    /** 拦截Boss血条渲染 */
    private static volatile boolean BLOCK_BOSS_BAR_RENDER = false;
    
    /** 拦截Boss音乐播放 */
    private static volatile boolean BLOCK_BOSS_MUSIC = false;
    
    /** 拦截Boss屏幕变暗效果 */
    private static volatile boolean BLOCK_BOSS_DARKEN_SCREEN = false;
    
    /** 拦截Boss迷雾效果 */
    private static volatile boolean BLOCK_BOSS_FOG = false;
    
    /** 拦截所有Boss事件相关的GUI叠加层 */
    private static volatile boolean BLOCK_BOSS_OVERLAY = false;
    
    // ==================== AI和Tick拦截标记 ====================
    /** 标记为禁止AI的实体 */
    private static final Set<Entity> AI_BLOCKED = Collections.newSetFromMap(new WeakHashMap<>());
    
    /** 标记为禁止Tick的实体 */
    private static final Set<Entity> TICK_BLOCKED = Collections.newSetFromMap(new WeakHashMap<>());
    
    /** 标记为禁止BaseTick的实体 */
    private static final Set<Entity> BASE_TICK_BLOCKED = Collections.newSetFromMap(new WeakHashMap<>());
    
    /** 全局AI拦截开关 */
    private static volatile boolean BLOCK_ALL_AI = false;
    
    /** 全局Tick拦截开关 */
    private static volatile boolean BLOCK_ALL_TICK = false;
    
    /** 全局BaseTick拦截开关 */
    private static volatile boolean BLOCK_ALL_BASE_TICK = false;
    
    private static volatile Set<Entity> BLOCK_ENTITY_ALL_RENDER = Collections.newSetFromMap(new WeakHashMap<>());;
    
    private static volatile boolean BLOCK_ALL_BOSS_MUSIC = false;
    
    /** 自动拦截被标记实体的AI */
    private static volatile boolean AUTO_BLOCK_MARKED_AI = true;
    
    /** 自动拦截被标记实体的Tick */
    private static volatile boolean AUTO_BLOCK_MARKED_TICK = true;
    
    // ==================== 原有标记方法 ====================
    
    public static void makeEntityDeath(Entity entity) {
        if (entity != null) {
            DEATH_MARKED.add(entity);
            LOGGER.debug("Entity marked as DEATH: {}", entity);
        }
    }
    
    public static void makeEntityRemoved(Entity entity) {
        if (entity != null) {
            REMOVED_MARKED.add(entity);
            LOGGER.debug("Entity marked as REMOVED: {}", entity);
        }
    }
    
    public static void makeEntityNoHealth(LivingEntity le) {
        if (le != null) {
            NO_HEALTH_MARKED.add(le);
            LOGGER.debug("LivingEntity marked as NO_HEALTH: {}", le);
        }
    }
    
    public static void makeEntityDataHealth(LivingEntity le) {
        if (le != null) {
            DATA_HEALTH_MARKED.add(le);
            LOGGER.debug("LivingEntity marked as DATA_HEALTH_ZERO: {}", le);
        }
    }
    
    public static void markAllDeath(Entity entity) {
        makeEntityDeath(entity);
        makeEntityRemoved(entity);
        if (entity instanceof LivingEntity le) {
            makeEntityNoHealth(le);
            makeEntityDataHealth(le);
        }
        LOGGER.info("Entity fully marked for termination: {}", entity);
    }
    
    // ==================== 渲染拦截标记方法 ====================
    
    /**
     * 标记指定实体禁止渲染
     */
    public static void fuckEntityRenderer(Entity entity) {
        if (entity != null) {
            RENDER_BLOCKED.add(entity);
            LOGGER.info("Entity rendering BLOCKED: {} (Type: {})", 
                entity, entity.getClass().getSimpleName());
        }
    }
    
    /**
     * 全局拦截所有渲染
     */
    public static void fuckAllRenderer() {
        BLOCK_ALL_RENDERING = true;
        LOGGER.warn("ALL entity rendering has been BLOCKED globally!");
    }
    
    public static void fuckEntityAllRender(Entity e) {
        if(e == null) return;
        BLOCK_ENTITY_ALL_RENDER.add(e);
        LOGGER.warn("Entity ALL rendering has been BLOCKED globally!");
    }
    
    public static boolean shouldBlockEntityAllRender(Entity e) {
        return BLOCK_ENTITY_ALL_RENDER.contains(e);
    }
    
    
    public static void fuckAllBossMusic() {
        BLOCK_ALL_BOSS_MUSIC = true;
        LOGGER.warn("ALL boss music has been BLOCKED globally!");
    }
    
    public static void fuckAllRenderStageEvent() {
        BLOCK_ALL_RENDER_STAGES_EVENT = true;
        LOGGER.warn("ALL entity rendering event has been BLOCKED globally!");
    }
    
    /**
     * 恢复指定实体的渲染
     */
    public static void unfuckEntityRenderer(Entity entity) {
        if (entity != null && RENDER_BLOCKED.remove(entity)) {
            LOGGER.info("Entity rendering RESTORED: {}", entity);
        }
    }
    
    /**
     * 恢复所有实体的渲染
     */
    public static void unfuckAllRenderer() {
        BLOCK_ALL_RENDERING = false;
        LOGGER.info("Global entity rendering block LIFTED");
    }
    
    /**
     * 拦截所有渲染阶段事件
     */
    public static void fuckAllRenderStages() {
        BLOCK_ALL_RENDER_STAGES = true;
        LOGGER.warn("ALL render stage events have been BLOCKED!");
    }
    
    /**
     * 恢复所有渲染阶段事件
     */
    public static void unfuckAllRenderStages() {
        BLOCK_ALL_RENDER_STAGES = false;
        LOGGER.info("All render stage events RESTORED");
    }
    
    /**
     * 设置是否自动阻止被标记实体的渲染
     */
    public static void setAutoBlockMarkedEntities(boolean enable) {
        AUTO_BLOCK_MARKED_ENTITIES = enable;
        LOGGER.info("Auto-block marked entities rendering: {}", enable ? "ENABLED" : "DISABLED");
    }
    
    // ==================== Boss事件拦截方法 ====================
    
    /**
     * 拦截Boss血条渲染
     */
    public static void fuckBossBar() {
        BLOCK_BOSS_BAR_RENDER = true;
        LOGGER.warn("Boss bar rendering has been BLOCKED!");
    }
    
    /**
     * 恢复Boss血条渲染
     */
    public static void unfuckBossBar() {
        BLOCK_BOSS_BAR_RENDER = false;
        LOGGER.info("Boss bar rendering RESTORED");
    }
    
    /**
     * 拦截Boss音乐
     */
    public static void fuckBossMusic() {
        BLOCK_BOSS_MUSIC = true;
        LOGGER.warn("Boss music has been BLOCKED!");
    }
    
    /**
     * 恢复Boss音乐
     */
    public static void unfuckBossMusic() {
        BLOCK_BOSS_MUSIC = false;
        LOGGER.info("Boss music RESTORED");
    }
    
    /**
     * 拦截Boss屏幕变暗
     */
    public static void fuckBossDarken() {
        BLOCK_BOSS_DARKEN_SCREEN = true;
        LOGGER.warn("Boss darken screen has been BLOCKED!");
    }
    
    /**
     * 恢复Boss屏幕变暗
     */
    public static void unfuckBossDarken() {
        BLOCK_BOSS_DARKEN_SCREEN = false;
        LOGGER.info("Boss darken screen RESTORED");
    }
    
    /**
     * 拦截Boss迷雾
     */
    public static void fuckBossFog() {
        BLOCK_BOSS_FOG = true;
        LOGGER.warn("Boss fog has been BLOCKED!");
    }
    
    /**
     * 恢复Boss迷雾
     */
    public static void unfuckBossFog() {
        BLOCK_BOSS_FOG = false;
        LOGGER.info("Boss fog RESTORED");
    }
    
    /**
     * 拦截所有Boss叠加层
     */
    public static void fuckAllBossOverlay() {
        BLOCK_BOSS_OVERLAY = true;
        BLOCK_BOSS_BAR_RENDER = true;
        BLOCK_BOSS_MUSIC = true;
        BLOCK_BOSS_DARKEN_SCREEN = true;
        BLOCK_BOSS_FOG = true;
        LOGGER.warn("ALL Boss overlays and effects have been BLOCKED!");
    }
    
    /**
     * 恢复所有Boss叠加层
     */
    public static void unfuckAllBossOverlay() {
        BLOCK_BOSS_OVERLAY = false;
        BLOCK_BOSS_BAR_RENDER = false;
        BLOCK_BOSS_MUSIC = false;
        BLOCK_BOSS_DARKEN_SCREEN = false;
        BLOCK_BOSS_FOG = false;
        LOGGER.info("ALL Boss overlays and effects RESTORED");
    }
    
    // ==================== AI拦截方法 ====================
    
    /**
     * 标记指定实体禁止AI
     */
    public static void fuckEntityAI(Entity entity) {
        if (entity != null) {
            AI_BLOCKED.add(entity);
            LOGGER.info("Entity AI BLOCKED: {} (Type: {})", 
                entity, entity.getClass().getSimpleName());
        }
    }
    
    /**
     * 恢复指定实体的AI
     */
    public static void unfuckEntityAI(Entity entity) {
        if (entity != null && AI_BLOCKED.remove(entity)) {
            LOGGER.info("Entity AI RESTORED: {}", entity);
        }
    }
    
    /**
     * 全局拦截所有AI
     */
    public static void fuckAllAI() {
        BLOCK_ALL_AI = true;
        LOGGER.warn("ALL entity AI has been BLOCKED globally!");
    }
    
    /**
     * 恢复所有AI
     */
    public static void unfuckAllAI() {
        BLOCK_ALL_AI = false;
        LOGGER.info("Global entity AI block LIFTED");
    }
    
    /**
     * 设置是否自动阻止被标记实体的AI
     */
    public static void setAutoBlockMarkedAI(boolean enable) {
        AUTO_BLOCK_MARKED_AI = enable;
        LOGGER.info("Auto-block marked entities AI: {}", enable ? "ENABLED" : "DISABLED");
    }
    
    // ==================== Tick拦截方法 ====================
    
    /**
     * 标记指定实体禁止Tick
     */
    public static void fuckEntityTick(Entity entity) {
        if (entity != null) {
            TICK_BLOCKED.add(entity);
            LOGGER.info("Entity Tick BLOCKED: {} (Type: {})", 
                entity, entity.getClass().getSimpleName());
        }
    }
    
    /**
     * 恢复指定实体的Tick
     */
    public static void unfuckEntityTick(Entity entity) {
        if (entity != null && TICK_BLOCKED.remove(entity)) {
            LOGGER.info("Entity Tick RESTORED: {}", entity);
        }
    }
    
    /**
     * 全局拦截所有Tick
     */
    public static void fuckAllTick() {
        BLOCK_ALL_TICK = true;
        LOGGER.warn("ALL entity Tick has been BLOCKED globally!");
    }
    
    /**
     * 恢复所有Tick
     */
    public static void unfuckAllTick() {
        BLOCK_ALL_TICK = false;
        LOGGER.info("Global entity Tick block LIFTED");
    }
    
    /**
     * 设置是否自动阻止被标记实体的Tick
     */
    public static void setAutoBlockMarkedTick(boolean enable) {
        AUTO_BLOCK_MARKED_TICK = enable;
        LOGGER.info("Auto-block marked entities Tick: {}", enable ? "ENABLED" : "DISABLED");
    }
    
    // ==================== BaseTick拦截方法 ====================
    
    /**
     * 标记指定实体禁止BaseTick
     */
    public static void fuckEntityBaseTick(Entity entity) {
        if (entity != null) {
            BASE_TICK_BLOCKED.add(entity);
            LOGGER.info("Entity BaseTick BLOCKED: {} (Type: {})", 
                entity, entity.getClass().getSimpleName());
        }
    }
    
    /**
     * 恢复指定实体的BaseTick
     */
    public static void unfuckEntityBaseTick(Entity entity) {
        if (entity != null && BASE_TICK_BLOCKED.remove(entity)) {
            LOGGER.info("Entity BaseTick RESTORED: {}", entity);
        }
    }
    
    /**
     * 全局拦截所有BaseTick
     */
    public static void fuckAllBaseTick() {
        BLOCK_ALL_BASE_TICK = true;
        LOGGER.warn("ALL entity BaseTick has been BLOCKED globally!");
    }
    
    /**
     * 恢复所有BaseTick
     */
    public static void unfuckAllBaseTick() {
        BLOCK_ALL_BASE_TICK = false;
        LOGGER.info("Global entity BaseTick block LIFTED");
    }

    public static void fuckEntityCompletely(Entity entity) {
        if (entity != null) {
            markAllDeath(entity);
            fuckEntityRenderer(entity);
            fuckEntityAI(entity);
            fuckEntityTick(entity);
            fuckEntityBaseTick(entity);
            LOGGER.warn("Entity COMPLETELY FUCKED: {} - All functions disabled!", entity);
        }
    }
    
    // ==================== 渲染判断方法 ====================

    public static boolean shouldBlockEntityRender(Entity entity) {
        if (entity == null) {
            return false;
        }
        
        if(entity instanceof Player) return false;
        
        // 1. 全局拦截优先
        if (BLOCK_ALL_RENDERING) {
            return true;
        }
        
        // 2. 检查单独的渲染阻止标记
        if (RENDER_BLOCKED.contains(entity)) {
            return true;
        }
        
        // 3. 如果启用了自动阻止，检查实体是否被标记为死亡/移除
        if (AUTO_BLOCK_MARKED_ENTITIES && isMarked(entity)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查是否应该阻止所有实体渲染
     */
    public static boolean shouldBlockAllRender() {
        return BLOCK_ALL_RENDERING;
    }
    
    /**
     * 检查是否应该阻止所有渲染阶段事件
     */
    public static boolean shouldBlockRenderStage() {
        return BLOCK_ALL_RENDER_STAGES;
    }
    
    public static boolean shouldBlockRenderStageEvent() {
        return BLOCK_ALL_RENDER_STAGES_EVENT;
    }
    
    /**
     * 检查实体是否被标记为不可渲染
     */
    public static boolean isRenderBlocked(Entity entity) {
        return entity != null && RENDER_BLOCKED.contains(entity);
    }
    
    // ==================== Boss事件判断方法 ====================
    
    /**
     * 检查是否应该阻止Boss血条渲染
     */
    public static boolean shouldBlockBossBar() {
        return BLOCK_BOSS_BAR_RENDER || BLOCK_BOSS_OVERLAY;
    }
    
    /**
     * 检查是否应该阻止Boss音乐
     */
    public static boolean shouldBlockBossMusic() {
        return BLOCK_BOSS_MUSIC || BLOCK_BOSS_OVERLAY;
    }
    
    /**
     * 检查是否应该阻止Boss屏幕变暗
     */
    public static boolean shouldBlockBossDarken() {
        return BLOCK_BOSS_DARKEN_SCREEN || BLOCK_BOSS_OVERLAY;
    }
    
    /**
     * 检查是否应该阻止Boss迷雾
     */
    public static boolean shouldBlockBossFog() {
        return BLOCK_BOSS_FOG || BLOCK_BOSS_OVERLAY;
    }
    
    /**
     * 检查是否应该阻止Boss叠加层事件
     * 用于拦截CustomizeGuiOverlayEvent.BossEventProgress
     */
    public static boolean shouldBlockBossOverlayEvent() {
        return BLOCK_BOSS_OVERLAY;
    }
    
    // ==================== AI判断方法 ====================
    
    /**
     * 检查实体AI是否应该被阻止
     */
    public static boolean shouldBlockEntityAI(Entity entity) {
        if (entity == null) {
            return false;
        }
        
        if(entity instanceof Player) return false;
        
        // 1. 全局拦截优先
        if (BLOCK_ALL_AI) {
            return true;
        }
        
        // 2. 检查单独的AI阻止标记
        if (AI_BLOCKED.contains(entity)) {
            return true;
        }
        
        // 3. 如果启用了自动阻止，检查实体是否被标记
        if (AUTO_BLOCK_MARKED_AI && isMarked(entity)) {
            return true;
        }
        
        return false;
    }
    
    // ==================== Tick判断方法 ====================
    
    /**
     * 检查实体Tick是否应该被阻止
     */
    public static boolean shouldBlockEntityTick(Entity entity) {
        if (entity == null) {
            return false;
        }
        
        if(entity instanceof Player) return false;
        
        // 1. 全局拦截优先
        if (BLOCK_ALL_TICK) {
            return true;
        }
        
        // 2. 检查单独的Tick阻止标记
        if (TICK_BLOCKED.contains(entity)) {
            return true;
        }
        
        // 3. 如果启用了自动阻止，检查实体是否被标记
        if (AUTO_BLOCK_MARKED_TICK && isMarked(entity)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查实体BaseTick是否应该被阻止
     */
    public static boolean shouldBlockEntityBaseTick(Entity entity) {
        if (entity == null) {
            return false;
        }
        
        if(entity instanceof Player) return false;
        
        // 1. 全局拦截优先
        if (BLOCK_ALL_BASE_TICK) {
            return true;
        }
        
        // 2. 检查单独的BaseTick阻止标记
        if (BASE_TICK_BLOCKED.contains(entity)) {
            return true;
        }
        
        // 3. 如果启用了自动阻止，检查实体是否被标记
        if (isMarked(entity)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 综合判断 - 检查实体是否应该被完全跳过
     */
    public static boolean shouldSkipEntity(Entity entity) {
        return isMarked(entity) || shouldBlockEntityRender(entity);
    }
    
    // ==================== 原有判断方法 ====================
    
    public static boolean shouldDeath(Entity entity) {
        return entity != null && DEATH_MARKED.contains(entity);
    }
    
    public static boolean shouldRemoved(Entity entity) {
        return entity != null && REMOVED_MARKED.contains(entity);
    }
    
    public static boolean shouldNoHealth(LivingEntity le) {
        return le != null && NO_HEALTH_MARKED.contains(le);
    }
    
    public static boolean shouldDataHealth(LivingEntity le) {
        return le != null && DATA_HEALTH_MARKED.contains(le);
    }
    
    /**
     * 检查实体是否被任意方式标记
     */
    public static boolean isMarked(Entity entity) {
        if (entity == null) return false;
        return shouldDeath(entity) || shouldRemoved(entity) || 
               (entity instanceof LivingEntity le && 
                (shouldNoHealth(le) || shouldDataHealth(le)));
    }
    
    // ==================== 清理方法 ====================
    
    public static void clearMark(Entity entity) {
        if (entity != null) {
            DEATH_MARKED.remove(entity);
            REMOVED_MARKED.remove(entity);
            NO_HEALTH_MARKED.remove(entity);
            DATA_HEALTH_MARKED.remove(entity);
            RENDER_BLOCKED.remove(entity);
            AI_BLOCKED.remove(entity);
            TICK_BLOCKED.remove(entity);
            BASE_TICK_BLOCKED.remove(entity);
            LOGGER.debug("All marks cleared for entity: {}", entity);
        }
    }
    
    public static void clearAllMarks() {
        DEATH_MARKED.clear();
        REMOVED_MARKED.clear();
        NO_HEALTH_MARKED.clear();
        DATA_HEALTH_MARKED.clear();
        RENDER_BLOCKED.clear();
        AI_BLOCKED.clear();
        TICK_BLOCKED.clear();
        BASE_TICK_BLOCKED.clear();
        BLOCK_ALL_RENDERING = false;
        BLOCK_ALL_RENDER_STAGES = false;
        BLOCK_ALL_AI = false;
        BLOCK_ALL_TICK = false;
        BLOCK_ALL_BASE_TICK = false;
        LOGGER.info("All entity marks and blocks cleared");
    }
    
    public static void clearRenderBlocks() {
        RENDER_BLOCKED.clear();
        BLOCK_ALL_RENDERING = false;
        BLOCK_ALL_RENDER_STAGES = false;
        LOGGER.info("All render blocks cleared");
    }
    
    public static void clearBossBlocks() {
        BLOCK_BOSS_BAR_RENDER = false;
        BLOCK_BOSS_MUSIC = false;
        BLOCK_BOSS_DARKEN_SCREEN = false;
        BLOCK_BOSS_FOG = false;
        BLOCK_BOSS_OVERLAY = false;
        LOGGER.info("All Boss blocks cleared");
    }
    
    public static void clearAIBlocks() {
        AI_BLOCKED.clear();
        BLOCK_ALL_AI = false;
        LOGGER.info("All AI blocks cleared");
    }
    
    public static void clearTickBlocks() {
        TICK_BLOCKED.clear();
        BASE_TICK_BLOCKED.clear();
        BLOCK_ALL_TICK = false;
        BLOCK_ALL_BASE_TICK = false;
        LOGGER.info("All Tick blocks cleared");
    }
    
    // ==================== 统计方法 ====================
    
    public static int getMarkedCount() {
        return DEATH_MARKED.size() + REMOVED_MARKED.size() + 
               NO_HEALTH_MARKED.size() + DATA_HEALTH_MARKED.size();
    }
    
    public static int getRenderBlockedCount() {
        return RENDER_BLOCKED.size();
    }
    
    public static int getAIBlockedCount() {
        return AI_BLOCKED.size();
    }
    
    public static int getTickBlockedCount() {
        return TICK_BLOCKED.size() + BASE_TICK_BLOCKED.size();
    }
    
    public static String getBlockStatus() {
        return String.format(
            "Blocks Status:\n" +
            "  Render: [Individual: %d, Global: %s, Stage: %s, Auto: %s]\n" +
            "  Boss: [Bar: %s, Music: %s, Darken: %s, Fog: %s, Overlay: %s]\n" +
            "  AI: [Individual: %d, Global: %s, Auto: %s]\n" +
            "  Tick: [Individual: %d, Global: %s, Auto: %s]\n" +
            "  Marks: %d",
            getRenderBlockedCount(),
            BLOCK_ALL_RENDERING ? "ON" : "OFF",
            BLOCK_ALL_RENDER_STAGES ? "ON" : "OFF",
            AUTO_BLOCK_MARKED_ENTITIES ? "ON" : "OFF",
            BLOCK_BOSS_BAR_RENDER ? "ON" : "OFF",
            BLOCK_BOSS_MUSIC ? "ON" : "OFF",
            BLOCK_BOSS_DARKEN_SCREEN ? "ON" : "OFF",
            BLOCK_BOSS_FOG ? "ON" : "OFF",
            BLOCK_BOSS_OVERLAY ? "ON" : "OFF",
            getAIBlockedCount(),
            BLOCK_ALL_AI ? "ON" : "OFF",
            AUTO_BLOCK_MARKED_AI ? "ON" : "OFF",
            getTickBlockedCount(),
            BLOCK_ALL_TICK ? "ON" : "OFF",
            AUTO_BLOCK_MARKED_TICK ? "ON" : "OFF",
            getMarkedCount()
        );
    }
}
