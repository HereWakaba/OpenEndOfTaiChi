package com.ryjs.reflection.hook;

import com.ryjs.reflection.death.DeathInjector;


public final class DeathForgeHooks {

    /** 注入开关：true 时渲染死亡画面。 */
    private static volatile boolean injecting = false;

    private DeathForgeHooks() {}

    public static boolean isInjecting() {
        return injecting;
    }

    public static void setInjecting(boolean on) {
        injecting = on;
        // C++ GL 直绘通道联动（死亡画面开/关）
        try {
            com.ryjs.reflection.death.DeathGlDirectRender.setEnabled(on);
        } catch (Throwable ignored) {
        }
    }

    /** GUI 渲染末尾回调（由 Gui.render RETURN hook 驱动）：注入开启时画标准死亡画面。 */
    public static void renderIfInjecting() {
        if (injecting) {
            DeathInjector.renderFullScreenDeath(); // 标准死亡画面（彩虹 + 玩家名描边大字）
        }
    }
}
