package com.ryjs.event.tooltip;

import com.ryjs.reflection.Reflection;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Reflection.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ReflectionShaders {
    public static ShaderInstance blackholeTooltipShader;
    public static ShaderInstance taichiTooltipShader;
    public static ShaderInstance optimaTooltipShader;
    public static ShaderInstance caveTooltipShader;
    public static ShaderInstance taichiWorldShader;
    public static ShaderInstance starNestShader;

    private ReflectionShaders() {}

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) {
        try {
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), Reflection.rl("blackhole_tooltip"), DefaultVertexFormat.POSITION_COLOR),
                shader -> blackholeTooltipShader = shader
        );
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), Reflection.rl("taichi_tooltip"), DefaultVertexFormat.POSITION_COLOR),
                shader -> taichiTooltipShader = shader
        );

        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), Reflection.rl("optima_tooltip"), DefaultVertexFormat.POSITION_COLOR),
                shader -> optimaTooltipShader = shader
        );

        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), Reflection.rl("cave_tooltip"), DefaultVertexFormat.POSITION_COLOR),
                shader -> caveTooltipShader = shader
        );
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), Reflection.rl("taichi_world"), DefaultVertexFormat.POSITION_TEX_COLOR),
                shader -> taichiWorldShader = shader
        );

        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), Reflection.rl("star_nest"), DefaultVertexFormat.POSITION_COLOR),
                shader -> starNestShader = shader
        );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
