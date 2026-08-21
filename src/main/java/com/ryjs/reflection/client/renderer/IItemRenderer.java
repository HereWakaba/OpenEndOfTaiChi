package com.ryjs.reflection.client.renderer;


import com.mojang.blaze3d.vertex.PoseStack;
import com.ryjs.reflection.client.model.PerspectiveModel;
import com.ryjs.reflection.api.client.util.TextureUtils;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public interface IItemRenderer extends PerspectiveModel {
    void renderItem(ItemStack stack, ItemDisplayContext ctx, PoseStack mStack, MultiBufferSource source, int packedLight, int packedOverlay);
    @Override default @NotNull List<BakedQuad> getQuads(BlockState state, Direction side, @NotNull RandomSource rand) { return Collections.emptyList(); }
    @Override default boolean isCustomRenderer() { return true; }
    @Override default @NotNull TextureAtlasSprite getParticleIcon() { return TextureUtils.getMissingSprite(); }
    @Override default@NotNull ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }
}
