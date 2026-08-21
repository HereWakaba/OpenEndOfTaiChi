package com.ryjs.reflection.Protect.ServerPlayer;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class ServerDeathPlayer extends ServerPlayer {
    public ServerDeathPlayer(MinecraftServer p_254143_, ServerLevel p_254435_, GameProfile p_253651_) {
        super(p_254143_, p_254435_, p_253651_);
    }

    public float getHealth() {
        return 0.0F;
    }

    public void setHealth(float p_21154_) {
        super.setHealth(0.0F);
    }

    public boolean isDeadOrDying() {
        return true;
    }

    public void tickDeath() {
        super.tickDeath();
    }
}
