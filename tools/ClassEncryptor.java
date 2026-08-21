import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Build-time encryptor: turns the build jar into the encrypted deploy jar.
 * (Keep this file ASCII-only: it is compiled by a standalone javac.)
 *
 * Rules (must stay in sync with runtime RyjsClassLoader.decrypt):
 * <ul>
 *   <li>Encrypt ONLY the custom-loader-managed classes: com/ryjs/core/impl/* (managed package)
 *       and com/ryjs/core/RyjsCoreImpl (hidden core) - rename to .mcmod (fake extension, madness
 *       style: jar scanners / offline analysis see no class files for the managed core);</li>
 *   <li>All other com/ryjs/ classes stay PLAIN - module layer (entries/callbacks/tools) must be
 *       loadable by ModuleClassLoader; encrypted bytes there would crash ModLauncher (ASM parse);</li>
 *   <li>Format: 4B magic "RYJS" + 4B original length (big-endian) + XOR stream with dynamic key
 *       f(length, index) - identical algorithm to RyjsClassLoader.decrypt (change both together);</li>
 *   <li>META-INF/mods.toml is kept (removing it drops the jar from classpath - GAME layer CNFE);</li>
 *   <li>Self-check: every encrypted entry must decrypt back to the original bytes.</li>
 * </ul>
 *
 * Usage: java ClassEncryptor &lt;in.jar&gt; &lt;out.jar&gt;
 */
public class ClassEncryptor {

    /** Magic "RYJS" (same as RyjsClassLoader.MAGIC). */
    private static final int MAGIC = 0x52594A53;
    /** Key salt (same as RyjsClassLoader.KEY_SALT). */
    private static final int KEY_SALT = 0x6A5C4E31;
    private static final int HEADER_LEN = 8; // 4 magic + 4 length

    private ClassEncryptor() {
    }

    /** Encrypt scope: everything under com/ryjs/ EXCEPT the plain whitelist (see isPlain).
     *  Managed package (com/ryjs/core/impl/*) and hidden core (RyjsCoreImpl) are already covered
     *  by this rule (they are not plain). */
    private static boolean isEncryptTarget(String entry) {
        return entry.endsWith(".class") && entry.startsWith("com/ryjs/") && !isPlain(entry);
    }

    /** Plain whitelist (module layer, constant-pool referenced by FML entries / bootstrap):
     *  these MUST stay loadable by ModuleClassLoader directly. No sensitive logic in any of them.
     *  <ul>
     *   <li>com/ryjs/coremod/** - ITS/IWP entries, AgentUtil bootstrap, transformers, cleaners;</li>
     *   <li>com/ryjs/agent/** - premain filter system;</li>
     *   <li>com/ryjs/core/ - RyjsClassLoader/RyjsCore/RyjsCoreHost/CoreBridge/CoreAccess
     *       (bootstrap chain; impl/* and RyjsCoreImpl stay encrypted);</li>
     *   <li>com/ryjs/hook/hook/ - annotation/enum classes referenced by callbacks &amp; managed core;</li>
     *   <li>com/ryjs/hook/DiagLog + com/ryjs/hook/transformer/** - referenced by managed core
     *       (parent delegation) and by hidden core (host-loader resolution);</li>
     *   <li>com/ryjs/proxyshell/ProxyShellSupport + ProxyShellBlocker - entry bootstrap;</li>
     *   <li>com/ryjs/reflection/hook/ - callback classes (madness Transformers role: hosts injected
     *       into MC call sites, execution surface - kept plain, protected by class-restore guard).</li>
     *  </ul> */
    private static boolean isPlain(String entry) {
        if (entry.startsWith("com/ryjs/coremod/") || entry.startsWith("com/ryjs/agent/")
                || entry.startsWith("com/ryjs/asm/") || entry.startsWith("com/ryjs/api/")) {
            return true;
        }
        if (entry.startsWith("com/ryjs/core/") && !entry.startsWith("com/ryjs/core/impl/")
                && !entry.equals("com/ryjs/core/RyjsCoreImpl.class")) {
            return true;
        }
        if (entry.startsWith("com/ryjs/hook/hook/") || entry.startsWith("com/ryjs/hook/transformer/")
                || entry.equals("com/ryjs/hook/DiagLog.class") || entry.startsWith("com/ryjs/reflection/hook/")) {
            return true;
        }
        // @Mod 主类（madness 对齐：主类不加密——Forge 按 mods.toml 加载，必须模块层可见）
        if (entry.equals("com/ryjs/reflection/Reflection.class")) {
            return true;
        }
        // @Mod.EventBusSubscriber 类：Forge 构建期注解扫描（ASMDataTable）发现并自动注册事件订阅——
        // 加密成 .mcmod 后扫描器看不到注解 → 事件订阅失效（2026-08-15 实测：cosmic 模型加载器未注册）。
        if (entry.startsWith("com/ryjs/event/tooltip/ReflectionShaders")
                || entry.startsWith("com/ryjs/reflection/client/AvaritiaClient")
                || entry.startsWith("com/ryjs/reflection/client/render/TaiChiClientSetup")
                || entry.startsWith("com/ryjs/reflection/client/render/TaiChiForgeRenderFallback")
                || entry.startsWith("com/ryjs/reflection/client/shader/AvaritiaShaders")
                || entry.startsWith("com/ryjs/reflection/command/RyjsCommand")
                || entry.startsWith("com/ryjs/reflection/entity/WitherzillaReconciler")
                || entry.startsWith("com/ryjs/reflection/proxyshell/ProxyShellClientSetup")
                || entry.startsWith("com/ryjs/reflection/proxyshell/ProxyShellModListClient")
                || entry.startsWith("com/ryjs/timestop/TimeStopEvents")) {
            return true;
        }
        return entry.startsWith("com/ryjs/proxyshell/ProxyShellSupport")
                || entry.startsWith("com/ryjs/proxyshell/ProxyShellBlocker");
    }

