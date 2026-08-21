package com.ryjs.reflection.guard;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;


public final class WindowGuard {

    /** 隐藏窗口的 ShowWindow 命令（SW_HIDE = 0）。 */
    private static final int SW_HIDE = 0;

    /** 扩展样式：置顶（WS_EX_TOPMOST = 0x8）。 */
    private static final int WS_EX_TOPMOST = 0x00000008;

    /** GetWindow 命令：GW_OWNER（附属窗口 owner）。 */
    private static final int GW_OWNER = 4;

    /** 系统桌面/任务栏窗口类名（覆盖全屏但属正常系统 UI，排除误伤）。 */
    private static final String[] EXCLUDED_CLASSES = {
        "Progman",                // 桌面（程序管理器）
        "WorkerW",                // 桌面壁纸/图标宿主
        "Shell_TrayWnd",          // 任务栏
        "Shell_SecondaryTrayWnd", // 副任务栏
    };

    private WindowGuard() {}

    /** 每帧调用：检测并隐藏覆盖 MC 窗口的未知窗口。
     * 普通防御/MAX（isProtectEnabled）与实时重绘（战斗模式）都触发扫描——实时重绘下
     * V3 类分层窗口的 UpdateLayeredWindow 被 C 层 hook 拦截后内容停更，窗口本身残留
     * 最后一帧（redraw 清不掉——redraw 只重绘 MC 窗口）→ 必须把窗口隐藏掉。 */
    public static void maintain() {
        if (!RenderProtect.isProtectEnabled() && !isRealtimeRedraw() && !isFullRedraw()) {
            return;
        }
        cleanOverlayWindows();
    }

