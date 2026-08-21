package com.ryjs.reflection.api.client.util;


import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class TextureUtils {

    public static int[] loadTextureData(ResourceLocation resource) {
        BufferedImage img = loadBufferedImage(resource);
        if (img == null) {
            return new int[0];
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int[] data = new int[w * h];
        img.getRGB(0, 0, w, h, data, 0, w);
        return data;
    }

    public static BufferedImage loadBufferedImage(ResourceLocation textureFile) {
        try {
            return loadBufferedImage(ResourceUtils.getResourceAsStream(textureFile));
        } catch (Exception e) {
            System.err.println("Failed to load texture file: " + textureFile);
            e.printStackTrace();
        }
        return null;
    }

    public static BufferedImage loadBufferedImage(InputStream in) throws IOException {
        BufferedImage img = ImageIO.read(in);
        in.close();
        return img;
    }

    public static TextureAtlas getTextureMap() {
        return Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
    }

    public static TextureAtlasSprite getMissingSprite() {
        return getTextureMap().getSprite(MissingTextureAtlasSprite.getLocation());
    }

    public static TextureAtlasSprite getTexture(ResourceLocation location) {
        return getTextureMap().getSprite(location);
    }

    @Deprecated
    public static TextureAtlasSprite[] getSideIconsForBlock(BlockState state) {
        TextureAtlasSprite[] sideSprites = new TextureAtlasSprite[6];
        TextureAtlasSprite missingSprite = getMissingSprite();
        for (int i = 0; i < 6; i++) {
            TextureAtlasSprite[] sprites = getIconsForBlock(state, i);
            TextureAtlasSprite sideSprite = missingSprite;
            if (sprites.length != 0) {
                sideSprite = sprites[0];
            }
            sideSprites[i] = sideSprite;
        }
        return sideSprites;
    }

    @Deprecated
    public static TextureAtlasSprite[] getIconsForBlock(BlockState state, int side) {
        return getIconsForBlock(state, Direction.values()[side]);
    }

    @Deprecated
    public static TextureAtlasSprite[] getIconsForBlock(BlockState state, Direction side) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        if (model != null) {
            List<BakedQuad> quads = model.getQuads(state, side, RandomSource.create(0));
            if (quads != null && quads.size() > 0) {
                TextureAtlasSprite[] sprites = new TextureAtlasSprite[quads.size()];
                for (int i = 0; i < quads.size(); i++) {
                    sprites[i] = quads.get(i).getSprite();
                }
                return sprites;
            }
        }
        return new TextureAtlasSprite[0];
    }

    @Deprecated
    public static TextureAtlasSprite getParticleIconForBlock(BlockState state) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        if (model != null) {
            return model.getParticleIcon();
        }
        return null;
    }

}
