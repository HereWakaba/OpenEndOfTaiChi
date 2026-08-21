package com.ryjs.event.hud;

import com.ryjs.reflection.Registration;

import com.ryjs.reflection.Reflection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;

import java.awt.Color;

public final class TaiChiHudOverlay {

    private static final String TEXT = "-太极终焉-";

    private TaiChiHudOverlay() {}

    /** HUD 渲染末尾回调（由 Gui.render RETURN hook 驱动）。 */
    public static void onGuiRender(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.options.hideGui) return;
        if (!hasSword(player)) return;

        long now = System.currentTimeMillis();
        boolean flicker = isFlickerHidden(now); // 只作用于“太极终焉”，横杠不受影响

        Font font = mc.font;
        int sw = graphics.guiWidth();
        int sh = graphics.guiHeight();

        int totalW = 0;
        for (int i = 0; i < TEXT.length(); i++) {
            totalW += font.width(String.valueOf(TEXT.charAt(i)));
        }

        float penX = (sw - totalW) / 2.0f;
        int baseY = sh - 32; // 再下移 8px

        for (int i = 0; i < TEXT.length(); i++) {
            char cc = TEXT.charAt(i);
            String ch = String.valueOf(cc);
            int adv = font.width(ch);
            boolean isDash = (cc == '-');
            // “太极终焉”按概率消失 1 tick；横杠始终显示、不变色不抽搐
            if (!isDash && flicker) {
                penX += adv;
                continue;
            }
            int dx = isDash ? 0 : twitch(now, i, 11L);
            int dy = isDash ? 0 : twitch(now, i, 37L);
            int x = (int) penX + dx;
            int yy = baseY + dy;
            int color = isDash ? 0xFFFFFFFF : charColor(now, i);
            // 黑色描边
            graphics.drawString(font, ch, x - 1, yy, 0xFF000000, false);
            graphics.drawString(font, ch, x + 1, yy, 0xFF000000, false);
            graphics.drawString(font, ch, x, yy - 1, 0xFF000000, false);
            graphics.drawString(font, ch, x, yy + 1, 0xFF000000, false);
            // 主体（画两遍、右移 1px => 加粗）
            graphics.drawString(font, ch, x, yy, color, false);
            graphics.drawString(font, ch, x + 1, yy, color, false);
            penX += adv;
        }
    }

    private static boolean hasSword(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == Registration.END_OF_TAI_CHI.get()) {
                return true;
            }
        }
        return false;
    }

    private static long hash(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x & 0x7fffffffffffffffL;
    }

    /** 40% 概率消失 1 tick（~50ms），每 500ms 一个判定窗口 */
    private static boolean isFlickerHidden(long ms) {
        long window = 500L;
        long cycle = ms / window;
        if (hash(cycle * 2654435761L) % 100L < 40L) {
            return (ms % window) < 50L;
        }
        return false;
    }

    /** 抽搐：每 60ms 变一次，范围 [-2, 2] px */
    private static int twitch(long ms, int i, long salt) {
        long h = hash((ms / 60L) * 131L + i * 977L + salt);
        return (int) (h % 5L) - 2;
    }

    /** 炫彩变色：高饱和流动色（更快更剧烈）+ 偶发白色故障闪 */
    private static int charColor(long ms, int i) {
        if (hash((ms / 60L) * 17L + i * 101L) % 100L < 10L) {
            return 0xFFFFFFFF; // 故障白闪（更频繁）
        }
        float hue = (((ms * 0.22f) + i * 68f) % 360f) / 360f; // 转得更快、字间跨度更大
        int rgb = Color.HSBtoRGB(hue, 1.0f, 1.0f) & 0xFFFFFF;  // 满饱和更艳
        return 0xFF000000 | rgb;
    }
}
