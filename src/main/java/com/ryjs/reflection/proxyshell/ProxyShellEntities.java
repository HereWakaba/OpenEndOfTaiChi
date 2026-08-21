package com.ryjs.reflection.proxyshell;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.ryjs.proxyshell.ProxyShellSupport;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;

import java.nio.charset.StandardCharsets;
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


public final class ProxyShellEntities {

    private ProxyShellEntities() {}

    public static final class ShellEntityEntry {
        public final String group;
        public final String ns;
        public final String name;
        public final String entityReg;
        public final String spawnReg;
        public final String displayName;
        public final Path texJar;
        public final String texEntry;
        public final String mcmetaEntry;
        EntityType<ShellBillboardEntity> type;
        RegistryObject<Item> spawnItem;

        ShellEntityEntry(String group, String ns, String name, String entityReg, String spawnReg, String displayName, Path texJar, String texEntry, String mcmetaEntry) {
            this.group = group;
            this.ns = ns;
            this.name = name;
            this.entityReg = entityReg;
            this.spawnReg = spawnReg;
            this.displayName = displayName;
            this.texJar = texJar;
            this.texEntry = texEntry;
            this.mcmetaEntry = mcmetaEntry;
        }
    }

    public static final List<ShellEntityEntry> ENTITY_ENTRIES = new ArrayList<>();

    private static final Map<EntityType<?>, ResourceLocation> TYPE_TO_TEX = new HashMap<>();

    private static final Map<String, List<ShellEntityEntry>> BY_MOD_ENT = new LinkedHashMap<>();

    private static final Map<String, String> MOD_TITLE = new LinkedHashMap<>();
    private static volatile boolean scanned = false;

