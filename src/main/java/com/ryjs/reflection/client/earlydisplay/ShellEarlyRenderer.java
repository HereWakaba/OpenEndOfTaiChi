package com.ryjs.reflection.client.earlydisplay;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import javax.imageio.ImageIO;


public final class ShellEarlyRenderer {

    private ShellEarlyRenderer() {
    }

    private static final String LOGO_PATH = "/assets/reflection/textures/gui/modloader/main.png";

    private static final String VERT_SRC =
            "#version 150 core\n" +
            "in vec2 aPos;\nin vec2 aUV;\nout vec2 vUV;\n" +
            "uniform vec2 uOffset;\nuniform vec2 uScale;\n" +
            "void main(){ gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0); vUV = aUV; }\n";
    private static final String FRAG_SRC =
            "#version 150 core\n" +
            "in vec2 vUV;\nout vec4 fragColor;\n" +
            "uniform sampler2D uTex;\nuniform vec4 uColor;\n" +
            "void main(){ fragColor = texture(uTex, vUV) * uColor; }\n";

    private static volatile boolean initialized = false;
    private static volatile boolean failed = false;
    private static volatile boolean preloaded = false;

    private static int program;
    private static int vao;
    private static int vbo;
    private static int ebo;
    private static int locOffset;
    private static int locScale;
    private static int locColor;
    private static int frameTex;
    private static int whiteTex;


    private static int cachedW = 854;
    private static int cachedH = 480;
    private static int[] cachedPx = null;
    private static boolean firstFrame = true;
    private static int frameGenW = 0;
    private static int frameGenH = 0;
    private static boolean fontFailed = false;


    private static Method mGetProgress;
    private static Method mMeterProgress;
    private static boolean progressResolved = false;
    private static float shownProgress = 0.0F;


    private static BufferedImage bgImage;
    private static boolean bgFailed = false;


    public static void preload() {
        try {
            cachedPx = com.ryjs.coremod.Agent.EarlyWindowBridge.composeFrame(cachedW, cachedH);
            if (cachedPx == null) cachedPx = renderFrameImpl(cachedW, cachedH);
            preloaded = true;
        } catch (Throwable t) {
            System.out.println("预热失败: " + t);
        }
    }


