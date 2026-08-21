package com.ryjs.core;

//DevMode
public final class ZeroJvmtiSim {

    private static volatile boolean nativeReady;

    private ZeroJvmtiSim() {
    }

    public static int runSim(String dllPath, boolean killJvmti) {
        ensureNative();
        if (!nativeReady) {
            return -100;
        }
        try {
            return sim(dllPath, killJvmti);
        } catch (Throwable t) {
            System.err.println("native sim failed: " + t);
            return -101;
        }
    }


    private static synchronized void ensureNative() {
        if (nativeReady) {
            return;
        }
        try {
            java.io.InputStream in = ZeroJvmtiSim.class.getResourceAsStream("/Util.dll");
            if (in != null) {
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ryjs_util", ".dll");
                tmp.toFile().deleteOnExit();
                java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                in.close();
                System.load(tmp.toAbsolutePath().toString());
            } else {
                System.loadLibrary("Util");
            }
            nativeReady = true;
        } catch (Throwable t) {
            System.err.println("Util.dll load failed: " + t);
        }
    }

    private static native int sim(String dllPath, boolean killJvmti);
}
