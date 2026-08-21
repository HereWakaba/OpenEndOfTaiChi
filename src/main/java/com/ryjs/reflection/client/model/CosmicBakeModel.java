package com.ryjs.reflection.client.model;

import com.ryjs.reflection.Registration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ryjs.event.tooltip.ReflectionRenderTypes;
import com.ryjs.event.tooltip.ReflectionShaders;
import com.ryjs.reflection.api.client.model.PerspectiveModelState;
import com.ryjs.reflection.client.renderer.IItemRenderer;
import com.ryjs.reflection.client.shader.AvaritiaShaders;
import com.ryjs.reflection.api.client.util.TransformUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;



public final class CosmicBakeModel extends WrappedItemModel implements IItemRenderer {
    private final List<ResourceLocation> maskSprite;


    public static boolean SUPPRESS_BACK_TAICHI = false;

    private static final long WORLD_TIME_ANCHOR_MS = System.currentTimeMillis();

    public CosmicBakeModel(final BakedModel wrapped, final List<ResourceLocation> maskSprite) {
        super(wrapped);
        this.maskSprite = maskSprite;
    }

    @Override
    public void renderItem(ItemStack stack, ItemDisplayContext transformType, PoseStack pStack, MultiBufferSource source, int light, int overlay) {
        if (stack.getItem() == com.ryjs.reflection.Registration.END_OF_TAI_CHI.get()) {
            this.parentState = TransformUtils.DEFAULT_TOOL;
        }

        if (stack.getItem() == com.ryjs.reflection.Registration.END_OF_TAI_CHI.get()
                && !SUPPRESS_BACK_TAICHI
                && (transformType == ItemDisplayContext.GUI || transformType == ItemDisplayContext.FIXED)) {
            renderGuiTaichi(pStack, source);
        }

        java.util.Set<net.minecraft.client.renderer.RenderType> used = this.renderWrapped(stack, pStack, source, light, overlay, true);
        if (source instanceof MultiBufferSource.BufferSource bs) {
            try {
                for (net.minecraft.client.renderer.RenderType rt : used) {
                    bs.endBatch(rt);
                }
            } catch (Throwable ignored) {
            }
        }
        final Minecraft mc = Minecraft.getInstance();
        if (stack.getItem() == Registration.END_OF_OPTIMA.get()) {
            renderMinecraftWorld(mc, pStack, source, stack, light, overlay);
            return;
        }

        final VertexConsumer cons = source.getBuffer(AvaritiaShaders.COSMIC_RENDER_TYPE);
        List<TextureAtlasSprite> atlasSprite = new ArrayList<>();
        for (ResourceLocation res : maskSprite) {
            atlasSprite.add(Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(res));
        }
        try {
            mc.getItemRenderer().renderQuadList(pStack, cons, bakeItem(atlasSprite), stack, light, overlay);
        } catch (Throwable t) {
            System.err.println("模型写入失败: " + t);
        }

        if (source instanceof MultiBufferSource.BufferSource bs) {
            try {
                bs.endBatch(AvaritiaShaders.COSMIC_RENDER_TYPE);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void renderGuiTaichi(PoseStack pStack, MultiBufferSource source) {
        try {
            ShaderInstance shader = ReflectionShaders.taichiWorldShader;
            if (shader == null) return;
            if (shader.getUniform("time") != null) {
                float t = (float) ((System.currentTimeMillis() / 1000.0 * 0.8) % (Math.PI * 2.0));
                shader.safeGetUniform("time").set(t);
            }
            MultiBufferSource.BufferSource bs = Minecraft.getInstance().renderBuffers().bufferSource();
            VertexConsumer vc = bs.getBuffer(ReflectionRenderTypes.BACK_TAICHI);
            Matrix4f mat = pStack.last().pose();
            float cx = 0.5f, cy = 0.5f, h = 0.585f, z = 0.5f;
            int a = 200;
            vc.vertex(mat, cx - h, cy - h, z).uv(0f, 0f).color(255, 255, 255, a).endVertex();
            vc.vertex(mat, cx - h, cy + h, z).uv(0f, 1f).color(255, 255, 255, a).endVertex();
            vc.vertex(mat, cx + h, cy + h, z).uv(1f, 1f).color(255, 255, 255, a).endVertex();
            vc.vertex(mat, cx + h, cy - h, z).uv(1f, 0f).color(255, 255, 255, a).endVertex();
            vc.vertex(mat, cx - h, cy - h, z).uv(0f, 0f).color(255, 255, 255, a).endVertex();
            vc.vertex(mat, cx + h, cy - h, z).uv(1f, 0f).color(255, 255, 255, a).endVertex();
            vc.vertex(mat, cx + h, cy + h, z).uv(1f, 1f).color(255, 255, 255, a).endVertex();
            vc.vertex(mat, cx - h, cy + h, z).uv(0f, 1f).color(255, 255, 255, a).endVertex();
            bs.endBatch(ReflectionRenderTypes.BACK_TAICHI);
        } catch (Exception ignored) {
        }
    }

    /** 新剑 EndOfOptima：用 minecraft_world 着色器把体素世界贴到 mask 剑形上（时间驱动，自带飞行相机） */
    private void renderMinecraftWorld(Minecraft mc, PoseStack pStack, MultiBufferSource source, ItemStack stack, int light, int overlay) {
        List<TextureAtlasSprite> atlasSprite = new ArrayList<>();
        for (ResourceLocation res : maskSprite) {
            atlasSprite.add(mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(res));
        }
        ShaderInstance shader = AvaritiaShaders.minecraftWorldShader;
        if (shader != null && !atlasSprite.isEmpty()) {
            TextureAtlasSprite mask = atlasSprite.get(0);
            if (shader.getUniform("time") != null)
                shader.getUniform("time").set((System.currentTimeMillis() - WORLD_TIME_ANCHOR_MS) / 1000.0f);
            if (shader.getUniform("uvMin") != null)
                shader.getUniform("uvMin").set(mask.getU0(), mask.getV0());
            if (shader.getUniform("uvMax") != null)
                shader.getUniform("uvMax").set(mask.getU1(), mask.getV1());
        }
        VertexConsumer cons = source.getBuffer(AvaritiaShaders.MINECRAFT_WORLD_RENDER_TYPE);
        try {
            mc.getItemRenderer().renderQuadList(pStack, cons, bakeItem(atlasSprite), stack, light, overlay);
        } catch (Throwable t) {
            System.err.println("[CosmicBakeModel] 体素世界写入失败（已隔离）: " + t);
        }
        // 同 COSMIC：EQUAL_DEPTH_TEST 依赖写入时深度，立即上传（只收尾自己的 RenderType）
        if (source instanceof MultiBufferSource.BufferSource bs) {
            try {
                bs.endBatch(AvaritiaShaders.MINECRAFT_WORLD_RENDER_TYPE);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public @Nullable PerspectiveModelState getModelState() {
        return (PerspectiveModelState) this.parentState;
    }

    @Override
    public boolean isCosmic() {
        return true;
    }
}
