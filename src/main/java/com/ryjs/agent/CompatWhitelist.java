package com.ryjs.agent;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;


public final class CompatWhitelist {

    private CompatWhitelist() {}

    private static final String FILE_NAME = "reflection_compat_whitelist.txt";

    private static volatile boolean loaded = false;
    private static final Set<String> PKG_PREFIXES = new LinkedHashSet<>();

    private static final Set<String> JAR_KEYS = new LinkedHashSet<>();

    public static void warmup() {
        ensureLoaded();
    }

    public static boolean isWhitelistedClass(String className) {
        if (className == null || className.isEmpty()) return false;
        ensureLoaded();
        if (PKG_PREFIXES.isEmpty()) return false;
        String n = className.replace('.', '/');
        for (String p : PKG_PREFIXES) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }

    public static boolean isWhitelistedJar(String jarPathOrName) {
        if (jarPathOrName == null || jarPathOrName.isEmpty()) return false;
        ensureLoaded();
        if (JAR_KEYS.isEmpty()) return false;
        String s = jarPathOrName.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String k : JAR_KEYS) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (CompatWhitelist.class) {
            if (loaded) return;
            try {
                Path ext = resolveExternalFile();
                if (Files.exists(ext)) {
                    try (InputStream in = Files.newInputStream(ext)) {
                        parse(in);
                    }
                } else {
                    byte[] bundled = readBundled();
                    if (bundled != null) {
                        parse(new ByteArrayInputStream(bundled));
                        try {
                            Path parent = ext.getParent();
                            if (parent != null) Files.createDirectories(parent);
                            Files.write(ext, bundled);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                System.out.println("已加载兼容白名单：" + PKG_PREFIXES.size()
                        + " 个包前缀，" + JAR_KEYS.size() + " 个 jar 关键字");
            } catch (Throwable t) {
                System.err.println("加载失败（按空名单继续，不影响防御）: " + t);
            }
            loaded = true;
        }
    }


    private static Path resolveExternalFile() {
        Path config = Paths.get("config");
        if (Files.isDirectory(config)) {
            return config.resolve(FILE_NAME);
        }
        return Paths.get(FILE_NAME);
    }

    private static byte[] readBundled() {
        try (InputStream in = CompatWhitelist.class.getResourceAsStream("/" + FILE_NAME)) {
            return in == null ? null : in.readAllBytes();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void parse(InputStream in) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                if (s.regionMatches(true, 0, "jar=", 0, 4)) {
                    String k = s.substring(4).trim().toLowerCase(Locale.ROOT);
                    if (!k.isEmpty()) JAR_KEYS.add(k);
                } else {
                    String p = s.replace('.', '/');
                    if (!p.endsWith("/")) p = p + "/";
                    PKG_PREFIXES.add(p);
                }
            }
        }
    }
}
