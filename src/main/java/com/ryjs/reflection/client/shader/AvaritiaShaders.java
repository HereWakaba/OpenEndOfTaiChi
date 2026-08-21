package com.ryjs.reflection.client.shader;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.ryjs.reflection.Reflection;
import com.ryjs.reflection.api.client.shader.CCShaderInstance;
import com.ryjs.reflection.api.client.shader.CCUniform;
import com.ryjs.reflection.api.client.util.AccessUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


import java.io.IOException;
import java.util.Objects;


@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Reflection.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AvaritiaShaders {
    public static boolean inventoryRender = false;

    public static int renderTime;
    public static float renderFrame;

    public static CCShaderInstance cosmicShader;

    public static CCUniform cosmicTime;
    public static CCUniform cosmicScreenSize;

    public static RenderType COSMIC_RENDER_TYPE = RenderType.create("cosmic_sword:cosmic",
            DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 2097152, true, false,
            RenderType.CompositeState.builder().setShaderState(new RenderStateShard.ShaderStateShard(() -> cosmicShader))
                    .setDepthTestState(AccessUtils.EQUAL_DEPTH_TEST)
                    .setLightmapState(AccessUtils.LIGHT_MAP)
                    .setTransparencyState(AccessUtils.TRANSLUCENT_TRANSPARENCY)
                    .setTextureState(AccessUtils.BLOCK_SHEET_MIPPED)
                    .createCompositeState(true)
    );


    public static ShaderInstance minecraftWorldShader;

    public static RenderType MINECRAFT_WORLD_RENDER_TYPE = RenderType.create("reflection:minecraft_world",
            DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 2097152, true, false,
            RenderType.CompositeState.builder().setShaderState(new RenderStateShard.ShaderStateShard(() -> minecraftWorldShader))
                    .setDepthTestState(AccessUtils.EQUAL_DEPTH_TEST)
                    .setLightmapState(AccessUtils.LIGHT_MAP)
                    .setTransparencyState(AccessUtils.TRANSLUCENT_TRANSPARENCY)
                    .setTextureState(AccessUtils.BLOCK_SHEET_MIPPED)
                    .createCompositeState(true)
    );

    public static void onRegisterShaders(RegisterShadersEvent event){
        event.registerShader(CCShaderInstance.create(event.getResourceProvider(), new ResourceLocation(Reflection.MODID, "cosmic"), DefaultVertexFormat.BLOCK), e -> {
            cosmicShader = (CCShaderInstance)e;
            cosmicTime = Objects.requireNonNull(cosmicShader.getUniform("time"));
            cosmicScreenSize = Objects.requireNonNull(cosmicShader.getUniform("screenSize"));

            cosmicShader.onApply(() -> {
                cosmicTime.set((renderTime + renderFrame) / 20.0F);
                Window win = Minecraft.getInstance().getWindow();
                cosmicScreenSize.set((float) win.getWidth(), (float) win.getHeight());
            });
        });


        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), Reflection.rl("minecraft_world"), DefaultVertexFormat.BLOCK),
                    s -> minecraftWorldShader = s);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (!Minecraft.getInstance().isPaused() && event.phase == TickEvent.Phase.END) {
            ++renderTime;
        }
    }

    @SubscribeEvent
    public static void renderTick(TickEvent.RenderTickEvent event) {
        if (!Minecraft.getInstance().isPaused() && event.phase == TickEvent.Phase.START) {
            renderFrame = event.renderTickTime;
        }
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void drawScreenPre(final ScreenEvent.Render.Pre e) {
        AvaritiaShaders.inventoryRender = true;
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void drawScreenPost(final ScreenEvent.Render.Post e) {
        AvaritiaShaders.inventoryRender = false;
    }
}
