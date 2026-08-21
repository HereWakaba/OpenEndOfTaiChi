package com.ryjs.event.world;

import com.ryjs.reflection.Registration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ryjs.event.tooltip.ReflectionRenderTypes;
import com.ryjs.event.tooltip.ReflectionShaders;
import com.ryjs.reflection.Reflection;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


public final class TaiChiGroundArray {

    private static final double FULL_RADIUS = 2.6;
    private static final double IDLE_RADIUS = 0.6;
    private static final double DROP_RADIUS = 1.5;
    private static final double MAX_GROUND_DIST = 8.0;
    private static final double STIFFNESS = 0.25;
    private static final double DAMPING = 0.6;
    private static final float MAX_CHARGE_TICKS = 200f; // 10 秒

    private static final Map<UUID, ArrayState> STATES = new HashMap<>();

    private TaiChiGroundArray() {}

    private static final class ArrayState {
        double arrayY;
        double vy;
        double radius;
        boolean init;
    }


    public static void onRenderLevelFrame(PoseStack poseStack, float pt, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 cam = camera.getPosition();


        float time = (float) ((System.currentTimeMillis() / 1000.0 * 0.8) % (Math.PI * 2.0));
        if (ReflectionShaders.taichiWorldShader != null
                && ReflectionShaders.taichiWorldShader.getUniform("time") != null) {
            ReflectionShaders.taichiWorldShader.safeGetUniform("time").set(time);
        }

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Set<UUID> alive = new HashSet<>();


        boolean fullPause = com.ryjs.timestop.TimeStopManager.isFullPause();
        for (Player player : mc.level.players()) {
            boolean holding = isHolding(player);
            boolean owns = (player == mc.player) ? hasInInventory(player) : holding;
            float charge = chargeProgress(player);
            double targetRadius;
            if (holding) {
                targetRadius = FULL_RADIUS * (1.0 + 9.0 * charge); // 蓄满 => 10 倍
            } else {
                targetRadius = owns ? IDLE_RADIUS : 0.0;
            }
            if (fullPause) targetRadius = 0.0;
            alive.add(player.getUUID());
            processArray(mc, poseStack, buffers, cam, pt, player, player.getUUID(), targetRadius);
        }

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ItemEntity ie
                    && ie.getItem().getItem() == Registration.END_OF_TAI_CHI.get()) {
                alive.add(e.getUUID());
                processArray(mc, poseStack, buffers, cam, pt, e, e.getUUID(), DROP_RADIUS);
            }
        }


        STATES.keySet().removeIf(u -> !alive.contains(u));

        buffers.endBatch(ReflectionRenderTypes.TAICHI_WORLD);
    }

    private static void processArray(Minecraft mc, PoseStack pose, MultiBufferSource buffers,
                                     Vec3 cam, float pt, Entity entity, UUID uuid, double targetRadius) {
        ArrayState st = STATES.get(uuid);
        if (st == null) {
            if (targetRadius <= 0.0) return;
            st = new ArrayState();

            if (entity instanceof ItemEntity) st.radius = FULL_RADIUS;
            STATES.put(uuid, st);
        }
        st.radius += (targetRadius - st.radius) * 0.15;
        if (targetRadius <= 0.0 && st.radius < 0.05) {
            STATES.remove(uuid);
            return;
        }

        double px = Mth.lerp(pt, entity.xo, entity.getX());
        double py = Mth.lerp(pt, entity.yo, entity.getY());
        double pz = Mth.lerp(pt, entity.zo, entity.getZ());


        Vec3 from = new Vec3(px, py + 0.1, pz);
        Vec3 to = new Vec3(px, py - MAX_GROUND_DIST, pz);
        BlockHitResult hit = mc.level.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        boolean groundFound = hit.getType() != HitResult.Type.MISS;
        double groundY = groundFound ? hit.getLocation().y : py;
        double targetY = groundY;

        if (!st.init) {
            st.arrayY = targetY;
            st.vy = 0;
            st.init = true;
        } else {
            st.vy += (targetY - st.arrayY) * STIFFNESS;
            st.vy *= DAMPING;
            st.arrayY += st.vy;
        }

        if (st.arrayY > py) {
            st.arrayY = py;
            if (st.vy > 0) st.vy = 0;
        }
        if (groundFound && st.arrayY < groundY) {
            st.arrayY = groundY;
            if (st.vy < 0) st.vy = 0;
        }

        float r = (float) st.radius;
        float alpha = (float) Math.min(1.0, st.radius / IDLE_RADIUS);
        renderArray(pose, buffers, px - cam.x, st.arrayY + 0.02 - cam.y, pz - cam.z, r, alpha);
    }

    private static void renderArray(PoseStack pose, MultiBufferSource buffers,
                                    double ox, double oy, double oz, float r, float alpha) {
        pose.pushPose();
        pose.translate(ox, oy, oz);

        VertexConsumer vc = buffers.getBuffer(ReflectionRenderTypes.TAICHI_WORLD);
        Matrix4f mat = pose.last().pose();
        int a = (int) (220 * alpha);
        vc.vertex(mat, -r, 0f, -r).uv(0f, 0f).color(255, 255, 255, a).endVertex();
        vc.vertex(mat, -r, 0f, r).uv(0f, 1f).color(255, 255, 255, a).endVertex();
        vc.vertex(mat, r, 0f, r).uv(1f, 1f).color(255, 255, 255, a).endVertex();
        vc.vertex(mat, r, 0f, -r).uv(1f, 0f).color(255, 255, 255, a).endVertex();

        pose.popPose();
    }


    private static float chargeProgress(Player p) {
        if (p == null) return 0f;
        if (p.isUsingItem() && p.getUseItem().getItem() == Registration.END_OF_TAI_CHI.get()) {
            return Math.min(1f, p.getTicksUsingItem() / MAX_CHARGE_TICKS);
        }
        return 0f;
    }

    private static boolean isHolding(Player p) {
        return p.getMainHandItem().getItem() == Registration.END_OF_TAI_CHI.get()
                || p.getOffhandItem().getItem() == Registration.END_OF_TAI_CHI.get();
    }

    private static boolean hasInInventory(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == Registration.END_OF_TAI_CHI.get()) {
                return true;
            }
        }
        return false;
    }
}
