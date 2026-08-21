package com.ryjs.reflection.guard;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;


public final class PlayerGuard {

    private PlayerGuard() {}

    /** 玩家防御总开关，默认关闭（opt-in：用户主动开启后才生效）。 */
    private static volatile boolean protect = false;

    /** 锁死亡（攻防对抗）：usualKill 开启时玩家一律锁定死亡，含自己。默认关闭。 */
    private static volatile boolean usualKill = false;

    public static boolean isProtectEnabled() {
        return protect;
    }

    public static void setProtectEnabled(boolean on) {
        protect = on;
        System.out.println("[PlayerGuard] 玩家防御: " + (on ? "§a已开启（锁20不死）" : "§c已关闭"));
    }

    public static boolean isUsualKill() {
        return usualKill;
    }

    public static void setUsualKill(boolean on) {
        usualKill = on;
        System.out.println("[PlayerGuard] 锁死亡: " + (on ? "§a已开启（玩家一律锁定死亡，含自己）" : "§c已关闭"));
    }

    /** 该实体是否是被保护的用户自己（仅玩家，UUID 匹配本地玩家）。 */
    public static boolean isProtected(Entity entity) {
        if (!protect || !(entity instanceof Player)) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getUUID().equals(entity.getUUID());
    }

    /** 该实体是否被锁定为死亡（usualKill：攻防对抗——开启时玩家一律锁死，含自己，验证注入覆盖胜负）。 */
    public static boolean isDoomed(Entity entity) {
        return usualKill && entity instanceof Player;
    }
}
