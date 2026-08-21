package com.ryjs.reflection.item;

import com.ryjs.agent.AllReturnUtil;
import com.ryjs.reflection.util.AdvancedKillUtils;
import com.ryjs.reflection.util.EntityMaker;
import com.ryjs.reflection.util.OptimaName;
import com.ryjs.reflection.Protect.EntityUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

public class EndOfOptimaItem extends SwordItem {

    public EndOfOptimaItem(Properties properties) {
        super(Tiers.NETHERITE, 3, -2.4F, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return OptimaName.asComponent(System.currentTimeMillis());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                boolean next = !AllReturnUtil.shouldAR();
                AllReturnUtil.set(next);
                player.displayClientMessage(
                        Component.literal("AllReturn:" + (next ? "§aON" : "§cOFF")),
                        true);
            }
            return InteractionResultHolder.success(stack);
        }

        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 72000;
    }
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.CUSTOM;
    }
    @Override
    @OnlyIn(Dist.CLIENT)
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        com.ryjs.reflection.client.HandTransform.register(consumer);
    }

    @Override
    public boolean onEntitySwing(ItemStack stack, LivingEntity entity) {
        try {
            if (entity instanceof Player p && Minecraft.getInstance().getSingleplayerServer() != null) {
                doFullLogic(p, entity.level());
            }
            return super.onEntitySwing(stack, entity);
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    private void doFullLogic(Player p, Level level) {
        for (ServerLevel sl : Minecraft.getInstance().getSingleplayerServer().getAllLevels()) {
            try {
                EntityUtil.killEntityInit(sl);
                EntityUtil.init(sl);
            } catch (Throwable t) {
                t.printStackTrace();
                throw new RuntimeException("EntityUtil初始化失败（level=" + sl + "），详见上方堆栈", t);
            }
        }
        if (p.isCrouching()) {
            if (!level.isClientSide) {
                AllReturnUtil.set(!AllReturnUtil.shouldAR());
            }
        }

        for (ServerLevel sl : Minecraft.getInstance().getSingleplayerServer().getAllLevels()) {


            java.util.List<Entity> snapshot = new java.util.ArrayList<>();
            for (Entity e : sl.getEntities().getAll()) {
                if (e == null) continue;
                if (e instanceof LightningBolt) continue;
                if (e instanceof Player) continue;
                snapshot.add(e);
            }

            if (p.isCrouching()) continue;

            for (Entity e : snapshot) {
                try {
                    EntityMaker.fuckEntityCompletely(e);
                    EntityUtil.addDeath(e);
                    AdvancedKillUtils.ultimateKill(e);
                    AdvancedKillUtils.serverCompleteRemove(e);
                } catch (Exception er) {
                    er.printStackTrace();
                }
            }

        }
    }

    @Override
    public void releaseUsing(ItemStack itemStack, Level level, LivingEntity entity2, int timeLeft) {

        if (entity2 instanceof Player p) {
            doFullLogic(p, level);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
    }

}
