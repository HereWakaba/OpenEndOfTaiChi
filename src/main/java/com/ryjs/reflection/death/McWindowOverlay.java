package com.ryjs.reflection.death;

import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.nio.IntBuffer;


public abstract class McWindowOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger("McWindowOverlay");

    static {
        forceHeadlessOff();
    }


    private static boolean forceHeadlessOff() {
        try {
            System.setProperty("java.awt.headless", "false");
            boolean headless = java.awt.GraphicsEnvironment.isHeadless();
            if (headless) {
                LOGGER.warn("AWT 处于 headless 模式，Swing 窗口无法显示");
            }
            return !headless;
        } catch (Throwable t) {
            LOGGER.error("关闭 headless 失败", t);
            return false;
        }
    }

    private long mcWindow;
    protected JFrame frame;
    private Timer trackTimer;
    private boolean disposed = false;
    private boolean peerReady = false;


    protected McWindowOverlay() {
    }




    public void preInit() {
        if (peerReady || disposed) return;
        resolveMcWindow();
        if (mcWindow == 0) return;
        forceHeadlessOff();

        if (SwingUtilities.isEventDispatchThread()) {
            doPreInit();
        } else {
            try {
                SwingUtilities.invokeAndWait(this::doPreInit);
            } catch (Exception e) {
                LOGGER.error("预初始化失败", e);
            }
        }
    }

    public void show() {
        if (disposed) return;
        forceHeadlessOff();

        if (peerReady) {

            SwingUtilities.invokeLater(() -> {
                try {
                    frame.setVisible(true);
                    frame.requestFocus();
                    updatePosition();
                } catch (Exception e) {
                    LOGGER.error("显示失败", e);
                }
            });
        } else {
            resolveMcWindow();
            SwingUtilities.invokeLater(() -> {
                try {
                    buildFrame();
                    frame.setVisible(true);
                    frame.requestFocus();
                    startTracking();
                    LOGGER.info("窗口已显示");
                } catch (Exception e) {
                    LOGGER.error("显示失败", e);
                }
            });
        }
    }

    public void close() {
        disposed = true;
        SwingUtilities.invokeLater(() -> {
            if (trackTimer != null) {
                trackTimer.stop();
                trackTimer = null;
            }
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
            peerReady = false;
        });
    }

    public boolean isVisible() {
        return !disposed && frame != null && frame.isVisible();
    }



    private void doPreInit() {
        try {
            buildFrame();
            updatePosition();
            startTracking();
            peerReady = true;
            LOGGER.info("预初始化完成");
        } catch (Exception e) {
            LOGGER.error("预初始化失败", e);
        }
    }

    private void resolveMcWindow() {
        if (mcWindow != 0) return;
        try {
            mcWindow = Minecraft.getInstance().getWindow().getWindow();
            LOGGER.info("MC GLFW 窗口句柄: {}", mcWindow);
        } catch (Exception e) {
            LOGGER.warn("无法获取 MC 窗口句柄", e);
        }
    }


    private void buildFrame() {
        frame = new JFrame();
        frame.setUndecorated(true);
        frame.setBackground(new Color(0, 0, 0, 0));
        frame.setAlwaysOnTop(true);
        frame.setResizable(false);

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setFocusable(true);


        JPanel content = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                drawDeathScene(g, getWidth(), getHeight());
            }
        };
        content.setOpaque(false);
        frame.setContentPane(content);


        frame.setSize(1, 1);  // 占位，updatePosition() 即刻修正
        updatePosition();
    }


    private void startTracking() {
        if (trackTimer != null) return;
        trackTimer = new Timer(50, e -> {
            if (disposed || frame == null) {
                if (trackTimer != null) trackTimer.stop();
                return;
            }

            if (isMcWindowGone()) {
                LOGGER.info("检测到 MC 窗口已销毁，遮罩随进程收起");
                close();
                return;
            }
            updatePosition();
            frame.repaint();
        });
        trackTimer.start();
    }


    private boolean isMcWindowGone() {
        if (mcWindow == 0) return true;
        try {

            return GLFW.glfwWindowShouldClose(mcWindow);
        } catch (Throwable t) {

            return true;
        }
    }

    private void updatePosition() {
        if (mcWindow == 0 || frame == null) return;
        try {
            long winHwnd = GLFWNativeWin32.glfwGetWin32Window(mcWindow);
            if (winHwnd != 0) {
                com.sun.jna.platform.win32.WinDef.HWND hwnd =
                        new com.sun.jna.platform.win32.WinDef.HWND(new com.sun.jna.Pointer(winHwnd));
                com.sun.jna.platform.win32.WinDef.RECT rect =
                        new com.sun.jna.platform.win32.WinDef.RECT();
                if (com.sun.jna.platform.win32.User32.INSTANCE.GetWindowRect(hwnd, rect)) {
                    int x = rect.left;
                    int y = rect.top;
                    int w = rect.right - rect.left;
                    int h = rect.bottom - rect.top;
                    if (w > 0 && h > 0) {
                        try {
                            if (x != lastX || y != lastY || w != lastW || h != lastH) {
                                com.sun.jna.Pointer framePtr = com.sun.jna.Native.getComponentPointer(frame);
                                if (framePtr != null && framePtr != com.sun.jna.Pointer.NULL) {
                                    com.sun.jna.platform.win32.WinDef.HWND fh =
                                            new com.sun.jna.platform.win32.WinDef.HWND(framePtr);
                                    com.sun.jna.platform.win32.User32.INSTANCE.SetWindowPos(fh, null, x, y, w, h,
                                            0x0004 | 0x0010);
                                    lastX = x; lastY = y; lastW = w; lastH = h;
                                    frame.repaint();
                                    LOGGER.info("已跟随 MC 窗口: {}x{} @({},{})", w, h, x, y);
                                } else {
                                    LOGGER.warn("overlay 句柄为空，暂不移动");
                                }
                            }
                        } catch (Throwable t) {
                            LOGGER.warn("JNA 移动失败: {}", t.toString());
                        }
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("JNA 跟随失败，回退 GLFW: {}", t.toString());
        }
        try {
            IntBuffer xPos  = BufferUtils.createIntBuffer(1);
            IntBuffer yPos  = BufferUtils.createIntBuffer(1);
            IntBuffer wBuf  = BufferUtils.createIntBuffer(1);
            IntBuffer hBuf  = BufferUtils.createIntBuffer(1);

            GLFW.glfwGetWindowPos(mcWindow, xPos, yPos);
            GLFW.glfwGetWindowSize(mcWindow, wBuf, hBuf);

            int x = xPos.get(0);
            int y = yPos.get(0);
            int w = wBuf.get(0);
            int h = hBuf.get(0);

            if (x != frame.getX() || y != frame.getY() ||
                    w != frame.getWidth() || h != frame.getHeight()) {
                frame.setBounds(x, y, w, h);
                frame.repaint();
            }
        } catch (Exception e) {
            LOGGER.warn("GLFW 获取窗口位置失败", e);
        }
    }

    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;
    private int lastW = -1;
    private int lastH = -1;

    private static java.awt.Font deathFontBase;

    private static java.awt.Font deathFont(float size) {
        if (deathFontBase == null) {
            try (java.io.InputStream in = McWindowOverlay.class.getResourceAsStream("/assets/reflection/font/reflection.ttf")) {
                if (in != null) {
                    deathFontBase = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, in);
                }
            } catch (Throwable t) {
                LOGGER.error("reflection.ttf 加载失败，回退 SansSerif", t);
            }
        }
        if (deathFontBase != null) return deathFontBase.deriveFont(java.awt.Font.BOLD, size);
        return new java.awt.Font("SansSerif", java.awt.Font.BOLD, (int) size);
    }

    private static void drawOutlined(Graphics g, String s, int x, int y, java.awt.Color c) {
        g.setColor(java.awt.Color.BLACK);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                g.drawString(s, x + dx, y + dy);
            }
        }
        g.setColor(c);
        g.drawString(s, x, y);
    }

    private void drawDeathScene(Graphics g, int w, int h) {
        if (w <= 0 || h <= 0) return;
        long now = System.currentTimeMillis();
        float flow = (now % 6000L) / 6000.0f;
        float flicker = 0.85f + 0.15f * (float) Math.sin(now * 0.012);
        float span = (float) (w + h);
        for (int y = 0; y < h; y += 2) {
            float hue = ((y + (float) y) / span + flow) % 1.0f;
            int rgb = java.awt.Color.HSBtoRGB(hue, 1.0f, flicker);
            g.setColor(new java.awt.Color(rgb));
            g.fillRect(0, y, w, Math.min(2, h - y));
        }

        g.setFont(deathFont(Math.max(28, w / 14)));
        java.awt.FontMetrics fm = g.getFontMetrics();
        String name = DeathInjector.playerName();
        String died = "You Died";
        drawOutlined(g, name, (w - fm.stringWidth(name)) / 2, h / 2 - fm.getHeight() * 2, java.awt.Color.WHITE);
        drawOutlined(g, died, (w - fm.stringWidth(died)) / 2, h / 2 + fm.getHeight(), new java.awt.Color(255, 85, 85));
    }
}
