package com.ryjs.reflection.guard;

import net.minecraft.client.Minecraft;


public final class MouseGuard {

    private MouseGuard() {}

    /** 每帧调用：强制重新捕获鼠标。 */
    public static void maintain() {
        if (!RenderProtect.isProtectEnabled()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.mouseHandler != null) {
                mc.mouseHandler.grabMouse();
            }
        } catch (Throwable ignored) {
        }
    }
}
