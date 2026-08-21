package com.ryjs.reflection.proxyshell;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ryjs.proxyshell.ProxyShellSupport;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.resource.PathPackResources;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


public final class ProxyShellContent {

    private ProxyShellContent() {}

    public static final class ShellEntry {
        public final String modid;
        public final String ns;
        public final String name;
        public final String regName;
        public final String displayName;
        public final boolean handheld;
        public final String layer0;
        public final boolean proxied;
        public final Path texJar;
        public final String texEntry;
        public final String mcmetaEntry;
        public final String inheritParent;
        public final String synthModelJson;
        public final boolean catchAll;
        public final String literalName;
        Item item;

        ShellEntry(String modid, String regName, String displayName, boolean handheld, String layer0,
                   boolean proxied, Path texJar, String texEntry, String mcmetaEntry, String inheritParent,
                   String ns, String name, String synthModelJson) {
            this(modid, regName, displayName, handheld, layer0, proxied, texJar, texEntry, mcmetaEntry,
                    inheritParent, ns, name, synthModelJson, false, null);
        }

        ShellEntry(String modid, String regName, String displayName, boolean handheld, String layer0,
                   boolean proxied, Path texJar, String texEntry, String mcmetaEntry, String inheritParent,
                   String ns, String name, String synthModelJson, boolean catchAll, String literalName) {
            this.modid = modid;
            this.regName = regName;
            this.displayName = displayName;
            this.handheld = handheld;
            this.layer0 = layer0;
            this.proxied = proxied;
            this.texJar = texJar;
            this.texEntry = texEntry;
            this.mcmetaEntry = mcmetaEntry;
            this.inheritParent = inheritParent;
            this.ns = ns;
            this.name = name;
            this.synthModelJson = synthModelJson;
            this.catchAll = catchAll;
            this.literalName = literalName;
        }

       static ShellEntry catchAll(String group, String regName, String ns, String name,
                                   String synthModelJson, Path texJar, String texEntry, String literalName) {
            return new ShellEntry(group, regName, literalName, false, null, texJar != null,
                    texJar, texEntry, null, null, ns, name, synthModelJson, true, literalName);
        }
    }

    public static final List<ShellEntry> ENTRIES = new ArrayList<>();
    private static final Map<String, List<ShellEntry>> BY_MOD = new LinkedHashMap<>();
    private static final Map<String, String> MOD_TITLE = new LinkedHashMap<>();
    private static volatile boolean scanned = false;
    private static final List<RegistryObject<CreativeModeTab>> TABS = new ArrayList<>();

     public static synchronized void scan(Class<?> selfClass) {
        if (scanned) {
            return;
        }
        scanned = true;
        if (!ProxyShellSupport.enabled()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Path jar : ProxyShellSupport.listBlockedJars(selfClass)) {
            String modid = ProxyShellSupport.readPrimaryModId(jar);
            String group = modid != null ? modid : jar.getFileName().toString();
            int[] stat = new int[5];
            try (ZipFile zf = new ZipFile(jar.toFile())) {

                for (String ns : collectAssetNamespaces(zf)) {
                    if (isVanillaNs(ns)) {
                        continue;
                    }
                    Map<String, String> lang = readLang(zf, ns);
                    scanCategory(zf, jar, group, ns, "item", "item.", lang, seen, stat);
                    scanCategory(zf, jar, group, ns, "block", "block.", lang, seen, stat);
                }

                int[] allStat = new int[3];
                scanAllEntries(zf, jar, group, modid, seen, allStat);
                ProxyShellSupport.log("mod [" + group + "] 全量注册：扫描 " + allStat[0]
                        + " 条目 → 登记 " + allStat[1] + " 个占位空壳");
                MOD_TITLE.put(group, ProxyShellSupport.readModDisplayName(jar, group));
            } catch (Throwable t) {
                ProxyShellSupport.log("扫描空壳失败 " + jar.getFileName() + ": " + t);
            }
            int mine = BY_MOD.getOrDefault(group, List.of()).size();
            ProxyShellSupport.log("mod [" + group + "] 模型文件 " + stat[0] + " → 空壳 " + mine
                    + "（真模型 " + stat[1] + " 去loader " + stat[2] + " 合成 " + stat[3] + " 重名 " + stat[4] + "）");
        }
        ProxyShellSupport.log("空壳条目共 " + ENTRIES.size() + " 个，来自 " + BY_MOD.size() + " 个被拦 mod");
    }

