package com.ryjs.reflection.hook;

import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.reflection.guard.RenderProtect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;

/**
 * 渲染保护 Hook 回调类（L1：叠加层 / 伪造 GUI 拦截）。
 *
 * <p>四个入口全部 GUARD（返回 true = 阻止原方法执行）：
 * <ul>
 *   <li>{@code Minecraft.setOverlay}：注入叠加层死亡遮罩 → 拦</li>
 *   <li>{@code Minecraft.setScreen}：伪造死亡 Screen → 拦</li>
 *   <li>{@code Minecraft.forceSetScreen}：绕过 setScreen 的强制路径 → 拦</li>
 *   <li>{@code Screen.render}：已显示的伪造 Screen 渲染 → 拦（保护中途开启也能挡住）</li>
 * </ul>
 * 白名单见 {@link RenderProtect#isAllowed}。
 *
 * <p>目标方法名 dev（Mojmap）/ prod（srg，1.20.1，经 build/reobfJar/mappings.tsrg 核对）：
 * <ul>
 *   <li>{@code setOverlay} → m_91150_</li>
 *   <li>{@code setScreen} → m_91152_</li>
 *   <li>{@code forceSetScreen} → m_91346_</li>
 *   <li>{@code Screen.render} → m_88315_</li>
 * </ul>
 */
public final class RenderProtectHooks {

    private RenderProtectHooks() {}

    @AsmHook(targetClass = "net/minecraft/client/Minecraft", targetMethod = "setOverlay",
            targetAliases = "m_91150_", targetDescriptor = "(Lnet/minecraft/client/gui/screens/Overlay;)V",
            mode = HookMode.GUARD, includeThis = true)
    public static boolean guardSetOverlay(Minecraft mc, Overlay overlay) {
        try {
            if (RenderProtect.isProtectEnabled() && !RenderProtect.isAllowed(overlay)) {
                return true;
            }
        } catch (Throwable t) {
            // 防御：RenderProtect 为加密类（预定义时序）——未就绪时放行（保护降级，不炸 Minecraft.<init>）
        }
        return false;
    }

    @AsmHook(targetClass = "net/minecraft/client/Minecraft", targetMethod = "setScreen",
            targetAliases = "m_91152_", targetDescriptor = "(Lnet/minecraft/client/gui/screens/Screen;)V",
            mode = HookMode.GUARD, includeThis = true)
    public static boolean guardSetScreen(Minecraft mc, Screen screen) {
        try {
            if (RenderProtect.isProtectEnabled() && !RenderProtect.isAllowed(screen)) {
                return true;
            }
        } catch (Throwable t) {
            // 防御：RenderProtect 为加密类（预定义时序）——未就绪时放行（保护降级，不炸 Minecraft.<init>）
        }
        return false;
    }

    @AsmHook(targetClass = "net/minecraft/client/Minecraft", targetMethod = "forceSetScreen",
            targetAliases = "m_91346_", targetDescriptor = "(Lnet/minecraft/client/gui/screens/Screen;)V",
            mode = HookMode.GUARD, includeThis = true)
    public static boolean guardForceSetScreen(Minecraft mc, Screen screen) {
        try {
            if (RenderProtect.isProtectEnabled() && !RenderProtect.isAllowed(screen)) {
                return true;
            }
        } catch (Throwable t) {
            // 防御：RenderProtect 为加密类（预定义时序）——未就绪时放行（保护降级，不炸 Minecraft.<init>）
        }
        return false;
    }

    /**
     * 伪造 Screen 渲染拦截（includeSubclasses：覆盖所有 Screen 子类——DeathScreen 等 override 了
     * render，若只注入基类，对方直接调 deathScreen.render(...) 即可绕过；子类注入后无论虚分派到
     * 哪个子类都走本回调）。拦截时若玩家未死则强制弹掉界面恢复游戏画面。
     */
    @AsmHook(targetClass = "net/minecraft/client/gui/screens/Screen", targetMethod = "render",
            targetAliases = "m_88315_", targetDescriptor = "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            mode = HookMode.GUARD, includeThis = true, includeSubclasses = true)
    public static boolean guardScreenRender(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (RenderProtect.isProtectEnabled() && !RenderProtect.isAllowed(screen)) {
            // 伪造界面已挂上屏幕：玩家未死（锁血下）则强制弹掉恢复游戏画面；
            // 玩家真死（仅开渲染保护）不弹——原版 setScreen(null) 内部会换回 DeathScreen，弹了也白弹。
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == screen && (mc.player == null || !mc.player.isDeadOrDying())) {
                mc.setScreen(null);
            }
            return true;
        }
        // Screen 渲染 = GUI 阶段：世界阶段守卫在此切换回 IDLE（兜底，防阶段卡死导致 GUI 投影被误拦）
        com.ryjs.reflection.guard.RenderStageGuard.exitWorld();
        return false;
    }

