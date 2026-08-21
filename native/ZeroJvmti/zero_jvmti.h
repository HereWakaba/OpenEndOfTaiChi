/*
 * zero_jvmti.h — 零 jvmtiEnv 参数的 JVMTI 功能 API
 *
 * 五个功能, 全部不暴露 jvmtiEnv, 调用者完全不需要 JVMTI 知识。
 *
 * 实现策略:
 *   ClassFileLoadHook  → Detours Hook JVM_DefineClassWithSource (导出函数)
 *   RedefineClasses    → 从函数表包装器提取成员函数地址, NULL this 调用
 *   RetransformClasses → 同上
 *   GetAllLoadedClasses→ 同上
 *   GetInstances       → 内部 bootstrap env (仅此功能需要 heap walk)
 */

#ifndef MYCODE_ZERO_JVMTI_H
#define MYCODE_ZERO_JVMTI_H

#include "jni.h"

#ifdef _WIN32
#define ZEROJVMTI_API __declspec(dllexport)
#else
#define ZEROJVMTI_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

    // ── 类加载钩子 ─────────────────────────────────────────
    typedef int (*ZeroClassLoadHook)(
        JNIEnv* jni_env,
        jobject              loader,
        const unsigned char* class_data,
        jint                 class_data_len,
        unsigned char** new_data,
        jint* new_len,
        void* user_data
        );

    // ── 初始化/销毁 ────────────────────────────────────────
    ZEROJVMTI_API int  ZeroJvmti_Init(JNIEnv* env);
    ZEROJVMTI_API void ZeroJvmti_Shutdown(void);

    // ── 1. ClassFileLoadHook ───────────────────────────────
    ZEROJVMTI_API void ZeroJvmti_SetClassLoadHook(ZeroClassLoadHook hook, void* user_data);

    // ── 2. GetAllLoadedClasses ─────────────────────────────
    ZEROJVMTI_API int ZeroJvmti_GetAllLoadedClasses(JNIEnv* env, jint* count, jclass** classes);

    // ── 3. GetInstancesOfClass ─────────────────────────────
    ZEROJVMTI_API int ZeroJvmti_GetInstances(JNIEnv* env, jclass targetKlass,
        jint* count, jobject** instances);

    // ── 4. RedefineClass ───────────────────────────────────
    ZEROJVMTI_API int ZeroJvmti_RedefineClass(JNIEnv* env, jclass target,
        const unsigned char* new_bytes, jint new_len);

    // ── 5. RetransformClass ────────────────────────────────
    ZEROJVMTI_API int ZeroJvmti_RetransformClass(JNIEnv* env, jclass target);

    // ── 6. KillJvmti ───────────────────────────────────────
    // 永久禁用 Redefine/Retransform:
    //   L1 函数表槽位 → stub; L2 成员函数入口 → Detours 跳转;
    //   L3 JVM_RedefineClasses 导出 → Detours 跳转;
    //   watchdog 线程防写回 (只改内存, 不改磁盘)。
    ZEROJVMTI_API int ZeroJvmti_KillJvmti(void);

#ifdef __cplusplus
}
#endif

#endif
