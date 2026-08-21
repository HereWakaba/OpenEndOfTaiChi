package com.ryjs.reflection.Protect.ServerPlayer;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class ServerGodPlayer extends ServerPlayer {
    public ServerGodPlayer(MinecraftServer p_254143_, ServerLevel p_254435_, GameProfile p_253651_) {
        super(p_254143_, p_254435_, p_253651_);
    }

    public float getHealth() {
            return 20.0f;
    }

    public void setHealth(float p_21154_) {
        super.setHealth(20.0f);
    }
}
