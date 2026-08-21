package com.ryjs.reflection.item;

import com.ryjs.reflection.entity.WitherzillaReconciler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;


public class WitherzillaEggItem extends Item {

    public WitherzillaEggItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("\u00a75释放 凋灵斯拉 ");
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level instanceof ServerLevel serverLevel) {

            BlockPos placePos = context.getClickedPos().relative(context.getClickedFace());
            summonPhantom(serverLevel, placePos.getX() + 0.5, placePos.getY(), placePos.getZ() + 0.5);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level instanceof ServerLevel serverLevel) {
            Vec3 pos = player.position();
            summonPhantom(serverLevel, pos.x, pos.y, pos.z);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    private static void summonPhantom(ServerLevel level, double x, double y, double z) {
        WitherzillaReconciler.summon(level, x, y, z, 10);
    }
}