    public static void render() {
        if (failed) {
            return;
        }
        try {
            if (!initialized) {
                initGL();
                initialized = true;
            }
        } catch (Throwable t) {
            failed = true;
            System.err.println("GL初始化失败: " + t);
            return;
        }

        int savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int savedVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int savedActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int savedTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int blendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        int blendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);

        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);

            int[] px = renderFrame();
            if (px != null) {
                uploadFrame(px);
                drawQuad(frameTex, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F);
            }
        } catch (Throwable ignore) {
        } finally {
            GL30.glBindVertexArray(savedVao);
            GL20.glUseProgram(savedProgram);
            GL13.glActiveTexture(savedActive);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTex);
            if (wasBlend) {
                GL11.glEnable(GL11.GL_BLEND);
            } else {
                GL11.glDisable(GL11.GL_BLEND);
            }
            if (wasDepth) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            } else {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }
            if (wasCull) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            } else {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            GL11.glBlendFunc(blendSrc, blendDst);
        }
    }


    private static int[] renderFrame() {
        IntBuffer vp = BufferUtils.createIntBuffer(4);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        int w = vp.get(2);
        int h = vp.get(3);

        if (firstFrame && cachedPx != null) {
            firstFrame = false;
            frameGenW = cachedW;
            frameGenH = cachedH;
            return cachedPx;
        }
        firstFrame = false;
        if (w <= 0 || h <= 0) {
            return cachedPx;
        }
        int[] px = com.ryjs.coremod.Agent.EarlyWindowBridge.composeFrame(w, h);
        if (px == null) px = renderFrameImpl(w, h); // 回退（极不可能）
        frameGenW = w;
        frameGenH = h;
        return px;
    }


    private static int[] renderFrameImpl(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            for (int y = 0; y < height; y++) {
                float t = (float) y / height;
                int r = (int) (0x16 + (0x0A - 0x16) * t);
                int gr = (int) (0x18 + (0x0B - 0x18) * t);
                int b = (int) (0x2A + (0x14 - 0x2A) * t);
                g.setColor(new Color(r, gr, b));
                g.fillRect(0, y, width, 1);
            }
    

            BufferedImage bg = loadBgImage();
            if (bg != null) {
                g.drawImage(bg, 0, 0, width, height, null);
            }
    

            float p = Math.max(0.0F, Math.min(1.0F, computeProgress()));
            int barW = (int) (width * 0.6f);
            int barH = Math.max(9, height / 55);
            int bx = (width - barW) / 2;
            int by = (int) (height * 0.86f);
            int arc = barH;

            g.setColor(new Color(0x0A, 0x0B, 0x14, 210));
            g.fillRoundRect(bx, by, barW, barH, arc, arc);
            g.setColor(new Color(0x2C, 0x30, 0x50, 170));
            g.drawRoundRect(bx, by, barW, barH, arc, arc);

            if (p > 0.0F) {
                int fillW = Math.max(barH, (int) (barW * p));
                Shape oldClip = g.getClip();
                g.clip(new RoundRectangle2D.Float(bx, by, barW, barH, arc, arc));
                g.setPaint(new GradientPaint(bx, 0,
                        new Color(0x4E, 0xC9, 0xB0), bx + fillW, 0, new Color(0x2E, 0x8B, 0x7A)));
                g.fillRoundRect(bx, by, fillW, barH, arc, arc);
                g.setClip(oldClip);

                int lx = bx + fillW - 1;
                g.setColor(new Color(255, 255, 255, 130));
                g.fillOval(lx - barH / 2, by + 1, barH - 2, barH - 2);
            }

            g.setFont(loadFont().deriveFont(Font.BOLD, Math.max(13f, barH + 6f)));
            String pct = (int) (p * 100) + "%";
            g.setColor(new Color(0xE8, 0xEA, 0xF6, 220));
            g.drawString(pct, bx + barW + 14, by + barH - 2);
        } finally {
            g.dispose();
        }
        return img.getRGB(0, 0, width, height, null, 0, width);
    }
    

    private static BufferedImage loadBgImage() {
        if (bgImage == null && !bgFailed) {
            try (InputStream in = ShellEarlyRenderer.class.getResourceAsStream(LOGO_PATH)) {
                if (in != null) {
                    bgImage = ImageIO.read(in);
                }
                if (bgImage == null) {
                    bgFailed = true;
                }
            } catch (Throwable t) {
                bgFailed = true;
                System.err.println("背景图加载失败: " + t);
            }
        }
        return bgImage;
    }

    private static void drawOutlined(Graphics2D g, String s, int x, int y, Color c) {
        g.setColor(Color.BLACK);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                g.drawString(s, x + dx, y + dy);
            }
        }
        g.setColor(c);
        g.drawString(s, x, y);
    }


    private static Font fontBase;

    private static Font loadFont() {
        if (fontBase == null && !fontFailed) {
            try (InputStream in = ShellEarlyRenderer.class.getResourceAsStream("/assets/reflection/font/reflection.ttf")) {
                if (in != null) {
                    fontBase = Font.createFont(Font.TRUETYPE_FONT, in);
                }
            } catch (Throwable t) {
                fontFailed = true;
            }
        }
        return fontBase != null ? fontBase : new Font("SansSerif", Font.BOLD, 24);
    }

    private static void uploadFrame(int[] px) {
        int w = frameGenW;
        int h = frameGenH;
        if (w <= 0 || h <= 0 || px.length < w * h) {
            return;
        }
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            int argb = px[i];
            rgba[i * 4] = (byte) (argb >> 16 & 0xFF);
            rgba[i * 4 + 1] = (byte) (argb >> 8 & 0xFF);
            rgba[i * 4 + 2] = (byte) (argb & 0xFF);
            rgba[i * 4 + 3] = (byte) (argb >> 24 & 0xFF);
        }
        ByteBuffer buf = BufferUtils.createByteBuffer(rgba.length);
        buf.put(rgba).flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, frameTex);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
    }


    private static void drawQuad(int tex, float ox, float oy, float sx, float sy,
                                 float r, float g, float b, float a) {
        GL20.glUniform2f(locOffset, ox, oy);
        GL20.glUniform2f(locScale, sx, sy);
        GL20.glUniform4f(locColor, r, g, b, a);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glDrawElements(GL11.GL_TRIANGLES, 6, GL11.GL_UNSIGNED_INT, 0L);
    }

    private static void initGL() {
        program = buildProgram(VERT_SRC, FRAG_SRC);
        locOffset = GL20.glGetUniformLocation(program, "uOffset");
        locScale = GL20.glGetUniformLocation(program, "uScale");
        locColor = GL20.glGetUniformLocation(program, "uColor");
        int locTex = GL20.glGetUniformLocation(program, "uTex");

        float[] verts = {
                -1.0F, 1.0F, 0.0F, 1.0F,
                -1.0F, -1.0F, 0.0F, 0.0F,
                1.0F, -1.0F, 1.0F, 0.0F,
                1.0F, 1.0F, 1.0F, 1.0F
        };
        int[] idx = {0, 1, 2, 2, 3, 0};

        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        ebo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        FloatBuffer vb = BufferUtils.createFloatBuffer(verts.length);
        vb.put(verts).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vb, GL15.GL_STATIC_DRAW);
        IntBuffer ib = BufferUtils.createIntBuffer(idx.length);
        ib.put(idx).flip();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ib, GL15.GL_STATIC_DRAW);
        int aPos = GL20.glGetAttribLocation(program, "aPos");
        int aUV = GL20.glGetAttribLocation(program, "aUV");
        GL20.glVertexAttribPointer(aPos, 2, GL11.GL_FLOAT, false, 16, 0L);
        GL20.glEnableVertexAttribArray(aPos);
        GL20.glVertexAttribPointer(aUV, 2, GL11.GL_FLOAT, false, 16, 8L);
        GL20.glEnableVertexAttribArray(aUV);
        GL30.glBindVertexArray(0);

        whiteTex = createTexture(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}, 1, 1);
        frameTex = createTexture(new byte[]{(byte) 0, (byte) 0, (byte) 0, (byte) 255}, 1, 1); 
        GL20.glUseProgram(program);
        GL20.glUniform1i(locTex, 0);
    }

    private static int createTexture(byte[] rgba, int w, int h) {
        ByteBuffer buf = BufferUtils.createByteBuffer(rgba.length);
        buf.put(rgba).flip();
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        return id;
    }

    private static int buildProgram(String vsrc, String fsrc) {
        int vs = compile(GL20.GL_VERTEX_SHADER, vsrc);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, fsrc);
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) {
            throw new RuntimeException("link: " + GL20.glGetProgramInfoLog(prog));
        }
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return prog;
    }

    private static int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("compile: " + GL20.glGetShaderInfoLog(s));
        }
        return s;
    }


    private static float computeProgress() {
        try {
            if (!progressResolved) {
                progressResolved = true;
                Class<?> snm = Class.forName("net.minecraftforge.fml.loading.progress.StartupNotificationManager");
                mGetProgress = snm.getMethod("getCurrentProgress");
                Class<?> pm = Class.forName("net.minecraftforge.fml.loading.progress.ProgressMeter");
                mMeterProgress = pm.getMethod("progress");
            }
            if (mGetProgress == null || mMeterProgress == null) {
                return shownProgress;
            }
            Object listObj = mGetProgress.invoke(null);
            if (!(listObj instanceof List)) {
                return shownProgress;
            }
            List<?> meters = (List<?>) listObj;
            if (meters.isEmpty()) {
                return shownProgress;
            }
            float sum = 0.0F;
            int n = 0;
            for (Object m : meters) {
                Object v = mMeterProgress.invoke(m);
                if (v instanceof Float) {
                    float f = (Float) v;
                    if (!Float.isNaN(f) && !Float.isInfinite(f) && f >= 0.0F && f <= 1.0F) {
                        sum += f;
                        n++;
                    }
                }
            }
            if (n > 0) {
                float avg = sum / n;
                if (avg > shownProgress) {
                    shownProgress = avg;
                }
            }
        } catch (Throwable ignored) {
        }
        return shownProgress;
    }
}
