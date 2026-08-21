package com.ryjs.agent;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;


public final class DefenseConfigScreen {

    private static final Color BG_TOP = new Color(0x16182A);
    private static final Color BG_BOTTOM = new Color(0x0A0B14);
    private static final Color CARD_BG = new Color(0x1B1E33);
    private static final Color CARD_BORDER = new Color(0x2C3050);
    private static final Color ACCENT = new Color(0x4EC9B0);
    private static final Color TEXT = new Color(0xE8EAF6);
    private static final Color TEXT_DIM = new Color(0x8E93AC);
    private static final Color BTN_PRIMARY_TOP = new Color(0x4EC9B0);
    private static final Color BTN_PRIMARY_BOTTOM = new Color(0x2E8B7A);
    private static final Color BTN_TOP = new Color(0x343957);
    private static final Color BTN_BOTTOM = new Color(0x232741);
    private static final Color BTN_PRIMARY_TEXT = new Color(0x0B1520);

    private DefenseConfigScreen() {
    }

    private static final Object[][] GROUPS = {
            {"反射防御", new String[][]{
                    {"反射拦截（ReflectionFactory 改写 + 反射黑名单） ", DefenseConfig.KEY_INTERCEPT_REFLECTION},
            }},
            {"Unsafe 防御", new String[][]{
                    {"Unsafe 拦截总开关 未完成", DefenseConfig.KEY_INTERCEPT_UNSAFE},
                    {"反射黑名单 未完成", DefenseConfig.KEY_UNSAFE_BLACKLIST},
                    {"字节码代理拦截 Unsafe 方法调用 未完成", DefenseConfig.KEY_UNSAFE_BYTECODE},
                    {"retransform 已加载类未完成", DefenseConfig.KEY_UNSAFE_RETRANSFORM},
                    {"全局置空 theUnsafe未完成", DefenseConfig.KEY_UNSAFE_NULLIFY_THEUNSAFE},
            }},
            {"高危系统接口", new String[][]{
                    {"高危拦截总开关", DefenseConfig.KEY_INTERCEPT_HIGH_RISK},
                    {"进程退出：System.exit 未完成", DefenseConfig.KEY_HR_SYSTEM_EXIT},
                    {"进程退出：Runtime.exit / Runtime.halt 未完成", DefenseConfig.KEY_HR_RUNTIME_EXIT},
                    {"命令执行：Runtime.exec 未完成", DefenseConfig.KEY_HR_EXEC},
                    {"命令执行：ProcessBuilder.start 未完成", DefenseConfig.KEY_HR_PROCESS_START},
                    {"本地库加载：System.load / loadLibrary 完成一半？", DefenseConfig.KEY_HR_SYSTEM_LOAD},
                    {"本地库加载：Runtime.load / loadLibrary 未完成", DefenseConfig.KEY_HR_RUNTIME_LOAD},
            }},
            {"Agent / Attach", new String[][]{
                    {"阻止 attach（VirtualMachine.attach）很显然这个没写完", DefenseConfig.KEY_HR_ATTACH},
                    {"阻止 loadAgent0（Instrumentation 挂载）很显然这个也没写完", DefenseConfig.KEY_HR_LOAD_AGENT},
            }},
            {"拦截", new String[][]{
                    {"AllSuper", DefenseConfig.KEY_INTERCEPT_ALLRETURN},
                    {"反退出", DefenseConfig.KEY_FULL_ANTI_EXIT},
                    {"中和优先级低于我方的SPI服务", DefenseConfig.KEY_INTERCEPT_COREMOD},
                    {"不重启删Mixin注解", DefenseConfig.KEY_INTERCEPT_MIXIN},
                    {"清空方法体", DefenseConfig.KEY_FULL_CLEAN_METHOD},
                    {"AllReturn", DefenseConfig.KEY_FULL_COEXIST_ALLRETURN},
                    {"类还原", DefenseConfig.KEY_HOOK_RESTORE_GUARD},
            }},
            {"BUG模式", new String[][]{
                    {"打爆本项目对于JVMTI相关已知功能", DefenseConfig.KEY_HOOK_FULL_BLOCK},
                    {"殴打JVMTIEnv", DefenseConfig.KEY_HOOK_JVMTI_BREAK},
                    {"打爆JVMTI", DefenseConfig.KEY_HOOK_JVMTI_BLAST},
            }},
            {"？？？", new String[][]{
                    {"太极悖论亲自出手", DefenseConfig.KEY_PROXY_SHELL},
            }},
    };

