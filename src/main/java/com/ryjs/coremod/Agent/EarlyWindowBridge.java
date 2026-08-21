package com.ryjs.coremod.Agent;

import com.ryjs.reflection.client.render.TaiChiRenderBridge;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class EarlyWindowBridge {

    private static final String BG_PATH = "/assets/reflection/textures/gui/modloader/main.png";
    /** 帧尺寸：与启动参数 --width/--height 一致（C++ 全屏 quad 拉伸，实际 framebuffer 不同也只轻微变形）。 */
    private static final int FW = 854;
    private static final int FH = 480;
    /** 帧推送周期（ms）：进度条动画粒度。 */
    private static final long TICK_MS = 150L;
    /** GAME 层就绪后再持续推送时长（ms）：给 GAME 渲染器首次渲染留缓冲，避免闪 Forge 默认画面。 */
    private static final long SETTLE_MS = 800L;

    private static volatile boolean started = false;
    private static volatile boolean stopped = false;
    private static volatile long gameReadySince = -1L;

    private static BufferedImage bgImage;
    private static ScheduledExecutorService scheduler;

    // ---- 进度反射（fml SERVICE 层可见，但反射更稳——失败只影响进度条，不影响背景） ----
    private static Method mGetGameLayer;
    private static Method mGetCurrentProgress;
    private static Method mMeterProgress;
    private static Method mGetMessages;
    private static boolean progressResolved = false;
    /** 单调不回退的进度（fml 多 bar 取平均后缓存，避免回跳）。 */
    private static float shownProgress = 0.0F;
    /** 阶段消息缓存（fml StartupNotificationManager.getMessages，500ms 节流）。 */
    private static String stageLabel = "加载中…";
    private static long stageLabelAt = 0L;
    /** C++ 通道亮块模式：SERVICE 副本=true（亮块由 C++ 每帧绘制）；GAME 副本默认 false（Java 每帧画）。 */
    private static boolean cppHandlesBar = false;

    private EarlyWindowBridge() {
    }

    /** 启动早期画面桥（AgentUtil 注册 DisplayWindowTransformer 时调用；失败自动禁用，不影响其他流程）。 */
    public static void start() {
        if (started) return;
        started = true;
        try {
            try {
                loadBackground();
                resolveProgressRefs();
                cppHandlesBar = true; // 亮块交给 C++ 每帧绘制（60fps）；本副本帧只画轨道+文字
                TaiChiRenderBridge.nativeEarlyBar(1);
            } catch (Throwable t) {
                System.out.println("[EarlyWindow] 早期资源准备失败（不影响主流程）: " + t);
            }
            pushFrame(); // 立即推首帧——窗口第一帧 swap 即上屏
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "early-window-bridge");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(EarlyWindowBridge::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            stopped = true;
            System.out.println("[EarlyWindow] 启动失败（已禁用，回退深蓝黑 glClear）: " + t);
        }
    }

    private static void tick() {
        try {
            if (stopped) return;
            if (gameReady()) {
                if (gameReadySince < 0) gameReadySince = System.currentTimeMillis();
                if (System.currentTimeMillis() - gameReadySince > SETTLE_MS) {
                    stop(); // GAME 渲染器已接管——关闭早期通道
                    return;
                }
            } else {
                gameReadySince = -1L;
            }
            pushFrame();
        } catch (Throwable ignored) {
        }
    }

    /** GAME 层就绪检测：FMLLoader.getGameLayer() 存在且含 reflection 模块（与 DisplayWindowTransformer 合成方法的判定一致）。 */
    private static boolean gameReady() {
        try {
            if (mGetGameLayer == null) {
                mGetGameLayer = Class.forName("net.minecraftforge.fml.loading.FMLLoader")
                        .getMethod("getGameLayer");
            }
            Object layer = mGetGameLayer.invoke(null);
            if (layer instanceof ModuleLayer ml) {
                return ml.findModule("reflection").isPresent();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 停止：关定时器 + 关 C++ 通道（GAME 渲染器接管后不再贴图）。 */
    public static void stop() {
        if (stopped) return;
        stopped = true;
        try {
            if (scheduler != null) scheduler.shutdownNow();
        } catch (Throwable ignored) {
        }
        try {
            TaiChiRenderBridge.nativeEarlyFrameOff();
        } catch (Throwable ignored) {
        }
    }

    private static void loadBackground() throws Exception {
        try (java.io.InputStream in = EarlyWindowBridge.class.getResourceAsStream(BG_PATH)) {
            if (in == null) throw new IllegalStateException("找不到资源 " + BG_PATH);
            bgImage = ImageIO.read(in);
        }
    }

    /** 合成一帧（main.png cover 缩放 + 青色胶囊进度条 + 百分比）→ 返回 ARGB 像素或 null。
     * 供 {@link #pushFrame()}（C++ 早期通道，854x480）与 ShellEarlyRenderer（GAME 渲染器，视口尺寸）
     * 共用——两张画面 100% 同逻辑，切换无缝不弹跳。
     */
    public static int[] composeFrame(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        try {
            if (bgImage == null) {
                try {
                    loadBackground();
                } catch (Throwable ignored) {
                }
            }
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                // 1) 深蓝黑渐变打底（背景图透明区域兜底，与配置面板同配色）
                for (int y = 0; y < h; y++) {
                    float t = (float) y / h;
                    g.setColor(new Color(
                            (int) (0x16 + (0x0A - 0x16) * t),
                            (int) (0x18 + (0x0B - 0x18) * t),
                            (int) (0x2A + (0x14 - 0x2A) * t)));
                    g.fillRect(0, y, w, 1);
                }

                // 2) main.png cover 缩放（无变形：按目标比例裁剪源图）
                if (bgImage != null) {
                    double scale = Math.max((double) w / bgImage.getWidth(), (double) h / bgImage.getHeight());
                    int sw = (int) Math.round(w / scale);
                    int sh = (int) Math.round(h / scale);
                    int sx = (bgImage.getWidth() - sw) / 2;
                    int sy = (bgImage.getHeight() - sh) / 2;
                    g.drawImage(bgImage, 0, 0, w, h, sx, sy, sx + sw, sy + sh, null);
                }

                // 3) 进度条（底部居中青色胶囊）+ 阶段文字/百分比：主类入口（Main.main）已执行后=完成态 100%，
                //    之前为 indeterminate（亮块：C++ 通道由 C++ 画，GAME 通道由 Java 画）+ 阶段消息
                //    注意：不能用 Class.forName(Minecraft) 探测（提前加载打乱 eventbus transform 时序——见 EarlyMainHooks）
                boolean ready = com.ryjs.reflection.hook.EarlyMainHooks.mainEntered;
                float prog = ready ? 1.0f : currentProgress();
                drawProgressBar(g, w, h, prog, ready);
            } finally {
                g.dispose();
            }
            return img.getRGB(0, 0, w, h, null, 0, w);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void resolveProgressRefs() {
        try {
            Class<?> snm = Class.forName("net.minecraftforge.fml.loading.progress.StartupNotificationManager");
            mGetCurrentProgress = snm.getMethod("getCurrentProgress");
            Class<?> pm = Class.forName("net.minecraftforge.fml.loading.progress.ProgressMeter");
            mMeterProgress = pm.getMethod("progress");
        } catch (Throwable ignored) {
        }
        try {
            if (mGetMessages == null) {
                mGetMessages = Class.forName("net.minecraftforge.fml.loading.progress.StartupNotificationManager")
                        .getMethod("getMessages");
            }
        } catch (Throwable ignored) {
        }
        progressResolved = mGetCurrentProgress != null && mMeterProgress != null;
    }

    /** fml 最近阶段消息文本（取最新一条）；失败回退「加载中…」。500ms 节流缓存。 */
    private static String stageText() {
        long now = System.currentTimeMillis();
        if (now - stageLabelAt < 500L) return stageLabel;
        stageLabelAt = now;
        try {
            if (mGetMessages == null) return stageLabel;
            Object list = mGetMessages.invoke(null);
            if (list instanceof List<?> l && !l.isEmpty()) {
                Object am = l.get(0);
                Object msg = am.getClass().getMethod("message").invoke(am);
                Object txt = msg.getClass().getMethod("getText").invoke(msg);
                if (txt instanceof String s && !s.isEmpty()) {
                    stageLabel = s.length() > 26 ? s.substring(0, 26) + "…" : s;
                }
            }
        } catch (Throwable ignored) {
        }
        return stageLabel;
    }

    /** 当前 fml 加载进度（0~1）：多 bar 取平均、单调不回退；拿不到返回上一值（首帧 0）。 */
    private static float currentProgress() {
        if (!progressResolved) return shownProgress;
        try {
            Object list = mGetCurrentProgress.invoke(null);
            if (list instanceof List<?> l && !l.isEmpty()) {
                float sum = 0.0F;
                int n = 0;
                for (Object m : l) {
                    Object p = mMeterProgress.invoke(m);
                    if (p instanceof Float f && !Float.isNaN(f) && !Float.isInfinite(f) && f >= 0.0F && f <= 1.0F) {
                        sum += f;
                        n++;
                    }
                }
                if (n > 0) {
                    float avg = sum / n;
                    if (avg > shownProgress) shownProgress = avg; // 单调：只升不降
                }
            }
        } catch (Throwable ignored) {
        }
        return shownProgress;
    }

    /** 合成一帧（main.png cover 缩放 + 青色胶囊进度条 + 百分比）→ 推给 C++。 */
    private static void pushFrame() {
        try {
            int[] px = composeFrame(FW, FH);
            if (px != null) {
                TaiChiRenderBridge.nativeEarlyFrame(px, FW, FH);
            }
        } catch (Throwable t) {
            stopped = true;
            System.out.println("[EarlyWindow] 帧生成失败（已禁用）: " + t);
        }
    }

    /**
     * 进度条：底槽 + 填充/动画 + 边框 + 上方文字。
     * ready=true：完成态（全满 + 100%）；prog&gt;0：真实进度（渐变填充 + 百分比）；
     * 否则 indeterminate 滑动动画（2s 来回）+ 阶段消息文字（fml getMessages——1.20.1 无真实进度数据）。
     */
    private static void drawProgressBar(Graphics2D g, int w, int h, float prog, boolean ready) {
        final int barW = (int) (w * 0.6f);
        final int barH = 16;
        final int bx = (w - barW) / 2;
        final int by = h - 64;
        final int r = barH / 2;

        // 底槽
        g.setColor(new Color(0x0A, 0x0B, 0x14, 170));
        g.fill(new RoundRectangle2D.Float(bx, by, barW, barH, r, r));

        if (ready) {
            // 完成态：全满（青色）
            g.setColor(new Color(0x4A, 0x90, 0xFA, 235));
            g.fill(new RoundRectangle2D.Float(bx, by, barW, barH, r, r));
        } else if (prog > 0.001f) {
            // 真实进度：青色渐变填充
            int fillW = Math.min(barW, (int) (barW * prog));
            g.clip(new RoundRectangle2D.Float(bx, by, barW, barH, r, r));
            for (int x = 0; x < fillW; x++) {
                float t = (float) x / barW;
                g.setColor(new Color(
                        (int) (0x89 + (0x4A - 0x89) * t),
                        (int) (0xB4 + (0x78 - 0xB4) * t),
                        (int) (0xFA + (0x90 - 0xFA) * t)));
                g.fillRect(bx + x, by, 1, barH);
            }
            g.setClip(null);
        } else if (!cppHandlesBar) {
            // indeterminate：亮块左右滑动（2s 一个来回——与 Forge 默认进度条同风格）
            // 仅 GAME 通道（每帧合成，60fps 流畅）画；C++ 通道的亮块由 C++ 侧每帧绘制（帧率同样流畅）
            long t = System.currentTimeMillis() % 2000L;
            float pos = t < 1000L ? t / 1000.0f : (2000L - t) / 1000.0f; // 0→1→0
            int blkW = Math.max(barH * 2, barW / 4);
            int bx2 = bx + (int) (pos * (barW - blkW));
            g.setColor(new Color(0x89, 0xB4, 0xFA, 225));
            g.fill(new RoundRectangle2D.Float(bx2, by, blkW, barH, r, r));
        }

        // 边框
        g.setColor(new Color(0x89, 0xB4, 0xFA, 200));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new RoundRectangle2D.Float(bx, by, barW, barH, r, r));

        // 上方文字：完成=100%，真实进度=百分比，窗口期=阶段消息（会随 fml 阶段变化）
        String label;
        if (ready) {
            label = "100%";
        } else if (prog > 0.001f) {
            label = Math.round(prog * 100f) + "%";
        } else {
            label = stageText();
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(0xE6, 0xE9, 0xF5, 230));
        g.drawString(label, bx + (barW - fm.stringWidth(label)) / 2, by - 8);
    }
}
