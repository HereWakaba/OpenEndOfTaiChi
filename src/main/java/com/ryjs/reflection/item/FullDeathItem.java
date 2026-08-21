package com.ryjs.reflection.item;

import com.ryjs.reflection.Registration;

import com.ryjs.agent.AllReturnUtil;
import com.ryjs.reflection.Reflection;
import com.ryjs.reflection.death.DeathInjector;
import com.ryjs.reflection.death.DeathOverlay;
import com.ryjs.reflection.death.FakeDeathOverlay;
import com.ryjs.reflection.death.FakeDeathScreen;
import com.ryjs.reflection.guard.PlayerGuard;
import com.ryjs.reflection.hook.DeathForgeHooks;
import com.ryjs.reflection.hook.DeathGlintHooks;
import com.ryjs.reflection.hook.DeathWorldHooks;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;

import static com.ryjs.reflection.Protect.PlayerDefense.DeathPlayer;

public class FullDeathItem extends Item {

    /** 抹除已触发（防止重复触发叠加）。 */
    private static volatile boolean erased = false;

    /** 死亡 BGM 单实例（isLooping——播放完自动重播，不重复新开）。 */
    private static SoundInstance bgmInstance = null;

    public FullDeathItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        String s = "死亡物品";
        long now = System.currentTimeMillis();
        float flow = (now % 8000L) / 8000.0f;
        net.minecraft.network.chat.MutableComponent comp = Component.empty();
        for (int i = 0; i < s.length(); i++) {
            float hue = (flow + i * 0.25f) % 1.0f; // 逐字错开 + 整体流动
            int rgb = net.minecraft.util.Mth.hsvToRgb(hue, 0.85f, 1.0f);
            comp.append(Component.literal(String.valueOf(s.charAt(i)))
                    .withStyle(style -> style.withColor(rgb).withFont(com.ryjs.reflection.Reflection.rl("reflection"))));
        }
        return comp;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            try {
                DeathPlayer();
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            triggerErase(player);
        }
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    @Override
    public boolean onEntitySwing(ItemStack stack, LivingEntity entity) {
        if (entity.level().isClientSide && entity instanceof Player player) {
            try {
                DeathPlayer();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
            triggerErase(player);
        }
        return super.onEntitySwing(stack, entity);
    }

    /** Tooltip 重绘（不借助 Forge 事件——纯原版 Item.appendHoverText API）：彩虹标题 + 加粗死亡宣告。 */
    @Override
    public void appendHoverText(@NotNull ItemStack stack, @Nullable Level level, @NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
        // 分隔线
        tooltip.add(Component.literal("Test"));
        StringBuilder title = new StringBuilder(DeathInjector.playerName()).append("Test");
        String s = title.toString();
        float span = Math.max(1, s.length());
        net.minecraft.network.chat.MutableComponent comp = Component.empty();
        for (int i = 0; i < s.length(); i++) {
            int rgb = net.minecraft.util.Mth.hsvToRgb(i / span, 0.85F, 1.0F);
            comp.append(Component.literal(String.valueOf(s.charAt(i)))
                    .withStyle(style -> style.withColor(rgb).withBold(true)));
        }
        tooltip.add(comp);
//暂时禁用
    }

    /** 触发死亡抹除（客户端）。 */
    private static void triggerErase(Player player) {
        if (erased) {
            return; // 已抹除，不重复触发
        }
        erased = true;
        Minecraft mc = Minecraft.getInstance();

        // ① 全攻击渲染：六层注入（覆盖层/GUI/Forge/世界/Glint/离窗）
        mc.setOverlay(new FakeDeathOverlay());
        mc.setScreen(new FakeDeathScreen());
        DeathForgeHooks.setInjecting(true);
        DeathWorldHooks.setInjecting(true);
        DeathGlintHooks.setInjecting(true);
        if (!DeathOverlay.isOverlayVisible()) {
            DeathOverlay.open();
        }

        // ② 死亡画面（直绘 + 直调 + 标准死亡画面）——渲染线程，本帧末执行并 swap 上屏
        RenderSystem.recordRenderCall(() -> {
            try {
                // 直绘：Java 裸调清屏（JIT 区调用）
                org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
                org.lwjgl.opengl.GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
                org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
                // 直调：C++ 直调 glClear（返回地址在 taichi_hook.dll）
                com.ryjs.reflection.client.render.TaiChiRenderBridge.nativeGlAttack();
                // 标准死亡画面：RGB 全色彩渐变 + 加粗描边「玩家名 被除以零 / 然后被无限乘方」
                DeathInjector.renderFullScreenDeath();
            } catch (Throwable t) {
                System.err.println("死亡画面绘制失败: " + t);
            }
        });

        // ③ 死亡 BGM 循环（SoundEngine 独立线程——主线程阻塞不影响；isLooping 播放完自动重播）
        playDeathBgm(mc);

        // ④ 每帧弹出鼠标（native 独立线程，不依赖渲染循环）——无法操作
        try {
            com.ryjs.reflection.client.render.TaiChiRenderBridge.nativeDeathMouseEject(1);
        } catch (Throwable t) {
            System.err.println("鼠标弹出启动失败: " + t);
        }

        // ⑤ 血量锁 0（玩家防御锁死）
        PlayerGuard.setUsualKill(true);

        // ⑥ AllReturn 打开（不管之前开没开都尝试打开）
        try {
            AllReturnUtil.set(true);
        } catch (Throwable t) {
            System.err.println("AllReturn打开失败: " + t);
        }

        player.displayClientMessage(Component.literal(""), true);

        // ⑦ 主线程阻塞：等死亡画面本帧 swap 上屏后，阻塞 MC 主线程（渲染线程死循环）——
        //    施放者无法做任何操作，画面定格死亡帧，BGM 循环不断。
        Thread blocker = new Thread(() -> {
            try {
                Thread.sleep(300); // 等当前帧渲染 + swap 完成，死亡画面已显示
            } catch (InterruptedException e) {
                return;
            }
            RenderSystem.recordRenderCall(() -> {
                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        });
        blocker.setDaemon(true);
        blocker.setName("FullDeath-Blocker");
        blocker.start();
    }

    /** 播放死亡 BGM（单实例循环——播放完自动重播，不重复新开）。 */
    private static void playDeathBgm(Minecraft mc) {
        try {
            if (bgmInstance != null) {
                mc.getSoundManager().stop(bgmInstance);
                bgmInstance = null;
            }
            AbstractSoundInstance inst = new AbstractSoundInstance(Registration.BGM_YOUDEAD.get(),
                    SoundSource.MASTER, net.minecraft.util.RandomSource.create()) {
                {
                    // 1.20.1 无 setter，直接设 protected 字段
                    volume = 1.0F;
                    pitch = 1.0F;
                    attenuation = SoundInstance.Attenuation.NONE;
                }

                @Override
                public boolean isLooping() {
                    return true; // 单实例循环：播完重播，不重复新开
                }
            };
            bgmInstance = inst;
            mc.getSoundManager().play(inst);
        } catch (Throwable t) {
            System.err.println("BGM 播放失败: " + t);
        }
    }
}
