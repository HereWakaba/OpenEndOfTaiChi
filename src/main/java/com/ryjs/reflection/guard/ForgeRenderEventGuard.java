package com.ryjs.reflection.guard;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.BusBuilderImpl;
import net.minecraftforge.eventbus.api.Event;


public final class ForgeRenderEventGuard {

    private ForgeRenderEventGuard() {}

    /** 是否已完成事件总线替换（当前总线是我们的实例）。 */
    public static boolean isInstalled() {
        return MinecraftForge.EVENT_BUS instanceof RenderBlockingEventBus;
    }

    /**
     * 替换 Forge 事件总线为渲染阻断总线（幂等）。
     * 原总线作为转发目标，所有监听器注册/派发保持原样（busID 桶一致）。
     */
    public static synchronized void install() {
        try {
            net.minecraftforge.eventbus.api.IEventBus original = MinecraftForge.EVENT_BUS;
            if (original == null || original instanceof RenderBlockingEventBus) {
                return;
            }
            MinecraftForge.EVENT_BUS = new RenderBlockingEventBus(new BusBuilderImpl(), original);
            System.out.println("[ForgeRenderEventGuard] 事件总线已替换（渲染事件阻断就绪）。");
        } catch (Throwable t) {
            System.err.println("[ForgeRenderEventGuard] 事件总线替换失败: " + t);
        }
    }

    /**
     * 持续维护（每 tick 调用）：总线被其他 mod 反射替换后抢回。
     * 重新安装时以当前总线为转发目标（其监听器照常工作），阻断能力恢复。
     */
    public static void maintain() {
        if (!isInstalled()) {
            install();
        }
    }

    /**
     * 渲染事件判定 + 阻断决策（防御 / 实时重绘都阻断——重绘也要源头掐断 Forge 渲染回调）。
     * @return true = 阻断该事件派发
     */
    public static boolean shouldBlock(Event event) {
        if (event == null) {
            return false;
        }
        if (!RenderProtect.isProtectEnabled() && !WindowGuard.isRealtimeRedraw() && !WindowGuard.isFullRedraw()) {
            return false;
        }
        String name = event.getClass().getName();
        return name.startsWith("net.minecraftforge.client.event.")
                && (name.contains("Render") || name.contains("ScreenEvent$Render"));
    }
}
