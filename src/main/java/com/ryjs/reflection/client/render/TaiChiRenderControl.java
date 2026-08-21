package com.ryjs.reflection.client.render;

import com.ryjs.reflection.command.RyjsCommand;


public final class TaiChiRenderControl {

    private static volatile boolean loaded;
    private static volatile boolean installed;
    private static volatile boolean installing;

    private TaiChiRenderControl() {}


    public static synchronized void preloadEarly() {
        if (loaded) return;
        try {
            if (com.ryjs.agent.NativePreloader.isLoaded()) {
                loaded = true;
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.io.InputStream in = TaiChiRenderControl.class.getResourceAsStream("/taichi_hook.dll");
            if (in == null) {
                System.loadLibrary("taichi_hook");
            } else {

                java.nio.file.Path tmp = java.nio.file.Files.createTempFile("taichi_hook", ".dll");
                tmp.toFile().deleteOnExit();
                java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                in.close();
                System.load(tmp.toAbsolutePath().toString());
            }
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("early preload 失败: " + e.getMessage());
        } catch (Throwable e) {
            System.err.println("early preload 失败: " + e);
        }
    }


    public static synchronized void install() {
        if (installed || installing) return;
        installing = true;
        try {
            if (!loaded) preloadEarly();
            if (!loaded) {
                return;
            }
            boolean ok = TaiChiRenderBridge.nativeBind();
            if (ok) {
                installed = true;
                System.out.println("[TaiChiRenderControl] beforeSwap 回调已注册（hook 由 DLL 的 JNI_OnLoad 安装）。");
            } else {
                System.err.println("[TaiChiRenderControl] Native bind failed, falling back to Forge events.");
            }
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[TaiChiRenderControl] nativeBind link error: " + e.getMessage() + ", using Forge fallback.");
        } catch (Throwable t) {
            // 兜底：即便旧版 DLL 找不到 beforeSwap 抛 NoSuchMethodError 等，也绝不让 mod 加载崩溃（退化为无 native 渲染）。
            System.err.println("[TaiChiRenderControl] install failed: " + t);
        } finally {
            installing = false;
        }
    }

    public static boolean isNativeInstalled() {
        return installed;
    }

    /** 同步防御标志到 DLL（native 防御 hook 的开关）：bit0=renderprotect，bit1=MAX。 */
    public static void syncDefenseFlags() {
        try {
            int flags = (com.ryjs.reflection.guard.RenderProtect.isProtectEnabled() ? 1 : 0)
                    | (com.ryjs.reflection.guard.MaxProtect.isEnabled() ? 2 : 0);
            TaiChiRenderBridge.nativeSetDefenseFlags(flags);
        } catch (Throwable t) {
            // DLL 未加载/旧版 DLL 无此导出：native 防御 hook 不生效（Java 层防御照常）——打印一次便于定位
            System.err.println("[TaiChiRenderControl] nativeSetDefenseFlags 失败（native 防御 hook 不生效）: " + t);
        }
    }

    /** 切换纯 C 层绘制模式。 */
    public static boolean setPureCLayer(boolean enabled) {
        if (!installed) return false;
        try {
            TaiChiRenderBridge.nativeSetOverlayVisible(enabled);
            return true;
        } catch (Throwable t) {
            System.err.println("[TaiChiRenderControl] nativeSetOverlayVisible 失败: " + t);
            return false;
        }
    }

    /** 推送一帧像素到 C 层独立窗口。 */
    public static void pushOverlayFrame(int[] pixels, int width, int height, int winX, int winY) {
        if (!installed || pixels == null || width <= 0 || height <= 0) return;
        try {
            TaiChiRenderBridge.nativePushFrame(pixels, width, height, winX, winY);
        } catch (Throwable t) {
            System.err.println("nativePushFrame 失败: " + t);
        }
    }


    @SuppressWarnings("unused")
    public static void beforeSwap() {
        if (RyjsCommand.isForceForgeRenderer()) return;
        TaiChiManualRenderer.renderBeforeSwap();
    }


    @SuppressWarnings("unused")
    public static void swapAfter() {

    }


    @SuppressWarnings("unused")
    public static void swapBeforeDraw() {

    }

    @SuppressWarnings("unused")
    public static boolean isNativeGlStackClean() {
        try {
            return !com.ryjs.reflection.hook.RenderProtectHooks.hasModCallerInStack();
        } catch (Throwable t) {
            return true;
        }
    }
}