    private static void scanAllEntries(ZipFile zf, Path jar, String group, String modid, Set<String> seen, int[] allStat) {
        String ns = (modid != null && !modid.isEmpty()) ? modid : sanitizeAll(jar.getFileName().toString());
        if (ns.isEmpty()) {
            ns = "blocked";
        }
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) {
                continue;
            }
            String full = e.getName();
            allStat[0]++;
            String name = sanitizeAll(full);
            if (name.isEmpty()) {
                continue;
            }
            if (name.length() > 120) {
                name = name.substring(0, 110) + "_" + Integer.toHexString(full.hashCode());
            }
            String key = ns + ":" + name;
            if (ResourceLocation.tryParse(key) == null || !seen.add(key)) {
                continue;
            }
            String regName = sanitize("shell_all_" + ns + "_" + name);
            String synth = buildSynthModelJson(false, "reflection:item/help/item");
            ShellEntry entry = ShellEntry.catchAll(group, regName, ns, name, synth, null, null, full);
            allStat[1]++;
            ENTRIES.add(entry);
            BY_MOD.computeIfAbsent(group, k -> new ArrayList<>()).add(entry);
        }
    }

    private static void scanCategory(ZipFile zf, Path jar, String group, String ns, String subdir, String langKeyPrefix,
                                     Map<String, String> lang, Set<String> seen, int[] stat) {
        String prefix = "assets/" + ns + "/models/" + subdir + "/";
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (!n.startsWith(prefix) || !n.endsWith(".json")) {
                continue;
            }
            String name = n.substring(prefix.length(), n.length() - 5);
            if (name.isEmpty() || name.contains("/") || ResourceLocation.tryParse(ns + ":" + name) == null) {
                continue;
            }
            stat[0]++;
            String key = ns + ":" + name;
            if (!seen.add(key)) {
                stat[4]++;
                continue;
            }
            boolean handheld = modelParentHasHandheld(zf, n) || isWeaponName(name);
            String synth = null;
            if (isModelSafe(zf, ns, n, 0)) {
                stat[1]++;
            } else {

                String deLoadered = tryDeLoader(zf, ns, n);
                if (deLoadered != null) {
                    synth = deLoadered;
                    stat[2]++;
                } else {

                    String texEntry = findTextureByBaseName(zf, ns, name);
                    String layer0;
                    if (texEntry != null) {
                        String base = "assets/" + ns + "/textures/";
                        layer0 = ns + ":" + texEntry.substring(base.length(), texEntry.length() - 4);
                    } else {
                        layer0 = ns + ":item/" + name;
                    }
                    synth = buildSynthModelJson(handheld, layer0);
                    stat[3]++;
                }
            }
            String regName = sanitize("shell_" + ns + "_" + subdir + "_" + name);
            ShellEntry entry = new ShellEntry(group, regName, name, handheld,
                    null, false, jar, null, null, null, ns, name, synth);
            ENTRIES.add(entry);
            BY_MOD.computeIfAbsent(group, k -> new ArrayList<>()).add(entry);
        }
    }

    private static boolean isVanillaNs(String ns) {
        return "minecraft".equals(ns) || "forge".equals(ns) || "realms".equals(ns) || "c".equals(ns);
    }

    private static boolean isModelSafe(ZipFile zf, String ns, String modelEntry, int depth) {
        if (depth > 8) {
            return false;
        }
        ZipEntry ze = zf.getEntry(modelEntry);
        if (ze == null) {
            return false;
        }
        try {
            JsonElement je = JsonParser.parseString(new String(zf.getInputStream(ze).readAllBytes(), StandardCharsets.UTF_8));
            if (!je.isJsonObject()) {
                return false;
            }
            return isModelObjectSafe(zf, je.getAsJsonObject(), depth);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isModelObjectSafe(ZipFile zf, JsonObject o, int depth) {
        if (depth > 8) {
            return false;
        }
        if (o.has("loader") && o.get("loader").isJsonPrimitive()) {
            String l = o.get("loader").getAsString();
            int c = l.indexOf(':');
            String lns = c > 0 ? l.substring(0, c) : l;
            if (!"forge".equals(lns) && !"minecraft".equals(lns)) {
                return false;
            }
        }
        if (o.has("parent") && o.get("parent").isJsonPrimitive()) {
            String p = o.get("parent").getAsString();
            int c = p.indexOf(':');
            String pns = c >= 0 ? p.substring(0, c) : "minecraft";
            String ppath = c >= 0 ? p.substring(c + 1) : p;
            if (isVanillaNs(pns)) {
                return true;
            }
            return isModelSafe(zf, pns, "assets/" + pns + "/models/" + ppath + ".json", depth + 1);
        }
        return o.has("elements");
    }


    private static final Set<String> VANILLA_MODEL_KEYS = Set.of(
            "parent", "textures", "elements", "display", "overrides", "gui_light", "ambientocclusion", "particle", "render_type");

    private static String tryDeLoader(ZipFile zf, String ns, String modelEntry) {
        ZipEntry ze = zf.getEntry(modelEntry);
        if (ze == null) {
            return null;
        }
        JsonObject o;
        try {
            JsonElement je = JsonParser.parseString(new String(zf.getInputStream(ze).readAllBytes(), StandardCharsets.UTF_8));
            if (!je.isJsonObject() || !je.getAsJsonObject().has("loader")) {
                return null;
            }
            o = je.getAsJsonObject();
        } catch (Throwable t) {
            return null;
        }

        JsonObject clean = new JsonObject();
        for (String k : o.keySet()) {
            if (VANILLA_MODEL_KEYS.contains(k)) {
                clean.add(k, o.get(k));
            }
        }
        return isModelObjectSafe(zf, clean, 0) ? clean.toString() : null;
    }

    private static String buildSynthModelJson(boolean handheld, String layer0) {
        String parent = handheld ? "minecraft:item/handheld" : "minecraft:item/generated";
        return "{\"parent\":\"" + parent + "\",\"textures\":{\"layer0\":\"" + layer0 + "\"}}";
    }

    private static boolean modelParentHasHandheld(ZipFile zf, String modelEntry) {
        try {
            JsonElement je = JsonParser.parseString(new String(zf.getInputStream(zf.getEntry(modelEntry)).readAllBytes(), StandardCharsets.UTF_8));
            if (je.isJsonObject()) {
                JsonObject o = je.getAsJsonObject();
                return o.has("parent") && o.get("parent").isJsonPrimitive()
                        && o.get("parent").getAsString().contains("handheld");
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static boolean isWeaponName(String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT);
        for (String kw : new String[]{"sword", "pickaxe", "axe", "shovel", "hoe", "blade", "dagger",
                "scythe", "hammer", "spear", "trident", "katana", "saber", "glaive", "halberd",
                "lance", "mace", "cutlass", "sickle", "cleaver", "rapier", "machete", "greatsword"}) {
            if (s.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArmorName(String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT);
        for (String kw : new String[]{"helmet", "chestplate", "leggings", "boots", "armor", "helm",
                "gauntlet", "greaves", "cuirass", "shield", "bow", "crossbow", "wand", "staff"}) {
            if (s.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> collectAssetNamespaces(ZipFile zf) {
        Set<String> out = new LinkedHashSet<>();
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.startsWith("assets/")) {
                int slash = n.indexOf('/', 7);
                if (slash > 7) {
                    out.add(n.substring(7, slash));
                }
            }
        }
        return out;
    }

    private static final Map<Path, FileSystem> JAR_FS = new HashMap<>();

    public static void registerAll(Class<?> selfClass, DeferredRegister<CreativeModeTab> tabs) {
        if (!ProxyShellSupport.enabled()) {
            return;
        }
        scan(selfClass);
        if (ENTRIES.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<ShellEntry>> me : BY_MOD.entrySet()) {
            final List<ShellEntry> list = me.getValue();
            final String title = MOD_TITLE.getOrDefault(me.getKey(), me.getKey());
            String tabId = sanitize("proxy_shell_" + me.getKey());
            TABS.add(tabs.register(tabId, () -> CreativeModeTab.builder()
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .title(Component.literal(title))
                    .icon(() -> {
                        for (ShellEntry e : list) {
                            if (e.item != null) {
                                return e.item.getDefaultInstance();
                            }
                        }
                        return new ItemStack(Items.BARRIER);
                    })
                    .displayItems((params, output) -> {
                        for (ShellEntry e : list) {
                            if (e.item != null) {
                                output.accept(e.item);
                            }
                        }
                    })
                    .build()));
        }
        ProxyShellSupport.log("已登记 " + TABS.size() + " 个按 mod 分的空壳创造栏");
    }

    public static void onRegisterItems(RegisterEvent event) {
        if (!ProxyShellSupport.enabled() || ENTRIES.isEmpty()) {
            return;
        }
        if (!ForgeRegistries.Keys.ITEMS.equals(event.getRegistryKey())) {
            return;
        }
        setForgeRegistryLogLevel("ERROR");
        try {
            int n = 0;
            for (ShellEntry e : ENTRIES) {
                ResourceLocation id = ResourceLocation.tryParse(e.ns + ":" + e.name);
                if (id == null) {
                    continue;
                }
                final boolean handheld = e.handheld;
                Item.Properties p = new Item.Properties();
                if (handheld || isArmorName(e.name)) {
                    p.stacksTo(1); // 剑/工具/盔甲 → 最大堆叠 1
                }
                final ShellItem item = new ShellItem(handheld, e.literalName, p);
                event.register(ForgeRegistries.Keys.ITEMS, id, () -> item);
                e.item = item;
                n++;
            }
            ProxyShellSupport.log("已按 modid name 注册 " + n + " 个空壳物品");
        } finally {
            setForgeRegistryLogLevel("WARN");
        }
    }

    static void setForgeRegistryLogLevel(String level) {
        try {
            Class<?> cfg = Class.forName("org.apache.logging.log4j.core.config.Configurator");
            Class<?> lvl = Class.forName("org.apache.logging.log4j.Level");
            Object lv = lvl.getMethod("valueOf", String.class).invoke(null, level);
            cfg.getMethod("setLevel", String.class, lvl).invoke(null, "net.minecraftforge.registries.ForgeRegistry", lv);
        } catch (Throwable ignore) {
        }
    }

    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (!ProxyShellSupport.enabled() || ENTRIES.isEmpty()) {
            return;
        }
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        event.addRepositorySource(consumer -> {
            Pack pack = Pack.readMetaAndCreate(
                    "reflection_proxy_shell",
                    Component.literal("Reflection 空壳资源"),
                    true,
                    id -> new ShellPackResources(),
                    PackType.CLIENT_RESOURCES,
                    Pack.Position.TOP,
                    PackSource.BUILT_IN);
            if (pack != null) {
                consumer.accept(pack);
            }
        });
        mountOverrideModels(event);
        mountBlockedJarPacks(event);
    }

    private static volatile Path OVERRIDE_DIR;

    private static void mountOverrideModels(AddPackFindersEvent event) {
        Path dir = extractOverrideModels();
        if (dir == null) {
            return;
        }
        event.addRepositorySource(consumer -> {
            Pack pack = Pack.readMetaAndCreate("reflection_shell_models", Component.literal("Reflection 去loader模型"),
                    true, id -> new PathPackResources(id, true, dir),
                    PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack != null) {
                consumer.accept(pack);
            }
        });
    }

    private static synchronized Path extractOverrideModels() {
        if (OVERRIDE_DIR != null) {
            return OVERRIDE_DIR;
        }
        try {
            Path dir = Files.createTempDirectory("reflection_shell_models");
            Files.writeString(dir.resolve("pack.mcmeta"),
                    "{\"pack\":{\"pack_format\":15,\"description\":\"Reflection proxy models\"}}", StandardCharsets.UTF_8);
            int n = 0;
            for (ShellEntry e : ENTRIES) {
                if (e.synthModelJson == null) {
                    continue;
                }
                try {
                    Path p = dir.resolve("assets").resolve(e.ns).resolve("models").resolve("item").resolve(e.name + ".json");
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, e.synthModelJson, StandardCharsets.UTF_8);
                    n++;
                } catch (Throwable perEntry) {

                }
            }
            OVERRIDE_DIR = dir;
            ProxyShellSupport.log("已提取 " + n + " 个去loader/合成模型到真实文件目录: " + dir);
        } catch (Throwable t) {
            ProxyShellSupport.log("提取去loader模型失败: " + t);
        }
        return OVERRIDE_DIR;
    }

    private static void mountBlockedJarPacks(AddPackFindersEvent event) {
        Set<Path> jars = new LinkedHashSet<>();
        for (ShellEntry e : ENTRIES) {
            if (e.texJar != null) {
                jars.add(e.texJar);
            }
        }
        for (Path jar : jars) {
            Set<String> allowed = new LinkedHashSet<>();
            for (ShellEntry e : ENTRIES) {
                if (jar.equals(e.texJar) && !isVanillaNs(e.ns)) {
                    allowed.add(e.ns);
                }
            }
            if (allowed.isEmpty()) {
                continue;
            }
            final Path root;
            try {
                root = openJarFs(jar).getPath("/");
            } catch (Throwable t) {
                ProxyShellSupport.log("挂被拦 jar 资源包失败 " + jar.getFileName() + ": " + t);
                continue;
            }
            final Set<String> suppressed = new HashSet<>();
            for (ShellEntry e : ENTRIES) {
                if (jar.equals(e.texJar) && e.synthModelJson != null) {
                    suppressed.add(e.ns + ":models/item/" + e.name + ".json");
                }
            }
            final String id = sanitize("reflection_shelljar_" + jar.getFileName().toString());
            event.addRepositorySource(consumer -> {
                Pack pack = Pack.readMetaAndCreate(id, Component.literal("Reflection 真资源代理"),
                        true, sid -> new ShellJarPack(id, root, allowed, suppressed),
                        PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.BUILT_IN);
                if (pack != null) {
                    consumer.accept(pack);
                    ProxyShellSupport.log("已挂被拦 jar 真资源包: " + jar.getFileName() + " ns=" + allowed);
                }
            });
        }
    }

    private static synchronized FileSystem openJarFs(Path jar) throws Exception {
        FileSystem fs = JAR_FS.get(jar);
        if (fs != null && fs.isOpen()) {
            return fs;
        }
        java.net.URI uri = java.net.URI.create("jar:" + jar.toUri());
        try {
            fs = FileSystems.newFileSystem(uri, java.util.Map.of());
        } catch (java.nio.file.FileSystemAlreadyExistsException ex) {
            fs = FileSystems.getFileSystem(uri);
        }
        JAR_FS.put(jar, fs);
        return fs;
    }

    private static Map<String, String> readLang(ZipFile zf, String modid) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String loc : new String[]{"en_us", "zh_cn"}) {
            ZipEntry e = zf.getEntry("assets/" + modid + "/lang/" + loc + ".json");
            if (e == null) {
                continue;
            }
            try {
                JsonElement je = JsonParser.parseString(new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
                if (je.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> me : je.getAsJsonObject().entrySet()) {
                        if (me.getValue().isJsonPrimitive()) {
                            out.put(me.getKey(), me.getValue().getAsString());
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        return out;
    }

    private static final class TexResult {
        boolean handheld;
        boolean proxied;
        String jarEntry;
        String directRef;
        String inheritParent;
    }

    private static TexResult analyzeModel(ZipFile zf, String modid, String modelEntry, String subdir, String name) {
        TexResult r = new TexResult();
        String texRef = null;
        String parentRef = null;
        try {
            JsonElement je = JsonParser.parseString(new String(zf.getInputStream(zf.getEntry(modelEntry)).readAllBytes(), StandardCharsets.UTF_8));
            if (je.isJsonObject()) {
                JsonObject obj = je.getAsJsonObject();
                if (obj.has("parent") && obj.get("parent").isJsonPrimitive()) {
                    parentRef = obj.get("parent").getAsString();
                    r.handheld = parentRef.contains("handheld");
                }
                if (obj.has("textures") && obj.get("textures").isJsonObject()) {
                    JsonObject tex = obj.getAsJsonObject("textures");
                    String[] prefer = subdir.equals("item")
                            ? new String[]{"layer0"}
                            : new String[]{"all", "texture", "side", "top", "particle", "end", "front"};
                    for (String k : prefer) {
                        if (tex.has(k) && tex.get(k).isJsonPrimitive()) {
                            texRef = tex.get(k).getAsString();
                            break;
                        }
                    }
                    if (texRef == null) {
                        for (Map.Entry<String, JsonElement> me : tex.entrySet()) {
                            if (me.getValue().isJsonPrimitive()) {
                                String v = me.getValue().getAsString();
                                if (!v.startsWith("#")) {
                                    texRef = v;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        if (texRef != null && !texRef.isEmpty() && !texRef.startsWith("#")) {
            String ns = modid;
            String path = texRef;
            int c = texRef.indexOf(':');
            if (c != -1) {
                ns = texRef.substring(0, c);
                path = texRef.substring(c + 1);
            }
            String jarEntry = "assets/" + ns + "/textures/" + path + ".png";
            if (zf.getEntry(jarEntry) != null) {
                r.proxied = true;
                r.jarEntry = jarEntry;
                return r;
            }
            if (!ns.equals(modid)) {
                r.proxied = false;
                r.directRef = ns + ":" + path;
                return r;
            }
        }
        for (String cand : new String[]{
                "assets/" + modid + "/textures/" + subdir + "/" + name + ".png",
                "assets/" + modid + "/textures/items/" + name + ".png"}) {
            if (zf.getEntry(cand) != null) {
                r.proxied = true;
                r.jarEntry = cand;
                return r;
            }
        }

        if (parentRef != null) {
            String pl = parentRef.toLowerCase();
            boolean vanilla = !pl.contains(":") || pl.startsWith("minecraft:") || pl.startsWith("forge:");
            boolean needsLayers = pl.endsWith("item/generated") || pl.endsWith("item/handheld") || pl.contains("builtin/");
            if (vanilla && !needsLayers) {
                r.proxied = false;
                r.inheritParent = parentRef.contains(":") ? parentRef : ("minecraft:" + parentRef);
                return r;
            }
        }

        String broad = findTextureByBaseName(zf, modid, name);
        if (broad != null) {
            r.proxied = true;
            r.jarEntry = broad;
            return r;
        }
        return null;
    }


    private static String findTextureByBaseName(ZipFile zf, String ns, String name) {
        String prefix = "assets/" + ns + "/textures/";
        List<String> pngs = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.startsWith(prefix) && n.endsWith(".png")) {
                pngs.add(n);
            }
        }
        for (String base : deriveBaseNames(name)) {
            String flat = prefix + base + ".png";
            String suffix = "/" + base + ".png";
            for (String n : pngs) {
                if (n.equals(flat) || n.endsWith(suffix)) {
                    return n;
                }
            }
        }
        return null;
    }

    private static List<String> deriveBaseNames(String name) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(name);
        for (String suf : new String[]{"_spawn_egg", "_spawnegg", "_spawn_item", "spawn_egg", "spawnegg", "_spawn", "_egg"}) {
            if (name.endsWith(suf) && name.length() > suf.length()) {
                out.add(name.substring(0, name.length() - suf.length()));
            }
        }
        for (String pre : new String[]{"spawn_egg_", "spawnegg_", "spawn_", "spawn"}) {
            if (name.startsWith(pre) && name.length() > pre.length()) {
                out.add(name.substring(pre.length()));
            }
        }
        return new ArrayList<>(out);
    }

    private static String lookupDisplayName(Map<String, String> lang, String modid, String name, String primaryPrefix) {
        String v = lang.get(primaryPrefix + modid + "." + name);
        if (v != null) {
            return v;
        }
        String other = primaryPrefix.equals("item.") ? "block." : "item.";
        v = lang.get(other + modid + "." + name);
        if (v != null) {
            return v;
        }
        String modidSeg = "." + modid + ".";
        String suffix = "." + name;
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Map.Entry<String, String> e : lang.entrySet()) {
            String k = e.getKey();
            if (k.contains(modidSeg) && k.endsWith(suffix)) {
                int rank = prefixRank(k);
                if (rank < bestRank) {
                    bestRank = rank;
                    best = e.getValue();
                }
            }
        }
        return best;
    }

    private static int prefixRank(String key) {
        if (key.startsWith("item.")) return 0;
        if (key.startsWith("block.")) return 1;
        if (key.startsWith("entity.")) return 2;
        if (key.startsWith("task.")) return 3;
        if (key.startsWith("advancements.")) return 4;
        return 9;
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = Character.toLowerCase(s.charAt(i));
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '.' || ch == '-') {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String sanitizeAll(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = Character.toLowerCase(s.charAt(i));
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
