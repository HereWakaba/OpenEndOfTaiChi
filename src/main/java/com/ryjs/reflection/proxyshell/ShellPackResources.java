package com.ryjs.reflection.proxyshell;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ShellPackResources extends AbstractPackResources {

    private static final String NS = "reflection";
    private static final int PACK_FORMAT = 15; // 1.20.1 资源包格式
    private static final String PACK_MCMETA =
            "{\"pack\":{\"description\":\"Reflection proxy shell\",\"pack_format\":" + PACK_FORMAT + "}}";

    private static volatile boolean langLogged = false;

    public ShellPackResources() {
        super("reflection_proxy_shell", true);
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        if (paths.length == 1 && "pack.mcmeta".equals(paths[0])) {
            return bytes(PACK_MCMETA.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES || !NS.equals(location.getNamespace())) {
            return null;
        }
        return supply(location.getPath());
    }

    private IoSupplier<InputStream> supply(String path) {
        if ("lang/en_us.json".equals(path) || "lang/zh_cn.json".equals(path)) {
            byte[] b = buildLangJson().getBytes(StandardCharsets.UTF_8);
            if (!langLogged) {
                langLogged = true;
                com.ryjs.proxyshell.ProxyShellSupport.log("供给 " + path + "（" + ProxyShellContent.ENTRIES.size()
                        + " 键，字节=" + b.length + "）——若游戏内仍显示原始 key，则问题在 pack 应用/合并而非本供给");
            }
            return bytes(b);
        }
        if (path.startsWith("models/item/") && path.endsWith(".json")) {
            String reg = path.substring("models/item/".length(), path.length() - ".json".length());
            ProxyShellContent.ShellEntry ie = findEntry(reg);
            if (ie != null && !ie.catchAll) {
                return bytes(buildItemModel(ie).getBytes(StandardCharsets.UTF_8));
            }
            ProxyShellEntities.ShellEntityEntry se = findSpawn(reg);
            if (se != null) {
                return bytes(buildSpawnItemModel(se.entityReg).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        }
        if (path.startsWith("textures/entity/") && path.endsWith(".png.mcmeta")) {
            String reg = path.substring("textures/entity/".length(), path.length() - ".png.mcmeta".length());
            ProxyShellEntities.ShellEntityEntry se = findEntity(reg);
            if (se != null && se.mcmetaEntry != null) {
                byte[] mm = readZip(se.texJar, se.mcmetaEntry);
                if (mm != null) {
                    return bytes(mm);
                }
            }
            return null;
        }
        // 实体贴图从被拦 jar 现读
        if (path.startsWith("textures/entity/") && path.endsWith(".png")) {
            String reg = path.substring("textures/entity/".length(), path.length() - ".png".length());
            ProxyShellEntities.ShellEntityEntry se = findEntity(reg);
            if (se != null) {
                byte[] png = readZip(se.texJar, se.texEntry);
                if (png != null) {
                    return bytes(png);
                }
            }
            return null;
        }

        if (path.startsWith("textures/item/") && path.endsWith(".png.mcmeta")) {
            String reg = path.substring("textures/item/".length(), path.length() - ".png.mcmeta".length());
            ProxyShellContent.ShellEntry e = findEntry(reg);
            if (e != null && e.mcmetaEntry != null) {
                byte[] mm = readZip(e.texJar, e.mcmetaEntry);
                if (mm != null) {
                    return bytes(mm);
                }
            }
            return null;
        }
        if (path.startsWith("textures/item/") && path.endsWith(".png")) {
            String reg = path.substring("textures/item/".length(), path.length() - ".png".length());
            ProxyShellContent.ShellEntry e = findEntry(reg);
            if (e != null && e.proxied) {
                byte[] png = readZip(e.texJar, e.texEntry);
                if (png != null) {
                    return bytes(png);
                }
            }
            return null;
        }
        return null;
    }

    @Override
    public void listResources(PackType type, String namespace, String path, PackResources.ResourceOutput out) {
        if (type != PackType.CLIENT_RESOURCES || !NS.equals(namespace)) {
            return;
        }
        for (String loc : new String[]{"lang/en_us.json", "lang/zh_cn.json"}) {
            if (loc.startsWith(path)) {
                emit(out, loc);
            }
        }
        for (ProxyShellContent.ShellEntry e : ProxyShellContent.ENTRIES) {
            String model = "models/item/" + e.regName + ".json";
            String tex = "textures/item/" + e.regName + ".png";
            if (model.startsWith(path)) {
                emit(out, model);
            }
            if (tex.startsWith(path)) {
                emit(out, tex);
            }
            if (e.mcmetaEntry != null) {
                String mm = "textures/item/" + e.regName + ".png.mcmeta";
                if (mm.startsWith(path)) {
                    emit(out, mm);
                }
            }
        }
    }

    private void emit(PackResources.ResourceOutput out, String path) {
        IoSupplier<InputStream> s = supply(path);
        if (s != null) {
            out.accept(new ResourceLocation(NS, path), s);
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? Set.of(NS) : Collections.emptySet();
    }

    @Override
    public void close() {
    }


    private static String buildItemModel(ProxyShellContent.ShellEntry e) {
        if (e.inheritParent != null) {
            return "{\"parent\":\"" + e.inheritParent + "\"}";
        }
        String parent = e.handheld ? "minecraft:item/handheld" : "minecraft:item/generated";
        return "{\"parent\":\"" + parent + "\",\"textures\":{\"layer0\":\"" + e.layer0 + "\"}}";
    }

    private static String buildSpawnItemModel(String entityReg) {
        return "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + NS + ":entity/" + entityReg + "\"}}";
    }

    private static ProxyShellEntities.ShellEntityEntry findEntity(String entityReg) {
        for (ProxyShellEntities.ShellEntityEntry e : ProxyShellEntities.ENTITY_ENTRIES) {
            if (e.entityReg.equals(entityReg)) {
                return e;
            }
        }
        return null;
    }

    private static ProxyShellEntities.ShellEntityEntry findSpawn(String spawnReg) {
        for (ProxyShellEntities.ShellEntityEntry e : ProxyShellEntities.ENTITY_ENTRIES) {
            if (e.spawnReg.equals(spawnReg)) {
                return e;
            }
        }
        return null;
    }

    private static String buildLangJson() {
        JsonObject o = new JsonObject();
        for (ProxyShellContent.ShellEntry e : ProxyShellContent.ENTRIES) {
            if (e.catchAll) {
                continue;
            }
            o.addProperty("item." + NS + "." + e.regName, e.displayName);
        }
        for (ProxyShellEntities.ShellEntityEntry e : ProxyShellEntities.ENTITY_ENTRIES) {
            o.addProperty("entity." + NS + "." + e.entityReg, e.displayName);
            o.addProperty("item." + NS + "." + e.spawnReg, e.displayName);
        }
        return o.toString();
    }

    private static ProxyShellContent.ShellEntry findEntry(String regName) {
        for (ProxyShellContent.ShellEntry e : ProxyShellContent.ENTRIES) {
            if (e.regName.equals(regName)) {
                return e;
            }
        }
        return null;
    }

    private static byte[] readZip(java.nio.file.Path jar, String entry) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry(entry);
            if (e == null) {
                return null;
            }
            try (InputStream in = zf.getInputStream(e)) {
                return in.readAllBytes();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static IoSupplier<InputStream> bytes(byte[] b) {
        return () -> new ByteArrayInputStream(b);
    }
}
