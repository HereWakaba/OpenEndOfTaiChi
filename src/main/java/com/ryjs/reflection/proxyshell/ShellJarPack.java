package com.ryjs.reflection.proxyshell;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.resource.PathPackResources;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

public class ShellJarPack extends PathPackResources {

    private static final String SYNTH_META =
            "{\"pack\":{\"pack_format\":15,\"description\":\"Reflection proxy assets\"}}";

    private final Set<String> allowed;
    private final Set<String> suppressedModels;

    public ShellJarPack(String packId, Path zipRoot, Set<String> allowedNamespaces, Set<String> suppressedModels) {
        super(packId, true, zipRoot);
        this.allowed = allowedNamespaces;
        this.suppressedModels = suppressedModels;
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        if (paths.length == 1 && "pack.mcmeta".equals(paths[0])) {
            return () -> new ByteArrayInputStream(SYNTH_META.getBytes(StandardCharsets.UTF_8));
        }
        return super.getRootResource(paths);
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (!allowed.contains(location.getNamespace())) {
            return null;
        }
        if (type == PackType.CLIENT_RESOURCES && suppressedModels != null
                && suppressedModels.contains(location.getNamespace() + ":" + location.getPath())) {
            return null;
        }
        return super.getResource(type, location);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, PackResources.ResourceOutput out) {
        if (!allowed.contains(namespace)) {
            return;
        }
        super.listResources(type, namespace, path, out);
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return allowed;
    }
}
