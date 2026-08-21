package com.ryjs.coremod;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;


public final class ModClassScanner {

    private static final String LOG_TAG = "[ModClassScanner]";

    static final Set<String> JAR_WHITELIST = new HashSet<>(List.of(
            // 示例: "jei-1.20.1-forge-15.2.0.27.jar"
    ));


    static final Set<String> PACKAGE_WHITELIST = new HashSet<>(List.of(
            "com/ryjs/"
    ));

    private ModClassScanner() {}

    public static Set<String> scan(Class<?> selfClass) {
        Set<String> classes = new HashSet<>(4096);

        Path selfJar = resolveSelfJar(selfClass);
        Path modsDir = resolveModsDir(selfJar);

        if (modsDir == null || !Files.isDirectory(modsDir)) {
            log("无法定位 mods 目录，放弃扫描");
            return classes;
        }

        log("mods 目录: " + modsDir);
        if (selfJar != null) {
            log("自身 JAR: " + selfJar.getFileName());
        }

        int jarCount = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jar : stream) {
                Path absJar = jar.toAbsolutePath().normalize();

                // ── 排除自身 ──
                if (selfJar != null && absJar.equals(selfJar)) {
                    log("跳过自身: " + jar.getFileName());
                    continue;
                }

                // ── JAR 白名单 ──
                String fileName = jar.getFileName().toString();
                if (isJarWhitelisted(fileName) || com.ryjs.agent.CompatWhitelist.isWhitelistedJar(fileName)) {
                    log("跳过白名单 JAR: " + fileName);
                    continue;
                }

                scanJar(jar, classes);
                jarCount++;
            }
        } catch (IOException e) {
            log("扫描 mods 目录出错: " + e.getMessage());
        }

        log("扫描完成 — " + jarCount + " 个 JAR，共 " + classes.size() + " 个目标类");
        return classes;
    }


    private static void scanJar(Path jarPath, Set<String> out) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (!name.endsWith(".class")) continue;
                if (name.startsWith("META-INF/")) continue;
                if (name.contains("module-info")) continue;
                if (name.contains("package-info")) continue;

                String className = name.substring(0, name.length() - 6);

                if (isPackageWhitelisted(className) || com.ryjs.agent.CompatWhitelist.isWhitelistedClass(className)) continue;

                out.add(className);
            }
        } catch (IOException e) {
            log("无法读取 JAR: " + jarPath.getFileName() + " - " + e.getMessage());
        }
    }

    private static boolean isJarWhitelisted(String fileName) {
        if (JAR_WHITELIST.contains(fileName)) return true;
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String entry : JAR_WHITELIST) {
            if (entry.endsWith("-") && lower.startsWith(entry.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPackageWhitelisted(String internalClassName) {
        for (String prefix : PACKAGE_WHITELIST) {
            if (internalClassName.startsWith(prefix)) return true;
        }
        return false;
    }


    private static Path resolveModsDir(Path selfJar) {
        if (selfJar != null) {
            Path parent = selfJar.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                return parent;
            }
        }
        Path fallback = Paths.get("mods").toAbsolutePath();
        if (Files.isDirectory(fallback)) return fallback;
        return null;
    }

    static Path resolveSelfJar(Class<?> clazz) {
        try {
            String raw = clazz.getProtectionDomain().getCodeSource().getLocation().getPath();
            if (raw == null || raw.isEmpty()) return null;

            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);

            if (decoded.startsWith("union:")) {
                decoded = decoded.substring(6);
            }

            // Windows 路径
            if (decoded.matches("^[/\\\\]*[A-Za-z]:.*")) {
                decoded = decoded.replaceFirst("^[/\\\\]*", "");
                decoded = decoded.replace('/', '\\');
            }

            int jarIdx = decoded.lastIndexOf(".jar");
            if (jarIdx != -1) {
                decoded = decoded.substring(0, jarIdx + 4);
            }

            return Paths.get(decoded).toAbsolutePath().normalize();
        } catch (Exception e) {
            log("解析自身路径失败: " + e.getMessage());
            return null;
        }
    }

    private static void log(String msg) {
        System.out.println(LOG_TAG + " " + msg);
    }
}
