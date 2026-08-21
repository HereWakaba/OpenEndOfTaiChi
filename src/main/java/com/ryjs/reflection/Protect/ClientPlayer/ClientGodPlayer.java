package com.ryjs.reflection.Protect.ClientPlayer;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.stats.StatsCounter;

public class ClientGodPlayer extends LocalPlayer {
    public ClientGodPlayer(Minecraft p_108621_, ClientLevel p_108622_, ClientPacketListener p_108623_, StatsCounter p_108624_, ClientRecipeBook p_108625_, boolean p_108626_, boolean p_108627_) {
        super(p_108621_, p_108622_, p_108623_, p_108624_, p_108625_, p_108626_, p_108627_);
    }

    public void setHealth(float p_21154_) {
            super.setHealth(20.0f);
    }

    public float getHealth() {
            return 20.0f;
        }
    }
