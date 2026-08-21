package com.ryjs.reflection.guard;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;


public final class RenderStageGuard {

    private RenderStageGuard() {}

    /** 世界渲染阶段标记（渲染线程）。 */
    private static volatile boolean inWorld = false;

    /** 世界投影（renderLevel 入口捕获），世界阶段内投影必须等于它。 */
    private static volatile Matrix4f worldProjection = null;

    /** 当前是否处于世界渲染阶段。 */
    public static boolean isInWorld() {
        return inWorld;
    }

    /** renderLevel 入口：标记世界阶段并捕获世界投影。 */
    public static void enterWorld() {
        inWorld = true;
        try {
            worldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        } catch (Throwable ignored) {
            worldProjection = null;
        }
    }

    /** renderLevel 出口：恢复空闲阶段。 */
    public static void exitWorld() {
        inWorld = false;
        worldProjection = null;
    }

    /**
     * 投影守卫：世界阶段内设置非世界投影（正交全屏等 GUI 特征）→ 阻止。
     * @return true = 阻止原设置
     */
    public static boolean guardProjection(Matrix4f projection, VertexSorting sorting) {
        if (!RenderProtect.isProtectEnabled() || !inWorld) {
            return false;
        }
        Matrix4f expected = worldProjection;
        if (expected == null || projection == null || !projection.equals(expected)) {
            return true;
        }
        return false;
    }

    /**
     * 深度守卫：世界阶段内禁用深度测试（GUI 绘制特征）→ 阻止。
     * 说明：防御为 opt-in 开关（renderprotect true 才生效），开启即战斗模式，该拦拦，
     * 宁可误伤（原版半透明水层也可能触发）也不放注入钻空子。
     */
    public static boolean guardDisableDepthTest() {
        if (!RenderProtect.isProtectEnabled() || !inWorld) {
            return false;
        }
        return true;
    }

    /** 深度守卫：世界阶段内关闭深度掩码（depthMask(false)，GUI 绘制特征）→ 阻止；恢复（true）放行。 */
    public static boolean guardDepthMask(boolean mask) {
        if (!RenderProtect.isProtectEnabled() || !inWorld || mask) {
            return false;
        }
        return true;
    }
}
