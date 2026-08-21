package com.ryjs.reflection.Protect;

import com.ryjs.reflection.Protect.ClientPlayer.ClientDeathPlayer;
import com.ryjs.reflection.Protect.ClientPlayer.ClientGodPlayer;
import com.ryjs.reflection.Protect.ServerPlayer.ServerDeathPlayer;
import com.ryjs.reflection.Protect.ServerPlayer.ServerGodPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;


public final class PlayerDefense {

    private PlayerDefense() {
    }


    public static void ProtectPlayer() throws Throwable {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            throw new IllegalStateException("Minecraft未就绪");
        }

        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                EntityUtil.setKlass(sp, ServerGodPlayer.class);
            }
        } else {
            System.out.println("联机模式跳过服务端玩家替换");
        }

        if (mc.player != null) {
            EntityUtil.setKlass(mc.player, ClientGodPlayer.class);
        } else {
            System.out.println("客户端玩家尚未加载，跳过客户端替换");
        }
    }
    public static void DeathPlayer() throws Throwable {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            throw new IllegalStateException("Minecraft未就绪");
        }

        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                EntityUtil.setKlass(sp, ServerDeathPlayer.class);
            }
        } else {
            System.out.println("联机模式跳过服务端玩家替换");
        }

        if (mc.player != null) {
            EntityUtil.setKlass(mc.player, ClientDeathPlayer.class);
        } else {
            System.out.println("客户端玩家尚未加载，跳过客户端替换");
        }
    }
}
