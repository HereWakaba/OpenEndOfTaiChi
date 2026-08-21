package com.ryjs.reflection.proxyshell;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

public class ShellSpawnItem extends Item {

    private final Supplier<EntityType<ShellBillboardEntity>> type;

    public ShellSpawnItem(Supplier<EntityType<ShellBillboardEntity>> type, Properties props) {
        super(props);
        this.type = type;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        EntityType<ShellBillboardEntity> t = type != null ? type.get() : null;
        if (!level.isClientSide && t != null) {
            BlockPos pos = ctx.getClickedPos().above();
            ShellBillboardEntity e = new ShellBillboardEntity(t, level);
            e.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
            level.addFreshEntity(e);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§c已陷入太极悖论之中"));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
