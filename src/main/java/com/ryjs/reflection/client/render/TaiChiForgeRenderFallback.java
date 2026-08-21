package com.ryjs.reflection.client.render;

import com.ryjs.reflection.entity.TaiChiParadoxManager;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


@Mod.EventBusSubscriber(modid = "reflection", bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class TaiChiForgeRenderFallback {

    private TaiChiForgeRenderFallback() {}

    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        TaiChiParadoxManager.destroy();
        com.ryjs.reflection.entity.WitherzillaPhantomManager.destroy();
    }
}
