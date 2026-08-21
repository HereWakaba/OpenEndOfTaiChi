package com.ryjs.reflection.api.client.util;


import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;


public class ResourceUtils {

    public static InputStream getResourceAsStream(ResourceLocation resource) throws IOException {
        return getResource(resource).open();
    }

    public static ReloadableResourceManager getResourceManager() {
        return (ReloadableResourceManager) Minecraft.getInstance().getResourceManager();
    }

    public static Resource getResource(ResourceLocation location) throws IOException {
        return getResourceManager().getResourceOrThrow(location);
    }

    public static void registerReloadListener(ResourceManagerReloadListener reloadListener) {
        getResourceManager().registerReloadListener(reloadListener);
    }

    public static List<String> loadResource(ResourceProvider resourceProvider, ResourceLocation loc) {
        try {
            Resource resource = resourceProvider.getResourceOrThrow(loc);
            try (BufferedReader reader = resource.openAsReader()) {
                return reader.lines().toList();
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to load MTL file: " + loc, ex);
        }
    }

}
