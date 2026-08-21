package com.ryjs.reflection.proxyshell;

import com.ryjs.reflection.client.HandTransform;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;


public class ShellItem extends Item {

    private final boolean handheld;

    private final String literalName;

    public ShellItem(boolean handheld, Properties props) {
        this(handheld, null, props);
    }

    public ShellItem(boolean handheld, String literalName, Properties props) {
        super(props);
        this.handheld = handheld;
        this.literalName = literalName;
    }

    @Override
    public Component getName(ItemStack stack) {
        return literalName != null ? Component.literal(literalName) : super.getName(stack);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return handheld ? UseAnim.CUSTOM : super.getUseAnimation(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return handheld ? 72000 : super.getUseDuration(stack);
    }


    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        if (handheld) {
            HandTransform.register(consumer);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§c被太极之力粉碎"));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
