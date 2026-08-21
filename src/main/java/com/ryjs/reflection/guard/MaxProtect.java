package com.ryjs.reflection.guard;


public final class MaxProtect {

    private MaxProtect() {}

    /** MAX 开关，默认关闭。 */
    private static volatile boolean enabled = false;

    /** MAX 开启前普通防御的值（关闭 MAX 时恢复，避免污染普通防御开关）。 */
    private static volatile boolean savedProtect = false;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        if (on && !enabled) {
            savedProtect = RenderProtect.isProtectEnabled(); // 记录开启前普通防御状态（MAX 此时未开）
            enabled = true;
            RenderProtect.setProtectEnabled(true); // MAX 强制渲染保护全开
        } else if (!on && enabled) {
            enabled = false;
            RenderProtect.setProtectEnabled(savedProtect); // 恢复开启前的普通防御状态（不残留）
        }
        try {
            com.ryjs.reflection.client.render.TaiChiRenderControl.syncDefenseFlags(); // 同步 native 防御 hook
        } catch (Throwable ignored) {
        }
        System.out.println("[MaxProtect] 最大限度防御: " + (on ? "§a已开启（渲染能力压制）" : "§c已关闭"));
    }

    public static void toggle() {
        setEnabled(!enabled);
    }
}