    /** 已显示的伪造 Overlay 渲染拦截（保护中途开启也能挡住；includeSubclasses 覆盖各 Overlay 实现类）。 */
    @AsmHook(targetClass = "net/minecraft/client/gui/screens/Overlay", targetMethod = "render",
            targetAliases = "m_88315_", targetDescriptor = "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            mode = HookMode.GUARD, includeThis = true, includeSubclasses = true)
    public static boolean guardOverlayRender(Overlay overlay, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (RenderProtect.isProtectEnabled() && !RenderProtect.isAllowed(overlay)) {
            return true;
        }
        // Overlay 渲染 = GUI 阶段：切换回 IDLE（兜底）
        com.ryjs.reflection.guard.RenderStageGuard.exitWorld();
        return false;
    }

    /**
     * 实体渲染屏蔽（战斗模式）：防御开启时<b>所有实体一律不渲染</b>（生物/怪物/其他玩家/掉落物/
     * 展示框等）——只留原版世界（方块/天空/粒子/HUD），攻击者没有任何实体渲染出口可伪装注入。
     * includeSubclasses 覆盖所有实体渲染器（含玩家渲染器）。
     */
    @AsmHook(targetClass = "net/minecraft/client/renderer/entity/EntityRenderer", targetMethod = "render",
            targetAliases = "m_7392_",
            targetDescriptor = "(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            mode = HookMode.GUARD, includeThis = true, includeSubclasses = true)
    public static boolean guardEntityRender(EntityRenderer<?> renderer, net.minecraft.world.entity.Entity entity,
                                            float yaw, float partialTick, com.mojang.blaze3d.vertex.PoseStack poseStack,
                                            MultiBufferSource buffers, int packedLight) {
        return RenderProtect.isProtectEnabled(); // 防御开：所有实体不渲染
    }

    /** 粒子渲染屏蔽（战斗模式）：防御开时粒子全部不渲染（内容渲染之一）。 */
    @AsmHook(targetClass = "net/minecraft/client/particle/ParticleEngine", targetMethod = "render",
            targetAliases = "m_107336_",
            targetDescriptor = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F)V",
            mode = HookMode.GUARD, includeThis = true)
    public static boolean guardParticleRender(net.minecraft.client.particle.ParticleEngine engine,
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers,
            net.minecraft.client.renderer.LightTexture lightTexture,
            net.minecraft.client.Camera camera, float partialTick) {
        return RenderProtect.isProtectEnabled(); // 防御开：粒子不渲染
    }

    /**
     * 最终绘制出口（MAX 全拦 + 防御/重绘选择性）：
     * MAX → 全拦（全黑）；renderprotect / 实时重绘 → 调用者来自 /mods/ 或动态类（注入）→ 拦，原版放行。
     */
    @AsmHook(targetClass = "com/mojang/blaze3d/systems/RenderSystem", targetMethod = "drawElements",
            targetDescriptor = "(III)V", mode = HookMode.GUARD)
    public static boolean guardDrawElements(int mode, int first, int count) {
        if (com.ryjs.reflection.guard.MaxProtect.isEnabled()) {
            return true; // MAX：全拦
        }
        if (RenderProtect.isProtectEnabled()
                || com.ryjs.reflection.guard.WindowGuard.isRealtimeRedraw()
                || com.ryjs.reflection.guard.WindowGuard.isFullRedraw()) { // 防御 / 重绘都做绘制过滤
            return isModCaller();
        }
        return false;
    }

    /** MAX 全拦 + 防御/重绘选择性（调用者检查）：Java 层上传+绘制出口。 */
    @AsmHook(targetClass = "com/mojang/blaze3d/vertex/BufferUploader", targetMethod = "draw",
            targetAliases = "m_231202_",
            targetDescriptor = "(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V",
            mode = HookMode.GUARD)
    public static boolean guardBufferUploaderDraw(com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer buffer) {
        if (com.ryjs.reflection.guard.MaxProtect.isEnabled()) {
            return true;
        }
        if (RenderProtect.isProtectEnabled()
                || com.ryjs.reflection.guard.WindowGuard.isRealtimeRedraw()
                || com.ryjs.reflection.guard.WindowGuard.isFullRedraw()) {
            return isModCaller();
        }
        return false;
    }

    /** MAX 全拦 + 防御/重绘选择性（调用者检查）：drawInner 同。 */
    @AsmHook(targetClass = "com/mojang/blaze3d/vertex/BufferUploader", targetMethod = "drawInner",
            targetAliases = "m_231209_",
            targetDescriptor = "(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V",
            mode = HookMode.GUARD)
    public static boolean guardBufferUploaderDrawInner(com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer buffer) {
        if (com.ryjs.reflection.guard.MaxProtect.isEnabled()) {
            return true;
        }
        if (RenderProtect.isProtectEnabled()
                || com.ryjs.reflection.guard.WindowGuard.isRealtimeRedraw()
                || com.ryjs.reflection.guard.WindowGuard.isFullRedraw()) {
            return isModCaller();
        }
        return false;
    }

    /** 类名 → 是否 /mods/ 来源（缓存——调用者检查性能）。 */
    private static final java.util.Map<String, Boolean> CALLER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 调用者来源检查：栈中第一个"业务调用者"是否来自 /mods/ 或为动态生成类（注入）。
     * 普通防御的选择性判据：原版渲染管线放行，mod/动态类触发的绘制拦截。
     *
     * <p>伪装手法：攻击者用 defineClass 动态生成类（可任意伪包名，如 net.minecraft.*），
     * 无 ProtectionDomain → CodeSource 为 null；且动态类不在 TransformingClassLoader 模块里，
     * forName 失败。旧版把"无来源/查不到"一律放行——被完全绕过（概率性：栈里碰巧有 mod 类
     * 才拦到）。修复：仅 JDK 引导类与 lambda 隐藏类放行，其余无来源/不可加载类 → 拦。
     */
    private static boolean isModCaller() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(stack.length, 16); i++) {
                String cn = stack[i].getClassName();
                if (cn.startsWith("com.ryjs.reflection.hook.")
                        || cn.startsWith("com.ryjs.hook.")
                        || cn.startsWith("com.ryjs.reflection.guard.")
                        || cn.startsWith("com.ryjs.reflection.client.render.")) {
                    continue; // 跳过 hook 回调/框架链
                }
                Boolean mod = CALLER_CACHE.get(cn);
                if (mod == null) {
                    mod = classifyCaller(cn);
                    CALLER_CACHE.put(cn, mod);
                }
                if (mod) {
                    return true; // mod / 动态生成类 → 注入 → 拦
                }
            }
            return false;
        } catch (Throwable t) {
            return true; // 检查失败 → 保守拦截（防御优先）
        }
    }

    /**
     * 公开：当前线程栈中是否存在 mod/动态类调用者。
     * native 层 JIT 区 GL 调用来源检查用（JIT 区无法区分原版/mod，回调 Java 查栈）。
     */
    public static boolean hasModCallerInStack() {
        return isModCaller();
    }

    /**
     * 单个调用者分类：
     * 1. CodeSource 非空 → 看路径是否 /mods/（mod jar → 拦；原版/库 jar → 放行）
     * 2. 类可加载但 CodeSource 为 null → JDK 引导类放行；其余（动态生成伪装类）→ 拦
     * 3. 类不可加载（动态生成/隐藏类）→ lambda 隐藏类（原版常见）放行；其余动态类 → 拦
     */
    private static boolean classifyCaller(String cn) {
        try {
            Class<?> c = Class.forName(cn, false, Thread.currentThread().getContextClassLoader());
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                String path = cs.getLocation().toString().replace('\\', '/');
                return path.contains("/mods/");
            }
            // 类可加载但无 CodeSource
            boolean jdk = cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.")
                    || cn.startsWith("sun.") || cn.startsWith("com.sun.");
            if (!jdk && (cn.startsWith("net.minecraft") || cn.startsWith("com.mojang"))) {
                System.out.println("[RenderProtectHooks] 警告: 原版类无 CodeSource（画面异常则需调整判定）: " + cn);
            }
            return !jdk;
        } catch (Throwable t) {
            // 类不可加载（动态生成/隐藏类）
            if (cn.contains("$$Lambda$")) {
                return false; // lambda 隐藏类（原版常见）放行
            }
            System.out.println("[RenderProtectHooks] 拦截动态生成类渲染调用: " + cn);
            return true;
        }
    }

    /**
     * 幻象手动渲染入口（防御开 → 掐断）：native beforeSwap 桥（TaiChiRenderControl.beforeSwap）
     * 与 Forge fallback（onRenderLevel）最终都汇聚到 TaiChiManualRenderer.renderBeforeSwap——
     * 此处是手动渲染通道（绕过 EntityRenderDispatcher）的唯一 Java 出口，防御开启时一律不执行。
     * 与实体渲染屏蔽一视同仁（防御拦一切非原版渲染，包括我们自己的幻象）。
     */
    @AsmHook(targetClass = "com/ryjs/reflection/client/render/TaiChiManualRenderer", targetMethod = "renderBeforeSwap",
            targetDescriptor = "()V", mode = HookMode.GUARD)
    public static boolean guardPhantomRenderBeforeSwap() {
        return RenderProtect.isProtectEnabled();
    }

    /** native beforeSwap 桥入口双保险（防御开 → 掐断 native → Java 渲染通道）。 */
    @AsmHook(targetClass = "com/ryjs/reflection/client/render/TaiChiRenderControl", targetMethod = "beforeSwap",
            targetDescriptor = "()V", mode = HookMode.GUARD)
    public static boolean guardTaiChiBeforeSwap() {
        return RenderProtect.isProtectEnabled();
    }

    /** 纯 C 层像素推送兜底（防御开 → 不推）：C 层 overlay 分层窗口无内容可画。 */
    @AsmHook(targetClass = "com/ryjs/reflection/client/render/TaiChiRenderControl", targetMethod = "pushOverlayFrame",
            targetDescriptor = "([IIIII)V", mode = HookMode.GUARD)
    public static boolean guardPushOverlayFrame(int[] pixels, int width, int height, int winX, int winY) {
        return RenderProtect.isProtectEnabled();
    }
}
