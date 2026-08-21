package com.ryjs.reflection.hook;

import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.hook.hook.HookResult;
import com.ryjs.reflection.guard.MaxProtect;
import com.ryjs.reflection.guard.PlayerGuard;
import com.ryjs.reflection.guard.RenderProtect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;


public final class MouseGuardHooks {

    private MouseGuardHooks() {}

    @AsmHook(targetClass = "net/minecraft/client/MouseHandler", targetMethod = "releaseMouse",
            targetAliases = "m_91602_", targetDescriptor = "()V",
            mode = HookMode.GUARD, includeThis = true)
    public static boolean guardReleaseMouse(MouseHandler handler) {
        return RenderProtect.isProtectEnabled(); // 防御开：阻止释放（鼠标不弹）
    }

    /**
     * 手持防御开关物品时的统一按键开关（左键开 / 右键关 / 蹲下控制 MAX）——
     * 普通点击不再 toggle（明确的开/关语义，正常使用物品不误触）。
     */
    @AsmHook(targetClass = "net/minecraft/client/MouseHandler", targetMethod = "onPress",
            targetAliases = "m_91530_", targetDescriptor = "(JIII)V",
            mode = HookMode.HEAD, includeThis = true)
    public static HookResult<Void> onMousePress(MouseHandler handler, long window, int button, int action, int modifiers) {
        if (action == 1 && (button == 0 || button == 1)) { // 左/右键按下
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null
                    && mc.player.getMainHandItem().getItem() instanceof com.ryjs.reflection.item.GuardToggleItem
                    && mc.screen == null) { // GUI 打开（整理物品栏等）不触发
                if (mc.player.isShiftKeyDown()) {
                    MaxProtect.setEnabled(button == 0); // 蹲下+左键=开 MAX；蹲下+右键=关 MAX
                } else if (button == 0) {
                    PlayerGuard.setProtectEnabled(true);   // 正常左键：开玩家防御 + 渲染防御
                    RenderProtect.setProtectEnabled(true);
                } else {
                    PlayerGuard.setProtectEnabled(false);  // 正常右键：关玩家防御 + 渲染防御
                    RenderProtect.setProtectEnabled(false);
                }
            }
        }
        return HookResult.pass();
    }
}
