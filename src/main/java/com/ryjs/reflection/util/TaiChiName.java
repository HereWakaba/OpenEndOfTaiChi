package com.ryjs.reflection.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * - "End Of TaiChi" 片假名乱码波从左到右循环扫描（然后扫回来）
 * - 每个决策窗口有 30% 概率整串变成 "太极终焉" 持续 2 tick
 * - 黑白无缝渐变配色（逐字符）
 * 纯时间驱动，无客户端依赖，client/server 均可安全调用。
 */
public final class TaiChiName {

    private static final String BASE = "End Of TaiChi";
    private static final String ALT = "太极终焉";
    // 片假名字符池（黑客帝国风格）
    private static final String KATA =
            "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲンヴ";

    private static final long SWEEP_MS = 2200L;   // 单向扫描时长
    private static final long WINDOW_MS = 1500L;  // 替换决策窗口
    private static final long ALT_HOLD_MS = 100L; // 2 tick = 100ms
    private static final double BAND = 2.0;       // 乱码波带宽（字符数）

    private TaiChiName() {}

    private static long hash(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x & 0x7fffffffffffffffL;
    }

    /** 当前是否处于 "太极终焉" 替换窗口 */
    public static boolean isAlt(long ms) {
        long cycle = ms / WINDOW_MS;
        long r = hash(cycle * 2654435761L) % 100L;
        if (r < 30L) {
            return (ms % WINDOW_MS) < ALT_HOLD_MS;
        }
        return false;
    }

    /** 当前应显示的字符串（乱码波 或 太极终焉） */
    public static String currentText(long ms) {
        if (isAlt(ms)) {
            return ALT;
        }
        return scramble(ms);
    }

    private static String scramble(long ms) {
        int len = BASE.length();
        double phase = (ms % (2L * SWEEP_MS)) / (double) SWEEP_MS; // 0..2
        // ping-pong: 0 -> len -> 0
        double front = phase < 1.0 ? phase * len : (2.0 - phase) * len;

        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = BASE.charAt(i);
            if (c == ' ') {
                sb.append(' ');
                continue;
            }
            if (Math.abs(i - front) < BAND) {
                // 乱码带内：快速跳变的随机片假名（每 40ms 变一次）
                int idx = (int) (hash(i * 131L + ms / 40L) % KATA.length());
                sb.append(KATA.charAt(idx));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 黑白无缝渐变色（含不透明 alpha），index/total 决定相位 */
    public static int charColor(int index, int total, long ms) {
        double phase = ms * 0.005 - index * 0.6;
        double v = 0.5 + 0.5 * Math.sin(phase);
        int g = (int) (v * 255.0);
        return 0xFF000000 | (g << 16) | (g << 8) | g;
    }

    /** 构建带黑白渐变的 Component（供 getName 使用） */
    public static MutableComponent asComponent(long ms) {
        String s = currentText(ms);
        MutableComponent out = Component.empty();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            int color = charColor(i, n, ms) & 0xFFFFFF;
            out.append(Component.literal(String.valueOf(s.charAt(i)))
                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color))));
        }
        return out;
    }
}
