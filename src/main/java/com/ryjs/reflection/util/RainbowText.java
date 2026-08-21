package com.ryjs.reflection.util;


import java.awt.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

public class RainbowText {

    private static final ChatFormatting[] colour;

    static {
        colour = new ChatFormatting[] {
                ChatFormatting.GOLD,
                ChatFormatting.YELLOW,
                ChatFormatting.AQUA,
                ChatFormatting.BLUE,
                ChatFormatting.LIGHT_PURPLE,
                ChatFormatting.DARK_PURPLE
        };
    }

    public RainbowText() {
    }

    private static String formatting(String input, ChatFormatting[] colours, double delay) {
        StringBuilder sb = new StringBuilder(input.length() * 3);
        if (delay <= 0.0) {
            delay = 0.001;
        }
        int offset = (int) (Math.floor((System.currentTimeMillis() & 16383L) / delay) % colours.length);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            sb.append(colours[(colours.length + i - offset) % colours.length].toString());
            sb.append(c);
        }
        return sb.toString();
    }

    public static MutableComponent rgb(String text) {
        MutableComponent result = Component.empty();
        long time = System.currentTimeMillis();
        int baseHue = (int) ((time / 10L) % 360L);
        for (int i = 0; i < text.length(); i++) {
            int hue = (baseHue + i * 15) % 360;
            int rgb = Color.HSBtoRGB(hue / 360.0f, 1.0f, 1.0f);
            Style style = Style.EMPTY.withColor(TextColor.fromRgb(rgb));
            result.append(Component.literal(String.valueOf(text.charAt(i))).setStyle(style));
        }
        return result;
    }

    public static String makeColour(String input) {
        return formatting(input, colour, 80.0);
    }
}
