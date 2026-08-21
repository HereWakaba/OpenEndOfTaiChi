package com.ryjs.reflection.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;


public final class OptimaName {

    private static final String BASE = "End Of Optima";
    private static final String GLITCH = "▓░▒█◈◆◇▪▫●○■□△▽◁▷※⌬⟁⏣⎔";

    private OptimaName() {}

    private static long hash(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x & 0x7fffffffffffffffL;
    }

    public static String currentText(long ms) {
        int len = BASE.length();
        // 轮换故障位置（每 100ms 换一个）
        int glitchPos1 = (int) ((ms / 100L) % len);
        int glitchPos2 = (glitchPos1 + 7) % len; // 第二个间隔 7 格
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = BASE.charAt(i);
            if (c == ' ') {
                sb.append(' ');
                continue;
            }
            if (i == glitchPos1 || i == glitchPos2) {
                int idx = (int) (hash(i * 131L + ms / 50L) % GLITCH.length());
                sb.append(GLITCH.charAt(idx));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static MutableComponent asComponent(long ms) {
        String s = currentText(ms);
        MutableComponent out = Component.empty();

        int[] palette = {0xFF0000, 0xFF8800, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0088FF, 0x8800FF, 0xFF00FF};
        int rgb = palette[(int)((ms / 5L) % palette.length)];
        for (int i = 0; i < s.length(); i++) {
            out.append(Component.literal(String.valueOf(s.charAt(i)))
                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(true)));
        }
        return out;
    }
}
