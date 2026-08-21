package com.ryjs.proxyshell;

import com.ryjs.agent.CompatWhitelist;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ProxyShellSupport {

    private ProxyShellSupport() {}

    private static final String LOG = "[瞎几把注册]";

    public static boolean enabled() {
        try {
            return com.ryjs.agent.DefenseConfig.proxyShell();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isBlockTargetJar(Path jar, Path selfJar) {
        if (jar == null) {
            return false;
        }
        String p = jar.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!p.contains("/mods/") || p.contains("/libraries/")) {
            return false;
        }

        if (selfJar != null) {
            try {
                if (jar.toAbsolutePath().normalize().equals(selfJar.toAbsolutePath().normalize())) {
                    return false;
                }
            } catch (Throwable ignore) {
            }
            Path jn = jar.getFileName();
            Path sn = selfJar.getFileName();
            if (jn != null && sn != null && jn.toString().equalsIgnoreCase(sn.toString())) {
                return false;
            }
        }
        if (CompatWhitelist.isWhitelistedJar(p)) {
            return false;
        }
        return true;
    }

    public static Path resolveSelfJar(Class<?> selfClass) {
        try {
            CodeSource cs = selfClass.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            String s = cs.getLocation().toString();
            if (s.startsWith("union:")) {
                s = s.substring(6);
            } else if (s.startsWith("jar:file:")) {
                s = s.substring(9);
            } else if (s.startsWith("file:")) {
                s = s.substring(5);
            }
            int bang = s.indexOf("!/");
            if (bang != -1) {
                s = s.substring(0, bang);
            }
            try {
                s = java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
            } catch (Throwable ignore) {
            }
            int jarIdx = s.lastIndexOf(".jar");
            if (jarIdx != -1) {
                s = s.substring(0, jarIdx + 4);
            }
            // Windows 盘符路径形如 /E:/... —— 去掉前导斜杠
            if (s.length() > 2 && s.charAt(0) == '/' && s.charAt(2) == ':') {
                s = s.substring(1);
            }
            return Paths.get(s);
        } catch (Throwable t) {
            return null;
        }
    }


    public static Path resolveModsDir(Path selfJar) {
        if (selfJar == null) {
            return null;
        }
        Path parent = selfJar.getParent();
        return (parent != null && Files.isDirectory(parent)) ? parent : null;
    }

    public static List<Path> listBlockedJars(Class<?> selfClass) {
        List<Path> out = new ArrayList<>();
        Path selfJar = resolveSelfJar(selfClass);
        Path modsDir = resolveModsDir(selfJar);
        if (modsDir == null) {
            return out;
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jar : s) {
                Path abs = jar.toAbsolutePath().normalize();
                if (isBlockTargetJar(abs, selfJar)) {
                    out.add(abs);
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    public static String readPrimaryModId(Path jar) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry("META-INF/mods.toml");
            if (e == null) {
                return null;
            }
            String txt = new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(txt);
            while (m.find()) {
                String id = m.group(1);
                if (id != null && !id.isEmpty() && !"forge".equals(id) && !"minecraft".equals(id)) {
                    return id;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    public static String readModDisplayName(Path jar, String fallback) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry("META-INF/mods.toml");
            if (e != null) {
                String txt = new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*\"([^\"]+)\"").matcher(txt);
                if (m.find()) {
                    String n = m.group(1);
                    if (n != null && !n.isEmpty()) {
                        return n;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return fallback;
    }

    public static final class ModMeta {
        public final String modId;
        public final String displayName;
        public final String version;
        public final String description;
        public final String logoFile; // 可能为 null

        public ModMeta(String modId, String displayName, String version, String description, String logoFile) {
            this.modId = modId;
            this.displayName = displayName;
            this.version = version;
            this.description = description;
            this.logoFile = logoFile;
        }
    }

    public static ModMeta readModMeta(Path jar) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry("META-INF/mods.toml");
            if (e == null) {
                return null;
            }
            String txt = new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
            String modId = null;
            Matcher m = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(txt);
            while (m.find()) {
                String id = m.group(1);
                if (id != null && !id.isEmpty() && !"forge".equals(id) && !"minecraft".equals(id)) {
                    modId = id;
                    break;
                }
            }
            if (modId == null) {
                return null;
            }
            String displayName = grp(txt, "(?m)^\\s*displayName\\s*=\\s*\"([^\"]+)\"");
            String version = grp(txt, "(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"");
            String logoFile = grp(txt, "(?m)^\\s*logoFile\\s*=\\s*\"([^\"]+)\"");
            String description;
            Matcher dm = Pattern.compile("(?s)description\\s*=\\s*'''(.*?)'''").matcher(txt);
            if (dm.find()) {
                description = dm.group(1).trim();
            } else {
                description = grp(txt, "(?m)^\\s*description\\s*=\\s*\"([^\"]*)\"");
            }
            if (version != null && version.contains("${")) {
                version = manifestVersion(zf);
            }
            if (displayName == null) displayName = modId;
            if (version == null || version.contains("${")) version = "NONE";
            if (description == null) description = "";
            return new ModMeta(modId, displayName, version, description, logoFile);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String grp(String txt, String regex) {
        Matcher m = Pattern.compile(regex).matcher(txt);
        return m.find() ? m.group(1) : null;
    }

    private static String manifestVersion(ZipFile zf) {
        try {
            ZipEntry e = zf.getEntry("META-INF/MANIFEST.MF");
            if (e != null) {
                String mf = new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                String v = grp(mf, "(?m)^Implementation-Version:\\s*(.+)$");
                if (v != null) return v.trim();
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    public static void log(String msg) {
        System.out.println(LOG + " " + msg);
    }
}
