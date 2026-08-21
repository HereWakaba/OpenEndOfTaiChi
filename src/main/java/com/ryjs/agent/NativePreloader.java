package com.ryjs.agent;


public final class NativePreloader {

    private static volatile boolean loaded = false;

    private NativePreloader() {}


    public static boolean isLoaded() {
        return loaded;
    }


    public static synchronized void preload(Class<?> resourceOwner) {
        if (loaded) return;
        try {
            java.io.InputStream in = resourceOwner.getResourceAsStream("/taichi_hook.dll");
            if (in == null) {
                System.out.println("未在 jar 内找到 /taichi_hook.dll，跳过加载");
                return;
            }
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("taichi_hook", ".dll");
            tmp.toFile().deleteOnExit();
            java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            in.close();
            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
        } catch (Throwable e) {
            System.out.println("dll加载失败: " + e);
        }
    }
}
