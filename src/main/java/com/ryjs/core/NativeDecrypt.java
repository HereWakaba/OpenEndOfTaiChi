package com.ryjs.core;

/**
 * 原生解密（Util.dll）——密钥/算法不出本 DLL：反编译 Java 字节码只能看到本调用点，
 * 必须逆 Util.dll 才能拿到解密逻辑（2026-08-16 接线：此前本类缺失，运行时一直走 Java 版）。
 *
 * <p>算法与 {@link RyjsClassLoader}（Java 版）/ 构建期 tools/ClassEncryptor 严格一致（三边同步）：
 * RYJS 魔数 + 大端长度 + XOR 流（KEY_SALT 0x6A5C4E31）。密文还原为明文；无魔数明文原样返回；
 * 失败返回 null（调用方回退 Java 版）。
 */
public final class NativeDecrypt {

    private static volatile boolean loaded;
    private static volatile boolean failed;

    private NativeDecrypt() {
    }

    /** 解密尝试：native 优先。未加载成功/调用失败 → 返回 null（调用方回退 Java 版解密）。 */
    public static byte[] tryDecrypt(byte[] data) {
        if (!loaded && !failed) {
            synchronized (NativeDecrypt.class) {
                if (!loaded && !failed) {
                    try {
                        loadNative();
                        loaded = true;
                    } catch (Throwable t) {
                        failed = true;
                        System.err.println("[NativeDecrypt] Util.dll 加载失败（回退 Java 解密）: " + t);
                    }
                }
            }
        }
        if (!loaded) {
            return null;
        }
        try {
            return decrypt(data);
        } catch (Throwable t) {
            failed = true; // 后续调用永久回退 Java 版
            return null;
        }
    }

    /** 加载 Util.dll：jar resources 根目录解压临时文件加载（与 RyjsAgent.dll 同模式）。 */
    private static void loadNative() throws Exception {
        java.io.InputStream in = NativeDecrypt.class.getResourceAsStream("/Util.dll");
        if (in != null) {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ryjs_util", ".dll");
            tmp.toFile().deleteOnExit();
            java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            in.close();
            System.load(tmp.toAbsolutePath().toString());
        } else {
            System.loadLibrary("Util");
        }
    }

    /** JNI 入口（方法名必须为 decrypt——Util.cpp 导出 Java_com_ryjs_core_NativeDecrypt_decrypt）。
     *  幂等：无魔数明文原样返回；长度非法返回 null。 */
    private static native byte[] decrypt(byte[] data);
}
