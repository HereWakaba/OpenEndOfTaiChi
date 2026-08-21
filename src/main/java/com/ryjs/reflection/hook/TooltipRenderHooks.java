package com.ryjs.reflection.hook;

import com.ryjs.reflection.Registration;

import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.reflection.Reflection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.List;


public final class TooltipRenderHooks {

    private TooltipRenderHooks() {}

    private static ItemStack hoveredStack() {
        try {
            net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().screen;
            if (screen instanceof AbstractContainerScreen<?> acs) {
                for (Field f : AbstractContainerScreen.class.getDeclaredFields()) {
                    if (Slot.class.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            Object v = f.get(acs);
                            if (v instanceof Slot s && s.hasItem()) {
                                return s.getItem();
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    @AsmHook(targetClass = "net/minecraft/client/gui/GuiGraphics", targetMethod = "renderTooltip",
            targetAliases = "m_280547_",
            targetDescriptor = "(Lnet/minecraft/client/gui/Font;Ljava/util/List;Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;II)V",
            mode = HookMode.GUARD, includeThis = true)
    public static boolean guardTooltip(GuiGraphics graphics, Font font, List<FormattedCharSequence> lines,
                                       ClientTooltipPositioner positioner, int x, int y) {
        return tryCustomTooltip(graphics, font, x, y, "m_280547_");
    }


    @AsmHook(targetClass = "net/minecraft/client/gui/GuiGraphics", targetMethod = "renderTooltip",
            targetAliases = "m_280677_",
            targetDescriptor = "(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V",
            mode = HookMode.GUARD, includeThis = true)
    public static boolean guardTooltipComponents(GuiGraphics graphics, Font font, List<net.minecraft.network.chat.Component> lines,
                                                 java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image,
                                                 int x, int y) {
        return tryCustomTooltip(graphics, font, x, y, "m_280677_");
    }


    private static boolean tryCustomTooltip(GuiGraphics graphics, Font font, int x, int y, String src) {
        ItemStack hovered = hoveredStack();
        if (hovered.isEmpty()) {
            return false;
        }
        boolean target = hovered.getItem() == Registration.END_OF_TAI_CHI.get()
                || hovered.getItem() == Registration.SCYTHE.get()
                || hovered.getItem() == Registration.END_OF_OPTIMA.get()
                || hovered.getItem() == Registration.FULL_DEATH_ITEM.get();
        if (target) {
            try {
                com.ryjs.event.tooltip.ReflectionTooltipRenderer.renderCustomTooltip(graphics, font, hovered, x, y);
            } catch (Throwable t) {

                System.err.println("[TooltipRenderHooks] 自定义 tooltip 失败（已隔离）: " + t);
            }
            return true;
        }
        return false;
    }
}
