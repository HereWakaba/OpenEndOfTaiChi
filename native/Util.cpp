#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <windows.h>
#include <psapi.h>
#include <stdio.h>

// 涓?Java 绔?RyjsClassLoader 鐨?MAGIC / KEY_SALT 涓ユ牸涓€鑷淬€?
static const uint32_t MAGIC    = 0x52594A53u;
static const uint32_t KEY_SALT = 0x6A5C4E31u;

extern "C" { // JNI export must be C-linkage (no C++ mangling -> UnsatisfiedLinkError otherwise)

JNIEXPORT jbyteArray JNICALL
Java_com_ryjs_core_NativeDecrypt_decrypt(JNIEnv *env, jclass clazz, jbyteArray data) {
    (void)clazz;
    if (data == NULL) {
        return NULL;
    }
    jsize dataLen = env->GetArrayLength(data);
    if (dataLen < 8) {
        // 澶煭涓嶈冻浠ュ绾抽瓟鏁?闀垮害澶达細涓?Java 鐗堜竴鑷村師鏍烽€忎紶
        return data;
    }
    jbyte *bytes = env->GetByteArrayElements(data, NULL);
    if (bytes == NULL) {
        return NULL;
    }
    uint32_t magic = ((uint32_t)(uint8_t)bytes[0] << 24)
                   | ((uint32_t)(uint8_t)bytes[1] << 16)
                   | ((uint32_t)(uint8_t)bytes[2] << 8)
                   | ((uint32_t)(uint8_t)bytes[3]);
    if (magic != MAGIC) {
        // 闈炲瘑鏂囷紙閫忎紶鏁版嵁锛屽 .class锛夛細鍘熸牱杩斿洖
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        return data;
    }
    uint32_t len = ((uint32_t)(uint8_t)bytes[4] << 24)
                 | ((uint32_t)(uint8_t)bytes[5] << 16)
                 | ((uint32_t)(uint8_t)bytes[6] << 8)
                 | ((uint32_t)(uint8_t)bytes[7]);
    if (len == 0 || (int32_t)len > dataLen - 8) {
        // 闀垮害鏍￠獙澶辫触锛氫笌 Java 鐗堜竴鑷磋繑鍥?NULL锛堣В瀵嗗け璐ワ級
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        return NULL;
    }
    jbyteArray out = env->NewByteArray((jsize)len);
    if (out == NULL) {
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        return NULL;
    }
    jbyte *dst = env->GetByteArrayElements(out, NULL);
    uint32_t acc = KEY_SALT ^ len;
    for (uint32_t i = 0; i < len; i++) {
        acc = acc * 33u ^ (i + len);
        dst[i] = (jbyte)(bytes[8 + i] ^ (jbyte)acc);
    }
    env->ReleaseByteArrayElements(out, dst, 0);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return out;
}

// DevMode first-mover simulation: locate (or load) ZeroJvmti.dll by exact path, call its
// ZeroJvmti_Init (optionally ZeroJvmti_KillJvmti for the write-war drill). Exact-path matching
// avoids accidentally binding to a REAL CPV ZeroJvmti.dll with the same base name.
JNIEXPORT jint JNICALL
Java_com_ryjs_core_ZeroJvmtiSim_sim(JNIEnv *env, jclass clazz, jstring dllPath, jboolean killJvmti) {
    (void)clazz;
    if (dllPath == NULL) return -1;
    const char *path = env->GetStringUTFChars(dllPath, NULL);
    if (path == NULL) return -1;
    char lower[MAX_PATH] = {0};
    for (int i = 0; path[i] && i < MAX_PATH - 1; i++) {
        lower[i] = (char)((path[i] >= 'A' && path[i] <= 'Z') ? path[i] + 32 : path[i]);
    }
    HMODULE mod = NULL;
    HMODULE mods[512];
    DWORD needed = 0;
    if (EnumProcessModules(GetCurrentProcess(), mods, sizeof(mods), &needed)) {
        int n = (int)(needed / sizeof(HMODULE));
        if (n > 512) n = 512;
        for (int i = 0; i < n; i++) {
            char mpath[MAX_PATH] = {0};
            if (GetModuleFileNameA(mods[i], mpath, MAX_PATH) > 0) {
                char mlower[MAX_PATH] = {0};
                for (int k = 0; mpath[k] && k < MAX_PATH - 1; k++) {
                    mlower[k] = (char)((mpath[k] >= 'A' && mpath[k] <= 'Z') ? mpath[k] + 32 : mpath[k]);
                }
                if (strcmp(mlower, lower) == 0) { mod = mods[i]; break; }
            }
        }
    }
    if (mod == NULL) {
        mod = LoadLibraryA(path); // not loaded yet: we load it (the simulated adversary DLL enters the process)
    }
    env->ReleaseStringUTFChars(dllPath, path);
    if (mod == NULL) return -2;
    typedef int (*PFN_ZeroJvmti_Init)(JNIEnv *);
    PFN_ZeroJvmti_Init initFn = (PFN_ZeroJvmti_Init)GetProcAddress(mod, "ZeroJvmti_Init");
    if (initFn == NULL) return -3;
    int r = initFn(env);
    if (killJvmti) {
        typedef int (*PFN_ZeroJvmti_KillJvmti)(void);
        PFN_ZeroJvmti_KillJvmti killFn = (PFN_ZeroJvmti_KillJvmti)GetProcAddress(mod, "ZeroJvmti_KillJvmti");
        if (killFn != NULL) killFn();
    }
    fprintf(stderr, "[ZeroJvmtiSim] ZeroJvmti_Init=%d (killJvmti=%d)\n", r, (int)killJvmti);
    return r;
}

} // extern "C"


