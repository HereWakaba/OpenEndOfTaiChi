package com.ryjs.timestop;

import com.ryjs.reflection.Reflection;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


@Mod.EventBusSubscriber(modid = Reflection.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TimeStopEvents {

    private static boolean soundsPaused = false;

    private TimeStopEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        TimeStopManager.tickDown();

        boolean active = TimeStopManager.isActive();
        if (active && !soundsPaused) {
            Minecraft.getInstance().getSoundManager().pause();
            soundsPaused = true;
        } else if (!active && soundsPaused) {
            Minecraft.getInstance().getSoundManager().resume();
            soundsPaused = false;
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        TimeStopManager.deactivate();
        if (soundsPaused) {
            Minecraft.getInstance().getSoundManager().resume();
            soundsPaused = false;
        }
    }
}