    public static ResourceLocation textureFor(EntityType<?> type) {
        return TYPE_TO_TEX.get(type);
    }

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
            try (ZipFile zf = new ZipFile(jar.toFile())) {

                for (String ns : collectAssetNamespaces(zf)) {
                    Map<String, String> lang = readLang(zf, ns);
                    Set<String> names = new LinkedHashSet<>();
                    collectEntityNamesFromLang(lang, ns, names);
                    collectEntityNamesFromTextures(zf, ns, names);
                    for (String name : names) {
                        String texEntry = findEntityTexture(zf, ns, name);
                        if (texEntry == null) {
                            continue;
                        }
                        String entityReg = sanitize("shell_entity_" + ns + "_" + name);
                        if (!seen.add(entityReg)) {
                            continue;
                        }
                        String disp = lang.getOrDefault("entity." + ns + "." + name, name);
                        String mcmeta = zf.getEntry(texEntry + ".mcmeta") != null ? texEntry + ".mcmeta" : null;
                        String spawnReg = sanitize("shell_spawn_" + ns + "_" + name);
                        ShellEntityEntry e = new ShellEntityEntry(group, ns, name, entityReg, spawnReg, disp, jar, texEntry, mcmeta);
                        ENTITY_ENTRIES.add(e);
                        BY_MOD_ENT.computeIfAbsent(group, k -> new ArrayList<>()).add(e);
                    }
                }
                MOD_TITLE.put(group, ProxyShellSupport.readModDisplayName(jar, group));
            } catch (Throwable t) {
                ProxyShellSupport.log("扫描实体失败 " + jar.getFileName() + ": " + t);
            }
        }
        ProxyShellSupport.log("实体空壳条目共 " + ENTITY_ENTRIES.size() + " 个");
    }

    public static void registerAll(Class<?> selfClass, DeferredRegister<Item> items, DeferredRegister<CreativeModeTab> tabs) {
        if (!ProxyShellSupport.enabled()) {
            return;
        }
        scan(selfClass);
        if (ENTITY_ENTRIES.isEmpty()) {
            return;
        }
        for (ShellEntityEntry e : ENTITY_ENTRIES) {
            e.spawnItem = items.register(e.spawnReg, () -> new ShellSpawnItem(() -> e.type, new Item.Properties()));
        }
        for (Map.Entry<String, List<ShellEntityEntry>> me : BY_MOD_ENT.entrySet()) {
            final List<ShellEntityEntry> list = me.getValue();
            final String title = MOD_TITLE.getOrDefault(me.getKey(), me.getKey()) + "（实体）";
            String tabId = sanitize("proxy_shell_ent_" + me.getKey());
            tabs.register(tabId, () -> CreativeModeTab.builder()
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .title(Component.literal(title))
                    .icon(() -> {
                        for (ShellEntityEntry e : list) {
                            if (e.spawnItem != null) {
                                return e.spawnItem.get().getDefaultInstance();
                            }
                        }
                        return new ItemStack(Items.BARRIER);
                    })
                    .displayItems((params, output) -> {
                        for (ShellEntityEntry e : list) {
                            if (e.spawnItem != null) {
                                output.accept(e.spawnItem.get());
                            }
                        }
                    })
                    .build());
        }
        ProxyShellSupport.log("已登记 " + ENTITY_ENTRIES.size() + " 个生成物品 + " + BY_MOD_ENT.size() + " 个实体创造栏");
    }

    public static void onRegisterEntityTypes(RegisterEvent event) {
        if (!ProxyShellSupport.enabled() || ENTITY_ENTRIES.isEmpty()) {
            return;
        }
        if (!ForgeRegistries.Keys.ENTITY_TYPES.equals(event.getRegistryKey())) {
            return;
        }
        ProxyShellContent.setForgeRegistryLogLevel("ERROR");
        try {
            int n = 0;
            for (ShellEntityEntry e : ENTITY_ENTRIES) {
                ResourceLocation id = ResourceLocation.tryParse(e.ns + ":" + e.name);
                if (id == null) {
                    continue;
                }
                EntityType<ShellBillboardEntity> type = EntityType.Builder.<ShellBillboardEntity>of(ShellBillboardEntity::new, MobCategory.MISC)
                        .sized(0.6F, 1.8F)
                        .clientTrackingRange(8)
                        .build(e.entityReg);
                event.register(ForgeRegistries.Keys.ENTITY_TYPES, id, () -> type);
                e.type = type;
                n++;
            }
            ProxyShellSupport.log("已按 modid name 注册 " + n + " 个空壳实体");
        } finally {
            ProxyShellContent.setForgeRegistryLogLevel("WARN");
        }
    }

    public static void bindTextures() {
        for (ShellEntityEntry e : ENTITY_ENTRIES) {
            if (e.type != null) {
                TYPE_TO_TEX.put(e.type, new ResourceLocation("reflection", "textures/entity/" + e.entityReg + ".png"));
            }
        }
    }


    public static List<EntityType<ShellBillboardEntity>> registeredTypes() {
        List<EntityType<ShellBillboardEntity>> out = new ArrayList<>();
        for (ShellEntityEntry e : ENTITY_ENTRIES) {
            if (e.type != null) {
                out.add(e.type);
            }
        }
        return out;
    }


    private static void collectEntityNamesFromLang(Map<String, String> lang, String modid, Set<String> out) {
        String prefix = "entity." + modid + ".";
        for (String key : lang.keySet()) {
            if (key.startsWith(prefix)) {
                String rest = key.substring(prefix.length());
                if (!rest.isEmpty() && !rest.contains(".")) { // 只取一层，跳过 entity.x.y.z 子键
                    out.add(rest);
                }
            }
        }
    }

    private static void collectEntityNamesFromTextures(ZipFile zf, String modid, Set<String> out) {
        for (String dir : new String[]{"entity", "entities"}) {
            String prefix = "assets/" + modid + "/textures/" + dir + "/";
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith(prefix) && n.endsWith(".png")) {
                    String name = n.substring(prefix.length(), n.length() - 4);
                    if (!name.isEmpty() && !name.contains("/")) {
                        out.add(name);
                    }
                }
            }
        }
    }


    private static String findEntityTexture(ZipFile zf, String modid, String name) {
        for (String dir : new String[]{"entity", "entities"}) {
            String c = "assets/" + modid + "/textures/" + dir + "/" + name + ".png";
            if (zf.getEntry(c) != null) {
                return c;
            }
        }
        return null;
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
}
