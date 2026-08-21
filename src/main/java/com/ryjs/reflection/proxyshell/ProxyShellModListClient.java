package com.ryjs.reflection.proxyshell;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.ModListScreen;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


@Mod.EventBusSubscriber(modid = "reflection", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProxyShellModListClient {

    private ProxyShellModListClient() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Pre event) {
        Screen screen = event.getScreen();
        if (screen instanceof ModListScreen) {
            ProxyShellModList.installLogos();
        } else if (screen instanceof TitleScreen) {
            ProxyShellModList.installBranding();
        }
    }
}
