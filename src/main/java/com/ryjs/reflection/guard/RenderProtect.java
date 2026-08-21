package com.ryjs.reflection.guard;

import java.security.CodeSource;
import net.minecraft.client.gui.screens.DeathScreen;


public final class RenderProtect {

    private RenderProtect() {}

    /** 渲染保护总开关，默认关闭（opt-in：用户主动开启后才生效，加载期间不拦截）。 */
    private static volatile boolean protect = false;

    /** 普通防御开启前玩家锁血的状态（关闭防御时恢复，避免覆盖手动设置）。 */
    private static volatile boolean savedPlayerGuard = false;

    public static boolean isProtectEnabled() {
        return protect || MaxProtect.isEnabled();
    }

    public static void setProtectEnabled(boolean on) {
        if (on && !protect) {
            savedPlayerGuard = PlayerGuard.isProtectEnabled(); // 记录开启前锁血状态
            protect = true;
            PlayerGuard.setProtectEnabled(true); // 普通防御包含锁血（锁 20 血不死）
        } else if (!on && protect) {
            protect = false;
            PlayerGuard.setProtectEnabled(savedPlayerGuard); // 恢复手动锁血状态（不残留）
        }
        try {
            com.ryjs.reflection.client.render.TaiChiRenderControl.syncDefenseFlags(); // 同步 native 防御 hook
        } catch (Throwable ignored) {
        }
        System.out.println("[RenderProtect] 渲染保护: " + (on ? "§a已开启（拦截死亡画面）" : "§c已关闭"));
    }

    /** 切换普通防御（含锁血联动）——防御开关物品普通键触发。 */
    public static void toggle() {
        setProtectEnabled(!isProtectEnabled());
    }

    /**
     * 该 GUI 组件（Screen/Overlay）是否放行。null 视为放行（关闭界面）。
     * 判据：jar 来源（CodeSource）——隐藏类/动态生成（无 CodeSource）或来自 /mods/ 一律拦截；
     * 原版/Forge/dev classes 放行；测试用品包（com.ryjs.reflection.death）单独拦截（dev 兜底）。
     * <p>特判：防御开启时 {@link DeathScreen}（含子类）一律拦截——锁血（isDeadOrDying 恒 false）
     * 下正常流程永不触发原版死亡界面，它出现只可能是对方强制注入（原版类无法用 CodeSource 区分）。
     */
    public static boolean isAllowed(Object gui) {
        if (gui == null) {
            return true;
        }
        // 死亡界面特判：原版类 CodeSource 放行，但锁血下它不该出现，出现即注入
        if (gui instanceof DeathScreen) {
            return false;
        }
        String name = gui.getClass().getName();
        // dev 环境兜底：测试用品类在 build/classes（不在 /mods/），按专用包名拦
        if (name.startsWith("com.ryjs.reflection.death.")) {
            return false;
        }
        CodeSource cs = gui.getClass().getProtectionDomain().getCodeSource();
        if (cs == null || cs.getLocation() == null) {
            return false; // 隐藏类/动态生成：无 jar 来源 → 拦
        }
        String path = cs.getLocation().toString().replace('\\', '/');
        return !path.contains("/mods/");
    }
}
