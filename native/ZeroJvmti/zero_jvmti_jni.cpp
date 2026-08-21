/*
 * zero_jvmti_jni.cpp — JNI 胶水层
 * 将 ZeroJvmti 的 C API 桥接到 Java native 方法
 */

#include <windows.h>
#include <stdlib.h>
#include <string.h>
#include "jni.h"
#include "zero_jvmti.h"

 // ── Java 回调桥接 ──────────────────────────────────────
static jobject  g_java_hook_obj = NULL;
static jmethodID g_java_hook_mid = NULL;

static int java_hook_bridge(JNIEnv* env, jobject loader,
    const unsigned char* data, jint len,
    unsigned char** out, jint* out_len,
    void* user_data) {
    (void)user_data;
    *out = NULL; *out_len = 0;
    if (g_java_hook_obj == NULL) return 0;

    jbyteArray inArr = env->NewByteArray(len);
    if (inArr == NULL) return -1;
    env->SetByteArrayRegion(inArr, 0, len, (const jbyte*)data);

    jobject result = env->CallObjectMethod(g_java_hook_obj, g_java_hook_mid,
        loader, inArr);
    env->DeleteLocalRef(inArr);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return -1; }
    if (result == NULL) return 0;

    jbyteArray outArr = (jbyteArray)result;
    *out_len = env->GetArrayLength(outArr);
    *out = (unsigned char*)malloc(*out_len);
    if (*out == NULL) { env->DeleteLocalRef(result); return -1; }
    env->GetByteArrayRegion(outArr, 0, *out_len, (jbyte*)*out);
    env->DeleteLocalRef(result);
    return 0;
}

// ── JNI 导出函数 ───────────────────────────────────────

extern "C" {

JNIEXPORT jint JNICALL
Java_renaSatou_iNative_jni_instrumentation_ZeroJvmti_init(JNIEnv* env, jclass cls) {
    return (jint)ZeroJvmti_Init(env);
}

JNIEXPORT void JNICALL
Java_renaSatou_iNative_jni_instrumentation_ZeroJvmti_shutdown(JNIEnv* env, jclass cls) {
    ZeroJvmti_Shutdown();
}

JNIEXPORT jobjectArray JNICALL
Java_renaSatou_iNative_jni_instrumentation_ZeroJvmti_getAllLoadedClasses(JNIEnv* env, jclass cls) {
    jint count = 0; jclass* classes = NULL;
    if (ZeroJvmti_GetAllLoadedClasses(env, &count, &classes) != 0) return NULL;
    if (count == 0 || classes == NULL) return NULL;

    jclass cc = env->FindClass("java/lang/Class");
    jobjectArray arr = env->NewObjectArray(count, cc, NULL);
    for (jint i = 0; i < count; i++) {
        env->SetObjectArrayElement(arr, i, classes[i]);
        env->DeleteLocalRef(classes[i]);
    }
    free(classes);
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_renaSatou_iNative_jni_instrumentation_ZeroJvmti_getInstances(JNIEnv* env, jclass cls, jclass target) {
    jint count = 0; jobject* objs = NULL;
    if (ZeroJvmti_GetInstances(env, target, &count, &objs) != 0) return NULL;
    if (count == 0 || objs == NULL) return NULL;

    jobjectArray arr = env->NewObjectArray(count, target, NULL);
    for (jint i = 0; i < count; i++) {
        env->SetObjectArrayElement(arr, i, objs[i]);
        env->DeleteLocalRef(objs[i]);
    }
    free(objs);
    return arr;
}

JNIEXPORT jint JNICALL
Java_renaSatou_iNative_jni_instrumentation_ZeroJvmti_redefineClass(JNIEnv* env, jclass cls,
    jclass target, jbyteArray newBytes) {
    jint len = env->GetArrayLength(newBytes);
    jbyte* buf = env->GetByteArrayElements(newBytes, NULL);
    jint r = (jint)ZeroJvmti_RedefineClass(env, target,
        (const unsigned char*)buf, len);
    env->ReleaseByteArrayElements(newBytes, buf, JNI_ABORT);
    return r;
}

JNIEXPORT jint JNICALL
Java_renaSatou_iNative_jni_instrumentation_ZeroJvmti_retransformClass(JNIEnv* env, jclass cls, jclass target) {
    return (jint)ZeroJvmti_RetransformClass(env, target);
}

JNIEXPORT jint JNICALL
Java_renaSatou_iNative_jni_instrumentation_ZeroJvmti_killJvmti(JNIEnv* env, jclass cls) {
    return (jint)ZeroJvmti_KillJvmti();
}

JNIEXPORT jint JNICALL
Java_renaSatou_iNative_jni_instrumentation_ZeroJvmti_setClassLoadHook(JNIEnv* env, jclass cls, jobject hook) {
    if (g_java_hook_obj != NULL) {
        env->DeleteGlobalRef(g_java_hook_obj);
        g_java_hook_obj = NULL; g_java_hook_mid = NULL;
    }
    if (hook == NULL) { ZeroJvmti_SetClassLoadHook(NULL, NULL); return 0; }

    jclass hc = env->GetObjectClass(hook);
    g_java_hook_mid = env->GetMethodID(hc, "onClassLoad",
        "(Ljava/lang/Object;[B)[B");
    if (g_java_hook_mid == NULL) return -1;
    g_java_hook_obj = env->NewGlobalRef(hook);
    ZeroJvmti_SetClassLoadHook(java_hook_bridge, NULL);
    return 0;
}

} // extern "C"
