package com.ryjs.coremod;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.NamedPath;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import sun.misc.Unsafe;

import java.lang.module.Configuration;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;


public final class CoexistenceCleaner {

    private static final Unsafe UNSAFE = getUnsafe();
    private static final String LOG_TAG = "";
    private static volatile boolean thirdPartyCleaned = false;
    private CoexistenceCleaner() {}
    public static void cleanAll(Class<?> clazz, long maxRetryMs) {
        long deadline = System.currentTimeMillis() + maxRetryMs;

        // ── 1. 获取 JAR 路径 ──
        Path jarPath = resolveJarPath(clazz);
        if (jarPath == null) {
            log("无法解析 JAR 路径，放弃清理");
            return;
        }
        log("本 JAR 路径: " + jarPath);
        String moduleName = clazz.getModule().getName();
        if (moduleName == null || moduleName.isEmpty()) {
            log("模块名为空，跳过模块层清理,found 清理仍会执行");
        } else {
            log("本模块名: " + moduleName);
        }
        if (!cleanFoundList(jarPath)) {
            log("found列表清理失败");
        }
        if (moduleName != null && !moduleName.isEmpty()) {
            cleanModuleLayerWithRetry(moduleName, deadline);
        }
    }

    public static void cleanThirdPartyCoremods(Class<?> selfClazz) {
        if (thirdPartyCleaned) {
            return;
        }
        try {
            if (!com.ryjs.agent.DefenseConfig.interceptCoremod() && !com.ryjs.agent.DefenseConfig.proxyShell()) {
                return;
            }
        } catch (Throwable ignored) {
            return;
        }
        try {
            Path selfJar = resolveJarPath(selfClazz);
            Set<Path> targets = collectThirdPartyFromFoundAndRemove(selfJar);
            if (targets.isEmpty()) {
                log("未发现第三方coremod服务，跳过模块层");
                thirdPartyCleaned = true;
                return;
            }
            log("第三方 coremod 目标 jar " + targets.size() + " 个，开始清模块层");
            removeModulesByJars(targets);
            thirdPartyCleaned = true;
        } catch (Throwable t) {
            log("清第三方coremod服务异常: " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }
    private static Set<Path> collectThirdPartyFromFoundAndRemove(Path selfJar) {
        Set<Path> targets = new HashSet<>();
        Set<Path> loaderJars = new HashSet<>();
        try {
            Field foundField = ModDirTransformerDiscoverer.class.getDeclaredField("found");
            @SuppressWarnings("unchecked")
            List<NamedPath> current = (List<NamedPath>) unsafeGetObject(null, foundField);
            if (current == null) {
                log("found 为 null，跳过第三方收集");
                return targets;
            }
            List<NamedPath> keep = new ArrayList<>(current.size());
            for (NamedPath np : current) {
                Path jar = firstPath(np);
                if (jar == null) {
                    keep.add(np);
                    continue;
                }
                boolean isSelf = selfJar != null && selfJar.equals(jar);
                String p = jar.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                boolean fromMods = p.contains("/mods/") && !p.contains("/libraries/");
                boolean whitelisted = com.ryjs.agent.CompatWhitelist.isWhitelistedJar(p);
                if (!isSelf && fromMods && !whitelisted) {
                    targets.add(jar);
                    String npName;
                    try {
                        npName = np.name();
                    } catch (Throwable t) {
                        npName = "<取不到>";
                    }
                    if (npName != null && (npName.contains("IModLocator") || npName.contains("IDependencyLocator"))) {
                        loaderJars.add(jar); // 加载器：found 照清，模块保留（防 ServiceLoader 声明残留崩溃）
                    }
                    log("found 命中第三方 coremod: " + jar + " (NamedPath.name=" + npName + ")");
                } else {
                    if (whitelisted && fromMods && !isSelf) {
                        log("兼容白名单，保留第三方 coremod jar: " + jar);
                    }
                    keep.add(np);
                }
            }
            if (!targets.isEmpty()) {
                unsafePutObject(null, foundField, keep);
                log("found 已移除 " + targets.size() + " 个第三方 coremod，剩余 " + keep.size());
            }
            if (!loaderJars.isEmpty()) {
                targets.removeAll(loaderJars);
                log("保留加载器模块: " + loaderJars);
            }
        } catch (Throwable t) {
            log("收集/移除 found 第三方异常: " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
        return targets;
    }

    @SuppressWarnings("unchecked")
    private static void removeModulesByJars(Set<Path> targetJars) {
        try {
            Field handlerField = Launcher.class.getDeclaredField("moduleLayerHandler");
            Object handler = unsafeGetObject(Launcher.INSTANCE, handlerField);
            if (handler == null) {
                log("ModuleLayerHandler 不可用");
                return;
            }
            Field completedField = handler.getClass().getDeclaredField("completedLayers");
            EnumMap<IModuleLayerManager.Layer, Object> completed =
                    (EnumMap<IModuleLayerManager.Layer, Object>) unsafeGetObject(handler, completedField);
            if (completed == null || completed.isEmpty()) {
                log("completedLayers 为空");
                return;
            }
            Map<String, Path> targetCanon = new HashMap<>();
            for (Path t : targetJars) {
                String cn = canonicalJarName(t);
                if (cn != null) targetCanon.put(cn, t);
            }
            log("待清模块的目标 jar: " + targetJars + "，规范化名: " + targetCanon.keySet());
            int totalMatched = 0;
            for (Map.Entry<IModuleLayerManager.Layer, Object> e : completed.entrySet()) {
                Object layerInfo = e.getValue();
                if (layerInfo == null) continue;
                try {
                    Field layerField = layerInfo.getClass().getDeclaredField("layer");
                    ModuleLayer layer = (ModuleLayer) unsafeGetObject(layerInfo, layerField);
                    if (layer == null) continue;
                    Configuration config = layer.configuration();
                    if (config == null) continue;
                    List<String> names = new ArrayList<>();
                    int unresolved = 0;
                    List<String> suspects = new ArrayList<>();
                    for (ResolvedModule rm : config.modules()) {
                        Path jar = jarOfModule(rm);
                        if (jar == null) {
                            unresolved++;
                            if (suspects.size() < 15) {
                                suspects.add(rm.name() + "[location=" + rawLocationOf(rm) + "]");
                            }
                            continue;
                        }
                        boolean hit = targetJars.contains(jar);
                        if (!hit) {
                            String cn = canonicalJarName(jar);
                            Path orig = (cn == null) ? null : targetCanon.get(cn);
                            if (orig != null) {
                                hit = true;
                                log("层 " + e.getKey() + " 按规范化文件名命中（原名含被 Forge 替换的字符）: "
                                        + rm.name() + " ← " + jar + "  ≈  " + orig);
                            }
                        }
                        if (hit) {
                            names.add(rm.name());
                            log("层 " + e.getKey() + " 命中模块: " + rm.name() + " ← " + jar);
                        } else {
                            String lp = jar.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                            if (lp.contains("/mods/") && !lp.contains("/libraries/") && suspects.size() < 15) {
                                suspects.add(rm.name() + "[jar=" + jar + "]");
                            }
                        }
                    }
                    log("层 " + e.getKey() + ": 模块数=" + config.modules().size()
                            + ", 路径无法解析=" + unresolved
                            + (suspects.isEmpty() ? "" : ", 可疑未命中=" + suspects));
                    for (String name : names) {
                        if (cleanConfiguration(config, name)) {
                            totalMatched++;
                            log("层 " + e.getKey() + " 已移除第三方模块: " + name);
                        }
                    }
                } catch (Throwable t) {
                    log("处理层 " + e.getKey() + " 异常: " + t.getMessage());
                }
            }
            if (totalMatched == 0) {
                log("警告：所有层都未匹配到任何目标模块 —— 清理未生效！请按上面每层打印的 location/解析路径校准判据");
            }
        } catch (Throwable t) {
            log("removeModulesByJars 异常: " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }
    private static String rawLocationOf(ResolvedModule rm) {
        try {
            return rm.reference().location().map(Object::toString).orElse("<无 location>");
        } catch (Throwable t) {
            return "<location 抛" + t.getClass().getSimpleName() + ">";
        }
    }

    private static Path jarOfModule(ResolvedModule rm) {
        try {
            java.net.URI loc = rm.reference().location().orElse(null);
            if (loc != null) {
                Path p = normalizeToJarPath(loc.toString());
                if (p != null) return p;
            }
        } catch (Throwable ignored) {
        }
        try {
            Path p = digPathFrom(rm.reference());
            if (p != null) return p.toAbsolutePath().normalize();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Path digPathFrom(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Path) return (Path) obj;
        Path p = tryPathAccessors(obj);
        if (p != null) return p;
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive() || f.getType() == String.class) continue;
                Object v = unsafeGetObject(obj, f);
                if (v == null) continue;
                if (v instanceof Path) return (Path) v;
                Path q = tryPathAccessors(v);
                if (q != null) return q;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Path tryPathAccessors(Object obj) {
        for (String name : new String[]{"getPrimaryPath", "getPath", "getRootPath"}) {
            try {
                java.lang.reflect.Method mt = obj.getClass().getMethod(name);
                Object r = mt.invoke(obj);
                if (r instanceof Path) return (Path) r;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Path firstPath(NamedPath np) {
        try {
            if (np == null || np.paths() == null || np.paths().length == 0) return null;
            return np.paths()[0].toAbsolutePath().normalize();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String canonicalJarName(Path p) {
        try {
            Path fn = (p == null) ? null : p.getFileName();
            if (fn == null) return null;
            String s = fn.toString();
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 128 && !(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-')) {
                    sb.append('_');
                } else {
                    sb.append(c);
                }
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }


    private static String percentDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) + lo);
                    i += 3;
                    continue;
                }
            }
            byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
            i++;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Path normalizeToJarPath(String raw) {
        try {
            if (raw == null || raw.isEmpty()) return null;
            String decoded = percentDecode(raw);
            if (decoded.startsWith("union:")) decoded = decoded.substring(6);
            else if (decoded.startsWith("jar:file:")) decoded = decoded.substring(9);
            else if (decoded.startsWith("file:")) decoded = decoded.substring(5);
            int bang = decoded.indexOf("!/");
            if (bang != -1) decoded = decoded.substring(0, bang);
            int hash = decoded.indexOf('#');
            if (hash != -1) decoded = decoded.substring(0, hash);
            if (decoded.matches("^[/\\\\]*[A-Za-z]:.*")) {
                decoded = decoded.replaceFirst("^[/\\\\]*", "");
                decoded = decoded.replace('/', '\\');
            }
            int jarIdx = decoded.lastIndexOf(".jar");
            if (jarIdx != -1) decoded = decoded.substring(0, jarIdx + 4);
            return Paths.get(decoded).toAbsolutePath().normalize();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Path resolveJarPath(Class<?> clazz) {
        try {
            String raw = clazz.getProtectionDomain().getCodeSource().getLocation().getPath();
            if (raw == null || raw.isEmpty()) return null;

            // URL 解码
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            log("原始路径: " + raw);
            log("解码后: " + decoded);

            // 处理 union: 前缀（开发环境常见）
            if (decoded.startsWith("union:")) {
                decoded = decoded.substring(6);
            }

            // 处理 Windows 路径（形如 /C:/... → C:\...）
            if (decoded.matches("^[/\\\\]*[A-Za-z]:.*")) {
                decoded = decoded.replaceFirst("^[/\\\\]*", "");
                decoded = decoded.replace('/', '\\');
            }

            // 裁剪到 .jar 结尾
            int jarIdx = decoded.lastIndexOf(".jar");
            if (jarIdx != -1) {
                decoded = decoded.substring(0, jarIdx + 4);
            }

            Path path = Paths.get(decoded).toAbsolutePath().normalize();
            log("标准化路径: " + path);
            return path;

        } catch (Exception e) {
            log("路径解析异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return null;
        }
    }


    private static boolean cleanFoundList(Path jarPath) {
        try {
            // ── 反射获取 found 静态字段 ──
            Field foundField = ModDirTransformerDiscoverer.class.getDeclaredField("found");
            if (UNSAFE == null) {
                log("Unsafe 不可用，回退到普通反射");
                foundField.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<NamedPath> list = (List<NamedPath>) foundField.get(null);
                if (list == null) return false;
                // 尝试 in-place 移除
                List<NamedPath> newList = new ArrayList<>(list);
                boolean removed = newList.removeIf(np -> matchesPath(np, jarPath));
                if (removed) {
                    foundField.set(null, newList);
                    log("found 列表已更新（普通反射），移除了 " + jarPath);
                } else {
                    log("found 列表中不包含本 JAR，无需移除");
                }
                return true;
            }

            // ── Unsafe 方式 ──
            long offset = UNSAFE.staticFieldOffset(foundField);
            Object base = UNSAFE.staticFieldBase(foundField);

            @SuppressWarnings("unchecked")
            List<NamedPath> current = (List<NamedPath>) UNSAFE.getObject(base, offset);
            if (current == null) {
                log("found 列表为 null，跳过");
                return false;
            }

            log("found 列表当前大小: " + current.size());

            // 检查是否需要移除
            boolean hasOurs = current.stream().anyMatch(np -> matchesPath(np, jarPath));
            if (!hasOurs) {
                log("found 列表中不包含本 JAR，无需清理");
                return true;
            }

            // 创建新列表（排除本 JAR）
            List<NamedPath> newList = new ArrayList<>(current.size());
            int removed = 0;
            for (NamedPath np : current) {
                if (matchesPath(np, jarPath)) {
                    removed++;
                } else {
                    newList.add(np);
                }
            }

            // Unsafe 替换字段
            UNSAFE.putObject(base, offset, newList);
            log("found 列表已替换，移除了 " + removed + " 个条目，新大小: " + newList.size());
            return true;

        } catch (NoSuchFieldException e) {
            log("字段 ModDirTransformerDiscoverer.found 不存在: " + e.getMessage());
            return false;
        } catch (Exception e) {
            log("清理 found 列表异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        }
    }

    private static boolean matchesPath(NamedPath np, Path target) {
        if (np == null || np.paths() == null || np.paths().length == 0) return false;
        try {
            Path npPath = np.paths()[0].toAbsolutePath().normalize();
            return target.equals(npPath);
        } catch (Exception e) {
            return false;
        }
    }

    private static void cleanModuleLayerWithRetry(String moduleName, long deadlineMs) {
        int attempt = 0;
        while (System.currentTimeMillis() <= deadlineMs) {
            attempt++;
            log("模块层清理尝试 #" + attempt);

            if (tryCleanModuleLayers(moduleName)) {
                log("模块层清理成功（尝试 #" + attempt + "）");
                return;
            }

            if (System.currentTimeMillis() < deadlineMs) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        log("模块层清理超时（尝试 " + attempt + " 次后放弃）— 模块可能在其他层加载");
    }

    @SuppressWarnings("unchecked")
    private static boolean tryCleanModuleLayers(String moduleName) {
        try {
            // ── 获取 ModuleLayerHandler ──
            Field handlerField = Launcher.class.getDeclaredField("moduleLayerHandler");
            Object handler = unsafeGetObject(Launcher.INSTANCE, handlerField);
            if (handler == null) {
                log("ModuleLayerHandler 不可用");
                return false;
            }

            // ── 获取 completedLayers ──
            Field completedField = handler.getClass().getDeclaredField("completedLayers");
            EnumMap<IModuleLayerManager.Layer, Object> completed =
                    (EnumMap<IModuleLayerManager.Layer, Object>) unsafeGetObject(handler, completedField);
            if (completed == null || completed.isEmpty()) {
                log("completedLayers 为空或不可用，稍后重试");
                return false;
            }

            log("completedLayers 大小: " + completed.size());

            boolean allDone = true;
            for (Map.Entry<IModuleLayerManager.Layer, Object> entry : completed.entrySet()) {
                IModuleLayerManager.Layer layerType = entry.getKey();
                Object layerInfo = entry.getValue();
                if (layerInfo == null) continue;

                try {
                    // ── 获取 ModuleLayer ──
                    Field layerField = layerInfo.getClass().getDeclaredField("layer");
                    ModuleLayer layer = (ModuleLayer) unsafeGetObject(layerInfo, layerField);
                    if (layer == null) continue;

                    // ── 检查这个层是否有我们的模块 ──
                    Optional<Module> ourModule = layer.modules().stream()
                            .filter(m -> m != null && moduleName.equals(m.getName()))
                            .findFirst();

                    if (ourModule.isEmpty()) {
                        log("层 " + layerType + " 中没有模块 " + moduleName + "，跳过");
                        continue;
                    }

                    log("在层 " + layerType + " 中找到模块 " + moduleName + "，正在移除...");

                    // ── 从 Configuration 中移除 ──
                    Configuration config = layer.configuration();
                    if (config == null) continue;

                    boolean configCleaned = cleanConfiguration(config, moduleName);
                    if (configCleaned) {
                        log("层 " + layerType + " 的 Configuration 已清理");
                    } else {
                        log("层 " + layerType + " 的 Configuration 清理失败（无模块或不可写）");
                    }

                } catch (Exception e) {
                    log("处理层 " + layerType + " 时出错: " + e.getMessage());
                    allDone = false;
                }
            }

            return allDone;

        } catch (NoSuchFieldException e) {
            log("字段不存在: " + e.getMessage());
            return false;
        } catch (Exception e) {
            log("模块层清理异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean cleanConfiguration(Configuration config, String moduleName) {
        boolean modified = false;

        try {
            // ── modules (Set<ResolvedModule>) ──
            Field modulesField = Configuration.class.getDeclaredField("modules");
            Set<ResolvedModule> currentModules = (Set<ResolvedModule>) unsafeGetObject(config, modulesField);
            if (currentModules != null) {
                // 找到目标模块
                Optional<ResolvedModule> target = currentModules.stream()
                        .filter(rm -> rm != null && moduleName.equals(rm.name()))
                        .findFirst();

                if (target.isPresent()) {
                    Set<ResolvedModule> newModules = new HashSet<>(currentModules);
                    newModules.remove(target.get());
                    unsafePutObject(config, modulesField, newModules);
                    log("Configuration.modules: 移除了 " + moduleName + " (" + (currentModules.size() - newModules.size()) + " 个)");
                    modified = true;
                } else {
                    log("Configuration.modules 中无模块 " + moduleName);
                }
            }

            // ── nameToModule (Map<String, ResolvedModule>) ──
            Field nameField = Configuration.class.getDeclaredField("nameToModule");
            Map<String, ResolvedModule> currentMap = (Map<String, ResolvedModule>) unsafeGetObject(config, nameField);
            if (currentMap != null && currentMap.containsKey(moduleName)) {
                Map<String, ResolvedModule> newMap = new HashMap<>(currentMap);
                newMap.remove(moduleName);
                unsafePutObject(config, nameField, newMap);
                log("Configuration.nameToModule: 移除了 " + moduleName);
                modified = true;
            }

        } catch (NoSuchFieldException e) {
            log("Configuration 字段不存在: " + e.getMessage());
        } catch (Exception e) {
            log("Configuration 清理异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }

        return modified;
    }


    private static Unsafe getUnsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (Exception e) {
            System.err.println(LOG_TAG + " 无法获取 Unsafe: " + e.getMessage());
            return null;
        }
    }

    private static Object unsafeGetObject(Object target, Field field) {
        if (UNSAFE == null) return fallbackGet(target, field);
        try {
            long offset;
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                offset = UNSAFE.staticFieldOffset(field);
                target = UNSAFE.staticFieldBase(field);
            } else {
                offset = UNSAFE.objectFieldOffset(field);
            }
            return UNSAFE.getObject(target, offset);
        } catch (Exception e) {
            return fallbackGet(target, field);
        }
    }

    private static void unsafePutObject(Object target, Field field, Object value) {
        if (UNSAFE == null) {
            fallbackSet(target, field, value);
            return;
        }
        try {
            long offset;
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                offset = UNSAFE.staticFieldOffset(field);
                target = UNSAFE.staticFieldBase(field);
            } else {
                offset = UNSAFE.objectFieldOffset(field);
            }
            UNSAFE.putObject(target, offset, value);
        } catch (Exception e) {
            fallbackSet(target, field, value);
        }
    }

    private static Object fallbackGet(Object target, Field field) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static void fallbackSet(Object target, Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            log("字段写入回退失败: " + field.getName() + " - " + e.getMessage());
        }
    }


    private static void log(String msg) {
        System.out.println(LOG_TAG + " " + msg);
    }
}
