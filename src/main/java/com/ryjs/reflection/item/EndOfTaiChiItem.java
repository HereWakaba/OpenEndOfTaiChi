package com.ryjs.reflection.item;

import com.ryjs.reflection.entity.TaiChiDominion;
import com.ryjs.reflection.util.TaiChiName;
import com.ryjs.timestop.TimeStopManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.ImmutableMultimap.Builder;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.world.entity.EquipmentSlot;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraftforge.common.ForgeMod;


public class EndOfTaiChiItem extends SwordItem {
    private static final UUID REACH_UUID = UUID.fromString("91AEAA56-376B-4498-935B-2F7F68070635");
    private static final UUID BLOCK_REACH_UUID = UUID.fromString("CB3F55D3-645C-4F38-A497-9C13A33DB5CF");


    public Multimap<Attribute, AttributeModifier> getAttributeModifiers(EquipmentSlot slot, ItemStack stack) {
        Multimap<Attribute, AttributeModifier> originalModifiers = super.getAttributeModifiers(slot, stack);
        if (slot == EquipmentSlot.MAINHAND) {
            Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
            builder.putAll(originalModifiers);
            builder.put(ForgeMod.ENTITY_REACH.get(), new AttributeModifier(REACH_UUID, "TaiChi Reach", 2147483647.0, Operation.ADDITION));
            builder.put(
                    ForgeMod.BLOCK_REACH.get(), new AttributeModifier(BLOCK_REACH_UUID, "TaiChi Block Reach", 1638465536.0, Operation.ADDITION)
            );
            return builder.build();
        } else {
            return originalModifiers;
        }
    }

    public EndOfTaiChiItem(Properties properties) {
        super(Tiers.NETHERITE, 3, -2.4F, properties);
    }

    @Override
    public @NotNull Component getName(@NotNull ItemStack stack) {
        return TaiChiName.asComponent(System.currentTimeMillis());
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        if (level.isClientSide){
            // 只开不关
            TaiChiDominion.registerOwner(player.getUUID());
            TaiChiDominion.activateAttacking();
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public int getUseDuration(@NotNull ItemStack stack) {
        return 72000;
    }

    @Override
    public void onUseTick(Level level, @NotNull LivingEntity entity, @NotNull ItemStack stack, int remainingUseDuration) {
        if (!level.isClientSide) return;
        if (entity instanceof Player player) {
            int charged = getUseDuration(stack) - remainingUseDuration;
            if (charged >= 2) { // 起手后即进入时停
                if (player.isShiftKeyDown()) {
                    TimeStopManager.sustainFullPause(player);
                } else {
                    TimeStopManager.sustain(player);
                }
            }
        }
    }

    @Override
    public void releaseUsing(@NotNull ItemStack stack, Level level, @NotNull LivingEntity entity, int timeLeft) {
        if (level.isClientSide) {
            TimeStopManager.deactivate();
        }


    }

    @Override
    public @NotNull UseAnim getUseAnimation(@NotNull ItemStack stack) {
        return UseAnim.CUSTOM;
    }
    @Override
    @OnlyIn(Dist.CLIENT)
    public void initializeClient(@NotNull Consumer<IClientItemExtensions> consumer) {
        com.ryjs.reflection.client.HandTransform.register(consumer);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @Nullable Level level, @NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
    }

}