    private static String groupTitle(int i) {
        return (String) GROUPS[i][0];
    }

    private static String[][] groupItems(int i) {
        return (String[][]) GROUPS[i][1];
    }

    public static void showBlocking() {
        DefenseConfig.ensureLoaded();
        if (!DefenseConfig.showPanel()) {
            DefenseAgent.log("panel.show=false，跳过配置面板，直接用现有配置继续");
            return;
        }
        try {
            if (GraphicsEnvironment.isHeadless()) {
                DefenseAgent.log("无图形环境，跳过配置面板，使用默认/现有配置");
                return;
            }
        } catch (Throwable t) {
            return;
        }

        try {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Throwable ignored) {}

            final JDialog dialog = new JDialog((java.awt.Frame) null, "防御配置", true);
            GradientPanel root = new GradientPanel();
            root.setLayout(new BorderLayout(0, 0));
            JPanel header = new JPanel();
            header.setOpaque(false);
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            header.setBorder(new EmptyBorder(18, 22, 10, 22));
            JLabel bigTitle = new JLabel("防御配置");
            bigTitle.setFont(bigTitle.getFont().deriveFont(Font.BOLD, 22f));
            bigTitle.setForeground(TEXT);
            bigTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.add(bigTitle);
            header.add(Box.createVerticalStrut(5));
            boolean agent = com.ryjs.coremod.ImmediateWindowProvider.EartyLoading.agentDetected;
            JLabel status = new JLabel(agent
                    ? "外部参数状态：已检测 -agent 参数"
                    : "外部参数状态：未检测到");
            status.setForeground(agent ? new Color(0xF38BA8) : new Color(0xA6E3A1));
            status.setFont(status.getFont().deriveFont(13f));
            status.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.add(status);
            root.add(header, BorderLayout.NORTH);

            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBorder(new EmptyBorder(4, 22, 4, 22));
            final Map<JCheckBox, String> boxToKey = new LinkedHashMap<>();
            final JCheckBox[] extra = new JCheckBox[3];

            for (int i = 0; i < GROUPS.length; i++) {
                JPanel body = new JPanel();
                body.setOpaque(false);
                body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
                for (String[] item : groupItems(i)) {
                    NiceCheckBox box = new NiceCheckBox(item[0], DefenseConfig.getRaw(item[1]));
                    stretch(box);
                    box.setAlignmentX(Component.LEFT_ALIGNMENT);
                    body.add(box);
                    boxToKey.put(box, item[1]);
                }
                CardPanel card = new CardPanel(groupTitle(i), body);
                content.add(card);
                content.add(Box.createVerticalStrut(8));
            }

            JPanel launchBody = new JPanel();
            launchBody.setOpaque(false);
            launchBody.setLayout(new BoxLayout(launchBody, BoxLayout.Y_AXIS));
            NiceCheckBox showBox = new NiceCheckBox("下次启动仍显示此配置面板", DefenseConfig.getRaw(DefenseConfig.KEY_PANEL_SHOW));
            stretch(showBox);
            showBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            launchBody.add(showBox);
            extra[0] = showBox;
            NiceCheckBox suicideBox = new NiceCheckBox("检测到外部 agent 参数时自爆",
                    DefenseConfig.getRaw(DefenseConfig.KEY_AGENT_SUICIDE));
            stretch(suicideBox);
            suicideBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            launchBody.add(suicideBox);
            extra[1] = suicideBox;
            NiceCheckBox earlyBox = new NiceCheckBox("启动早期画面接管 坏的",
                    DefenseConfig.getRaw(DefenseConfig.KEY_EARLY_DISPLAY));
            stretch(earlyBox);
            earlyBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            launchBody.add(earlyBox);
            extra[2] = earlyBox;
            content.add(new CardPanel("不知道", launchBody));
            content.add(Box.createVerticalStrut(8));

            // 对抗策略卡片（兼容模式——互斥）
            JPanel compatBody = new JPanel();
            compatBody.setOpaque(false);
            compatBody.setLayout(new BoxLayout(compatBody, BoxLayout.Y_AXIS));
            NiceCheckBox compatBox = new NiceCheckBox("可能比较兼容的模式 非必要功能全关", DefenseConfig.compatMode());
            stretch(compatBox);
            compatBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            compatBody.add(compatBox);
            NiceCheckBox fullFilterBox = new NiceCheckBox("重启游戏完整过滤Coremod服务",
                    DefenseConfig.getRaw(DefenseConfig.KEY_FULL_FILTER_COREMOD));
            stretch(fullFilterBox);
            fullFilterBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            compatBody.add(fullFilterBox);
            boxToKey.put(fullFilterBox, DefenseConfig.KEY_FULL_FILTER_COREMOD);
            NiceCheckBox filterMixinBox = new NiceCheckBox("重启游戏删@Mixin注解",
                    DefenseConfig.getRaw(DefenseConfig.KEY_FULL_FILTER_MIXIN));
            stretch(filterMixinBox);
            filterMixinBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            compatBody.add(filterMixinBox);
            boxToKey.put(filterMixinBox, DefenseConfig.KEY_FULL_FILTER_MIXIN);
            content.add(new CardPanel("何意味功能", compatBody));

            JScrollPane scroll = new JScrollPane(content);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setBorder(null);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            root.add(scroll, BorderLayout.CENTER);

            // ===== 底部：一键开关 + 保存 =====
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 12));
            buttons.setOpaque(false);
            buttons.setBorder(new EmptyBorder(0, 22, 16, 22));
            NiceButton allOn = new NiceButton("全选", false);
            NiceButton allOff = new NiceButton("全不选", false);
            NiceButton cancel = new NiceButton("按现有配置继续", false);
            NiceButton save = new NiceButton("保存并继续", true);
            buttons.add(allOn);
            buttons.add(allOff);
            buttons.add(cancel);
            buttons.add(save);
            root.add(buttons, BorderLayout.SOUTH);

            bindMutex(boxToKey, compatBox, extra);

            allOn.addActionListener(e -> {
                for (JCheckBox b : boxToKey.keySet()) b.setSelected(true);
                bindMutex(boxToKey, compatBox, extra);
            });
            allOff.addActionListener(e -> {
                for (JCheckBox b : boxToKey.keySet()) b.setSelected(false);
                bindMutex(boxToKey, compatBox, extra);
            });
            save.addActionListener(e -> {
                for (Map.Entry<JCheckBox, String> en : boxToKey.entrySet()) {
                    DefenseConfig.setValue(en.getValue(), en.getKey().isSelected());
                }
                String[] extraKeys = {DefenseConfig.KEY_PANEL_SHOW, DefenseConfig.KEY_AGENT_SUICIDE, DefenseConfig.KEY_EARLY_DISPLAY};
                for (int i = 0; i < extra.length; i++) {
                    if (extra[i] != null) {
                        DefenseConfig.setValue(extraKeys[i], extra[i].isSelected());
                    }
                }
                DefenseConfig.setCompatMode(compatBox.isSelected());
                DefenseConfig.save();
                dialog.dispose();
            });
            cancel.addActionListener(e -> dialog.dispose());

            dialog.setContentPane(root);
            dialog.setSize(700, 680);
            dialog.setLocationRelativeTo(null);
            dialog.setAlwaysOnTop(true);
            dialog.setVisible(true);
        } catch (Throwable t) {
            DefenseAgent.warn("[Defense] 配置面板异常，跳过并使用现有配置: " + t.getMessage());
        }
    }

    private static void stretch(NiceCheckBox box) {
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
    }

    private static void bindMutex(Map<JCheckBox, String> boxToKey, JCheckBox compatBox, JCheckBox[] extra) {
        final JCheckBox normalARBox = findBox(boxToKey, DefenseConfig.KEY_INTERCEPT_ALLRETURN);
        final JCheckBox cleanMethodBox = findBox(boxToKey, DefenseConfig.KEY_FULL_CLEAN_METHOD);
        final JCheckBox coexistARBox = findBox(boxToKey, DefenseConfig.KEY_FULL_COEXIST_ALLRETURN);
        if (normalARBox == null || cleanMethodBox == null || coexistARBox == null) return;
        Runnable mutexSync = () -> {
            if (compatBox.isSelected()) {
                for (Map.Entry<JCheckBox, String> e : boxToKey.entrySet()) {
                    e.getKey().setSelected(false);
                    e.getKey().setEnabled(false);
                }
                for (JCheckBox b : extra) {
                    if (b != null && b != extra[0]) {
                        b.setSelected(false);
                        b.setEnabled(false);
                    }
                }
                return;
            }

            for (Map.Entry<JCheckBox, String> e : boxToKey.entrySet()) {
                e.getKey().setEnabled(true);
            }
            for (JCheckBox b : extra) {
                if (b != null) b.setEnabled(true);
            }

            boolean fullOn = cleanMethodBox.isSelected() || coexistARBox.isSelected();
            if (fullOn && normalARBox.isSelected()) normalARBox.setSelected(false);
            normalARBox.setEnabled(!fullOn);
            boolean normalOn = normalARBox.isSelected();
            cleanMethodBox.setEnabled(!(normalOn && !fullOn));
            coexistARBox.setEnabled(!(normalOn && !fullOn));
        };
        normalARBox.addActionListener(e -> mutexSync.run());
        cleanMethodBox.addActionListener(e -> mutexSync.run());
        coexistARBox.addActionListener(e -> mutexSync.run());
        compatBox.addActionListener(e -> mutexSync.run());
        mutexSync.run();
    }

    private static JCheckBox findBox(Map<JCheckBox, String> boxToKey, String key) {
        for (Map.Entry<JCheckBox, String> e : boxToKey.entrySet()) {
            if (key.equals(e.getValue())) return e.getKey();
        }
        return null;
    }


    private static class GradientPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setPaint(new GradientPaint(0, 0, BG_TOP, 0, getHeight(), BG_BOTTOM));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }


    private static class CardPanel extends JPanel {
        CardPanel(String title, JComponent body) {
            setLayout(new BorderLayout(0, 6));
            setOpaque(false);
            setBorder(new EmptyBorder(12, 14, 12, 14));
            add(new TitleLabel(title), BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(CARD_BG);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.setColor(CARD_BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.dispose();
        }
    }

    private static class TitleLabel extends JLabel {
        TitleLabel(String title) {
            super(title);
            setOpaque(false);
            setForeground(TEXT);
            setFont(getFont().deriveFont(Font.BOLD, 15f));
            setBorder(new EmptyBorder(2, 12, 4, 4));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(ACCENT);
            g2.fillRoundRect(2, 4, 4, getHeight() - 10, 2, 2);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class NiceCheckBox extends JCheckBox {
        NiceCheckBox(String text, boolean selected) {
            super(text, selected);
            setOpaque(false);
            setForeground(TEXT);
            setFont(getFont().deriveFont(14f));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int box = 18;
            int y = (getHeight() - box) / 2;
            if (isSelected()) {
                g2.setColor(ACCENT);
                g2.fillRoundRect(0, y, box, box, 6, 6);
                g2.setColor(BG_BOTTOM);
                g2.setStroke(new BasicStroke(2.2f));
                g2.drawPolyline(new int[]{5, 8, 14}, new int[]{y + 9, y + 13, y + 6}, 3);
            } else {
                g2.setColor(CARD_BORDER);
                g2.drawRoundRect(0, y, box, box, 6, 6);
            }

            FontMetrics fm = g2.getFontMetrics();
            int avail = getWidth() - box - 10;
            String text = getText();
            if (fm.stringWidth(text) > avail) {
                String t = text;
                while (fm.stringWidth(t + "…") > avail && t.length() > 1) {
                    t = t.substring(0, t.length() - 1);
                }
                text = t + "…";
            }
            g2.setColor(isEnabled() ? TEXT : TEXT_DIM);
            g2.drawString(text, box + 8, y + box - 4);
            g2.dispose();
        }
    }

    private static class NiceButton extends JButton {
        private final boolean primary;
        private boolean hover = false;

        NiceButton(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setOpaque(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setForeground(primary ? BTN_PRIMARY_TEXT : TEXT);
            setFont(getFont().deriveFont(14f));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            Color top = primary ? BTN_PRIMARY_TOP : BTN_TOP;
            Color bottom = primary ? BTN_PRIMARY_BOTTOM : BTN_BOTTOM;
            if (hover) {
                top = top.brighter();
                bottom = bottom.brighter();
            }
            g2.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
            g2.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
            g2.setColor(new Color(0x4A, 0x50, 0x78, 140));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);

            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(getText());
            int th = fm.getAscent();
            g2.setColor(getForeground());
            g2.drawString(getText(), (w - tw) / 2, (h + th) / 2 - 2);
            g2.dispose();
        }
    }
}
