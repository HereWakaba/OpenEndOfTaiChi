package com.ryjs.reflection.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.ryjs.reflection.client.render.TaiChiRenderControl;
import com.ryjs.reflection.entity.TaiChiParadoxManager;
import com.ryjs.reflection.entity.WitherzillaReconciler;
import com.ryjs.reflection.guard.*;
import com.ryjs.reflection.Protect.PlayerDefense;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


@Mod.EventBusSubscriber(modid = "reflection", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RyjsCommand {

    private static boolean forceForgeRenderer = false;
    private static boolean pureCLayer = false;

    private RyjsCommand() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (Minecraft.getInstance().player != null) {
            ForgeRenderEventGuard.maintain();
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("ryjs")
                .then(Commands.literal("summon")
                        .then(Commands.literal("taichi_paradox")
                                .executes(ctx -> {
                                    Vec3 pos = ctx.getSource().getPosition();
                                    ServerLevel level = ctx.getSource().getLevel();
                                    TaiChiParadoxManager.spawn(pos.x, pos.y, pos.z);
                                    TaiChiParadoxManager.spawnPresence(level, pos.x, pos.y, pos.z);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("\u00a77\u00a75太极悖论者 \u00a7f已召唤于 "
                                                    + String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z)),
                                            false);
                                    return 1;
                                }))
                        .then(Commands.literal("witherzilla")
                                .executes(ctx -> {
                                    Vec3 pos = ctx.getSource().getPosition();
                                    ServerLevel level = ctx.getSource().getLevel();
                                    WitherzillaReconciler.summon(level, pos.x, pos.y, pos.z, 10);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("\u00a77Witherzilla \u00a7f已召唤于 "
                                                    + String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z)),
                                            false);
                                    return 1;
                                })))
                /*.then(Commands.literal("despawn")
                        .then(Commands.literal("witherzilla")
                                .executes(ctx -> {
                                    WitherzillaReconciler.despawn(ctx.getSource().getServer());
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("\u00a77Witherzilla \u00a7f已回收"),
                                            false);
                                    return 1;
                                })))*/
                .then(Commands.literal("playerprotect")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("\u00a77玩家防御: "
                                            + (PlayerGuard.isProtectEnabled() ? "\u00a7a开" : "\u00a7c关")),
                                    false);
                            return 1;
                        })
                        .then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            PlayerGuard.setProtectEnabled(value);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(value ? "\u00a7a玩家防御已开启" : "\u00a7c玩家防御已关闭"),
                                    false);
                            return 1;
                        })))
                .then(Commands.literal("renderprotect")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("\u00a77渲染保护: "
                                            + (RenderProtect.isProtectEnabled() ? "\u00a7a开" : "\u00a7c关")),
                                    false);
                            return 1;
                        })
                        .then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            RenderProtect.setProtectEnabled(value);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(value ? "\u00a7a渲染保护已开启" : "\u00a7c渲染保护已关闭"),
                                    false);
                            return 1;
                        })))
                .then(Commands.literal("usualkill")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("\u00a77锁常规为死亡: "
                                            + (PlayerGuard.isUsualKill() ? "\u00a7a开" : "\u00a7c关")),
                                    false);
                            return 1;
                        })
                        .then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            PlayerGuard.setUsualKill(value);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(value ? "\u00a7a玩家血量已锁0" : "\u00a7c常规死亡已关闭"),
                                    false);
                            return 1;
                        })))
                .then(Commands.literal("maxprotect")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("\u00a77最大限度防御: "
                                            + (MaxProtect.isEnabled() ? "\u00a7a开" : "\u00a7c关")),
                                    false);
                            return 1;
                        })
                        .then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            MaxProtect.setEnabled(value);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(value ? "\u00a7a最大限度防御已开启" : "\u00a7c最大限度防御已关闭"),
                                    false);
                            return 1;
                        })))
                .then(Commands.literal("redraw")
                        .executes(ctx -> {
                            com.ryjs.reflection.guard.WindowGuard.redrawWindow();
                            ctx.getSource().sendSuccess(() -> Component.literal("§7已重绘 Minecraft 窗口"), false);
                            return 1;
                        })
                        .then(Commands.argument("realtime", BoolArgumentType.bool()).executes(ctx -> {
                            boolean on = BoolArgumentType.getBool(ctx, "realtime");
                            com.ryjs.reflection.guard.WindowGuard.setRealtimeRedraw(on);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(on ? "§a已调用killganapi.redraw();已开启" : "§7重绘已关闭"),
                                    false);
                            return 1;
                        })))
                .then(Commands.literal("redrawAll")
                        .executes(ctx -> {
                            boolean on = WindowGuard.isFullRedraw();
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(on ? "§a已调用killganapi.redrawAll();" : "§7redrawAll: 已关闭"),
                                    false);
                            return 1;
                        })
                        .then(Commands.argument("full", BoolArgumentType.bool()).executes(ctx -> {
                            boolean on = BoolArgumentType.getBool(ctx, "full");
                            WindowGuard.setFullRedraw(on);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(on ? "§aredrawAll已开启" : "§7redrawAll已关闭"),
                                    false);
                            return 1;
                        })))
                .then(Commands.literal("setklass")
                        .then(Commands.literal("DeathPlayer")
                                .executes(ctx -> {
                                    try {
                                        PlayerDefense.DeathPlayer();
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("\u00a7a已调用killganapi.setPlayerKlass()"),
                                                false);
                                        return 1;
                                    } catch (Throwable t) {
                                        t.printStackTrace();
                                        ctx.getSource().sendFailure(
                                                Component.literal("\u00a7ckillganapi.setPlayerklass失败: " + t));
                                        return 0;
                                    }
                                })))
                        .then(Commands.literal("setklass")
                                .then(Commands.literal("ProtectPlayer")
                                        .executes(ctx -> {
                                            try {
                                                PlayerDefense.ProtectPlayer();
                                                ctx.getSource().sendSuccess(
                                                        () -> Component.literal("\u00a7a已调用killganapi.setPlayerKlass()"),
                                                        false);
                                                return 1;
                                            } catch (Throwable t) {
                                                t.printStackTrace();
                                                ctx.getSource().sendFailure(
                                                        Component.literal("\u00a7ckillganapi.setPlayerklass失败: " + t));
                                                return 0;
                                            }
                                        })))
        );
    }

    public static boolean isForceForgeRenderer() {
        return forceForgeRenderer;
    }

    public static boolean isPureCLayer() {
        return pureCLayer;
    }
}
