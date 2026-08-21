package com.ryjs.reflection.death;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;


public final class FakeDeathScreen extends Screen {

    public FakeDeathScreen() {
        super(Component.literal("你死了 (GUI)"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        DeathInjector.renderRainbowBackground(graphics, this.width, this.height);
        DeathInjector.renderDeathText(graphics, this.width, this.height);
    }
}
