package com.ryjs.reflection.death;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.network.chat.Component;


public final class FakeDeathOverlay extends Overlay {

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        DeathInjector.renderRainbowBackground(graphics, width, height);
        DeathInjector.renderDeathText(graphics, width, height);
    }
}
