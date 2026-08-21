package com.ryjs.reflection.Protect.ClientPlayer;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.stats.StatsCounter;

public class ClientDeathPlayer extends LocalPlayer {
    public ClientDeathPlayer(Minecraft p_108621_, ClientLevel p_108622_, ClientPacketListener p_108623_, StatsCounter p_108624_, ClientRecipeBook p_108625_, boolean p_108626_, boolean p_108627_) {
        super(p_108621_, p_108622_, p_108623_, p_108624_, p_108625_, p_108626_, p_108627_);
    }

    public float getHealth() {
        return 0.0F;
    }

    public void setHealth(float p_21154_) {
        super.setHealth(0.0F);
    }

    public void kill() {
        super.kill();
    }

    public boolean isDeadOrDying() {
        return true;
    }

    public void tickDeath() {
        super.tickDeath();
    }
}