    /** 窗口扫描净化（无条件——redraw 净化时也调用）：枚举顶层窗口 + 子窗口，
     * 隐藏覆盖 MC 窗口过半的未知可见窗口（排除 MC 自身/系统桌面/任务栏）。
     * 实时重绘（战斗模式）下不要求置顶（激进：任何覆盖 MC 过半的窗口都隐藏）。 */
    public static void cleanOverlayWindows() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return;
        }
        long glfwWindow = mc.getWindow().getWindow();
        if (glfwWindow == 0L) {
            return;
        }
        HWND mcHwnd = new HWND(new Pointer(GLFWNativeWin32.glfwGetWin32Window(glfwWindow)));
        if (mcHwnd == null || mcHwnd.getPointer() == null) {
            return;
        }
        RECT mcRect = new RECT();
        if (!User32.INSTANCE.GetWindowRect(mcHwnd, mcRect)) {
            return;
        }
        long mcArea = area(mcRect);
        if (mcArea <= 0) {
            return;
        }

        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            // 跳过 MC 自身窗口
            if (mcHwnd.equals(hwnd) || hwnd.getPointer() == null) {
                return true;
            }
            if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
                return true;
            }
            // 排除系统桌面/任务栏窗口
            if (isSystemWindow(hwnd)) {
                return true;
            }
            // 贴 MC 进程的窗口（owner=MC 的附属窗）不要求置顶；普通顶层窗口要求置顶
            // （离窗覆盖攻击几乎必置顶；IDE/浏览器等正常应用不置顶，避免误伤）。
            // 实时重绘（战斗模式）：仅"同进程窗口"（mod 创建的离窗覆盖，如 V3 分层窗口）
            // 不要求置顶——跨进程正常应用（IDE 等）仍要求置顶，绝不误伤。
            boolean attached = isOwnedBy(hwnd, mcHwnd);
            boolean sameProcess = isSameProcess(hwnd);
            if (!attached && !isTopmost(hwnd) && !(isRealtimeRedraw() && sameProcess)) {
                return true;
            }
            RECT rect = new RECT();
            if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) {
                return true;
            }
            long inter = intersectArea(mcRect, rect);
            // 覆盖 MC 窗口过半 → 判定为离窗覆盖 → 隐藏
            if (inter * 2 > mcArea) {
                User32.INSTANCE.ShowWindow(hwnd, SW_HIDE);
            }
            return true;
        }, null);

        // 子窗口（SetParent 挂靠）检测
        guardChildWindows(mcHwnd, mcRect, mcArea);
    }

    /** 是否为置顶窗口（WS_EX_TOPMOST）。 */
    private static boolean isTopmost(HWND hwnd) {
        try {
            int exStyle = User32.INSTANCE.GetWindowLong(hwnd, com.sun.jna.platform.win32.WinUser.GWL_EXSTYLE);
            return (exStyle & WS_EX_TOPMOST) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否为 MC 窗口的附属窗（owner = MC 窗口，贴 MC 进程挂靠的攻击窗形态）。 */
    private static boolean isOwnedBy(HWND hwnd, HWND owner) {
        try {
            HWND wndOwner = User32.INSTANCE.GetWindow(hwnd, new DWORD(GW_OWNER));
            return wndOwner != null && owner.equals(wndOwner);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 窗口是否属于当前（MC）进程——mod 同进程创建的离窗覆盖（V3 分层窗口类）。
     * 跨进程窗口（IDE/浏览器等正常应用）返回 false——实时重绘激进模式绝不误伤它们。 */
    private static boolean isSameProcess(HWND hwnd) {
        try {
            com.sun.jna.ptr.IntByReference pid = new com.sun.jna.ptr.IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            return pid.getValue() == (int) ProcessHandle.current().pid();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 子窗口检测：SetParent 挂到 MC 窗口下的攻击窗（EnumWindows 枚举不到子窗口，需单独枚举）。 */
    private static void guardChildWindows(HWND mcHwnd, RECT mcRect, long mcArea) {
        User32.INSTANCE.EnumChildWindows(mcHwnd, (child, data) -> {
            if (child.getPointer() == null || !User32.INSTANCE.IsWindowVisible(child)) {
                return true;
            }
            RECT rect = new RECT();
            if (!User32.INSTANCE.GetWindowRect(child, rect)) {
                return true;
            }
            long inter = intersectArea(mcRect, rect);
            if (inter * 2 > mcArea) {
                User32.INSTANCE.ShowWindow(child, SW_HIDE);
            }
            return true;
        }, null);
    }

    /**
     * 强制重绘 MC 窗口（净化重绘）：GL 内容不走 WM_PAINT，GDI 的 InvalidateRect/RedrawWindow
     * 对 GL 帧无效——真正生效的是 native 层：置位强制重绘标志 + glfwPostEmptyEvent 唤醒
     * 渲染线程 → 下一次 swap 把当前完整原版帧上屏，替换前台残留（GDI 覆盖/D2D 残留）。
     * 配合防御（注入源已被挡）即“只绘制原版该有的东西”。防御开关无关（恢复/净化随时可用）。
     */
    public static void redrawWindow() {
        // 0. 窗口扫描净化（无条件——清"覆盖窗口"类残留：分层窗口/置顶覆盖等，与防御开关无关）
        try {
            cleanOverlayWindows();
        } catch (Throwable ignored) {
        }
        // 1. GDI 层（保留：对子窗口/非 GL 表面残留仍有点用，且无害）
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getWindow() == null) {
                return;
            }
            long glfwWindow = mc.getWindow().getWindow();
            if (glfwWindow == 0L) {
                return;
            }
            HWND hwnd = new HWND(new Pointer(GLFWNativeWin32.glfwGetWin32Window(glfwWindow)));
            if (hwnd != null && hwnd.getPointer() != null) {
                User32.INSTANCE.InvalidateRect(hwnd, null, true);
                User32.INSTANCE.UpdateWindow(hwnd);
                // RDW_INVALIDATE(0x1) | RDW_UPDATENOW(0x100) | RDW_ALLCHILDREN(0x80)
                User32.INSTANCE.RedrawWindow(hwnd, null, null, new DWORD(0x0001 | 0x0100 | 0x0080));
            }
        } catch (Throwable ignored) {
        }
        // 2. native 真重绘：唤醒渲染循环 + 下一次 swap 上屏（GL 内容唯一有效的重绘通道）
        try {
            com.ryjs.reflection.client.render.TaiChiRenderBridge.nativeForceRedraw();
        } catch (Throwable t) {
            System.err.println("[WindowGuard] native 强制重绘不可用（DLL 未加载/旧版）：" + t);
        }
    }

    /** 实时重绘开关（只放行 MC 自身绘制、拦一切非 MC 绘制调用），默认关。 */
    private static volatile boolean realtimeRedraw = false;

    public static boolean isRealtimeRedraw() {
        return realtimeRedraw;
    }

    /**
     * 实时重绘模式（三档区分：MAX 全压制 &gt; 重绘绘制过滤 &gt; 普通防御内容屏蔽）：
     * <ul>
     *   <li>绘制调用过滤：Java 出口 isModCaller（mod/动态类 → 拦，原版放行）+ native（GDI/D3D/GL 白名单）</li>
     *   <li>glClear 拦截（重绘专属，防清屏类注入）</li>
     *   <li>事件总线阻断（源头掐断 Forge 渲染回调）</li>
     *   <li><b>不</b>做内容屏蔽：实体/物品/粒子/Overlay/Screen 照常渲染（那是普通防御的职责）</li>
     * </ul>
     * 独立于 renderprotect 开关（g_forceRedrawEnabled 单独驱动 native 判定）。
     */
    public static void setRealtimeRedraw(boolean on) {
        realtimeRedraw = on;
        try {
            com.ryjs.reflection.client.render.TaiChiRenderBridge.nativeSetForceRedraw(on);
        } catch (Throwable t) {
            System.err.println("[WindowGuard] native 实时重绘不可用（DLL 未加载/旧版）：" + t);
        }
        System.out.println("[WindowGuard] 实时重绘: " + (on ? "已开启（绘制过滤+事件阻断）" : "已关闭"));
    }

    /** 全量重绘开关（redrawAll）——真正意义上的重绘：native 层每帧 swap 前完整重绘流程
     * （GL 状态重置 + 屏幕 DC 残留清除）。一体化：开启时联动选择性拦截（nativeSetForceRedraw），
     * 一个开关控制"拦截 + 重绘"全部。默认关。 */
    private static volatile boolean fullRedraw = false;

    public static boolean isFullRedraw() {
        return fullRedraw;
    }

    public static void setFullRedraw(boolean on) {
        fullRedraw = on;
        try {
            // 一体化：拦截联动（redrawAll 开启自动获得选择性拦截；关闭一并关掉）
            com.ryjs.reflection.client.render.TaiChiRenderBridge.nativeSetForceRedraw(on);
            com.ryjs.reflection.client.render.TaiChiRenderBridge.nativeSetFullRedraw(on);
        } catch (Throwable t) {
            System.err.println("[WindowGuard] native 全量重绘不可用（DLL 未加载/旧版）：" + t);
        }
        System.out.println("[WindowGuard] 全量重绘: " + (on ? "已开启（完整重绘+绘制过滤）" : "已关闭"));
    }

    /** 是否为系统桌面/任务栏窗口（按类名排除）。 */
    private static boolean isSystemWindow(HWND hwnd) {
        try {
            char[] buffer = new char[64];
            int len = User32.INSTANCE.GetClassName(hwnd, buffer, buffer.length);
            if (len <= 0) {
                return false;
            }
            String cls = new String(buffer, 0, len);
            for (String excluded : EXCLUDED_CLASSES) {
                if (excluded.equals(cls)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static long area(RECT r) {
        return (long) (r.right - r.left) * (r.bottom - r.top);
    }

    private static long intersectArea(RECT a, RECT b) {
        int ix = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        int iy = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        return (long) ix * iy;
    }
}
