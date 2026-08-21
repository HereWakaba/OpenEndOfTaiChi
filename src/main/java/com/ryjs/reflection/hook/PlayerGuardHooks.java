package com.ryjs.reflection.hook;

import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.reflection.guard.PlayerGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;


public final class PlayerGuardHooks {

    private PlayerGuardHooks() {}


    @AsmHook(targetClass = "net/minecraft/world/entity/LivingEntity", targetMethod = "getHealth",
            targetAliases = "m_21223_", targetDescriptor = "()F",
            mode = HookMode.OVERRIDE, includeThis = true, includeSubclasses = true)
    public static float getHealth(LivingEntity entity) {
        if (PlayerGuard.isDoomed(entity)) {
            return 0.0F;
        }
        if (PlayerGuard.isProtected(entity)) {
            return 20.0F;
        }
        return entity.getEntityData().get(LivingEntity.DATA_HEALTH_ID);
    }

    /** 真实最大血量：锁定死亡恒 20；保护玩家无条件锁 20（血条比例恒满）。 */
    @AsmHook(targetClass = "net/minecraft/world/entity/LivingEntity", targetMethod = "getMaxHealth",
            targetAliases = "m_21233_", targetDescriptor = "()F",
            mode = HookMode.OVERRIDE, includeThis = true, includeSubclasses = true)
    public static float getMaxHealth(LivingEntity entity) {
        if (PlayerGuard.isDoomed(entity)) {
            return 20.0F;
        }
        if (PlayerGuard.isProtected(entity)) {
            return 20.0F;
        }
        return (float) entity.getAttributeValue(Attributes.MAX_HEALTH);
    }

    /** 保护玩家恒"存活"；锁定死亡恒"死亡"。 */
    @AsmHook(targetClass = "net/minecraft/world/entity/LivingEntity", targetMethod = "isAlive",
            targetAliases = "m_6084_", targetDescriptor = "()Z",
            mode = HookMode.OVERRIDE, includeThis = true, includeSubclasses = true)
    public static boolean isAlive(LivingEntity entity) {
        if (PlayerGuard.isProtected(entity)) {
            return true;
        }
        if (PlayerGuard.isDoomed(entity)) {
            return false;
        }
        return !entity.isRemoved() && entity.getHealth() > 0.0F;
    }

    /** 保护玩家恒"未死"；锁定死亡恒"死亡"。 */
    @AsmHook(targetClass = "net/minecraft/world/entity/LivingEntity", targetMethod = "isDeadOrDying",
            targetAliases = "m_21224_", targetDescriptor = "()Z",
            mode = HookMode.OVERRIDE, includeThis = true, includeSubclasses = true)
    public static boolean isDeadOrDying(LivingEntity entity) {
        if (PlayerGuard.isProtected(entity)) {
            return false;
        }
        if (PlayerGuard.isDoomed(entity)) {
            return true;
        }
        return entity.getHealth() <= 0.0F;
    }

    /**
     * 启动早期防崩：玩家实体尚未初始化（player == null）时跳过按键处理。
     *
     * <p>原版 handleKeybinds 的 player 访问都在按键循环内（consumeClick 为 true 才进入），
     * 正常启动无按键事件时不崩；但启动早期（LoadingOverlay 设置前，tick 已开始跑）
     * 若恰有按键事件被消费（启动器/焦点切换注入），会走进 player 分支直接 NPE。
     */
    @AsmHook(targetClass = "net/minecraft/client/Minecraft", targetMethod = "handleKeybinds",
            targetAliases = "m_91279_", targetDescriptor = "()V",
            mode = HookMode.GUARD, includeThis = true)
    public static boolean guardHandleKeybinds(Minecraft mc) {
        return mc.player == null;
    }
}
