package com.ryjs.reflection.client.render;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


@Mod.EventBusSubscriber(modid = "reflection", bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class TaiChiClientSetup {

    private TaiChiClientSetup() {}


    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {

        event.registerEntityRenderer(com.ryjs.reflection.Registration.TAICHI_PARADOX.get(),
                (ctx) -> new net.minecraft.client.renderer.entity.EntityRenderer<com.ryjs.reflection.entity.TaiChiPresenceEntity>(ctx) {
                    @Override
                    public net.minecraft.resources.ResourceLocation getTextureLocation(com.ryjs.reflection.entity.TaiChiPresenceEntity entity) {
                        return new net.minecraft.resources.ResourceLocation("reflection", "textures/entity/taichi_paradox.png");
                    }
                    @Override
                    public boolean shouldRender(com.ryjs.reflection.entity.TaiChiPresenceEntity entity,
                            net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
                        return false;
                    }
                });

        event.registerEntityRenderer(com.ryjs.reflection.Registration.WITHERZILLA.get(),
                com.ryjs.reflection.client.renderer.RenderWitherzilla::new);
    }


    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {

        event.registerLayerDefinition(
                com.ryjs.reflection.client.model.ModelWitherzilla.LAYER_LOCATION,
                com.ryjs.reflection.client.model.ModelWitherzilla::createBodyLayer);
    }

    @SubscribeEvent
    public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            TaiChiRenderControl.install();
        });
    }
}