    private static byte[] encrypt(byte[] data) {
        byte[] out = new byte[HEADER_LEN + data.length];
        out[0] = (byte) (MAGIC >>> 24);
        out[1] = (byte) (MAGIC >>> 16);
        out[2] = (byte) (MAGIC >>> 8);
        out[3] = (byte) MAGIC;
        out[4] = (byte) (data.length >>> 24);
        out[5] = (byte) (data.length >>> 16);
        out[6] = (byte) (data.length >>> 8);
        out[7] = (byte) data.length;
        int acc = KEY_SALT ^ data.length;
        for (int i = 0; i < data.length; i++) {
            acc = acc * 33 ^ (i + data.length);
            out[i + HEADER_LEN] = (byte) (data[i] ^ (byte) acc);
        }
        return out;
    }

    private static byte[] decrypt(byte[] data) {
        if (data.length <= HEADER_LEN) {
            return data;
        }
        int magic = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        if (magic != MAGIC) {
            return data;
        }
        int len = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16) | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        if (len <= 0 || len > data.length - HEADER_LEN) {
            return data;
        }
        byte[] out = new byte[len];
        int acc = KEY_SALT ^ len;
        for (int i = 0; i < len; i++) {
            acc = acc * 33 ^ (i + len);
            out[i] = (byte) (data[i + HEADER_LEN] ^ (byte) acc);
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            return is.readAllBytes();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java ClassEncryptor <in.jar> <out.jar>");
            System.exit(1);
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        int encrypted = 0;
        try (JarFile jf = new JarFile(in);
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(out))) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String name = e.getName();
                byte[] data = readAll(jf.getInputStream(e));
                if (isEncryptTarget(name)) {
                    byte[] enc = encrypt(data);
                    byte[] chk = decrypt(enc);
                    if (!Arrays.equals(chk, data)) {
                        throw new IllegalStateException("self-check failed: " + name);
                    }
                    data = enc;
                    encrypted++;
                    // fake extension: .class -> .mcmod (loader reads .mcmod first, .class fallback)
                    name = name.substring(0, name.length() - ".class".length()) + ".mcmod";
                }
                JarEntry ne = new JarEntry(name);
                ne.setTime(e.getTime());
                jos.putNextEntry(ne);
                jos.write(data);
                jos.closeEntry();
            }
        }
        System.out.println("[ClassEncryptor] done: " + encrypted + " classes encrypted (managed+hidden core) -> "
                + out.getAbsolutePath());
    }
}
