package com.ryjs.reflection.proxyshell;

import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


@Mod.EventBusSubscriber(modid = "reflection", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ProxyShellClientSetup {

    private ProxyShellClientSetup() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        for (EntityType<ShellBillboardEntity> t : ProxyShellEntities.registeredTypes()) {
            event.registerEntityRenderer(t, ShellSteveRenderer::new);
        }
        ProxyShellEntities.bindTextures();
    }
}
