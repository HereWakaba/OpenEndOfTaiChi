#include <jni.h>
#include <jvmti.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include <intrin.h>
#include <psapi.h>

#include "detours/src/detours.h"

extern "C" {

static jvmtiEnv *g_jvmti = NULL;
static JavaVM *g_jvm = NULL;                 // JNI_OnLoad 的 vm（GetEnv 封锁用）
static jclass g_bridgeClass = NULL;       // 全局引用（Java 分发器）
static jmethodID g_dispatchMethod = NULL; // dispatchTransform

// ===== 原指针保存（2026-08-16：表打爆后我们仍可用——所有内部 jvmti 调用走这些原指针，绝不碰函数表） =====
// 对方（vt_patch 式）可把 jvmtiInterface_1_ 表项改成 noop/自己的函数——我们保存表项原指针后
// 内部调用全部直接调原函数（不走表），表被改对我们零影响。
typedef jvmtiError (JNICALL *FN_AddCapabilities)(jvmtiEnv *, const jvmtiCapabilities *);
typedef jvmtiError (JNICALL *FN_SetEventCallbacks)(jvmtiEnv *, const jvmtiEventCallbacks *, jint);
typedef jvmtiError (JNICALL *FN_SetEventNotificationMode)(jvmtiEnv *, jvmtiEventMode, jvmtiEvent, jobject);
typedef jvmtiError (JNICALL *FN_GetLoadedClasses)(jvmtiEnv *, jint *, jclass **);
typedef jvmtiError (JNICALL *FN_GetClassSignature)(jvmtiEnv *, jclass, char **, char **);
typedef jvmtiError (JNICALL *FN_GetImplementedInterfaces)(jvmtiEnv *, jclass, jint *, jclass **);
typedef jvmtiError (JNICALL *FN_IsModifiableClass)(jvmtiEnv *, jclass, jboolean *);
typedef jvmtiError (JNICALL *FN_RetransformClasses)(jvmtiEnv *, jint, const jclass *);
typedef jvmtiError (JNICALL *FN_RedefineClasses)(jvmtiEnv *, jint, const jvmtiClassDefinition *);
typedef jvmtiError (JNICALL *FN_Allocate)(jvmtiEnv *, jlong, unsigned char **);
typedef jvmtiError (JNICALL *FN_Deallocate)(jvmtiEnv *, unsigned char *);
typedef jvmtiError (JNICALL *FN_GetErrorName)(jvmtiEnv *, jvmtiError, char **);
typedef jvmtiError (JNICALL *FN_DisposeEnvironment)(jvmtiEnv *);
static FN_AddCapabilities g_fnAddCapabilities = NULL;
static FN_SetEventCallbacks g_fnSetEventCallbacks = NULL;
static FN_SetEventNotificationMode g_fnSetEventNotificationMode = NULL;
static FN_GetLoadedClasses g_fnGetLoadedClasses = NULL;
static FN_GetClassSignature g_fnGetClassSignature = NULL;
static FN_GetImplementedInterfaces g_fnGetImplementedInterfaces = NULL;
static FN_IsModifiableClass g_fnIsModifiableClass = NULL;
static FN_RetransformClasses g_fnRetransformClasses = NULL;
static FN_RedefineClasses g_fnRedefineClasses = NULL;
static FN_Allocate g_fnAllocate = NULL;
static FN_Deallocate g_fnDeallocate = NULL;
static FN_GetErrorName g_fnGetErrorName = NULL;
static FN_DisposeEnvironment g_fnDisposeEnvironment = NULL;
// 包装器原始指针备份（disarm 时把 g_fnX 从成员 trampoline 退回包装器用）
static FN_AddCapabilities g_fnOrigAddCapabilities = NULL;
static FN_SetEventCallbacks g_fnOrigSetEventCallbacks = NULL;
static FN_SetEventNotificationMode g_fnOrigSetEventNotificationMode = NULL;
static FN_GetLoadedClasses g_fnOrigGetLoadedClasses = NULL;
static FN_GetClassSignature g_fnOrigGetClassSignature = NULL;
static FN_GetImplementedInterfaces g_fnOrigGetImplementedInterfaces = NULL;
static FN_IsModifiableClass g_fnOrigIsModifiableClass = NULL;
static FN_RetransformClasses g_fnOrigRetransformClasses = NULL;
static FN_RedefineClasses g_fnOrigRedefineClasses = NULL;
static FN_Allocate g_fnOrigAllocate = NULL;
static FN_Deallocate g_fnOrigDeallocate = NULL;
static FN_GetErrorName g_fnOrigGetErrorName = NULL;
static FN_DisposeEnvironment g_fnOrigDisposeEnvironment = NULL;
// 零-jvmti 通道（成员函数 NULL-this 直连——JvmtiEnv::RedefineClasses/RetransformClasses 不读 this，
// 源码实证；不经 env/函数表/包装器/入口，JVMTI 全废也照常工作）
static FN_RedefineClasses g_fnRedefineZero = NULL;
static FN_RetransformClasses g_fnRetransformZero = NULL;
static volatile LONG g_envBlasted = 0; // 打爆 JVMTI（Dispose env）——自废式模式

// 保存全部原指针（JNI_OnLoad 拿到 env 后立即调用——表打爆之前）
static void saveOriginalPointers(void) {
    const jvmtiInterface_1_ *t = g_jvmti->functions;
    g_fnAddCapabilities = (FN_AddCapabilities)t->AddCapabilities;
    g_fnSetEventCallbacks = (FN_SetEventCallbacks)t->SetEventCallbacks;
    g_fnSetEventNotificationMode = (FN_SetEventNotificationMode)t->SetEventNotificationMode;
    g_fnGetLoadedClasses = (FN_GetLoadedClasses)t->GetLoadedClasses;
    g_fnGetClassSignature = (FN_GetClassSignature)t->GetClassSignature;
    g_fnGetImplementedInterfaces = (FN_GetImplementedInterfaces)t->GetImplementedInterfaces;
    g_fnIsModifiableClass = (FN_IsModifiableClass)t->IsModifiableClass;
    g_fnRetransformClasses = (FN_RetransformClasses)t->RetransformClasses;
    g_fnRedefineClasses = (FN_RedefineClasses)t->RedefineClasses;
    g_fnAllocate = (FN_Allocate)t->Allocate;
    g_fnDeallocate = (FN_Deallocate)t->Deallocate;
    g_fnGetErrorName = (FN_GetErrorName)t->GetErrorName;
    g_fnDisposeEnvironment = (FN_DisposeEnvironment)t->DisposeEnvironment;
    g_fnOrigAddCapabilities = g_fnAddCapabilities;
    g_fnOrigSetEventCallbacks = g_fnSetEventCallbacks;
    g_fnOrigSetEventNotificationMode = g_fnSetEventNotificationMode;
    g_fnOrigGetLoadedClasses = g_fnGetLoadedClasses;
    g_fnOrigGetClassSignature = g_fnGetClassSignature;
    g_fnOrigGetImplementedInterfaces = g_fnGetImplementedInterfaces;
    g_fnOrigIsModifiableClass = g_fnIsModifiableClass;
    g_fnOrigRetransformClasses = g_fnRetransformClasses;
    g_fnOrigRedefineClasses = g_fnRedefineClasses;
    g_fnOrigAllocate = g_fnAllocate;
    g_fnOrigDeallocate = g_fnDeallocate;
    g_fnOrigGetErrorName = g_fnGetErrorName;
    g_fnOrigDisposeEnvironment = g_fnDisposeEnvironment;
}

// 前向声明（表打爆/封锁——定义在下方；armJvmtiGuard/disarm 先调用）
static void blastJvmtiTable(void);
static void blockGetCreatedJavaVMs(void);
// 前向声明（env 对象毒化/私有副本——定义在 nativeTool* 区；JNI_OnLoad/nativeBlastEnv 先调用）
static void snapshotPrivateEnv(void);
static void poisonRealEnv(void);
static jvmtiEnv *privateEnv(void);
static jvmtiInterface_1_ *realTable(void); // 真实表地址（blast 写入对象；毒化后仍可用）
static void restoreSlots(void);
static void blockGetEnv(void);
typedef jint (JNICALL *PFN_JNI_GetCreatedJavaVMs)(JavaVM **, jsize, jsize *);
static PFN_JNI_GetCreatedJavaVMs g_realGetCreatedJavaVMs = NULL; // 定义在此（C++ static 只能定义一次）
static jint JNICALL hookedGetCreatedJavaVMs(JavaVM **vmBuf, jsize bufLen, jsize *nVMs);

// GetEnv 封锁（blockGetEnv 定义在后；watchdog/disarm 前置引用）
typedef jint (JNICALL *PFN_JNI_GetEnv)(JavaVM *, void **, jint);
static PFN_JNI_GetEnv g_realGetEnv; // blockGetEnv 处赋值（后文不再重复声明）
static jint JNICALL hookedGetEnv(JavaVM *vm, void **envOut, jint version);

// 全局旗标（arm/disarm/watchdog 交叉引用——提前定义）
static volatile LONG g_guardArmed = 0;
static volatile LONG g_tableBlasted = 0; // 表打爆旗标（watchdog 校验 / 逃生通道清除）
// 无差别全表 seal 标志（KEY_HOOK_FULL_BLOCK——全部封死模式）：由 nativeSetFullSeal 设置。
// 默认 0 = 对称性封堵（保留我方通道槽——break/blast 用：外部全拒、我方 transform/事件链活）；
// 1 = 全表 155 槽无差别 reject（我方也不准用——预期，仅 fullBlock 模式）。
static volatile int g_fullSeal = 0;

// 前向声明（nativeSetFullSeal 调用——定义在表 blast 区）
static void reblastFullSeal(void);

// 新层前向声明（定义在下方"真·ZeroJvmti 反制"区）
static void extractMemberFunctions(void);
static void hookMemberFunctions(void);
static void redirectInternalPointers(void);
static void restoreInternalPointers(void);
static void hookJvmExportDefines(void);
static void hookJniEnvTable(void);
static void startGuardWatchdog(void);
static void rebuildWatchEntries(void);
static void watchAdd(const char *name, void *entry, PVOID *ppReal, PVOID hooked);
static void logThrottled(const char *fn, const void *caller);

// ===== 断链（JVMTI 函数表裁剪——2026-08-15） =====
// 原理：HotSpot jvmtiEnv 全局单例 + 所有 agent 共享同一张 jvmtiInterface_1_ 函数表。
// Detours 内联 hook 表项指向的 JVM 内部函数本体（不动函数表布局，兼容加固 JDK）：
// 我方 DLL 内调用放行（调用者模块检查），外部 agent 的关键能力
// （加能力/设回调/开关事件/改类/枚举/销毁环境）全部拒绝（返回 JVMTI_ERROR_NOT_AVAILABLE）。
// 先手优势：我方在 JNI_OnLoad 立即武装，外部 agent DLL later-loaded必然经过被 hook 的函数体。

static HMODULE g_selfModule = NULL;

// 调用者检查：caller 必须由 hooked 函数传入 _ReturnAddress()（在 isTrustedCaller 内部取会
// 拿到“hooked 调 isTrustedCaller 的地址”= 我们自己的 DLL → 永远放行，2026-08-15 实测）。
// 放行：① 我方 DLL（或同族 DLL 实例——SERVICE/GAME 各 System.load 一份 RyjsAgent.dll，模块名相同）；
// ② JDK 官方 instrument 库（libinstrument/JPLISAgent——我们的 premain 机制底层，
// getAllLoadedClasses/loadAgent0 内部调 jvmti API；不拦它则 java.lang.instrument 断言失败 → InternalError，
// 2026-08-15 生产实测）。CPV 无 premain 通道（加固 JDK 防 attach），放行 instrument 不影响断链。
// ③ JDK home 下的全部 native 库（awt.dll/java.dll/jvm.dll 等——GetEnv 封锁后 awt.dll 也调 GetEnv 拿
// JNI env，拒它则 AWT 崩溃 0xc0000005（2026-08-16 生产实测）；JDK 库是合法 JNI 使用，外部攻击 DLL
// 不可能出现在 JDK 目录）。
// 其余模块（外部 agent DLL）→ 拒绝。
static char g_jdkHome[MAX_PATH] = {0}; // JDK 根目录（...\jdk-xx\——detectJdkHome 登记，覆盖 bin+lib）

// 家族探针魔法值：同族 DLL（RyjsAgent 双实例 / taichi_hook）必须导出 RyjsAgentFamilyProbe 并
// 返回该值——对方"改名冒充"的 DLL 过不了这一关（2026-08-16 后门收窄：名字子串放行 → 探针认证）。
#define RYJS_FAMILY_MAGIC 0x52A95C0DEBEEF123LL
extern "C" __declspec(dllexport) jlong JNICALL RyjsAgentFamilyProbe(void) {
    return RYJS_FAMILY_MAGIC;
}

static BOOL isTrustedCaller(const void *caller) {
    HMODULE mod = NULL;
    if (!GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                                | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            (LPCSTR)caller, &mod)) {
        return FALSE;
    }
    if (mod == g_selfModule) return TRUE;
    char path[MAX_PATH] = {0};
    if (GetModuleFileNameA(mod, path, MAX_PATH) <= 0) return FALSE;
    for (char *q = path; *q; q++) *q = (char)tolower((unsigned char)*q);
    char name[MAX_PATH] = {0};
    if (GetModuleBaseNameA(GetCurrentProcess(), mod, name, MAX_PATH) <= 0) return FALSE;
    for (char *q = name; *q; q++) *q = (char)tolower((unsigned char)*q);

    // ① 同族模块：名字像 + 家族探针认证（改名冒充的第三方 DLL 无探针/魔法值不对 → 拒绝）
    if (strstr(name, "ryjsagent") != NULL || strstr(name, "taichi_hook") != NULL) {
        typedef jlong (JNICALL *PFN_Probe)(void);
        PFN_Probe probe = (PFN_Probe)GetProcAddress(mod, "RyjsAgentFamilyProbe");
        if (probe != NULL && probe() == RYJS_FAMILY_MAGIC) {
            return TRUE;
        }
        fprintf(stderr, "[RyjsAgent] REJECT impersonator module %s (%s) - no family probe\n", name, path);
        return FALSE;
    }
    // ② instrument（premain 机制底层——仅认 JDK lib 目录下的真 instrument.dll；打爆模式全拒）
    if (strstr(name, "instrument") != NULL) {
        if (g_jdkHome[0] != '\0' && _strnicmp(path, g_jdkHome, strlen(g_jdkHome)) == 0) {
            return !g_envBlasted;
        }
        return FALSE; // 非 JDK 目录的 "instrument" 模块 = 冒充
    }
    // ③ JDK 根目录下的合法 native 库（awt/java/jvm/fontmanager/jdwp 等）
    if (g_jdkHome[0] != '\0' && _strnicmp(path, g_jdkHome, strlen(g_jdkHome)) == 0) {
        return TRUE;
    }
    return FALSE;
}

// 检测 JDK 根目录（从 jvm.dll 路径推——...\jdk-xx\bin\server\jvm.dll → ...\jdk-xx\）
static void detectJdkHome(void) {
    HMODULE jvm = GetModuleHandleA("jvm.dll");
    if (jvm == NULL) return;
    char path[MAX_PATH] = {0};
    if (GetModuleFileNameA(jvm, path, MAX_PATH) <= 0) return;
    char *p = strstr(path, "\\bin\\");
    if (p != NULL) {
        p[1] = '\0'; // 截到 ...\jdk-xx\（含尾反斜杠——bin 下 awt/java.dll、lib 下 instrument/jdwp 全覆盖）
        for (char *q = path; *q; q++) *q = (char)tolower((unsigned char)*q);
        strncpy(g_jdkHome, path, MAX_PATH - 1);
        g_jdkHome[MAX_PATH - 1] = '\0';
    }
}

// 拦截日志节流（每个函数前 5 次打印——防侦察类高频调用刷屏；游戏内提示：确认断链在工作）
static void logBlock(const char *fn, const void *caller) {
    static volatile LONG counters[9] = {0};
    static const char *names[9] = {
        "AddCapabilities", "SetEventCallbacks", "SetEventNotificationMode",
        "RetransformClasses", "RedefineClasses", "DisposeEnvironment",
        "GetLoadedClasses", "GenerateEvents", "GetClassLoaderClasses",
    };
    for (int i = 0; i < 9; i++) {
        if (strcmp(fn, names[i]) == 0) {
            if (InterlockedIncrement((volatile LONG *)(counters + i)) <= 5) {
                fprintf(stderr, "[RyjsAgent] BLOCKED %s (caller=%p)\n", fn, caller);
            }
            return;
        }
    }
    fprintf(stderr, "[RyjsAgent] BLOCKED %s (caller=%p)\n", fn, caller);
}

// 新层拦截日志节流（每 100 次打印 1 次——导出 hook 的透传监视/JNIEnv 表/成员直调拦截面，防刷屏）
static void logThrottled(const char *fn, const void *caller) {
    static volatile LONG counter = 0;
    if ((InterlockedIncrement(&counter) % 100) != 1) return;
    fprintf(stderr, "[RyjsAgent] BLOCKED %s (caller=%p)\n", fn, caller);
}

// ---- 成员函数表（15 个我们内部使用/防守面需要的 JVMTI 成员——真·ZeroJvmti 反制核心） ----
// 注意：此声明必须在下方 9 个 hooked 包装器之前（它们的放行路径会引用 g_members[].attach）。
enum {
    M_ADD_CAPS = 0, M_SET_CB, M_SET_NOTIF, M_GET_LOADED, M_GET_SIG, M_GET_IFACES,
    M_IS_MOD, M_RETRANSFORM, M_REDEFINE, M_ALLOC, M_DEALLOC, M_GET_ERR,
    M_DISPOSE, M_GEN_EVENTS, M_GET_LOADER_CLASSES, M_COUNT
};

typedef jvmtiError (JNICALL *FN_Member)(void *, uintptr_t, uintptr_t, uintptr_t);

typedef struct {
    const char *name;
    void       *wrapper;  // 包装器入口（提取源；attach 前留档，永不直接 Detours）
    void       *member;   // 提取出的成员函数本体（watchdog 校验入口）
    PVOID       attach;   // Detours attach 后 = trampoline（重定位原始代码 = 我方直连通道）
    int         validated; // 提取结果是否经实证校验（仅对校验过的成员做 hook/直连）
} MemberTarget;

static MemberTarget g_members[M_COUNT] = {
    { "AddCapabilities",         NULL, NULL, NULL, 0 },
    { "SetEventCallbacks",       NULL, NULL, NULL, 0 },
    { "SetEventNotificationMode",NULL, NULL, NULL, 0 },
    { "GetLoadedClasses",        NULL, NULL, NULL, 0 },
    { "GetClassSignature",       NULL, NULL, NULL, 0 },
    { "GetImplementedInterfaces",NULL, NULL, NULL, 0 },
    { "IsModifiableClass",       NULL, NULL, NULL, 0 },
    { "RetransformClasses",      NULL, NULL, NULL, 0 },
    { "RedefineClasses",         NULL, NULL, NULL, 0 },
    { "Allocate",                NULL, NULL, NULL, 0 },
    { "Deallocate",              NULL, NULL, NULL, 0 },
    { "GetErrorName",            NULL, NULL, NULL, 0 },
    { "DisposeEnvironment",      NULL, NULL, NULL, 0 },
    { "GenerateEvents",          NULL, NULL, NULL, 0 },
    { "GetClassLoaderClasses",   NULL, NULL, NULL, 0 },
};
static volatile LONG g_membersHooked = 0;
static volatile LONG g_batchesAttached = 0;
static void *g_entryGetEnv = NULL;             // blockGetEnv 处赋值
static void *g_entryGetCreatedJavaVMs = NULL;  // blockGetCreatedJavaVMs 处赋值

// ---- 1. AddCapabilities（外部加能力） ----
typedef jvmtiError (JNICALL *PFN_jvmtiAddCapabilities)(jvmtiEnv *, const jvmtiCapabilities *);
static PFN_jvmtiAddCapabilities g_realAddCapabilities = NULL;
static jvmtiError JNICALL hookedAddCapabilities(jvmtiEnv *env, const jvmtiCapabilities *caps) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logBlock("AddCapabilities", caller);
        return JVMTI_ERROR_NOT_AVAILABLE;
    }
    if (g_members[M_ADD_CAPS].attach != NULL) {
        return ((PFN_jvmtiAddCapabilities)g_members[M_ADD_CAPS].attach)(env, caps);
    }
    return g_realAddCapabilities(env, caps);
}

// ---- 2. SetEventCallbacks（外部覆盖我方回调） ----
typedef jvmtiError (JNICALL *PFN_jvmtiSetEventCallbacks)(jvmtiEnv *, const jvmtiEventCallbacks *, jint);
static PFN_jvmtiSetEventCallbacks g_realSetEventCallbacks = NULL;
static jvmtiError JNICALL hookedSetEventCallbacks(jvmtiEnv *env, const jvmtiEventCallbacks *cb, jint size) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logBlock("SetEventCallbacks", caller);
        return JVMTI_ERROR_NOT_AVAILABLE;
    }
    if (g_members[M_SET_CB].attach != NULL) {
        return ((PFN_jvmtiSetEventCallbacks)g_members[M_SET_CB].attach)(env, cb, size);
    }
    return g_realSetEventCallbacks(env, cb, size);
}

// ---- 3. SetEventNotificationMode（外部开关事件） ----
typedef jvmtiError (JNICALL *PFN_jvmtiSetEventNotificationMode)(jvmtiEnv *, jvmtiEventMode, jvmtiEvent, jobject);
static PFN_jvmtiSetEventNotificationMode g_realSetEventNotificationMode = NULL;
static jvmtiError JNICALL hookedSetEventNotificationMode(jvmtiEnv *env, jvmtiEventMode mode, jvmtiEvent event, jobject data) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logBlock("SetEventNotificationMode", caller);
        return JVMTI_ERROR_NOT_AVAILABLE;
    }
    if (g_members[M_SET_NOTIF].attach != NULL) {
        return ((PFN_jvmtiSetEventNotificationMode)g_members[M_SET_NOTIF].attach)(env, mode, event, data);
    }
    return g_realSetEventNotificationMode(env, mode, event, data);
}

// ---- 4. RetransformClasses（外部改类） ----
typedef jvmtiError (JNICALL *PFN_jvmtiRetransformClasses)(jvmtiEnv *, jint, const jclass *);
static PFN_jvmtiRetransformClasses g_realRetransformClasses = NULL;
static jvmtiError JNICALL hookedRetransformClasses(jvmtiEnv *env, jint count, const jclass *classes) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logBlock("RetransformClasses", caller);
        return JVMTI_ERROR_NOT_AVAILABLE;
    }
    if (g_members[M_RETRANSFORM].attach != NULL) {
        return ((PFN_jvmtiRetransformClasses)g_members[M_RETRANSFORM].attach)(env, count, classes);
    }
    return g_realRetransformClasses(env, count, classes);
}

// ---- 5. RedefineClasses（外部改类） ----
typedef jvmtiError (JNICALL *PFN_jvmtiRedefineClasses)(jvmtiEnv *, jint, const jvmtiClassDefinition *);
static PFN_jvmtiRedefineClasses g_realRedefineClasses = NULL;
static jvmtiError JNICALL hookedRedefineClasses(jvmtiEnv *env, jint count, const jvmtiClassDefinition *defs) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logBlock("RedefineClasses", caller);
        return JVMTI_ERROR_NOT_AVAILABLE;
    }
    if (g_members[M_REDEFINE].attach != NULL) {
        return ((PFN_jvmtiRedefineClasses)g_members[M_REDEFINE].attach)(env, count, defs);
    }
    return g_realRedefineClasses(env, count, defs);
}

// ---- 6. DisposeEnvironment（外部销毁 env——单例销毁 = 我方一起死） ----
typedef jvmtiError (JNICALL *PFN_jvmtiDisposeEnvironment)(jvmtiEnv *);
static PFN_jvmtiDisposeEnvironment g_realDisposeEnvironment = NULL;
static jvmtiError JNICALL hookedDisposeEnvironment(jvmtiEnv *env) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logBlock("DisposeEnvironment", caller);
        return JVMTI_ERROR_NOT_AVAILABLE;
    }
    if (g_members[M_DISPOSE].attach != NULL) {
        return ((PFN_jvmtiDisposeEnvironment)g_members[M_DISPOSE].attach)(env);
    }
    return g_realDisposeEnvironment(env);
}

// ---- 7. GetLoadedClasses（外部枚举类） ----
typedef jvmtiError (JNICALL *PFN_jvmtiGetLoadedClasses)(jvmtiEnv *, jint *, jclass **);
static PFN_jvmtiGetLoadedClasses g_realGetLoadedClasses = NULL;
static jvmtiError JNICALL hookedGetLoadedClasses(jvmtiEnv *env, jint *count, jclass **classes) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logBlock("GetLoadedClasses", caller);
        return JVMTI_ERROR_NOT_AVAILABLE;
    }
    if (g_members[M_GET_LOADED].attach != NULL) {
        return ((PFN_jvmtiGetLoadedClasses)g_members[M_GET_LOADED].attach)(env, count, classes);
    }
    return g_realGetLoadedClasses(env, count, classes);
}

// ---- 8. GenerateEvents（外部生成事件/枚举） ----
typedef jvmtiError (JNICALL *PFN_jvmtiGenerateEvents)(jvmtiEnv *, jvmtiEvent);
static PFN_jvmtiGenerateEvents g_realGenerateEvents = NULL;
static jvmtiError JNICALL hookedGenerateEvents(jvmtiEnv *env, jvmtiEvent event) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logBlock("GenerateEvents", caller);
        return JVMTI_ERROR_NOT_AVAILABLE;
    }
    if (g_members[M_GEN_EVENTS].attach != NULL) {
        return ((PFN_jvmtiGenerateEvents)g_members[M_GEN_EVENTS].attach)(env, event);
    }
    return g_realGenerateEvents(env, event);
}

// ---- 9. GetClassLoaderClasses（外部枚举类加载器类） ----
typedef jvmtiError (JNICALL *PFN_jvmtiGetClassLoaderClasses)(jvmtiEnv *, jobject, jint *, jclass **);
static PFN_jvmtiGetClassLoaderClasses g_realGetClassLoaderClasses = NULL;
static jvmtiError JNICALL hookedGetClassLoaderClasses(jvmtiEnv *env, jobject loader, jint *count, jclass **classes) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logBlock("GetClassLoaderClasses", caller);
        return JVMTI_ERROR_NOT_AVAILABLE;
    }
    if (g_members[M_GET_LOADER_CLASSES].attach != NULL) {
        return ((PFN_jvmtiGetClassLoaderClasses)g_members[M_GET_LOADER_CLASSES].attach)(env, loader, count, classes);
    }
    return g_realGetClassLoaderClasses(env, loader, count, classes);
}

// ===================================================================================
// 真·ZeroJvmti 反制（2026-08-16）：成员函数 unwrap + JVM 导出 hook + JNIEnv 表 hook + watchdog
// ===================================================================================
// 背景：lib/ZeroJvmti 证明三条绕过本断链的路径——
//   1) 不走 JVMTI 的 ClassFileLoadHook：Detours hook JVM_DefineClassWithSource / JVM_LookupDefineClass
//      导出——我们的 cbClassFileLoadHook 看不见、表打爆管不着（缺口 1）。
//   2) env->GetJavaVM(&vm) 拿 JavaVM*（JNINativeInterface 函数序号 216 → 绝对槽位 4+216=220）——
//      绕过 JNI_GetCreatedJavaVMs 封锁（缺口 2）。
//   3) 函数表槽位是薄包装器，真正的 JvmtiEnvBase::xxx 成员函数在 jvm.dll 内部——从包装器反汇编
//      提取地址后 NULL-this 直调 → 表打爆/表项替换全部失效。
// 反制（本区新增）：
//   A. hook 三个 JVM 导出（DefineClass/DefineClassWithSource/LookupDefineClass）：untrusted **透传**
//      （拒绝会让链上方的对方 Mine 拿到 NULL → 所有类加载失败 = 游戏自爆；透传 + watchdog 弹射
//      可把对方 Detours 跳板从链上摘除——摘除后对方通道永久失效且类加载无感）。
//   B. hook JNIEnv 函数表 GetJavaVM/DefineClass 槽位指向的函数本体（jni_NativeInterface 全进程共享
//      一张表，hook 本体比写表更稳——对方换表也无效）。槽位号由 offsetof 实测：
//      GetJavaVM=219、DefineClass=5（jni.h）。DefineClass 是第四条定义通道——连 ZeroJvmti 自己都没
//      hook 它（JNI DefineClass 直走 resolve_from_stream；不过 KlassFactory 的 ClassFileLoadHook
//      仍会发给我们 cb，所以对方用它定义类我们看得见；hook 它是堵"绕过导出 hook"的剩余定义口）。
//   C. 成员函数 unwrap + hook 本体：先手提取 JvmtiEnvBase 成员函数并 Detours hook 本体
//      （untrusted 拒绝 = 对方 NULL-this 直调全废）。**严格校验集**：仅对经 ZeroJvmti 实证
//      （同 JDK 三个提取地址逐一吻合）的成员（GetLoadedClasses/RetransformClasses/RedefineClasses
//      ——恰好就是 ZeroJvmti_KillJvmti 的攻击对象）做 hook + trampoline 直连；其余成员是虚函数，
//      E8 扫描提取不可靠（会挑中包装器内部标签——hook 会踩坏活代码），继续走包装器路径。
//      直连通道 = Detours trampoline（重定位后的原始成员代码）——我方内部这 3 个 JVMTI 调用改走
//      trampoline，函数表/包装器入口/成员入口被谁改写都打不到我们的执行路径（KillJvmti 四层+watchdog 免疫）。
//   D. watchdog（first-loaded实例独占）：每 10ms 校验全部入口 16 字节 + 打爆表槽位，被改即
//      强制写回我方跳板字节（等效弹射：覆盖对方入口跳转，对方 trampoline 沦为孤儿；一次写入
//      必收敛——Detours detach/reattach 在对方踩过入口后会因线程挂起冲突 ERROR_INVALID_BLOCK 不收敛）。
//   E. blockGetEnv 提前到 JNI_OnLoad（原仅 nativeBlastEnv 时封锁——缺口 3 时序窗口OFF）。

// ---- 成员函数表（15 个我们内部使用/防守面需要的 JVMTI 成员） ----
// 声明已上移到 9 个 hooked 包装器之前（见 logThrottled 下方）。
// 成员 hook：untrusted 拒绝（对方 NULL-this 直调全废）；trusted 走 trampoline（原始成员代码）。
// x64 调用约定：this/self=RCX、参数 RDX/R8/R9——4 个通用参数槽覆盖全部 15 个成员签名
// （最多 4 参），多余寄存器原样透传、无害。
#define DEF_MEMBER_HOOK(i) \
static jvmtiError JNICALL hookedMember_##i(void *self, uintptr_t a, uintptr_t b, uintptr_t c) { \
    void *caller = _ReturnAddress(); \
    if (!isTrustedCaller(caller)) { \
        logThrottled("JVMTI member direct-call", caller); \
        return JVMTI_ERROR_NOT_AVAILABLE; \
    } \
    return ((FN_Member)g_members[i].attach)(self, a, b, c); \
}
DEF_MEMBER_HOOK(0) DEF_MEMBER_HOOK(1) DEF_MEMBER_HOOK(2) DEF_MEMBER_HOOK(3) DEF_MEMBER_HOOK(4)
DEF_MEMBER_HOOK(5) DEF_MEMBER_HOOK(6) DEF_MEMBER_HOOK(7) DEF_MEMBER_HOOK(8) DEF_MEMBER_HOOK(9)
DEF_MEMBER_HOOK(10) DEF_MEMBER_HOOK(11) DEF_MEMBER_HOOK(12) DEF_MEMBER_HOOK(13) DEF_MEMBER_HOOK(14)

static jvmtiError (JNICALL *g_memberHooks[M_COUNT])(void *, uintptr_t, uintptr_t, uintptr_t) = {
    hookedMember_0, hookedMember_1, hookedMember_2, hookedMember_3, hookedMember_4,
    hookedMember_5, hookedMember_6, hookedMember_7, hookedMember_8, hookedMember_9,
    hookedMember_10, hookedMember_11, hookedMember_12, hookedMember_13, hookedMember_14
};

// ---- PE 解析 jvm.dll .text（提取启发式：只在 .text 内的调用目标才算候选） ----
static BYTE  *g_jvmTextStart = NULL;
static SIZE_T g_jvmTextSize = 0;

static bool findJvmText(void) {
    if (g_jvmTextStart != NULL) return true;
    HMODULE jvm = GetModuleHandleA("jvm.dll");
    if (jvm == NULL) return false;
    BYTE *base = (BYTE *)jvm;
    PIMAGE_DOS_HEADER dos = (PIMAGE_DOS_HEADER)base;
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return false;
    PIMAGE_NT_HEADERS64 nt = (PIMAGE_NT_HEADERS64)(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return false;
    PIMAGE_SECTION_HEADER sec = IMAGE_FIRST_SECTION(nt);
    for (int i = 0; i < (int)nt->FileHeader.NumberOfSections; i++) {
        if (memcmp(sec[i].Name, ".text", 5) == 0) {
            g_jvmTextStart = base + sec[i].VirtualAddress;
            g_jvmTextSize = sec[i].Misc.VirtualSize;
            return true;
        }
    }
    return false;
}

static bool isInJvmText(const void *p) {
    BYTE *a = (BYTE *)p;
    return g_jvmTextStart != NULL && a >= g_jvmTextStart && a < g_jvmTextStart + g_jvmTextSize;
}

// ---- 原始 jvmtiInterface_1_ 表定位（防"对方先手假 env/假函数表"） ----
// 真表特征（jvmti.h 实测）：[0]=reserved1(NULL) + 之后 ≥121 个连续指向 .text 的函数指针。
// 对方先手若 hook GetEnv 返回伪造 env（假表全是自己 DLL 的 stub），我们扫 jvm.dll 数据段找回
// 真表 → 照样提取真实成员函数 → 零通道可用。若对方已把真表槽位原地改写（原值不可恢复），扫描
// 无候选——那属于签名库层面（未来）。只扫数据段，取首个候选。
static void *findOriginalJvmtiTable(void) {
    HMODULE jvm = GetModuleHandleA("jvm.dll");
    if (jvm == NULL) return NULL;
    if (!findJvmText()) return NULL;
    BYTE *base = (BYTE *)jvm;
    PIMAGE_DOS_HEADER dos = (PIMAGE_DOS_HEADER)base;
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return NULL;
    PIMAGE_NT_HEADERS64 nt = (PIMAGE_NT_HEADERS64)(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return NULL;
    PIMAGE_SECTION_HEADER sec = IMAGE_FIRST_SECTION(nt);
    for (int s = 0; s < (int)nt->FileHeader.NumberOfSections; s++) {
        DWORD ch = sec[s].Characteristics;
        if (!(ch & IMAGE_SCN_MEM_READ)) continue;
        if (ch & IMAGE_SCN_MEM_EXECUTE) continue; // 数据段才可能放表
        if (!(ch & IMAGE_SCN_MEM_WRITE)) continue; // 真表必须可写（vt_patch/我们打爆都要写它；.rdata 里的函数指针数组是假阳性）
        BYTE *start = base + sec[s].VirtualAddress;
        SIZE_T size = sec[s].Misc.VirtualSize;
        for (SIZE_T off = 0; off + 8 * 124 <= size; off += 8) {
            ULONG64 *p = (ULONG64 *)(start + off);
            if (p[0] != 0) continue; // reserved1 必须为 NULL
            int run = 1;
            for (int i = 1; i < 124; i++) {
                if (!isInJvmText((const void *)(uintptr_t)p[i])) break;
                run = i;
            }
            if (run >= 121) {
                return (void *)p;
            }
        }
    }
    return NULL;
}

// env 函数表真伪校验（放宽版）：抽查广泛槽位。对方 L1 式攻击只动 Redefine/Retransform 两个槽，
// 其余槽仍是真包装器 → 表仍可作提取源（被打槽的成员提取自然失败 → 优雅降级，绝不错 hook）。
// 只有大量抽查槽都非 .text（伪造 env 场景）才判定假表 → 走 .data 扫描兜底。
static bool tableLooksReal(const jvmtiInterface_1_ *t) {
    if (t == NULL) return false;
    int ok = 0;
    ok += isInJvmText((const void *)t->SetEventNotificationMode) ? 1 : 0;
    ok += isInJvmText((const void *)t->AddCapabilities) ? 1 : 0;
    ok += isInJvmText((const void *)t->GetLoadedClasses) ? 1 : 0;
    ok += isInJvmText((const void *)t->Allocate) ? 1 : 0;
    ok += isInJvmText((const void *)t->Deallocate) ? 1 : 0;
    ok += isInJvmText((const void *)t->GetClassSignature) ? 1 : 0;
    ok += isInJvmText((const void *)t->IsModifiableClass) ? 1 : 0;
    ok += isInJvmText((const void *)t->GetErrorName) ? 1 : 0;
    ok += isInJvmText((const void *)t->DisposeEnvironment) ? 1 : 0;
    ok += isInJvmText((const void *)t->GetClassLoaderClasses) ? 1 : 0;
    return ok >= 8; // 至少 8/10 抽查槽为真包装器 → 表可信
}

#define MAX_SCAN_CALLS 32
typedef struct {
    void *targets[MAX_SCAN_CALLS];
    int   count;
} CallScan;

// 反汇编收集包装器内的调用目标（E8/E9 rel32、FF15/FF25 间接——移植自 lib/ZeroJvmti collect_calls，
// 该启发式在同一 JDK 上经 ZeroJvmti 实证）。只收 jvm.dll .text 内的目标。
static void scanCalls(void *fn, CallScan *cs) {
    BYTE *code = (BYTE *)fn;
    cs->count = 0;
    int max = 512;
    // 防越界：扫描不得越过 .text 段尾（假表候选的"包装器"可能贴段尾）
    if (code >= g_jvmTextStart && code < g_jvmTextStart + g_jvmTextSize) {
        SIZE_T remain = (SIZE_T)(g_jvmTextStart + g_jvmTextSize - code);
        if (remain < (SIZE_T)max) max = (int)remain;
    }
    for (int i = 0; i < max && cs->count < MAX_SCAN_CALLS; i++) {
        void *target = NULL;
        if (code[i] == 0xE8 || code[i] == 0xE9) {
            int32_t rel = *(int32_t *)(code + i + 1);
            target = code + i + 5 + rel;
        } else if (code[i] == 0xFF && (code[i + 1] == 0x15 || code[i + 1] == 0x25)) {
            int32_t disp = *(int32_t *)(code + i + 2);
            target = *(void **)(code + i + 6 + disp);
        }
        if (target != NULL && isInJvmText(target)) {
            bool dup = false;
            for (int j = 0; j < cs->count; j++) {
                if (cs->targets[j] == target) { dup = true; break; }
            }
            if (!dup) { cs->targets[cs->count] = target; cs->count++; }
        }
        if (i > 52 && i < 509 && code[i] == 0xCC && code[i + 1] == 0xCC && code[i + 2] == 0xCC) break;
    }
}

// 成员 = 只在本包装器调用列表里出现的调用目标（公共 trace/校验辅助会出现在多个列表里）。
// 提取失败返回 NULL——宁可不 hook 也不赌错函数（ZeroJvmti 退回首个调用，我们更保守）。
static void *pickMember(int idx, CallScan *all, int n) {
    for (int i = 0; i < all[idx].count; i++) {
        void *t = all[idx].targets[i];
        int cnt = 0;
        for (int k = 0; k < n && cnt < 2; k++) {
            for (int j = 0; j < all[k].count; j++) {
                if (all[k].targets[j] == t) { cnt++; break; }
            }
        }
        if (cnt == 1) return t;
    }
    return NULL;
}

static void extractMemberFunctions(void) {
    if (g_members[0].member != NULL) return; // 已提取（本 JVM 生命周期内成员地址不变）
    CallScan scans[M_COUNT];
    if (!findJvmText()) {
        fprintf(stderr, "[RyjsAgent] member extraction failed: jvm.dll .text not found - falling back to wrapper path\n");
        return;
    }
    for (int i = 0; i < M_COUNT; i++) {
        if (g_members[i].wrapper != NULL) {
            scanCalls(g_members[i].wrapper, &scans[i]);
        }
    }
    for (int i = 0; i < M_COUNT; i++) {
        g_members[i].member = pickMember(i, scans, M_COUNT);
        // 严格校验集：仅对经 ZeroJvmti 实证（同一 JDK 上三个提取地址逐一吻合）的成员做 hook/直连。
        // 其余成员是虚函数（包装器内经 vtable 间接调用，E8 扫描看不到），启发式会挑中包装器内部
        // 标签/邻近辅助函数——hook 它们会踩坏活代码，直连调用会因栈帧错位 0xC0000005（2026-08-16 实测）。
        // 未校验成员继续走包装器路径（包装器内部自会正确虚分派），同样受 9 包装器 hook + 表打爆保护。
        g_members[i].validated = (i == M_GET_LOADED || i == M_RETRANSFORM || i == M_REDEFINE) ? 1 : 0;
        fprintf(stderr, "[RyjsAgent] member[%s] wrapper=%p calls=%d -> member=%p %s\n",
                g_members[i].name, g_members[i].wrapper, scans[i].count, g_members[i].member,
                g_members[i].validated ? "[VALIDATED]" : "[wrapper-path]");
    }
}

// 入口是否已被他人 Detours（对方先手者）：标准函数入口不会以跳转开头。
// 对成员/包装器的 kill 桩（不链式转发的 Detours 钩子）不能链式附加——Detours 会拿对方的跳转
// 字节当"原始代码"重建 trampoline，执行即跳进对方代码/错位（DevMode 先手模拟实测崩溃）。
// 跳过 = 优雅降级（该函数退回包装器/对方桩路径，安全返回错误码；其余能力不受影响）。
static bool entryAlreadyHooked(void *entry) {
    BYTE b = *(BYTE *)entry;
    return b == 0xE9 || b == 0xFF;
}

static void hookMemberFunctions(void) {
    if (g_membersHooked) return;
    int hooked = 0;
    int skipped = 0;
    LONG err = NO_ERROR;
    DetourTransactionBegin();
    DetourUpdateThread(GetCurrentThread());
    for (int i = 0; i < M_COUNT; i++) {
        if (g_members[i].member == NULL || !g_members[i].validated) continue; // 未校验不 hook（防踩活代码）
        if (entryAlreadyHooked(g_members[i].member)) {
            fprintf(stderr, "[RyjsAgent] SKIP member hook %s: entry already detoured (adversary first) - graceful degrade\n",
                    g_members[i].name);
            skipped++;
            continue; // 对方先手装过跳板 → 不链式附加（trampoline 会以对方字节重建 = 崩溃/错位）
        }
        g_members[i].attach = g_members[i].member;
        if (err == NO_ERROR) err = DetourAttach(&g_members[i].attach, (PVOID)g_memberHooks[i]);
        if (err == NO_ERROR) hooked++;
    }
    if (hooked > 0 && err == NO_ERROR && DetourTransactionCommit() == NO_ERROR) {
        InterlockedExchange(&g_membersHooked, 1);
        fprintf(stderr, "[RyjsAgent] member functions hooked: %d (validated set, skipped=%d) - attach=trampoline direct channel\n",
                hooked, skipped);
    } else if (skipped > 0 && hooked == 0) {
        DetourTransactionAbort();
        fprintf(stderr, "[RyjsAgent] member hooks all skipped (adversary first-mover on entries) - graceful degrade\n");
    } else {
        DetourTransactionAbort();
        for (int i = 0; i < M_COUNT; i++) { g_members[i].attach = NULL; }
        fprintf(stderr, "[RyjsAgent] member hook failed (err=%ld) - internal calls use wrappers\n", err);
    }
}

// 内部指针重定向：g_fnX 全部改走成员 trampoline（原始代码重定位副本 + jmp 回成员+5）——
// 之后函数表/包装器入口/成员入口被谁改写都打不到我们。
static void redirectInternalPointers(void) {
    if (g_members[M_ADD_CAPS].attach != NULL)     g_fnAddCapabilities = (FN_AddCapabilities)g_members[M_ADD_CAPS].attach;
    if (g_members[M_SET_CB].attach != NULL)       g_fnSetEventCallbacks = (FN_SetEventCallbacks)g_members[M_SET_CB].attach;
    if (g_members[M_SET_NOTIF].attach != NULL)    g_fnSetEventNotificationMode = (FN_SetEventNotificationMode)g_members[M_SET_NOTIF].attach;
    if (g_members[M_GET_LOADED].attach != NULL)   g_fnGetLoadedClasses = (FN_GetLoadedClasses)g_members[M_GET_LOADED].attach;
    if (g_members[M_GET_SIG].attach != NULL)      g_fnGetClassSignature = (FN_GetClassSignature)g_members[M_GET_SIG].attach;
    if (g_members[M_GET_IFACES].attach != NULL)   g_fnGetImplementedInterfaces = (FN_GetImplementedInterfaces)g_members[M_GET_IFACES].attach;
    if (g_members[M_IS_MOD].attach != NULL)       g_fnIsModifiableClass = (FN_IsModifiableClass)g_members[M_IS_MOD].attach;
    if (g_members[M_RETRANSFORM].attach != NULL)  { g_fnRetransformClasses = (FN_RetransformClasses)g_members[M_RETRANSFORM].attach; g_fnRetransformZero = g_fnRetransformClasses; }
    if (g_members[M_REDEFINE].attach != NULL)     { g_fnRedefineClasses = (FN_RedefineClasses)g_members[M_REDEFINE].attach; g_fnRedefineZero = g_fnRedefineClasses; }
    if (g_members[M_ALLOC].attach != NULL)        g_fnAllocate = (FN_Allocate)g_members[M_ALLOC].attach;
    if (g_members[M_DEALLOC].attach != NULL)      g_fnDeallocate = (FN_Deallocate)g_members[M_DEALLOC].attach;
    if (g_members[M_GET_ERR].attach != NULL)      g_fnGetErrorName = (FN_GetErrorName)g_members[M_GET_ERR].attach;
    if (g_members[M_DISPOSE].attach != NULL)      g_fnDisposeEnvironment = (FN_DisposeEnvironment)g_members[M_DISPOSE].attach;
}

// disarm 用：内部指针退回包装器原始入口
static void restoreInternalPointers(void) {
    g_fnAddCapabilities = g_fnOrigAddCapabilities;
    g_fnSetEventCallbacks = g_fnOrigSetEventCallbacks;
    g_fnSetEventNotificationMode = g_fnOrigSetEventNotificationMode;
    g_fnGetLoadedClasses = g_fnOrigGetLoadedClasses;
    g_fnGetClassSignature = g_fnOrigGetClassSignature;
    g_fnGetImplementedInterfaces = g_fnOrigGetImplementedInterfaces;
    g_fnIsModifiableClass = g_fnOrigIsModifiableClass;
    g_fnRetransformClasses = g_fnOrigRetransformClasses;
    g_fnRedefineClasses = g_fnOrigRedefineClasses;
    g_fnAllocate = g_fnOrigAllocate;
    g_fnDeallocate = g_fnOrigDeallocate;
    g_fnGetErrorName = g_fnOrigGetErrorName;
    g_fnDisposeEnvironment = g_fnOrigDisposeEnvironment;
    g_fnRedefineZero = NULL;
    g_fnRetransformZero = NULL;
}

// ---- 零-JVMTI transform 通道（导出 hook 跑 dispatchTransform——AsmHook 去 JVMTI 化实验） ----
// 主通道 = JVMTI ClassFileLoadHook（cb）；对方杀我们 cb（Dispose env/关事件）后，由导出 hook 接管：
// java.dll 的 ClassLoader.defineClass* 必经 JVM_DefineClassWithSource/LookupDefineClass/DefineClass，
// 我们在 real 调用前跑 g_dispatchMethod 拿变换字节（malloc 副本，调后 free——同 ZeroJvmti apply_hook）。
// 我方 nativeDefineClass（caller=我方 DLL）跳过 → 保留逃生语义；系统类过滤与 cb 完全一致。
static volatile LONG g_exportTransform = 0;
static volatile LONG g_exportObserve = 0;   // 类加载观察通道（导出 hook 实时推送 name+Class 给 Java）
static jmethodID g_notifyMethod = NULL;     // classLoaded(String, Class) —— nativeSetBridgeClass 时解析

// caller 是否来自我方 DLL 本体（区别于 isTrustedCaller：同族 RyjsAgent/taichi_hook 实例也算 trusted，
// 但逃生通道只认"调用者就是本 DLL"——nativeDefineClass 的调用者）
static BOOL isSelfCaller(const void *caller) {
    HMODULE mod = NULL;
    if (!GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                                | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            (LPCSTR)caller, &mod)) {
        return FALSE;
    }
    return mod == g_selfModule;
}

// 系统类/库类过滤（transform 与观察通道共用——与 cbClassFileLoadHook 的过滤一致）
static int isSystemClassName(const char *name) {
    if (name == NULL) return 1;
    if (strncmp(name, "java/", 5) == 0 || strncmp(name, "jdk/", 4) == 0
            || strncmp(name, "sun/", 4) == 0 || strncmp(name, "javax/", 6) == 0
            || strncmp(name, "com/sun/", 8) == 0 || strncmp(name, "org/objectweb/", 14) == 0) {
        return 1;
    }
    return 0;
}

static int shouldExportTransform(const char *name) {
    if (!g_exportTransform) return 0;
    if (g_bridgeClass == NULL || g_dispatchMethod == NULL) return 0;
    if (isSystemClassName(name)) return 0;
    return 1;
}

// 类加载观察：define 成功后把 (internalName, jclass) 推给 Java（classLoaded 回调）。
// 完全零 env——替代 GetLoadedClasses 枚举/probe 轮询的"等类加载就绪"。
static void notifyClassLoaded(JNIEnv *env, const char *name, jclass cls) {
    if (!g_exportObserve || g_notifyMethod == NULL || name == NULL || cls == NULL) return;
    if (isSystemClassName(name)) return;
    jstring jname = env->NewStringUTF(name);
    if (jname == NULL) return;
    env->CallStaticVoidMethod(g_bridgeClass, g_notifyMethod, jname, (jobject)cls);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jname);
}

// 调 Java 分发器拿变换后字节；成功返回 malloc 副本（调用方 free），未变换返回 NULL。
// 异常语义与 cb 相同：分发器异常 → 透传原始字节。
static unsigned char *exportTransformBytes(JNIEnv *env, jobject loader, const char *name,
                                           const jbyte *buf, jsize len, jint *outLen) {
    *outLen = 0;
    if (buf == NULL || len <= 0) return NULL;
    jbyteArray data = env->NewByteArray(len);
    if (data == NULL) return NULL;
    env->SetByteArrayRegion(data, 0, len, (const jbyte *)buf);
    jstring jname = env->NewStringUTF(name);
    jobject result = env->CallStaticObjectMethod(g_bridgeClass, g_dispatchMethod, loader, jname, data);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(data);
        if (jname != NULL) env->DeleteLocalRef(jname);
        return NULL;
    }
    env->DeleteLocalRef(data);
    if (jname != NULL) env->DeleteLocalRef(jname);
    if (result == NULL) return NULL; // 未变换
    jbyteArray out = (jbyteArray)result;
    jsize olen = env->GetArrayLength(out);
    if (olen <= 0) {
        env->DeleteLocalRef(result);
        return NULL;
    }
    jbyte *ob = env->GetByteArrayElements(out, NULL);
    if (ob == NULL) {
        env->DeleteLocalRef(result);
        return NULL;
    }
    unsigned char *copy = (unsigned char *)malloc((size_t)olen);
    if (copy != NULL) {
        memcpy(copy, ob, (size_t)olen);
        *outLen = olen;
    }
    env->ReleaseByteArrayElements(out, ob, JNI_ABORT);
    env->DeleteLocalRef(result);
    return copy;
}

// ---- 缺口 1：JVM 导出 hook（ZeroJvmti 的"不走 JVMTI 的 ClassFileLoadHook"通道） ----
typedef jclass (JNICALL *PFN_JVM_DefineClass)(JNIEnv *, const char *, jobject, const jbyte *, jsize, jobject);
typedef jclass (JNICALL *PFN_JVM_DefineClassWithSource)(JNIEnv *, const char *, jobject, const jbyte *, jsize, jobject, const char *);
typedef jclass (JNICALL *PFN_JVM_LookupDefineClass)(JNIEnv *, jclass, const char *, const jbyte *, jsize, jobject, jboolean, jint, jobject);
static PFN_JVM_DefineClass g_realJvmDefineClass = NULL;
static PFN_JVM_DefineClassWithSource g_realJvmDefineClassWithSource = NULL;
static PFN_JVM_LookupDefineClass g_realJvmLookupDefineClass = NULL;
static void *g_entryJvmDefineClass = NULL;
static void *g_entryJvmDefineClassWithSource = NULL;
static void *g_entryJvmLookupDefineClass = NULL;

// untrusted **透传**（+监视日志）：拒绝会让链上方的对方 Mine 拿到 NULL → 所有类加载失败 =
// 游戏自爆；透传保证类加载无感，随后 watchdog 的 detach/reattach 会把对方跳板从链上摘除。
static jclass JNICALL hookedJvmDefineClass(JNIEnv *env, const char *name, jobject loader,
                                           const jbyte *buf, jsize len, jobject pd) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logThrottled("JVM_DefineClass", caller);
    }
    unsigned char *mod = NULL; jint modLen = 0;
    const jbyte *finalBuf = buf; jsize finalLen = len;
    if (!isSelfCaller(caller) && shouldExportTransform(name)) {
        mod = exportTransformBytes(env, loader, name, buf, len, &modLen);
        if (mod != NULL) { finalBuf = (const jbyte *)mod; finalLen = modLen; }
    }
    jclass r = g_realJvmDefineClass(env, name, loader, finalBuf, finalLen, pd);
    if (mod != NULL) free(mod);
    if (r != NULL) notifyClassLoaded(env, name, r);
    return r;
}

static jclass JNICALL hookedJvmDefineClassWithSource(JNIEnv *env, const char *name, jobject loader,
                                                     const jbyte *buf, jsize len, jobject pd, const char *src) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logThrottled("JVM_DefineClassWithSource", caller);
    }
    // 零-JVMTI transform 通道：cb（JVMTI ClassFileLoadHook）死掉后由导出 hook 接管 dispatchTransform。
    // 我方自己的 nativeDefineClass（caller=我方 DLL）跳过——保留"绕开 transform 链"的逃生语义。
    unsigned char *mod = NULL; jint modLen = 0;
    const jbyte *finalBuf = buf; jsize finalLen = len;
    if (!isSelfCaller(caller) && shouldExportTransform(name)) {
        mod = exportTransformBytes(env, loader, name, buf, len, &modLen);
        if (mod != NULL) { finalBuf = (const jbyte *)mod; finalLen = modLen; }
    }
    jclass r = g_realJvmDefineClassWithSource(env, name, loader, finalBuf, finalLen, pd, src);
    if (mod != NULL) free(mod);
    if (r != NULL) notifyClassLoaded(env, name, r);
    return r;
}

static jclass JNICALL hookedJvmLookupDefineClass(JNIEnv *env, jclass lookup, const char *name,
                                                 const jbyte *buf, jsize len, jobject pd,
                                                 jboolean init, jint flags, jobject classData) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logThrottled("JVM_LookupDefineClass", caller);
    }
    unsigned char *mod = NULL; jint modLen = 0;
    const jbyte *finalBuf = buf; jsize finalLen = len;
    if (!isSelfCaller(caller) && shouldExportTransform(name)) {
        mod = exportTransformBytes(env, NULL, name, buf, len, &modLen); // 隐藏类：loader 传 NULL
        if (mod != NULL) { finalBuf = (const jbyte *)mod; finalLen = modLen; }
    }
    jclass r = g_realJvmLookupDefineClass(env, lookup, name, finalBuf, finalLen, pd, init, flags, classData);
    if (mod != NULL) free(mod);
    if (r != NULL) notifyClassLoaded(env, name, r);
    return r;
}

static void hookJvmExportDefines(void) {
    if (g_realJvmDefineClassWithSource != NULL) return; // 已 hook
    HMODULE jvmDll = GetModuleHandleA("jvm.dll");
    if (jvmDll == NULL) {
        fprintf(stderr, "[RyjsAgent] jvm.dll not found - JVM exports not hooked\n");
        return;
    }
    g_realJvmDefineClass = (PFN_JVM_DefineClass)GetProcAddress(jvmDll, "JVM_DefineClass");
    g_realJvmDefineClassWithSource = (PFN_JVM_DefineClassWithSource)GetProcAddress(jvmDll, "JVM_DefineClassWithSource");
    g_realJvmLookupDefineClass = (PFN_JVM_LookupDefineClass)GetProcAddress(jvmDll, "JVM_LookupDefineClass");
    if (g_realJvmDefineClassWithSource == NULL) {
        fprintf(stderr, "[RyjsAgent] JVM_DefineClassWithSource export not found - not hooked\n");
        return;
    }
    // 先手检测：入口已是 E9 跳转 = 有人比我们先 hook（mod first-loaded等）——我们仍附加在最上层
    if (((BYTE *)g_realJvmDefineClassWithSource)[0] == 0xE9) {
        fprintf(stderr, "[RyjsAgent] WARNING: JVM_DefineClassWithSource already hooked by others (first-mover lost) - attaching on top\n");
    }
    g_entryJvmDefineClass = (void *)g_realJvmDefineClass;
    g_entryJvmDefineClassWithSource = (void *)g_realJvmDefineClassWithSource;
    g_entryJvmLookupDefineClass = (void *)g_realJvmLookupDefineClass;
    LONG err = NO_ERROR;
    DetourTransactionBegin();
    DetourUpdateThread(GetCurrentThread());
    if (g_realJvmDefineClass != NULL)
        err = DetourAttach(&(PVOID &)g_realJvmDefineClass, (PVOID)hookedJvmDefineClass);
    if (err == NO_ERROR)
        err = DetourAttach(&(PVOID &)g_realJvmDefineClassWithSource, (PVOID)hookedJvmDefineClassWithSource);
    if (err == NO_ERROR && g_realJvmLookupDefineClass != NULL)
        err = DetourAttach(&(PVOID &)g_realJvmLookupDefineClass, (PVOID)hookedJvmLookupDefineClass);
    if (err == NO_ERROR && DetourTransactionCommit() == NO_ERROR) {
        fprintf(stderr, "[RyjsAgent] JVM exports hooked: DefineClass/DefineClassWithSource/LookupDefineClass (untrusted passthrough+watch, watchdog ejection)\n");
    } else {
        DetourTransactionAbort();
        fprintf(stderr, "[RyjsAgent] JVM export hook failed (err=%ld)\n", err);
        g_realJvmDefineClass = NULL;
        g_realJvmDefineClassWithSource = NULL;
        g_realJvmLookupDefineClass = NULL;
    }
}

// ---- 缺口 2：JNIEnv 函数表 hook（GetJavaVM + DefineClass 槽位函数本体） ----
// jni_NativeInterface 全进程共享一张表（jni.cpp: struct JNINativeInterface_ jni_NativeInterface），
// hook 槽位指向的函数本体（而非写表）→ 对方换表/写表都无效。-Xcheck:jni 时该槽指向 checked
// 包装器（checked_jni_GetJavaVM），hook 它同样覆盖全进程。
typedef jint (JNICALL *PFN_JNI_GetJavaVM)(JNIEnv *, JavaVM **);
typedef jclass (JNICALL *PFN_JNI_DefineClass)(JNIEnv *, const char *, jobject, const jbyte *, jsize);
static PFN_JNI_GetJavaVM g_realJniGetJavaVM = NULL;
static PFN_JNI_DefineClass g_realJniDefineClass = NULL;
static void *g_entryJniGetJavaVM = NULL;
static void *g_entryJniDefineClass = NULL;

#define JNI_TABLE_SLOT_DEFINECLASS 5    // offsetof(struct JNINativeInterface_, DefineClass)/8（offsetof 实测）
#define JNI_TABLE_SLOT_GETJAVAVM 219    // offsetof(struct JNINativeInterface_, GetJavaVM)/8（offsetof 实测）

static jint JNICALL hookedJniGetJavaVM(JNIEnv *env, JavaVM **vmOut) {
    (void)env;
    // 2026-08-16 实测回归：JNA（oshi 的 SystemReport、Minecraft Narrator 等合法库）依赖 GetJavaVM——
    // 拒绝会打坏它们（"JNA: Could not get JavaVM" 连锁报错）。安全模型不变：GetJavaVM 只给 JavaVM*，
    // 真正的 JVMTI 咽喉是 vm->GetEnv(JVMTI_VERSION)——仍被 GetEnv 封锁；JNIEnv 敏感能力（DefineClass 等）
    // 也各有 caller check。故此处对所有人透传（链条在下一环封死，威胁等价）。
    return g_realJniGetJavaVM(env, vmOut);
}

static jclass JNICALL hookedJniDefineClass(JNIEnv *env, const char *name, jobject loader,
                                           const jbyte *buf, jsize len) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        logThrottled("JNIEnv::DefineClass", caller);
        return NULL;
    }
    return g_realJniDefineClass(env, name, loader, buf, len);
}

static void hookJniEnvTable(void) {
    if (g_realJniGetJavaVM != NULL) return; // 已 hook
    if (g_jvm == NULL) return;
    JNIEnv *env = NULL;
    if (g_jvm->GetEnv((void **)&env, JNI_VERSION_1_8) != JNI_OK || env == NULL) {
        fprintf(stderr, "[RyjsAgent] JNIEnv acquire failed - JNIEnv table not hooked\n");
        return;
    }
    void **table = *(void ***)env;
    if (table == NULL) {
        fprintf(stderr, "[RyjsAgent] JNIEnv function table unavailable - not hooked\n");
        return;
    }
    g_realJniGetJavaVM = (PFN_JNI_GetJavaVM)(uintptr_t)table[JNI_TABLE_SLOT_GETJAVAVM];
    g_realJniDefineClass = (PFN_JNI_DefineClass)(uintptr_t)table[JNI_TABLE_SLOT_DEFINECLASS];
    g_entryJniGetJavaVM = (void *)g_realJniGetJavaVM;
    g_entryJniDefineClass = (void *)g_realJniDefineClass;
    if (g_realJniGetJavaVM == NULL || g_realJniDefineClass == NULL) {
        fprintf(stderr, "[RyjsAgent] JNIEnv table slot read failed - not hooked\n");
        return;
    }
    LONG err = NO_ERROR;
    DetourTransactionBegin();
    DetourUpdateThread(GetCurrentThread());
    err = DetourAttach(&(PVOID &)g_realJniGetJavaVM, (PVOID)hookedJniGetJavaVM);
    if (err == NO_ERROR) err = DetourAttach(&(PVOID &)g_realJniDefineClass, (PVOID)hookedJniDefineClass);
    if (err == NO_ERROR && DetourTransactionCommit() == NO_ERROR) {
        fprintf(stderr, "[RyjsAgent] JNIEnv table hooked: GetJavaVM/DefineClass (untrusted denied)\n");
    } else {
        DetourTransactionAbort();
        fprintf(stderr, "[RyjsAgent] JNIEnv table hook failed (err=%ld)\n", err);
        g_realJniGetJavaVM = NULL;
        g_realJniDefineClass = NULL;
    }
}

// ---- watchdog（双实例安全：仅"first-loaded实例"执行校验与表打爆；另一实例只装 inline hook） ----
static volatile LONG g_amFirstInstance = -1; // -1 未判 / 0 非first-loaded / 1 first-loaded

static BOOL isFirstLoadedInstance(void) {
    if (g_amFirstInstance >= 0) return g_amFirstInstance == 1;
    BOOL first = FALSE;
    char selfName[MAX_PATH] = {0};
    if (GetModuleBaseNameA(GetCurrentProcess(), g_selfModule, selfName, MAX_PATH) > 0) {
        HMODULE mods[512];
        DWORD needed = 0;
        if (EnumProcessModules(GetCurrentProcess(), mods, sizeof(mods), &needed)) {
            int n = (int)(needed / sizeof(HMODULE));
            if (n > 512) n = 512;
            for (int i = 0; i < n; i++) {
                char name[MAX_PATH] = {0};
                if (GetModuleBaseNameA(GetCurrentProcess(), mods[i], name, MAX_PATH) > 0
                        && _stricmp(name, selfName) == 0) {
                    first = (mods[i] == g_selfModule); // 模块列表序 = 加载序，第一个同名 = first-loaded
                    break;
                }
            }
        }
    }
    InterlockedExchange(&g_amFirstInstance, first ? 1 : 0);
    fprintf(stderr, "[RyjsAgent] %s instance (watchdog/table-blast rights)\n", first ? "first-loaded" : "later-loaded");
    return first;
}

#define MAX_WATCH 80
typedef struct {
    const char *name;
    void  *entry;   // 被 hook 的入口地址（校验 16 字节）
    PVOID *ppReal;  // Detours 重写的指针变量（detach/attach 用）
    PVOID  hooked;  // 我们的 hook 函数
    BYTE   snap[16];
    DWORD  lastLogTick; // 该入口上次打日志的时刻（写战限频：每入口最多 30s 一条）
} WatchEntry;
static WatchEntry g_watch[MAX_WATCH];
static int g_watchCount = 0;
static volatile LONG g_watchRepairs = 0;

static void watchAdd(const char *name, void *entry, PVOID *ppReal, PVOID hooked) {
    if (g_watchCount >= MAX_WATCH || entry == NULL) return;
    WatchEntry *w = &g_watch[g_watchCount++];
    w->name = name;
    w->entry = entry;
    w->ppReal = ppReal;
    w->hooked = hooked;
    memcpy(w->snap, entry, 16);
}

// 强制写回（VirtualProtect + memcpy——ZeroJvmti watchdog 同款手法）：写入我们保存的跳板字节。
// 比 detach/reattach 更稳：Detours 事务在对方踩过入口后常因线程挂起冲突返回 ERROR_INVALID_BLOCK(9)
// 而无法收敛（2026-08-16 实测）；直接写回一次到位：我方跳板覆盖对方入口，对方 trampoline 沦为
// 孤儿（无人跳入），等效"弹射"且必收敛。
static void forceWriteBytes(void *addr, const void *bytes, SIZE_T n) {
    DWORD old = 0;
    if (VirtualProtect(addr, n, PAGE_EXECUTE_READWRITE, &old)) {
        memcpy(addr, bytes, n);
        DWORD dummy;
        VirtualProtect(addr, n, old, &dummy);
    }
}

static volatile LONG g_warWarned = 0;
static void watchVerify(void) {
    for (int i = 0; i < g_watchCount; i++) {
        WatchEntry *w = &g_watch[i];
        if (memcmp(w->entry, w->snap, 16) == 0) continue;
        forceWriteBytes(w->entry, w->snap, 16); // 修复照常执行（功能第一）——只限日志
        InterlockedIncrement(&g_watchRepairs);
        DWORD now = GetTickCount();
        if (now - w->lastLogTick >= 30000) { // 写战限频：同一入口最多 30s 一条
            w->lastLogTick = now;
            fprintf(stderr, "[RyjsAgent] watchdog: entry rewritten %s (%p) - restored\n", w->name, w->entry);
            if (g_watchRepairs > 50 && InterlockedExchange(&g_warWarned, 1) == 0) {
                fprintf(stderr, "[RyjsAgent] WARNING: write-war with an external patcher detected "
                        "(our channels bypass it - functionality unaffected)\n");
            }
        }
    }
}

static void watchSlots(void); // 定义在表打爆区（依赖 g_origSlot 声明）

static HANDLE g_watchThread = NULL;
static DWORD WINAPI guardWatchdogThread(LPVOID param) {
    (void)param;
    DWORD lastPrint = GetTickCount();
    LONG lastRepairs = 0;
    for (;;) {
        Sleep(10);
        if (!g_guardArmed) continue;
        watchVerify();
        watchSlots();
        DWORD now = GetTickCount();
        if (now - lastPrint >= 10000) { // 汇总限频：10s 一条
            LONG r = g_watchRepairs;
            if (r != lastRepairs) {
                fprintf(stderr, "[RyjsAgent] watchdog: total repairs=%ld\n", r);
                lastRepairs = r;
            }
            lastPrint = now;
        }
    }
}

static void startGuardWatchdog(void) {
    if (g_watchThread != NULL) return;
    g_watchThread = CreateThread(NULL, 0, guardWatchdogThread, NULL, 0, NULL);
}

// 全部已装 hook 的 watchdog 登记（arm/disarm 周期后重建——watchCount==0 时调用）
static void rebuildWatchEntries(void) {
    if (g_watchCount != 0) return;
    if (g_batchesAttached) {
        watchAdd("AddCapabilities", g_members[M_ADD_CAPS].wrapper, &(PVOID &)g_realAddCapabilities, (PVOID)hookedAddCapabilities);
        watchAdd("SetEventCallbacks", g_members[M_SET_CB].wrapper, &(PVOID &)g_realSetEventCallbacks, (PVOID)hookedSetEventCallbacks);
        watchAdd("SetEventNotificationMode", g_members[M_SET_NOTIF].wrapper, &(PVOID &)g_realSetEventNotificationMode, (PVOID)hookedSetEventNotificationMode);
        watchAdd("RetransformClasses", g_members[M_RETRANSFORM].wrapper, &(PVOID &)g_realRetransformClasses, (PVOID)hookedRetransformClasses);
        watchAdd("RedefineClasses", g_members[M_REDEFINE].wrapper, &(PVOID &)g_realRedefineClasses, (PVOID)hookedRedefineClasses);
        watchAdd("DisposeEnvironment", g_members[M_DISPOSE].wrapper, &(PVOID &)g_realDisposeEnvironment, (PVOID)hookedDisposeEnvironment);
        watchAdd("GetLoadedClasses", g_members[M_GET_LOADED].wrapper, &(PVOID &)g_realGetLoadedClasses, (PVOID)hookedGetLoadedClasses);
        watchAdd("GenerateEvents", g_members[M_GEN_EVENTS].wrapper, &(PVOID &)g_realGenerateEvents, (PVOID)hookedGenerateEvents);
        watchAdd("GetClassLoaderClasses", g_members[M_GET_LOADER_CLASSES].wrapper, &(PVOID &)g_realGetClassLoaderClasses, (PVOID)hookedGetClassLoaderClasses);
    }
    if (g_membersHooked) {
        for (int i = 0; i < M_COUNT; i++) {
            // 只登记已挂成员的入口（attach==NULL 表示未挂——其地址未经校验，绝不 watch/改写）
            if (g_members[i].attach != NULL && g_members[i].attach != g_members[i].member) {
                watchAdd(g_members[i].name, g_members[i].member, &g_members[i].attach, (PVOID)g_memberHooks[i]);
                // trampoline（我方直连通道代码）自护：对方可直接改写我们 DLL 里的重定位副本
                watchAdd("trampoline", g_members[i].attach, NULL, NULL);
            }
        }
    }
    if (g_realJvmDefineClass != NULL)
        watchAdd("JVM_DefineClass", g_entryJvmDefineClass, &(PVOID &)g_realJvmDefineClass, (PVOID)hookedJvmDefineClass);
    if (g_realJvmDefineClassWithSource != NULL)
        watchAdd("JVM_DefineClassWithSource", g_entryJvmDefineClassWithSource, &(PVOID &)g_realJvmDefineClassWithSource, (PVOID)hookedJvmDefineClassWithSource);
    if (g_realJvmLookupDefineClass != NULL)
        watchAdd("JVM_LookupDefineClass", g_entryJvmLookupDefineClass, &(PVOID &)g_realJvmLookupDefineClass, (PVOID)hookedJvmLookupDefineClass);
    if (g_realJniGetJavaVM != NULL)
        watchAdd("JNIEnv::GetJavaVM", g_entryJniGetJavaVM, &(PVOID &)g_realJniGetJavaVM, (PVOID)hookedJniGetJavaVM);
    if (g_realJniDefineClass != NULL)
        watchAdd("JNIEnv::DefineClass", g_entryJniDefineClass, &(PVOID &)g_realJniDefineClass, (PVOID)hookedJniDefineClass);
    if (g_realGetEnv != NULL && g_entryGetEnv != NULL)
        watchAdd("GetEnv", g_entryGetEnv, &(PVOID &)g_realGetEnv, (PVOID)hookedGetEnv);
    if (g_realGetCreatedJavaVMs != NULL && g_entryGetCreatedJavaVMs != NULL)
        watchAdd("JNI_GetCreatedJavaVMs", g_entryGetCreatedJavaVMs, &(PVOID &)g_realGetCreatedJavaVMs, (PVOID)hookedGetCreatedJavaVMs);
    // ===== 我方 hooked 函数本体自护（对方可把 Detours 跳板/force_write 装到我们 DLL 内的 hook 入口上——
    // ZeroJvmti_KillJvmti L4 在"槽位原值=我方 hooked"时就会把 Kill 跳板装到我方函数入口，直接杀死我方 hook）。
    if (g_batchesAttached) {
        watchAdd("self:hookedAddCapabilities", (void *)hookedAddCapabilities, NULL, NULL);
        watchAdd("self:hookedSetEventCallbacks", (void *)hookedSetEventCallbacks, NULL, NULL);
        watchAdd("self:hookedSetEventNotificationMode", (void *)hookedSetEventNotificationMode, NULL, NULL);
        watchAdd("self:hookedRetransformClasses", (void *)hookedRetransformClasses, NULL, NULL);
        watchAdd("self:hookedRedefineClasses", (void *)hookedRedefineClasses, NULL, NULL);
        watchAdd("self:hookedDisposeEnvironment", (void *)hookedDisposeEnvironment, NULL, NULL);
        watchAdd("self:hookedGetLoadedClasses", (void *)hookedGetLoadedClasses, NULL, NULL);
        watchAdd("self:hookedGenerateEvents", (void *)hookedGenerateEvents, NULL, NULL);
        watchAdd("self:hookedGetClassLoaderClasses", (void *)hookedGetClassLoaderClasses, NULL, NULL);
    }
    if (g_membersHooked) {
        for (int i = 0; i < M_COUNT; i++) {
            if (g_members[i].attach != NULL && g_members[i].attach != g_members[i].member) {
                watchAdd("self:memberHook", (void *)g_memberHooks[i], NULL, NULL);
            }
        }
    }
    if (g_realJvmDefineClass != NULL)
        watchAdd("self:hookedJvmDefineClass", (void *)hookedJvmDefineClass, NULL, NULL);
    if (g_realJvmDefineClassWithSource != NULL)
        watchAdd("self:hookedJvmDefineClassWithSource", (void *)hookedJvmDefineClassWithSource, NULL, NULL);
    if (g_realJvmLookupDefineClass != NULL)
        watchAdd("self:hookedJvmLookupDefineClass", (void *)hookedJvmLookupDefineClass, NULL, NULL);
    if (g_realJniGetJavaVM != NULL)
        watchAdd("self:hookedJniGetJavaVM", (void *)hookedJniGetJavaVM, NULL, NULL);
    if (g_realJniDefineClass != NULL)
        watchAdd("self:hookedJniDefineClass", (void *)hookedJniDefineClass, NULL, NULL);
    if (g_realGetEnv != NULL)
        watchAdd("self:hookedGetEnv", (void *)hookedGetEnv, NULL, NULL);
    if (g_realGetCreatedJavaVMs != NULL)
        watchAdd("self:hookedGetCreatedJavaVMs", (void *)hookedGetCreatedJavaVMs, NULL, NULL);
}

// 武装断链：Detours 分两批 hook（核心 3 个必须成功；其余 6 个尽力——
// Detours 事务要么全成要么全败，拆两个事务让核心失败不影响整体回滚）。
// （g_guardArmed 已在文件前部定义——arm/disarm/watchdog 交叉引用）
static int g_hookedCount = 0;

static void armJvmtiGuard(void) {
    if (InterlockedExchange(&g_guardArmed, 1)) return;
    if (g_jvmti == NULL || g_jvmti->functions == NULL) {
        fprintf(stderr, "[RyjsAgent] guard not armed: jvmti unavailable\n");
        return;
    }
    // 真表校验前置：.text 定位（isInJvmText 依赖——必须先于 tableLooksReal 调用）
    findJvmText();
    const jvmtiInterface_1_ *t = g_jvmti->functions;

    // 真表校验（防对方先手假 env/假函数表）：表不可信 → **不再 .data 找回**（对称性封堵
    // 2026-08-17：DevMode 后手演练实测——对方 FULL SEAL 后 .data 扫描找回的"候选表"是
    // jvm.dll 数据段无关指针数组，提取到的成员是假地址（0x7FFCF3A6xxxx vs 真成员
    // 0x7FFCF3B6xxxx），调用即崩。且找回=绕过通道：对方 seal 后我们必须提取=0 才算封住。
    // 降级：保持 t 原值（已 seal 的表）→ 表项是 reject stub（无内部 call）→ 扫描提取自然
    // 失败（member=0）→ 查询类返回 null/false——这正是对称性验收（我方后手一个都拿不到），
    // 防御层（表 seal/导出 hook/GetEnv 封锁）照常武装。
    if (!tableLooksReal(t)) {
        fprintf(stderr, "[RyjsAgent] WARNING: env function table abnormal (sealed/fake) - member extraction disabled "
                        "(symmetric seal: we extract nothing as second-mover), defense layers still armed\n");
    }

    // 0. 包装器原始地址留档（DetourAttach 会把 g_realX 改写为 trampoline；成员提取必须先于任何 attach）
    g_members[M_ADD_CAPS].wrapper = (void *)t->AddCapabilities;
    g_members[M_SET_CB].wrapper = (void *)t->SetEventCallbacks;
    g_members[M_SET_NOTIF].wrapper = (void *)t->SetEventNotificationMode;
    g_members[M_GET_LOADED].wrapper = (void *)t->GetLoadedClasses;
    g_members[M_GET_SIG].wrapper = (void *)t->GetClassSignature;
    g_members[M_GET_IFACES].wrapper = (void *)t->GetImplementedInterfaces;
    g_members[M_IS_MOD].wrapper = (void *)t->IsModifiableClass;
    g_members[M_RETRANSFORM].wrapper = (void *)t->RetransformClasses;
    g_members[M_REDEFINE].wrapper = (void *)t->RedefineClasses;
    g_members[M_ALLOC].wrapper = (void *)t->Allocate;
    g_members[M_DEALLOC].wrapper = (void *)t->Deallocate;
    g_members[M_GET_ERR].wrapper = (void *)t->GetErrorName;
    g_members[M_DISPOSE].wrapper = (void *)t->DisposeEnvironment;
    g_members[M_GEN_EVENTS].wrapper = (void *)t->GenerateEvents;
    g_members[M_GET_LOADER_CLASSES].wrapper = (void *)t->GetClassLoaderClasses;

    // 0b. 成员函数 unwrap + 本体 hook（真·ZeroJvmti 反制核心——先于包装器 hook，
    //     保证提取源是 jvm.dll 原始包装器代码）
    extractMemberFunctions();
    hookMemberFunctions();
    redirectInternalPointers();

    // 批 1：核心三件套（攻击方执行攻击序列的必经之路）
    if (!g_batchesAttached) {
        g_realAddCapabilities = (PFN_jvmtiAddCapabilities)t->AddCapabilities;
        g_realSetEventCallbacks = (PFN_jvmtiSetEventCallbacks)t->SetEventCallbacks;
        g_realSetEventNotificationMode = (PFN_jvmtiSetEventNotificationMode)t->SetEventNotificationMode;
        // 对方先手在包装器入口装过跳板（KillJvmti L4 式）→ 跳过链式附加（见 entryAlreadyHooked 注释）
        int att0 = !entryAlreadyHooked((void *)t->AddCapabilities);
        int att1 = !entryAlreadyHooked((void *)t->SetEventCallbacks);
        int att2 = !entryAlreadyHooked((void *)t->SetEventNotificationMode);
        if (att0 == 0) fprintf(stderr, "[RyjsAgent] SKIP wrapper hook AddCapabilities: entry already detoured\n");
        if (att1 == 0) fprintf(stderr, "[RyjsAgent] SKIP wrapper hook SetEventCallbacks: entry already detoured\n");
        if (att2 == 0) fprintf(stderr, "[RyjsAgent] SKIP wrapper hook SetEventNotificationMode: entry already detoured\n");
        int attached = 0;
        LONG err = NO_ERROR;
        if (att0 + att1 + att2 > 0) {
            DetourTransactionBegin();
            DetourUpdateThread(GetCurrentThread());
            if (att0) { err = DetourAttach(&(PVOID &)g_realAddCapabilities, (PVOID)hookedAddCapabilities); if (err == NO_ERROR) attached++; }
            if (err == NO_ERROR && att1) { err = DetourAttach(&(PVOID &)g_realSetEventCallbacks, (PVOID)hookedSetEventCallbacks); if (err == NO_ERROR) attached++; }
            if (err == NO_ERROR && att2) { err = DetourAttach(&(PVOID &)g_realSetEventNotificationMode, (PVOID)hookedSetEventNotificationMode); if (err == NO_ERROR) attached++; }
        }
        if (attached > 0 && err == NO_ERROR && DetourTransactionCommit() == NO_ERROR) {
            g_hookedCount = attached;
        } else if (attached == 0 && err == NO_ERROR) {
            // 全部被对方先手占位——核心批空转，继续后续武装（表打爆/导出/JNI 表仍生效）
            fprintf(stderr, "[RyjsAgent] core batch: all entries pre-detoured by adversary - continuing with remaining layers\n");
        } else {
            DetourTransactionAbort();
            fprintf(stderr, "[RyjsAgent] core batch failed (err=%ld) - rolled back, not armed\n", err);
            InterlockedExchange(&g_guardArmed, 0);
            return;
        }
    }

    // 批 2：防御性裁剪（改类/枚举/销毁——尽力而为，失败不影响核心）
    if (!g_batchesAttached) {
        g_realRetransformClasses = (PFN_jvmtiRetransformClasses)t->RetransformClasses;
        g_realRedefineClasses = (PFN_jvmtiRedefineClasses)t->RedefineClasses;
        g_realDisposeEnvironment = (PFN_jvmtiDisposeEnvironment)t->DisposeEnvironment;
        g_realGetLoadedClasses = (PFN_jvmtiGetLoadedClasses)t->GetLoadedClasses;
        g_realGenerateEvents = (PFN_jvmtiGenerateEvents)t->GenerateEvents;
        g_realGetClassLoaderClasses = (PFN_jvmtiGetClassLoaderClasses)t->GetClassLoaderClasses;
        int b0 = !entryAlreadyHooked((void *)t->RetransformClasses);
        int b1 = !entryAlreadyHooked((void *)t->RedefineClasses);
        int b2 = !entryAlreadyHooked((void *)t->DisposeEnvironment);
        int b3 = !entryAlreadyHooked((void *)t->GetLoadedClasses);
        int b4 = !entryAlreadyHooked((void *)t->GenerateEvents);
        int b5 = !entryAlreadyHooked((void *)t->GetClassLoaderClasses);
        if (!b0) fprintf(stderr, "[RyjsAgent] SKIP wrapper hook RetransformClasses: entry already detoured\n");
        if (!b1) fprintf(stderr, "[RyjsAgent] SKIP wrapper hook RedefineClasses: entry already detoured\n");
        LONG err2 = NO_ERROR;
        int attached2 = 0;
        if (b0 + b1 + b2 + b3 + b4 + b5 > 0) {
            DetourTransactionBegin();
            DetourUpdateThread(GetCurrentThread());
            if (b0) { err2 = DetourAttach(&(PVOID &)g_realRetransformClasses, (PVOID)hookedRetransformClasses); if (err2 == NO_ERROR) attached2++; }
            if (err2 == NO_ERROR && b1) { err2 = DetourAttach(&(PVOID &)g_realRedefineClasses, (PVOID)hookedRedefineClasses); if (err2 == NO_ERROR) attached2++; }
            if (err2 == NO_ERROR && b2) { err2 = DetourAttach(&(PVOID &)g_realDisposeEnvironment, (PVOID)hookedDisposeEnvironment); if (err2 == NO_ERROR) attached2++; }
            if (err2 == NO_ERROR && b3) { err2 = DetourAttach(&(PVOID &)g_realGetLoadedClasses, (PVOID)hookedGetLoadedClasses); if (err2 == NO_ERROR) attached2++; }
            if (err2 == NO_ERROR && b4) { err2 = DetourAttach(&(PVOID &)g_realGenerateEvents, (PVOID)hookedGenerateEvents); if (err2 == NO_ERROR) attached2++; }
            if (err2 == NO_ERROR && b5) { err2 = DetourAttach(&(PVOID &)g_realGetClassLoaderClasses, (PVOID)hookedGetClassLoaderClasses); if (err2 == NO_ERROR) attached2++; }
        }
        if (attached2 > 0 && err2 == NO_ERROR && DetourTransactionCommit() == NO_ERROR) {
            g_hookedCount += attached2;
            InterlockedExchange(&g_batchesAttached, 1);
        } else {
            if (err2 != NO_ERROR) DetourTransactionAbort();
            fprintf(stderr, "[RyjsAgent] defense batch partially failed/skipped (err=%ld) - core armed, defense trim skipped\n", err2);
        }
    }

    // 新层：JVM 导出 hook（缺口 1）+ JNIEnv 表 hook（缺口 2）+ GetEnv 封锁（缺口 3——提前到 JNI_OnLoad）
    hookJvmExportDefines();
    hookJniEnvTable();
    blockGetEnv();
    // 表打爆（vt_patch 式——外部走表全废；我方内部调用走成员 trampoline 不受影响）——
    // 仅first-loaded实例执行（双实例共享同一张表，later-loaded实例再写会引发 watchdog 互打乒乓）
    if (isFirstLoadedInstance()) {
        if (!g_tableBlasted) {
            blastJvmtiTable();
        }
    } else {
        fprintf(stderr, "[RyjsAgent] later-loaded instance: skip table blast (first instance owns it)\n");
    }
    // 堵 JNI_GetCreatedJavaVMs（外部拿 VM 的入口）
    blockGetCreatedJavaVMs();
    // watchdog：入口完整性弹射 + 表槽位守护
    startGuardWatchdog();
    rebuildWatchEntries();
    fprintf(stderr, "[RyjsAgent] guard armed: hooked=%d members=%d watch=%d first=%d zero=%d\n",
            g_hookedCount, (int)g_membersHooked, g_watchCount, (int)g_amFirstInstance,
            (g_fnRedefineZero != NULL && g_fnRetransformZero != NULL) ? 1 : 0);
}

// 解除断链（Java 配置 nativeSetBreak(false)——DetourDetach 全部恢复原函数 + 表恢复；再武装幂等）
static void disarmJvmtiGuard(void) {
    if (!g_guardArmed) return;
    // 先停 watchdog（g_guardArmed=0 后其校验循环空转）并摘表打爆旗标——防并发 detach/attach 互踩
    InterlockedExchange(&g_guardArmed, 0);
    g_tableBlasted = 0;
    // 内部指针退回包装器（成员 trampoline 即将失效）
    restoreInternalPointers();
    LONG err = NO_ERROR;
    DetourTransactionBegin();
    DetourUpdateThread(GetCurrentThread());
    // 成员本体
    for (int i = 0; i < M_COUNT; i++) {
        if (g_members[i].attach != NULL && g_members[i].attach != g_members[i].member) {
            if (err == NO_ERROR) err = DetourDetach(&g_members[i].attach, (PVOID)g_memberHooks[i]);
        }
    }
    // JVM 导出（缺口 1）
    if (err == NO_ERROR && g_realJvmLookupDefineClass != NULL)
        err = DetourDetach(&(PVOID &)g_realJvmLookupDefineClass, (PVOID)hookedJvmLookupDefineClass);
    if (err == NO_ERROR && g_realJvmDefineClassWithSource != NULL)
        err = DetourDetach(&(PVOID &)g_realJvmDefineClassWithSource, (PVOID)hookedJvmDefineClassWithSource);
    if (err == NO_ERROR && g_realJvmDefineClass != NULL)
        err = DetourDetach(&(PVOID &)g_realJvmDefineClass, (PVOID)hookedJvmDefineClass);
    // JNIEnv 表（缺口 2）
    if (err == NO_ERROR && g_realJniDefineClass != NULL)
        err = DetourDetach(&(PVOID &)g_realJniDefineClass, (PVOID)hookedJniDefineClass);
    if (err == NO_ERROR && g_realJniGetJavaVM != NULL)
        err = DetourDetach(&(PVOID &)g_realJniGetJavaVM, (PVOID)hookedJniGetJavaVM);
    // GetEnv（缺口 3）
    if (err == NO_ERROR && g_realGetEnv != NULL)
        err = DetourDetach(&(PVOID &)g_realGetEnv, (PVOID)hookedGetEnv);
    // JNI_GetCreatedJavaVMs
    if (err == NO_ERROR && g_realGetCreatedJavaVMs != NULL)
        err = DetourDetach(&(PVOID &)g_realGetCreatedJavaVMs, (PVOID)hookedGetCreatedJavaVMs);
    // 批 2 + 批 1（原 9 个包装器）
    if (err == NO_ERROR) err = DetourDetach(&(PVOID &)g_realGetClassLoaderClasses, (PVOID)hookedGetClassLoaderClasses);
    if (err == NO_ERROR) err = DetourDetach(&(PVOID &)g_realGenerateEvents, (PVOID)hookedGenerateEvents);
    if (err == NO_ERROR) err = DetourDetach(&(PVOID &)g_realGetLoadedClasses, (PVOID)hookedGetLoadedClasses);
    if (err == NO_ERROR) err = DetourDetach(&(PVOID &)g_realDisposeEnvironment, (PVOID)hookedDisposeEnvironment);
    if (err == NO_ERROR) err = DetourDetach(&(PVOID &)g_realRedefineClasses, (PVOID)hookedRedefineClasses);
    if (err == NO_ERROR) err = DetourDetach(&(PVOID &)g_realRetransformClasses, (PVOID)hookedRetransformClasses);
    if (err == NO_ERROR) err = DetourDetach(&(PVOID &)g_realSetEventNotificationMode, (PVOID)hookedSetEventNotificationMode);
    if (err == NO_ERROR) err = DetourDetach(&(PVOID &)g_realSetEventCallbacks, (PVOID)hookedSetEventCallbacks);
    if (err == NO_ERROR) err = DetourDetach(&(PVOID &)g_realAddCapabilities, (PVOID)hookedAddCapabilities);
    if (err == NO_ERROR && DetourTransactionCommit() == NO_ERROR) {
        for (int i = 0; i < M_COUNT; i++) {
            g_members[i].attach = NULL;
            g_members[i].member = NULL; // 允许重新提取（表已恢复原始包装器）
        }
        g_membersHooked = 0;
        g_batchesAttached = 0;
        g_hookedCount = 0;
        g_watchCount = 0;
        g_realGetEnv = NULL;
        g_realGetCreatedJavaVMs = NULL;
        g_realJvmDefineClass = NULL;
        g_realJvmDefineClassWithSource = NULL;
        g_realJvmLookupDefineClass = NULL;
        g_realJniGetJavaVM = NULL;
        g_realJniDefineClass = NULL;
        restoreSlots();
        fprintf(stderr, "[RyjsAgent] guard disarmed (members/JVM exports/JNIEnv table/GetEnv)\n");
    } else {
        DetourTransactionAbort();
        fprintf(stderr, "[RyjsAgent] disarm failed (err=%ld) - staying armed\n", err);
        InterlockedExchange(&g_guardArmed, 1);
    }
}

// 敏感 native 调用令牌（后门收窄——2026-08-16）：nativeSetBreak/nativeBlastEnv/nativeRestoreEnv
// 是"解除武装/打爆/逃生"的高危导出——对方可 RegisterNatives 借道调用自杀我方。
// Java 侧必须携带正确令牌才生效（对方不知道令牌 → 调用无效）。
#define RYJS_NATIVE_TOKEN 0x52A9C0DE5EC0DE11LL

// 断链开关（Java 配置控制）：true=武装（幂等——JNI_OnLoad 已默认武装），false=解除
JNIEXPORT void JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeSetBreak(JNIEnv *env, jclass clazz, jboolean on, jlong token) {
    (void)env;
    (void)clazz;
    if (token != RYJS_NATIVE_TOKEN) {
        fprintf(stderr, "[RyjsAgent] REJECT illegal nativeSetBreak call (bad token)\n");
        return;
    }
    if (on) {
        if (!g_guardArmed) {
            armJvmtiGuard();
        }
    } else {
        disarmJvmtiGuard();
    }
}

// 打爆 JVMTI（不 Dispose——2026-08-16 重设计）：Dispose 会杀掉我们自己挂在 env 上的
// ClassFileLoadHook 回调（AsmHook/transform 链/类还原全死——自废 95%）。杀敌只需：
// 表打爆（外部走表全废）+ GetEnv 封锁（外部拿不到 env）+ JNI_GetCreatedJavaVMs 封锁
// （拿 VM 入口）+ instrument 全拒（attach 加载失败）。env 保留——回调/transform 链活，
// 我们全功能保留；外部对 env 拿不到（三通道封死）、用不了（表打爆）、看不见（回调仅我方注册）。
JNIEXPORT void JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeBlastEnv(JNIEnv *env, jclass clazz, jboolean on, jlong token) {
    (void)env;
    (void)clazz;
    if (token != RYJS_NATIVE_TOKEN) {
        fprintf(stderr, "[RyjsAgent] REJECT illegal nativeBlastEnv call (bad token)\n");
        return;
    }
    if (!on || g_envBlasted) return;
    // 1. 封锁 GetEnv（JNIInvokeInterface [6]——外部 agent 拿不到 env）
    blockGetEnv();
    // 2. 表打爆 + 封锁（外部走表全废；我方走原指针不受影响）
    if (!g_guardArmed) {
        armJvmtiGuard();
    }
    g_envBlasted = 1; // instrument 全拒（attach 的 agent 加载失败——外部借道无门）
    // 3. 毒化真实 env 对象本体（对称性封堵：对手内存扫描到的对象不可用；我方走私有副本续命）
    poisonRealEnv();
    fprintf(stderr, "[RyjsAgent] JVMTI blast (table blast + dual block + instrument deny + env object poisoned - private env %s)\n",
            privateEnv() != NULL ? "ready" : "NOT ready");
}

// 无差别全表 seal 开关（KEY_HOOK_FULL_BLOCK——全部封死模式）：true=全表 155 槽无差别 reject
// （我方也不准用——预期，仅 fullBlock）；false=对称性封堵（保留我方通道槽，break/blast 用）。
// 必须在 armJvmtiGuard（表 blast）之前设置——JvmtiBridge 静态块顺序：nativeSetFullSeal → nativeSetBreak → nativeBlastEnv。
JNIEXPORT void JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeSetFullSeal(JNIEnv *env, jclass clazz, jboolean on, jlong token) {
    (void)env;
    (void)clazz;
    if (token != RYJS_NATIVE_TOKEN) {
        fprintf(stderr, "[RyjsAgent] REJECT illegal nativeSetFullSeal call (bad token)\n");
        return;
    }
    int was = g_fullSeal;
    g_fullSeal = on ? 1 : 0;
    fprintf(stderr, "[RyjsAgent] full-seal mode: %s\n", g_fullSeal ? "UNCONDITIONAL (all slots, our channels sealed too)" : "symmetric (our channels preserved)");
    // JNI_OnLoad 已用对称性封堵 blast 过（g_fullSeal=0）→ 切换到全表 seal 需 re-blast
    // （把之前保留的 allow 槽也写成 reject）。re-blast 实现放在表 blast 区（reblastFullSeal，
    // 依赖 saveSlot/writeSlot 定义——定义在下方）。
    if (g_fullSeal && !was && g_tableBlasted) {
        reblastFullSeal();
    }
}

// ---- 封锁 GetEnv（JNIInvokeInterface [6]——外部 agent 拿不到 env；instrument 放行——loadAgent0 照常） ----
// （PFN_JNI_GetEnv / g_realGetEnv / hookedGetEnv 已在文件前部声明——watchdog/disarm 前置引用）
static jint JNICALL hookedGetEnv(JavaVM *vm, void **envOut, jint version) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        // 外部拿不到 env（靶子 JNI_OnLoad/nativeInit 的 GetEnv 全失败 → 中止初始化 → 无心跳无 UI）
        if (envOut != NULL) *envOut = NULL;
        return JNI_EVERSION;
    }
    return g_realGetEnv(vm, envOut, version);
}

static void blockGetEnv(void) {
    if (g_jvm == NULL || g_realGetEnv != NULL) return;
    ULONG64 *invoke = (ULONG64 *)(uintptr_t)(*(ULONG64 *)g_jvm); // JNIInvokeInterface 指针
    if (invoke == NULL) {
        fprintf(stderr, "[RyjsAgent] GetEnv block failed (JNIInvokeInterface unavailable)\n");
        return;
    }
    // JDK 17 JNIInvokeInterface：0-2 reserved, 3 DestroyJavaVM, 4 AttachCurrentThread, 5 DetachCurrentThread, 6 GetEnv
    g_realGetEnv = (PFN_JNI_GetEnv)(uintptr_t)invoke[6];
    g_entryGetEnv = (void *)g_realGetEnv;
    LONG err = NO_ERROR;
    DetourTransactionBegin();
    err = DetourUpdateThread(GetCurrentThread());
    if (err == NO_ERROR) err = DetourAttach(&(PVOID &)g_realGetEnv, (PVOID)hookedGetEnv);
    if (err == NO_ERROR && DetourTransactionCommit() == NO_ERROR) {
        fprintf(stderr, "[RyjsAgent] GetEnv blocked (from JNI_OnLoad - timing window closed)\n");
    } else {
        DetourTransactionAbort();
        fprintf(stderr, "[RyjsAgent] GetEnv block failed (err=%ld)\n", err);
        g_realGetEnv = NULL;
        g_entryGetEnv = NULL;
    }
}

// 逃生通道（打爆可逆——仅我方可触发）：恢复函数表 + 重新获取 env → jvmti 完整复活。
// 外部无法利用：① JNI 符号绑定我方类（模块隔离——外部调不到）② GetEnv 仍对外部封锁
// ③ 原表项（g_origSlot）仅我方持有 ④ 外部旧 env 指针已 disposed（复活后的新 env 与之无关）。
JNIEXPORT void JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeRestoreEnv(JNIEnv *env, jclass clazz, jlong token) {
    (void)env;
    (void)clazz;
    if (token != RYJS_NATIVE_TOKEN) {
        fprintf(stderr, "[RyjsAgent] REJECT illegal nativeRestoreEnv call (bad token)\n");
        return;
    }
    if (!g_envBlasted) return;
    // 1. 恢复函数表（原表项写回）——先摘表打爆旗标（watchdog 的槽位校验立刻停手）
    g_tableBlasted = 0;
    restoreSlots();
    // 2. 重新 GetEnv（走 hookedGetEnv——我方调用放行）
    jvmtiEnv *jvmti = NULL;
    jint result = g_jvm->GetEnv((void **)&jvmti, JVMTI_VERSION_1_2);
    if (result != JNI_OK || jvmti == NULL) {
        fprintf(stderr, "[RyjsAgent] escape hatch: GetEnv failed (%d) - staying blasted\n", result);
        return;
    }
    g_jvmti = jvmti;
    // 3. 重新保存原指针（新 env 的表）
    saveOriginalPointers();
    g_envBlasted = 0;
    g_guardArmed = 0; // 允许重新武装（如需要）
    fprintf(stderr, "[RyjsAgent] escape hatch: jvmti revived (env=%p) - external GetEnv still blocked\n", jvmti);
}

// ===== 表打爆（2026-08-16：vt_patch 式——直接写 jvmtiInterface_1_ 表项） =====
// 我们内部调用全部走原指针（saveOriginalPointers——不走表），表被改对我们零影响；
// 外部 agent 走表 → 表项是我们的 trampoline（调用者检查）或通用拒绝（NOT_AVAILABLE）。

// 写表项（VirtualProtect 改页 + 写 + 恢复——vt_patch 实证可行）
static void writeSlot(ULONG64 *slot, void *fn) {
    DWORD old = 0;
    if (VirtualProtect(slot, sizeof(ULONG64), PAGE_READWRITE, &old)) {
        *slot = (ULONG64)(uintptr_t)fn;
        DWORD dummy;
        VirtualProtect(slot, sizeof(ULONG64), old, &dummy);
    }
}

// 原表项保存（disarm 恢复用）
#define MAX_PATCH_SLOTS 160
static ULONG64 g_origSlot[MAX_PATCH_SLOTS];
static int g_origSlotIdx[MAX_PATCH_SLOTS];
static int g_origSlotCount = 0;
// 打爆后表槽位的期望值（watchdog 槽位校验用）——与 g_origSlotIdx 索引同步
static ULONG64 g_expectedSlot[MAX_PATCH_SLOTS];
// （g_tableBlasted 已在文件前部定义——arm/disarm/nativeRestoreEnv/watchSlots 交叉引用）

static void saveSlot(ULONG64 *slot) {
    if (g_origSlotCount < MAX_PATCH_SLOTS) {
        g_origSlotIdx[g_origSlotCount] = (int)(((ULONG64)slot - (ULONG64)g_jvmti->functions) / 8);
        g_origSlot[g_origSlotCount] = *slot;
        g_origSlotCount++;
    }
}

// 记录刚刚 writeSlot 写入的期望值（必须在 saveSlot 之后、下一个 saveSlot 之前调用）
static void markExpected(void *fn) {
    if (g_origSlotCount > 0 && g_origSlotCount <= MAX_PATCH_SLOTS) {
        g_expectedSlot[g_origSlotCount - 1] = (ULONG64)(uintptr_t)fn;
    }
}

static void restoreSlots(void) {
    jvmtiInterface_1_ *t = realTable();
    if (t == NULL) return;
    ULONG64 *base = (ULONG64 *)t;
    for (int i = 0; i < g_origSlotCount; i++) {
        writeSlot(&base[g_origSlotIdx[i]], (void *)(uintptr_t)g_origSlot[i]);
    }
    g_origSlotCount = 0;
}

// watchdog 槽位校验：表槽位被对方（KillJvmti L1/我方逃生）改写 → 立即重写回期望值。
// 持久化写战处理：同一槽位被对方连续改写 ≥20 次 → 停火（我们本就不依赖表槽位；停火后
// 对方的 watchdog 计数器停止变化 → 双方刷屏一起停——DevMode 演练实测）。
static DWORD g_slotLastLogTick[MAX_PATCH_SLOTS];
static int g_slotRepairs[MAX_PATCH_SLOTS];
static int g_slotSurrendered[MAX_PATCH_SLOTS];
static void watchSlots(void) {
    if (!g_tableBlasted) return;
    jvmtiInterface_1_ *t = realTable();
    if (t == NULL) return;
    ULONG64 *base = (ULONG64 *)t;
    for (int i = 0; i < g_origSlotCount; i++) {
        if (g_slotSurrendered[i]) continue; // 已停火
        if (base[g_origSlotIdx[i]] != g_expectedSlot[i]) {
            writeSlot(&base[g_origSlotIdx[i]], (void *)(uintptr_t)g_expectedSlot[i]); // 修复照常（功能第一）——只限日志
            InterlockedIncrement(&g_watchRepairs);
            if (++g_slotRepairs[i] >= 20) {
                g_slotSurrendered[i] = 1;
                fprintf(stderr, "[RyjsAgent] watchdog: table slot %d contested by persistent adversary - ceasefire (we do not depend on it)\n",
                        g_origSlotIdx[i]);
                continue;
            }
            DWORD now = GetTickCount();
            if (now - g_slotLastLogTick[i] >= 30000) { // 写战限频：同一槽位最多 30s 一条
                g_slotLastLogTick[i] = now;
                fprintf(stderr, "[RyjsAgent] watchdog: table slot %d rewritten - restored\n", g_origSlotIdx[i]);
                if (g_watchRepairs > 50 && InterlockedExchange(&g_warWarned, 1) == 0) {
                    fprintf(stderr, "[RyjsAgent] WARNING: write-war with an external patcher detected "
                            "(our channels bypass it - functionality unaffected)\n");
                }
            }
        }
    }
}

// 通用拒绝（外部攻击面槽位——无条件 NOT_AVAILABLE；不读参数（x64 变参安全））
static jvmtiError JNICALL hooked_generic_reject(jvmtiEnv *env, ...) {
    (void)env;
    return JVMTI_ERROR_NOT_AVAILABLE;
}

// 放行槽位 trampoline（后门收窄——2026-08-16）：原实现把 instrument/JDK 需要的槽位
// **保留原始包装器**（对方可无检查直调这些槽——真后门）；现在全部换成 caller-check：
// untrusted → NOT_AVAILABLE，trusted（instrument/JDK/我方）→ 转发保存的原始包装器。
#define MAX_ALLOW_HOOKS 22
static void *g_allowOrig[MAX_ALLOW_HOOKS];

#define DEF_ALLOW_HOOK(i) \
static jvmtiError JNICALL hookedAllow_##i(void *self, uintptr_t a, uintptr_t b, uintptr_t c) { \
    void *caller = _ReturnAddress(); \
    if (!isTrustedCaller(caller)) { \
        logThrottled("JVMTI allow-slot", caller); \
        return JVMTI_ERROR_NOT_AVAILABLE; \
    } \
    return ((FN_Member)g_allowOrig[i])(self, a, b, c); \
}
DEF_ALLOW_HOOK(0) DEF_ALLOW_HOOK(1) DEF_ALLOW_HOOK(2) DEF_ALLOW_HOOK(3) DEF_ALLOW_HOOK(4)
DEF_ALLOW_HOOK(5) DEF_ALLOW_HOOK(6) DEF_ALLOW_HOOK(7) DEF_ALLOW_HOOK(8) DEF_ALLOW_HOOK(9)
DEF_ALLOW_HOOK(10) DEF_ALLOW_HOOK(11) DEF_ALLOW_HOOK(12) DEF_ALLOW_HOOK(13) DEF_ALLOW_HOOK(14)
DEF_ALLOW_HOOK(15) DEF_ALLOW_HOOK(16) DEF_ALLOW_HOOK(17) DEF_ALLOW_HOOK(18) DEF_ALLOW_HOOK(19)
DEF_ALLOW_HOOK(20) DEF_ALLOW_HOOK(21)

static jvmtiError (JNICALL *g_allowHooks[MAX_ALLOW_HOOKS])(void *, uintptr_t, uintptr_t, uintptr_t) = {
    hookedAllow_0, hookedAllow_1, hookedAllow_2, hookedAllow_3, hookedAllow_4,
    hookedAllow_5, hookedAllow_6, hookedAllow_7, hookedAllow_8, hookedAllow_9,
    hookedAllow_10, hookedAllow_11, hookedAllow_12, hookedAllow_13, hookedAllow_14,
    hookedAllow_15, hookedAllow_16, hookedAllow_17, hookedAllow_18, hookedAllow_19,
    hookedAllow_20, hookedAllow_21
};

// 打爆表（armJvmtiGuard 调用——inline hook 之后）
// 无差别全表 seal 标志（KEY_HOOK_FULL_BLOCK——全部封死模式）：由 nativeSetFullSeal 设置。
// 默认 0 = 对称性封堵（保留我方通道槽——break/blast 用：外部全拒、我方 transform/事件链活）；
// 1 = 全表 155 槽无差别 reject（我方也不准用——预期，仅 fullBlock 模式）。
static void blastJvmtiTable(void) {
    if (g_jvmti == NULL || g_jvmti->functions == NULL) return;
    const jvmtiInterface_1_ *t = g_jvmti->functions;
    ULONG64 *vt = (ULONG64 *)t;
    // 槽位号 = 字段偏移/8
    #define SLOT_OF(field) ((int)(((ULONG64)&t->field - (ULONG64)t) / 8))
    // JVM 内部/我方 transform 链依赖槽（jvm.dll 自身会调用 + cbClassFileLoadHook 分配变换字节：
    // Allocate/Deallocate/GetClassSignature 等）→ 默认保留原始包装器原样。
    // 这些槽的成员函数本体已被我们 Detour（hookMemberFunctions）→ 对手经表提取到的是
    // 我们 detour 入口 → caller-check 拒绝（能力仍为 0）。其余槽 1..nslots-1 全部 reject：
    // 对称性封堵（完整封锁）——外部扫描提取不到真成员；我方通道（g_fnXxx 缓存/私有表/私有 env）不依赖表。
    // 注意：jvmtiInterface_1_ 的注释编号≠字段偏移（AddCapabilities 在字段 142/SetEventCallbacks
    // 121/RetransformClasses 152——2026-08-18 实测 B 场景 6 成员仍可提取，根因即按注释编号
    // seal 只覆盖前 120 字段）→ 用 sizeof 确定真实字段数。
    int allowSlots[64];
    int allowCount = 0;
    #define ADD_ALLOW(field) do { if (allowCount < 64) allowSlots[allowCount++] = SLOT_OF(field); } while (0)
    ADD_ALLOW(AddCapabilities); ADD_ALLOW(SetEventCallbacks); ADD_ALLOW(SetEventNotificationMode);
    ADD_ALLOW(RetransformClasses); ADD_ALLOW(RedefineClasses); ADD_ALLOW(DisposeEnvironment);
    ADD_ALLOW(GetLoadedClasses); ADD_ALLOW(GenerateEvents); ADD_ALLOW(GetClassLoaderClasses);
    // instrument/JVM 内部（JPLISAgent.c / jvm.dll）+ 我方 transform 回调（cbClassFileLoadHook 分配）：
    // caller-check trampoline 保留原样
    ADD_ALLOW(Allocate); ADD_ALLOW(Deallocate); ADD_ALLOW(GetClassSignature); ADD_ALLOW(GetImplementedInterfaces);
    ADD_ALLOW(IsModifiableClass); ADD_ALLOW(GetErrorName);
    ADD_ALLOW(AddToBootstrapClassLoaderSearch); ADD_ALLOW(AddToSystemClassLoaderSearch);
    ADD_ALLOW(GetCapabilities); ADD_ALLOW(GetEnvironmentLocalStorage); ADD_ALLOW(GetNamedModule);
    ADD_ALLOW(GetObjectSize); ADD_ALLOW(GetPhase); ADD_ALLOW(GetPotentialCapabilities);
    ADD_ALLOW(SetEnvironmentLocalStorage); ADD_ALLOW(SetNativeMethodPrefixes);
    #undef ADD_ALLOW
    // vt_patch 保留槽（41/42/44/45/51/52）——保留原样（noop 会崩）
    if (allowCount < 64) allowSlots[allowCount++] = 41;
    if (allowCount < 64) allowSlots[allowCount++] = 42;
    if (allowCount < 64) allowSlots[allowCount++] = 44;
    if (allowCount < 64) allowSlots[allowCount++] = 45;
    if (allowCount < 64) allowSlots[allowCount++] = 51;
    if (allowCount < 64) allowSlots[allowCount++] = 52;
    // 其余全部槽位 → 无条件 reject stub（对称性封堵 2026-08-18）
    int nslots = (int)(sizeof(jvmtiInterface_1_) / 8);
    for (int i = 1; i < nslots; i++) {
        // fullSeal（全部封死模式）：无差别——我方通道槽也 reject（预期，我方也不准用）
        if (!g_fullSeal) {
            bool allowed = false;
            for (int k = 0; k < allowCount; k++) {
                if (allowSlots[k] == i) { allowed = true; break; }
            }
            if (allowed) continue;
        }
        saveSlot(&vt[i]);
        writeSlot(&vt[i], (void *)hooked_generic_reject);
        markExpected((void *)hooked_generic_reject);
    }
    #undef SLOT_OF
    InterlockedExchange(&g_tableBlasted, 1);
    fprintf(stderr, "[RyjsAgent] table blast done: %s (slots 1..%d%s, external table dead, our channels bypass it)\n",
            g_fullSeal ? "FULL TABLE SEALED - unconditional" : "SYMMETRIC SEAL",
            nslots - 1, g_fullSeal ? " no-allow" : " allow-preserved");
}

// fullBlock 模式 re-blast：首次 blast（对称性封堵）已执行后切换到全表 seal——
// 把保留的 allow 槽也写成 reject（原值已在首次 blast 时 saveSlot，restore 语义不变）。
static void reblastFullSeal(void) {
    if (g_jvmti == NULL || g_jvmti->functions == NULL) return;
    ULONG64 *vt = (ULONG64 *)g_jvmti->functions;
    int nslots = (int)(sizeof(jvmtiInterface_1_) / 8);
    int sealed = 0;
    for (int i = 1; i < nslots; i++) {
        if (vt[i] != (ULONG64)(uintptr_t)hooked_generic_reject) {
            saveSlot(&vt[i]);
            writeSlot(&vt[i], (void *)hooked_generic_reject);
            markExpected((void *)hooked_generic_reject);
            sealed++;
        }
    }
    fprintf(stderr, "[RyjsAgent] full-seal re-blast: %d allow slots sealed (unconditional)\n", sealed);
}

// ---- 堵 JNI_GetCreatedJavaVMs（jvm.dll 导出——vt_patch 的拿 VM 入口） ----
static jint JNICALL hookedGetCreatedJavaVMs(JavaVM **vmBuf, jsize bufLen, jsize *nVMs) {
    void *caller = _ReturnAddress();
    if (!isTrustedCaller(caller)) {
        // 外部拿不到 VM（vt_patch 的 jvmti_early_attack 第一步 pGetVMs(&vm,1,&count) → !vm → -3）
        if (nVMs != NULL) *nVMs = 0;
        if (vmBuf != NULL && bufLen > 0) vmBuf[0] = NULL;
        return JNI_OK;
    }
    return g_realGetCreatedJavaVMs(vmBuf, bufLen, nVMs);
}

// 封锁 JNI_GetCreatedJavaVMs（armJvmtiGuard 调用——Detours inline）
static void blockGetCreatedJavaVMs(void) {
    if (g_realGetCreatedJavaVMs != NULL) return; // 已封锁（re-arm 幂等）
    HMODULE jvmDll = GetModuleHandleA("jvm.dll");
    if (jvmDll == NULL) {
        fprintf(stderr, "[RyjsAgent] jvm.dll not found - JNI_GetCreatedJavaVMs not blocked\n");
        return;
    }
    PFN_JNI_GetCreatedJavaVMs pfn = (PFN_JNI_GetCreatedJavaVMs)GetProcAddress(jvmDll, "JNI_GetCreatedJavaVMs");
    if (pfn == NULL) {
        fprintf(stderr, "[RyjsAgent] JNI_GetCreatedJavaVMs export not found - not blocked\n");
        return;
    }
    g_realGetCreatedJavaVMs = pfn;
    g_entryGetCreatedJavaVMs = (void *)pfn;
    LONG err = NO_ERROR;
    DetourTransactionBegin();
    err = DetourUpdateThread(GetCurrentThread());
    if (err == NO_ERROR) err = DetourAttach(&(PVOID &)g_realGetCreatedJavaVMs, (PVOID)hookedGetCreatedJavaVMs);
    if (err == NO_ERROR && DetourTransactionCommit() == NO_ERROR) {
        fprintf(stderr, "[RyjsAgent] JNI_GetCreatedJavaVMs blocked\n");
    } else {
        DetourTransactionAbort();
        fprintf(stderr, "[RyjsAgent] JNI_GetCreatedJavaVMs block failed (err=%ld)\n", err);
        g_realGetCreatedJavaVMs = NULL;
        g_entryGetCreatedJavaVMs = NULL;
    }
}

static void JNICALL cbClassFileLoadHook(jvmtiEnv *jvmti, JNIEnv *env, jclass class_being_redefined,
                                        jobject loader, const char *name, jobject protection_domain,
                                        jint class_data_len, const unsigned char *class_data,
                                        jint *new_class_data_len, unsigned char **new_class_data);

// ---------- 类加载钩子 → Java 分发器 ----------
// 注意：回调内绝不 FindClass（会触发“正在加载的类”递归加载 → 栈溢出，2026-08-15 实测 0xC00000FD）。
// 桥类引用由 Java 静态块显式传入（nativeSetBridgeClass）——回调只使用缓存。
static void JNICALL cbClassFileLoadHook(jvmtiEnv *jvmti, JNIEnv *env, jclass class_being_redefined,
                                        jobject loader, const char *name, jobject protection_domain,
                                        jint class_data_len, const unsigned char *class_data,
                                        jint *new_class_data_len, unsigned char **new_class_data) {
    (void)class_being_redefined; (void)protection_domain;
    if (name == NULL || class_data == NULL || class_data_len <= 0 || g_dispatchMethod == NULL) {
        return;
    }
    // 系统类/库类直接跳过：transformer 只处理 MC/Forge/mods 类。回调里处理这些类会触发
    // transform 内部依赖（反射访问器/ASM 等）类加载 → 回调递归 → ClassCircularityError（2026-08-15 实测）。
    // 注意：com/ryjs 不在此过滤（放行进 dispatchTransform——Java 侧对 com/ryjs 只跑类还原 transformer，
    // 恢复"恢复自己的类"能力；注入类 transformer 不碰 com/ryjs 故无递归）。
    if (strncmp(name, "java/", 5) == 0
            || strncmp(name, "jdk/", 4) == 0
            || strncmp(name, "sun/", 4) == 0
            || strncmp(name, "javax/", 6) == 0
            || strncmp(name, "com/sun/", 8) == 0
            || strncmp(name, "org/objectweb/", 14) == 0) {
        return;
    }
    // 构造 jbyteArray（copy 输入字节）
    jbyteArray data = env->NewByteArray(class_data_len);
    if (data == NULL) {
        return;
    }
    env->SetByteArrayRegion(data, 0, class_data_len, (const jbyte *)class_data);
    jstring jname = env->NewStringUTF(name);
    jobject result = env->CallStaticObjectMethod(g_bridgeClass, g_dispatchMethod, loader, jname, data);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(data);
        if (jname != NULL) env->DeleteLocalRef(jname);
        return;
    }
    env->DeleteLocalRef(data);
    if (jname != NULL) env->DeleteLocalRef(jname);
    if (result == NULL) {
        return; // 未变换
    }
    // 变换后字节 → jvmti 内存
    jbyteArray out = (jbyteArray)result;
    jsize outLen = env->GetArrayLength(out);
    if (outLen <= 0) {
        env->DeleteLocalRef(result);
        return;
    }
    jbyte *outBytes = env->GetByteArrayElements(out, NULL);
    if (outBytes == NULL) {
        env->DeleteLocalRef(result);
        return;
    }
    unsigned char *alloc = NULL;
    // 分配走私有 env（blast 前快照、is_valid 校验有效、poison 免疫）——真实 env 在
    // jvmtiBlast/fullBlock 下被毒化（首字段 0xDEADBEEF），g_fnAllocate(真实env) 会因
    // jvmtiEnter 校验失败 → 分配失败 → transform 注入丢失 → Hook 全挂（2026-08-18 实测）。
    // privateEnv() 不存在（未快照）时回退真实 env。
    jvmtiEnv *aenv = privateEnv();
    if (aenv == NULL) aenv = jvmti;
    jvmtiError err = g_fnAllocate(aenv, (jlong)outLen, &alloc);
    if (err == JVMTI_ERROR_NONE && alloc != NULL) {
        memcpy(alloc, outBytes, (size_t)outLen);
        *new_class_data_len = outLen;
        *new_class_data = alloc;
    }
    env->ReleaseByteArrayElements(out, outBytes, JNI_ABORT);
    env->DeleteLocalRef(result);
}

// ---------- 公共初始化：拿 jvmti + 快照 + 能力 + 回调（JNI_OnLoad 与 Agent_OnLoad 共用） ----------
// 返回 JNI_OK 成功 / JNI_ERR 失败。已初始化（g_jvmti != NULL）时幂等返回 JNI_OK。
static jint initJvmtiAgent(JavaVM *vm, const char *who) {
    g_jvm = vm;
    detectJdkHome();
    GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                           | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       (LPCSTR)(uintptr_t)&JNI_OnLoad, &g_selfModule);
    if (g_jvmti != NULL) {
        return JNI_OK; // 已初始化（重复加载/双入口幂等）
    }
    jvmtiEnv *jvmti = NULL;
    jint result = vm->GetEnv((void **)&jvmti, JVMTI_VERSION_1_2);
    if (result != JNI_OK || jvmti == NULL) {
        fprintf(stderr, "[RyjsAgent] %s GetEnv(jvmti) failed: %d\n", who, result);
        return JNI_ERR;
    }
    g_jvmti = jvmti;
    // 原指针保存（表打爆之前——之后内部调用全走原指针，表被改零影响）
    saveOriginalPointers();
    snapshotPrivateEnv(); // 私有 env 快照（blast 毒化后我方续命通道——对称性封堵 2026-08-17）
    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_generate_all_class_hook_events = 1;
    caps.can_retransform_classes = 1;
    caps.can_retransform_any_class = 1;
    caps.can_redefine_classes = 1;
    caps.can_get_bytecodes = 1;
    jvmtiError err = g_fnAddCapabilities(g_jvmti, &caps);
    fprintf(stderr, "[RyjsAgent] %s AddCapabilities: %d\n", who, err);
    jvmtiEventCallbacks cb;
    memset(&cb, 0, sizeof(cb));
    cb.ClassFileLoadHook = cbClassFileLoadHook;
    g_fnSetEventCallbacks(g_jvmti, &cb, (jint)sizeof(cb));
    g_fnSetEventNotificationMode(g_jvmti, JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);
    fprintf(stderr, "[RyjsAgent] %s jvmti ready\n", who);
    return JNI_OK;
}

// ---------- JNI_OnLoad：拿 jvmti ----------
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    jint rc = initJvmtiAgent(vm, "JNI_OnLoad");
    if (rc != JNI_OK) return rc;
    // 断链武装：我方回调注册完毕后再 hook（自身调用已走原函数，后续外部 agent 全被裁）。
    // arm 内含：9 包装器 hook + 成员本体 hook/直连通道 + JVM 导出 hook + JNIEnv 表 hook
    // + GetEnv 封锁 + 表打爆 + JNI_GetCreatedJavaVMs 封锁 + watchdog（全部在 JNI_OnLoad 先手）。
    armJvmtiGuard();
    return JNI_VERSION_1_8;
}

// ---------- Agent_OnLoad：-agentpath:RyjsAgent.dll=FuckJVMTI:N ----------
// 参数解析（options 形式）："FuckJVMTI"、"FuckJVMTI:0"、"FuckJVMTI:1"、"FuckJVMTI:2"、
// 或裸 "0"/"1"/"2"。模式：
//   0 = 不武装（仅加载，纯观察）
//   1 = 殴打 JVMTIEnv + 打爆 JVMTI（对称性封堵：外部全拒，我方通道保留——正常防御）
//   2 = 干爆 JVMTI 全部（无差别全表 seal：我方也不准用——之前 fullBlock 全干爆模式）
static int parseAgentFuckMode(const char *options) {
    if (options == NULL) return 1;
    const char *colon = strrchr(options, ':');
    const char *p = (colon != NULL) ? colon + 1 : options;
    while (*p == ' ' || *p == '\t') p++;
    if (*p >= '0' && *p <= '9') {
        int v = *p - '0';
        if (v >= 0 && v <= 2) return v;
    }
    return 1; // 无法解析 → 默认 1（殴打+打爆）
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    (void)reserved;
    int mode = parseAgentFuckMode(options);
    fprintf(stderr, "[RyjsAgent] Agent_OnLoad: options=\"%s\" -> mode=%d (%s)\n",
            options != NULL ? options : "(null)", mode,
            mode == 0 ? "observe only" : (mode == 1 ? "punch JVMTIEnv + blast JVMTI (symmetric, our channels alive)" : "nuke all JVMTI (unconditional full seal, our channels dead too)"));
    if (mode == 0) {
        return JNI_OK; // 不武装——纯加载
    }
    jint rc = initJvmtiAgent(vm, "Agent_OnLoad");
    if (rc != JNI_OK) return rc;
    // 模式 2 = 干爆全部：先设无差别标志再 arm（arm 内 blast 按 g_fullSeal 全表 seal）
    if (mode == 2) {
        g_fullSeal = 1;
        fprintf(stderr, "[RyjsAgent] Agent_OnLoad: full-seal mode ON (unconditional)\n");
    }
    armJvmtiGuard();
    // 模式 1/2 都毒化真实 env（我方走私有 env 快照续命）
    poisonRealEnv();
    fprintf(stderr, "[RyjsAgent] Agent_OnLoad: armed (mode=%d, fullSeal=%d, privateEnv=%s)\n",
            mode, g_fullSeal, privateEnv() != NULL ? "ready" : "NOT ready");
    return JNI_OK;
}

// Java 静态块显式传入桥类引用（回调内不再 FindClass——避免递归加载栈溢出）
JNIEXPORT void JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeSetBridgeClass(JNIEnv *env, jclass clazz, jclass bridge) {
    (void)clazz;
    if (bridge == NULL) return;
    g_bridgeClass = (jclass)env->NewGlobalRef(bridge);
    g_dispatchMethod = env->GetStaticMethodID(
            g_bridgeClass, "dispatchTransform",
            "(Ljava/lang/ClassLoader;Ljava/lang/String;[B)[B");
    if (g_dispatchMethod == NULL) {
        env->ExceptionClear();
    }
    // 观察回调（可选方法——生产桥若未定义 classLoaded 则观察通道自动不启用）
    g_notifyMethod = env->GetStaticMethodID(
            g_bridgeClass, "classLoaded",
            "(Ljava/lang/String;Ljava/lang/Class;)V");
    if (g_notifyMethod == NULL) {
        env->ExceptionClear();
        g_notifyMethod = NULL;
    }
}

// ---------- native 方法（JNCT 模式） ----------

JNIEXPORT jboolean JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeBootstrap(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return g_jvmti != NULL ? JNI_TRUE : JNI_FALSE;
}

// 已加载类（Class[]——NewGlobalRef 转全局引用规避 make_local 帧绑定崩溃）。
// 注意：GetLoadedClasses 返回的引用是 make_local（局部引用，绑定当前 JNI 帧，jvmtiGetLoadedClasses.cpp:74）
// ——直接 SetObjectArrayElement 跨 JNI 调用在大量类时引用表状态不可靠 → JVM crash（2026-08-15 实测）。
// 修复：每个 jclass 先 NewGlobalRef（全局表 64K+，几千类安全）再 Set，Set 后立即 DeleteGlobalRef
// （数组已持有对象强引用，不泄漏；也不钉住对象）。
JNIEXPORT jobjectArray JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeLoadedClasses(JNIEnv *env, jclass clazz) {
    (void)clazz;
    if (g_jvmti == NULL) return NULL;
    jint count = 0;
    jclass *classes = NULL;
    jvmtiError err = g_fnGetLoadedClasses(g_jvmti, &count, &classes);
    if (err != JVMTI_ERROR_NONE || classes == NULL) return NULL;
    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == NULL) {
        env->ExceptionClear();
        g_fnDeallocate(g_jvmti, (unsigned char *)classes);
        return NULL;
    }
    jobjectArray result = env->NewObjectArray(count, classClass, NULL);
    if (result == NULL) {
        g_fnDeallocate(g_jvmti, (unsigned char *)classes);
        return NULL;
    }
    for (jint i = 0; i < count; i++) {
        jobject globalRef = env->NewGlobalRef(classes[i]);
        if (globalRef != NULL) {
            env->SetObjectArrayElement(result, i, globalRef);
            env->DeleteGlobalRef(globalRef);
        }
    }
    g_fnDeallocate(g_jvmti, (unsigned char *)classes);
    return result;
}

// 已加载类名字（String[] internalName——GetLoadedClasses → GetClassSignature 转名字）。
// 注意：GetLoadedClasses 返回的 jclass 是 make_local（绑定当前 JNI 帧，jvmtiGetLoadedClasses.cpp:74）——
// 只在 native 内使用，绝不跨 JNI 传回 Java（2026-08-15 实测跨 JNI 传递在加固 JDK 崩溃）；
// 返回 String（JNI 创建——安全）。
JNIEXPORT jobjectArray JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeLoadedNames(JNIEnv *env, jclass clazz) {
    (void)clazz;
    if (g_jvmti == NULL) return NULL;
    jint count = 0;
    jclass *classes = NULL;
    jvmtiError err = g_fnGetLoadedClasses(g_jvmti, &count, &classes);
    if (err != JVMTI_ERROR_NONE || classes == NULL) return NULL;
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == NULL) {
        env->ExceptionClear();
        g_fnDeallocate(g_jvmti, (unsigned char *)classes);
        return NULL;
    }
    jobjectArray result = env->NewObjectArray(count, stringClass, NULL);
    if (result == NULL) {
        g_fnDeallocate(g_jvmti, (unsigned char *)classes);
        return NULL;
    }
    jint n = 0;
    for (jint i = 0; i < count; i++) {
        char *sig = NULL;
        if (g_fnGetClassSignature(g_jvmti, classes[i], &sig, NULL) == JVMTI_ERROR_NONE && sig != NULL) {
            // sig 形如 Lcom/foo/Bar; ——只收普通类（跳过数组 [ 和原始类型）
            size_t slen = strlen(sig);
            if (slen > 2 && sig[0] == 'L' && sig[slen - 1] == ';') {
                size_t nl = slen - 2;
                if (nl < 4096) {
                    char name[4096];
                    memcpy(name, sig + 1, nl);
                    name[nl] = '\0';
                    jstring js = env->NewStringUTF(name);
                    if (js != NULL) {
                        env->SetObjectArrayElement(result, n++, js);
                        env->DeleteLocalRef(js);
                    }
                }
            }
            g_fnDeallocate(g_jvmti, (unsigned char *)sig);
        }
    }
    g_fnDeallocate(g_jvmti, (unsigned char *)classes);
    return result;
}

// 父类/接口链是否在快照中（快照 = sigs 数组——已加载类的 Lxxx; 签名；jclass 只在 native 内用）。
// 注意：GetSuperclass 是 JNI 函数（jvmti 无此函数——jvmti.h 无 GetSuperclass 成员）——用 env->GetSuperclass
static bool ancestryInSnapshot(JNIEnv *env, char **sigs, int sigCount, jclass c) {
    jclass cur = c;
    int depth = 0;
    while (cur != NULL && depth++ < 128) {
        jclass super = env->GetSuperclass(cur);
        if (super == NULL) break; // Object 顶
        char *sig = NULL;
        if (g_fnGetClassSignature(g_jvmti, super, &sig, NULL) != JVMTI_ERROR_NONE || sig == NULL) return false;
        bool found = false;
        for (int i = 0; i < sigCount; i++) {
            if (sigs[i] != NULL && strcmp(sigs[i], sig) == 0) { found = true; break; }
        }
        g_fnDeallocate(g_jvmti, (unsigned char *)sig);
        if (!found) return false;
        cur = super;
    }
    jint icount = 0;
    jclass *interfaces = NULL;
    if (g_fnGetImplementedInterfaces(g_jvmti, c, &icount, &interfaces) == JVMTI_ERROR_NONE && interfaces != NULL) {
        for (jint i = 0; i < icount; i++) {
            char *sig = NULL;
            if (g_fnGetClassSignature(g_jvmti, interfaces[i], &sig, NULL) != JVMTI_ERROR_NONE || sig == NULL) continue;
            bool found = false;
            for (int k = 0; k < sigCount; k++) {
                if (sigs[k] != NULL && strcmp(sigs[k], sig) == 0) { found = true; break; }
            }
            g_fnDeallocate(g_jvmti, (unsigned char *)sig);
            if (!found) {
                g_fnDeallocate(g_jvmti, (unsigned char *)interfaces);
                return false;
            }
        }
        g_fnDeallocate(g_jvmti, (unsigned char *)interfaces);
    }
    return true;
}

// probe 轮询核心（全 JVMTI）：一次 GetLoadedClasses 构建快照——对 names 中
// “已加载 + 可修改 + 父类/接口链就绪”的逐个 RetransformClasses。返回 {ok, skipped}。
JNIEXPORT jintArray JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeProbeRetransform(JNIEnv *env, jclass clazz, jobjectArray names) {
    (void)clazz;
    if (g_jvmti == NULL || names == NULL) return NULL;
    jsize nameCount = env->GetArrayLength(names);
    if (nameCount <= 0) return NULL;
    jint count = 0;
    jclass *classes = NULL;
    jvmtiError err = g_fnGetLoadedClasses(g_jvmti, &count, &classes);
    if (err != JVMTI_ERROR_NONE || classes == NULL) return NULL;
    // 签名快照（只收普通类——sig[0]=='L'；sigs 与 snapClasses 索引同步——sigs[k] 来自 classes[k]）
    char **sigs = (char **)malloc(sizeof(char *) * (size_t)(count > 0 ? count : 1));
    jclass *snapClasses = (jclass *)malloc(sizeof(jclass) * (size_t)(count > 0 ? count : 1));
    int sigCount = 0;
    for (jint k = 0; k < count; k++) {
        char *sig = NULL;
        if (g_fnGetClassSignature(g_jvmti, classes[k], &sig, NULL) == JVMTI_ERROR_NONE && sig != NULL && sig[0] == 'L') {
            sigs[sigCount] = sig;
            snapClasses[sigCount] = classes[k];
            sigCount++;
        } else if (sig != NULL) {
            g_fnDeallocate(g_jvmti, (unsigned char *)sig);
        }
    }
    int ok = 0, skipped = 0;
    for (jsize i = 0; i < nameCount; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(names, i);
        if (js == NULL) { skipped++; continue; }
        const char *name = env->GetStringUTFChars(js, NULL);
        if (name == NULL) { env->DeleteLocalRef(js); skipped++; continue; }
        size_t nlen = strlen(name);
        // 快照里找 jclass（线性匹配 Lname;）
        jclass target = NULL;
        for (int k = 0; k < sigCount; k++) {
            size_t slen = strlen(sigs[k]);
            // sig = "L" + name + ";" → slen == nlen + 2
            if (slen == nlen + 2 && memcmp(sigs[k] + 1, name, nlen) == 0) { target = snapClasses[k]; break; }
        }
        env->ReleaseStringUTFChars(js, name);
        env->DeleteLocalRef(js);
        if (target == NULL) { skipped++; continue; }
        jboolean mod = JNI_FALSE;
        if (g_fnIsModifiableClass(g_jvmti, target, &mod) != JVMTI_ERROR_NONE || !mod) { skipped++; continue; }
        if (!ancestryInSnapshot(env, sigs, sigCount, target)) { skipped++; continue; }
        if (g_fnRetransformClasses(g_jvmti, 1, &target) == JVMTI_ERROR_NONE) { ok++; } else { skipped++; }
    }
    for (int k = 0; k < sigCount; k++) {
        g_fnDeallocate(g_jvmti, (unsigned char *)sigs[k]);
    }
    free(sigs);
    free(snapClasses);
    g_fnDeallocate(g_jvmti, (unsigned char *)classes);
    jintArray res = env->NewIntArray(2);
    if (res != NULL) {
        jint vals[2] = {ok, skipped};
        env->SetIntArrayRegion(res, 0, 2, vals);
    }
    return res;
}

// redefine（还原被篡改类——按 Class 引用）。零-jvmti 通道：NULL-this 直调成员函数
// （JvmtiEnv::RedefineClasses 源码实证不读 this/env）——JVMTI 全废/函数表被打爆/对方先手
// 都不影响。env 死（g_jvmti==NULL）也照常工作。
JNIEXPORT jint JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeRedefineClass(JNIEnv *env, jclass clazz, jclass target, jbyteArray bytes) {
    (void)clazz;
    if (target == NULL || bytes == NULL) return -1;
    jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return -1;
    jbyte *buf = env->GetByteArrayElements(bytes, NULL);
    if (buf == NULL) return -1;
    jvmtiClassDefinition def;
    def.klass = target;
    def.class_byte_count = len;
    def.class_bytes = (unsigned char *)buf;
    jvmtiError err;
    if (g_fnRedefineZero != NULL) {
        err = g_fnRedefineZero(NULL, 1, &def); // 零通道（NULL-this）
    } else if (g_jvmti != NULL) {
        err = g_fnRedefineClasses(g_jvmti, 1, &def); // 回退：包装器路径
    } else {
        env->ReleaseByteArrayElements(bytes, buf, JNI_ABORT);
        return -1;
    }
    env->ReleaseByteArrayElements(bytes, buf, JNI_ABORT);
    return err;
}

// redefine（按 internalName——快照找 jclass；jclass 只在 native 内用）。
// 返回：0=成功，-1 参数错误，-2 未加载，-5 不可修改，其他=jvmti 错误码。
// 枚举步骤依赖 env（GetLoadedClasses 成员函数解引用 this）；redefine 步骤走零通道。
JNIEXPORT jint JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeRedefineByName(JNIEnv *env, jclass clazz, jstring name, jbyteArray bytes) {
    (void)clazz;
    if (name == NULL || bytes == NULL) return -1;
    const char *nameStr = env->GetStringUTFChars(name, NULL);
    if (nameStr == NULL) return -1;
    jint count = 0;
    jclass *classes = NULL;
    if (g_jvmti == NULL) {
        env->ReleaseStringUTFChars(name, nameStr);
        return -1; // 按名 redefine 需要枚举（枚举依赖 env）
    }
    jvmtiError err = g_fnGetLoadedClasses(g_jvmti, &count, &classes);
    if (err != JVMTI_ERROR_NONE || classes == NULL) {
        env->ReleaseStringUTFChars(name, nameStr);
        return -1;
    }
    jclass target = NULL;
    size_t nlen = strlen(nameStr);
    for (jint k = 0; k < count; k++) {
        char *sig = NULL;
        if (g_fnGetClassSignature(g_jvmti, classes[k], &sig, NULL) == JVMTI_ERROR_NONE && sig != NULL) {
            size_t slen = strlen(sig);
            if (slen == nlen + 2 && sig[0] == 'L' && sig[slen - 1] == ';'
                    && memcmp(sig + 1, nameStr, nlen) == 0) {
                target = classes[k];
                g_fnDeallocate(g_jvmti, (unsigned char *)sig);
                break;
            }
            g_fnDeallocate(g_jvmti, (unsigned char *)sig);
        }
    }
    env->ReleaseStringUTFChars(name, nameStr);
    g_fnDeallocate(g_jvmti, (unsigned char *)classes);
    if (target == NULL) return -2;
    jboolean mod = JNI_FALSE;
    if (g_fnIsModifiableClass(g_jvmti, target, &mod) != JVMTI_ERROR_NONE || !mod) return -5;
    jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return -1;
    jbyte *buf = env->GetByteArrayElements(bytes, NULL);
    if (buf == NULL) return -1;
    jvmtiClassDefinition def;
    def.klass = target;
    def.class_byte_count = len;
    def.class_bytes = (unsigned char *)buf;
    jvmtiError r;
    if (g_fnRedefineZero != NULL) {
        r = g_fnRedefineZero(NULL, 1, &def); // 零通道
    } else {
        r = g_fnRedefineClasses(g_jvmti, 1, &def);
    }
    env->ReleaseByteArrayElements(bytes, buf, JNI_ABORT);
    return r;
}

/* ==================== nativeTool* 零通道工具集（2026-08-17） ====================
 * 成员函数直调（绕 jvmtiInterface_1_ 表/包装器/入口——表被打爆/入口被对方改写都不影响），
 * 但 this 传"有效 env"：jvmtiEnter 检查需要真 env（实测 NULL-this 只对 Redefine/Retransform 成立，
 * 其他成员 jvmtiEnter 直接拒）。env 被清（对方扫内存）→ toolEnv() 从 JavaVM 重新 GetEnv 自愈。
 * 命名避开 zero 字眼（nativeToolXxx），逐个提供高频 JVMTI 能力。 */

/* ==================== env 对象毒化（对称性封堵——2026-08-17） ====================
 * 我方后手活着的 80% 查询能力的载体 = 内存中可用的 JvmtiEnv 对象（对手内存扫描 →
 * 成员直调 this=对象 → 内部 env->Xxx 以 jvm.dll 调用者身份过 caller-check → bypass）。
 * 封法：毒化真实对象首字段（表指针）→ 成员内部 env->Xxx 全废（实测 116 不可用）。
 * 我方先手续命：JNI_OnLoad 快照"原始表 + 完整对象副本"→ 私有 env（首字段=私有表）→
 * toolEnv 返回私有副本 → 成员直调照常（实测 671 成功）。
 * 只毒化不 Dispose：transform 事件链保留；恢复 = 首字段写回（restoreEnv 兼容）。 */
#define PRIVATE_TABLE_SLOTS 256
#define PRIVATE_ENV_COPY_SIZE 4096
static jvmtiInterface_1_ *g_privateTable = NULL; // 私有原始表副本（blast 前快照）
static jvmtiEnv *g_privateEnvCopy = NULL;        // 私有完整 env 对象副本（首字段=私有表）
static jvmtiInterface_1_ *g_realTable = NULL;    // 真实表地址（blast 写入对象；毒化后仍可用——watchdog 用）

static jvmtiInterface_1_ *realTable(void) {
    return g_realTable;
}

static void snapshotPrivateEnv(void) {
    if (g_jvmti == NULL || g_jvmti->functions == NULL || g_privateEnvCopy != NULL) return;
    g_realTable = (jvmtiInterface_1_ *)(uintptr_t)g_jvmti->functions; // 真实表地址（毒化后 watchdog/restore 仍能操作 blast 写入对象）
    g_privateTable = (jvmtiInterface_1_ *)VirtualAlloc(NULL, PRIVATE_TABLE_SLOTS * 8,
                                                       MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    g_privateEnvCopy = (jvmtiEnv *)VirtualAlloc(NULL, PRIVATE_ENV_COPY_SIZE,
                                                MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (g_privateTable == NULL || g_privateEnvCopy == NULL) return;
    memcpy(g_privateTable, g_jvmti->functions, PRIVATE_TABLE_SLOTS * 8);
    memcpy(g_privateEnvCopy, g_jvmti, PRIVATE_ENV_COPY_SIZE);
    *(jvmtiInterface_1_ **)g_privateEnvCopy = g_privateTable; // 首字段 = 私有表
    fprintf(stderr, "[RyjsAgent] private env snapshot ready (table=%p env=%p)\n",
            (void *)g_privateTable, (void *)g_privateEnvCopy);
}

static void poisonRealEnv(void) {
    if (g_jvmti == NULL) return;
    ULONG64 *obj = (ULONG64 *)g_jvmti;
    DWORD old = 0;
    if (VirtualProtect(obj, 16, PAGE_READWRITE, &old)) {
        obj[0] = 0xDEADBEEFDEADBEEFULL;
        obj[1] = 0xCAFEBABECAFEBABEULL;
        VirtualProtect(obj, 16, old, &old);
    }
}

static jvmtiEnv *privateEnv(void) {
    return g_privateEnvCopy;
}

// 工具集有效 env：优先私有副本（blast 毒化后真实对象不可用）；无副本回退 g_jvmti；
// 被清 → 从 JavaVM 重新 GetEnv（GetEnv 钩子对家族内部放行；成员直调绕表，无需恢复表）。
static jvmtiEnv *toolEnv(void) {
    if (g_privateEnvCopy != NULL) return g_privateEnvCopy;
    if (g_jvmti != NULL) return g_jvmti;
    if (g_jvm == NULL) return NULL;
    jvmtiEnv *jvmti = NULL;
    if (g_jvm->GetEnv((void **)&jvmti, JVMTI_VERSION_1_2) == JNI_OK && jvmti != NULL) {
        g_jvmti = jvmti;
        fprintf(stderr, "[RyjsAgent] tool env revived (env=%p) - members direct call unaffected\n", jvmti);
    }
    return g_jvmti;
}

// 工具集调用源选择：优先 cached（saveOriginalPointers 缓存的表项原值=成员实现，实证可用）；
// 无缓存回退 attach（Detours trampoline）→ member（提取的成员本体）。
static void *toolMember(int idx, void *cached) {
    if (cached != NULL) return cached;
    if (g_members[idx].attach != NULL) return g_members[idx].attach;
    return g_members[idx].member;
}

// 释放 jvmti 分配的内存：成员 Deallocate 直调（走有效 env——jvmtiDeallocate 内部走 jvmtiEnter 检查）。
static void toolDealloc(void *p) {
    if (p == NULL) return;
    jvmtiEnv *env = toolEnv();
    FN_Deallocate fn = (FN_Deallocate)toolMember(M_DEALLOC, g_fnDeallocate);
    if (env != NULL && fn != NULL) {
        fn(env, (unsigned char *)p);
    }
}

// 零通道 GetLoadedClasses：全部已加载类。
// make_local 引用 → NewGlobalRef 转全局再入数组（与 nativeLoadedClasses 同坑处理——
// 2026-08-15 实测直接跨 JNI 传引用表状态不可靠 → JVM crash）。
JNIEXPORT jobjectArray JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeToolGetLoadedClasses(JNIEnv *env, jclass clazz) {
    (void)clazz;
    FN_GetLoadedClasses fn = (FN_GetLoadedClasses)toolMember(M_GET_LOADED, g_fnGetLoadedClasses);
    if (fn == NULL) return NULL;
    jvmtiEnv *tenv = toolEnv();
    if (tenv == NULL) return NULL;
    jint count = 0;
    jclass *classes = NULL;
    jvmtiError err = fn(tenv, &count, &classes);
    if (err != JVMTI_ERROR_NONE || classes == NULL) return NULL;
    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == NULL) { env->ExceptionClear(); toolDealloc(classes); return NULL; }
    jobjectArray result = env->NewObjectArray(count, classClass, NULL);
    if (result == NULL) { toolDealloc(classes); return NULL; }
    for (jint i = 0; i < count; i++) {
        jobject g = env->NewGlobalRef(classes[i]);
        if (g != NULL) {
            env->SetObjectArrayElement(result, i, g);
            env->DeleteGlobalRef(g);
        }
    }
    toolDealloc(classes);
    return result;
}

// 零通道 IsModifiableClass（redefine/retransform 前置检查）
JNIEXPORT jboolean JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeToolIsModifiableClass(JNIEnv *env, jclass clazz, jclass target) {
    (void)clazz;
    FN_IsModifiableClass fn = (FN_IsModifiableClass)toolMember(M_IS_MOD, g_fnIsModifiableClass);
    if (fn == NULL || target == NULL) return JNI_FALSE;
    jvmtiEnv *tenv = toolEnv();
    if (tenv == NULL) return JNI_FALSE;
    jboolean mod = JNI_FALSE;
    fn(tenv, target, &mod);
    return mod;
}

// 零通道 GetClassSignature → internalName（Lcom/foo/Bar; → com/foo/Bar；数组/原始类型返回 null）
JNIEXPORT jstring JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeToolGetClassInternalName(JNIEnv *env, jclass clazz, jclass target) {
    (void)clazz;
    FN_GetClassSignature fn = (FN_GetClassSignature)toolMember(M_GET_SIG, g_fnGetClassSignature);
    if (fn == NULL || target == NULL) return NULL;
    jvmtiEnv *tenv = toolEnv();
    if (tenv == NULL) return NULL;
    char *sig = NULL;
    char *generic = NULL;
    jvmtiError err = fn(tenv, target, &sig, &generic);
    if (err != JVMTI_ERROR_NONE || sig == NULL) return NULL;
    jstring out = NULL;
    size_t slen = strlen(sig);
    if (slen > 2 && sig[0] == 'L' && sig[slen - 1] == ';' && slen - 2 < 4096) {
        char name[4096];
        memcpy(name, sig + 1, slen - 2);
        name[slen - 2] = '\0';
        out = env->NewStringUTF(name);
    }
    toolDealloc(sig);
    if (generic != NULL) toolDealloc(generic);
    return out;
}

// 零通道 GetImplementedInterfaces（make_local 引用 → NewGlobalRef 保护，同 GetLoadedClasses）
JNIEXPORT jobjectArray JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeToolGetImplementedInterfaces(JNIEnv *env, jclass clazz, jclass target) {
    (void)clazz;
    FN_GetImplementedInterfaces fn = (FN_GetImplementedInterfaces)toolMember(M_GET_IFACES, g_fnGetImplementedInterfaces);
    if (fn == NULL || target == NULL) return NULL;
    jvmtiEnv *tenv = toolEnv();
    if (tenv == NULL) return NULL;
    jint count = 0;
    jclass *ifaces = NULL;
    jvmtiError err = fn(tenv, target, &count, &ifaces);
    if (err != JVMTI_ERROR_NONE || ifaces == NULL) return NULL;
    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == NULL) { env->ExceptionClear(); toolDealloc(ifaces); return NULL; }
    jobjectArray result = env->NewObjectArray(count, classClass, NULL);
    if (result == NULL) { toolDealloc(ifaces); return NULL; }
    for (jint i = 0; i < count; i++) {
        jobject g = env->NewGlobalRef(ifaces[i]);
        if (g != NULL) {
            env->SetObjectArrayElement(result, i, g);
            env->DeleteGlobalRef(g);
        }
    }
    toolDealloc(ifaces);
    return result;
}

// 零通道 GetClassLoaderClasses：指定 ClassLoader 加载的类（make_local 引用 → NewGlobalRef 保护）
JNIEXPORT jobjectArray JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeToolGetClassLoaderClasses(JNIEnv *env, jclass clazz, jobject loader) {
    (void)clazz;
    PFN_jvmtiGetClassLoaderClasses fn = (PFN_jvmtiGetClassLoaderClasses)toolMember(M_GET_LOADER_CLASSES, NULL);
    if (fn == NULL || loader == NULL) return NULL;
    jvmtiEnv *tenv = toolEnv();
    if (tenv == NULL) return NULL;
    jint count = 0;
    jclass *classes = NULL;
    jvmtiError err = fn(tenv, loader, &count, &classes);
    if (err != JVMTI_ERROR_NONE || classes == NULL) return NULL;
    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == NULL) { env->ExceptionClear(); toolDealloc(classes); return NULL; }
    jobjectArray result = env->NewObjectArray(count, classClass, NULL);
    if (result == NULL) { toolDealloc(classes); return NULL; }
    for (jint i = 0; i < count; i++) {
        jobject g = env->NewGlobalRef(classes[i]);
        if (g != NULL) {
            env->SetObjectArrayElement(result, i, g);
            env->DeleteGlobalRef(g);
        }
    }
    toolDealloc(classes);
    return result;
}

// 零通道 RetransformClasses（触发 ClassFileLoadHook 重变换；同 nativeRetransform 语义，
// 统一走 g_members[M_RETRANSFORM].attach NULL-this）
JNIEXPORT jint JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeToolRetransformClasses(JNIEnv *env, jclass clazz, jobjectArray classes) {
    (void)clazz;
    if (classes == NULL) return -1;
    jsize count = env->GetArrayLength(classes);
    if (count <= 0) return 0;
    jclass *arr = (jclass *)malloc(sizeof(jclass) * (size_t)count);
    if (arr == NULL) return -1;
    for (jsize i = 0; i < count; i++) {
        arr[i] = (jclass)env->GetObjectArrayElement(classes, i);
    }
    jvmtiError err;
    if (g_members[M_RETRANSFORM].attach != NULL) {
        err = ((FN_RetransformClasses)g_members[M_RETRANSFORM].attach)(NULL, count, arr);
    } else if (g_fnRetransformZero != NULL) {
        err = g_fnRetransformZero(NULL, count, arr);
    } else {
        err = JVMTI_ERROR_NOT_AVAILABLE;
    }
    for (jsize i = 0; i < count; i++) {
        env->DeleteLocalRef(arr[i]);
    }
    free(arr);
    return err;
}

// 零通道 RedefineClasses（按 Class 引用 + 字节码；同 nativeRedefineClass 语义，
// 统一走 g_members[M_REDEFINE].attach NULL-this）
JNIEXPORT jint JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeToolRedefineClasses(JNIEnv *env, jclass clazz, jclass target, jbyteArray bytes) {
    (void)clazz;
    if (target == NULL || bytes == NULL) return -1;
    jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return -1;
    jbyte *buf = env->GetByteArrayElements(bytes, NULL);
    if (buf == NULL) return -1;
    jvmtiClassDefinition def;
    def.klass = target;
    def.class_byte_count = len;
    def.class_bytes = (unsigned char *)buf;
    jvmtiError err;
    if (g_members[M_REDEFINE].attach != NULL) {
        err = ((FN_RedefineClasses)g_members[M_REDEFINE].attach)(NULL, 1, &def);
    } else if (g_fnRedefineZero != NULL) {
        err = g_fnRedefineZero(NULL, 1, &def);
    } else {
        err = JVMTI_ERROR_NOT_AVAILABLE;
    }
    env->ReleaseByteArrayElements(bytes, buf, JNI_ABORT);
    return err;
}

// 零通道 GetErrorName：错误码 → 文案（调试用；成员直调走有效 env）
JNIEXPORT jstring JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeToolGetErrorName(JNIEnv *env, jclass clazz, jint errCode) {
    (void)clazz;
    FN_GetErrorName fn = (FN_GetErrorName)toolMember(M_GET_ERR, g_fnGetErrorName);
    if (fn == NULL) return NULL;
    jvmtiEnv *tenv = toolEnv();
    if (tenv == NULL) return NULL;
    char *name = NULL;
    jvmtiError err = fn(tenv, (jvmtiError)errCode, &name);
    if (err != JVMTI_ERROR_NONE || name == NULL) return NULL;
    jstring out = env->NewStringUTF(name);
    toolDealloc(name);
    return out;
}

// retransform（触发 ClassFileLoadHook 重变换）。零-jvmti 通道：NULL-this 直调成员函数
// （JvmtiEnv::RetransformClasses 源码实证不读 this/env）。
JNIEXPORT jint JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeRetransform(JNIEnv *env, jclass clazz, jobjectArray classes) {
    (void)clazz;
    if (classes == NULL) return -1;
    jsize count = env->GetArrayLength(classes);
    if (count <= 0) return 0;
    jclass *arr = (jclass *)malloc(sizeof(jclass) * (size_t)count);
    if (arr == NULL) return -1;
    for (jsize i = 0; i < count; i++) {
        arr[i] = (jclass)env->GetObjectArrayElement(classes, i);
    }
    jvmtiError err;
    if (g_fnRetransformZero != NULL) {
        err = g_fnRetransformZero(NULL, count, arr); // 零通道（NULL-this）
    } else if (g_jvmti != NULL) {
        err = g_fnRetransformClasses(g_jvmti, count, arr); // 回退
    } else {
        err = JVMTI_ERROR_NOT_AVAILABLE;
    }
    for (jsize i = 0; i < count; i++) {
        env->DeleteLocalRef(arr[i]);
    }
    free(arr);
    return err;
}

// 零-jvmti 通道状态（Java 侧可用性上报）：redefine/retransform 成员直连是否就绪
JNIEXPORT jboolean JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeZeroChannel(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return (g_fnRedefineZero != NULL && g_fnRetransformZero != NULL) ? JNI_TRUE : JNI_FALSE;
}

#ifdef RYJS_TEST_BUILD
// 演练用（仅测试构建 RYJS_TEST_BUILD 编译——生产 DLL 不含这些导出，防对方 RegisterNatives 借道自杀我方）：
// 模拟对方清空我方 env（扫 DLL 内存写 0 式攻击）——验证零通道在 JVMTI 全废后仍存活。
JNIEXPORT void JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeZeroTestKillEnv(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    g_jvmti = NULL;
    g_fnAllocate = NULL;
    g_fnDeallocate = NULL;
    fprintf(stderr, "[RyjsAgent] test: env cleared (simulated attack) - zero channel should survive\n");
}

// 演练用：禁用我方 ClassFileLoadHook 事件（模拟对方关我们事件 → cb 死）——验证导出通道接管
JNIEXPORT void JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeZeroTestDisableHook(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (g_jvmti == NULL) {
        fprintf(stderr, "[RyjsAgent] test: event disable failed (env dead)\n");
        return;
    }
    g_fnSetEventNotificationMode(g_jvmti, JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);
    fprintf(stderr, "[RyjsAgent] test: ClassFileLoadHook disabled (simulated cb kill)\n");
}

/* 演练用：env 对象毒化（对称性封堵实验——2026-08-17）
 * 生产实现：JNI_OnLoad snapshotPrivateEnv 快照私有副本；nativeBlastEnv poisonRealEnv 毒化真实对象。
 * 本函数供实验直接触发毒化（快照已由 JNI_OnLoad 完成；只毒化不 Dispose——事件链保留）。 */
JNIEXPORT jint JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeTestPoisonEnv(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (g_jvmti == NULL) return -1;
    poisonRealEnv();
    fprintf(stderr, "[RyjsAgent] test: env object poisoned (private env=%p)\n", (void *)g_privateEnvCopy);
    return 0;
}

// 模拟"我方先手续命"：用私有完整 env 副本（首字段=私有表）调 GetLoadedClasses——应成功
JNIEXPORT jint JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeTestPrivateCall(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (g_privateEnvCopy == NULL) return -99;
    jint count = 0;
    jclass *classes = NULL;
    jvmtiError e = g_fnGetLoadedClasses(g_privateEnvCopy, &count, &classes);
    if (e == JVMTI_ERROR_NONE && classes != NULL) {
        if (g_fnDeallocate != NULL) g_fnDeallocate(g_privateEnvCopy, (unsigned char *)classes);
        fprintf(stderr, "[RyjsAgent] test: private-env member call OK count=%d\n", (int)count);
        return count;
    }
    fprintf(stderr, "[RyjsAgent] test: private-env member call failed err=%d\n", (int)e);
    return (jint)e;
}

// 模拟"后手对手拿到毒化对象"：直接成员直调（this=毒化对象）——SEH 包裹，预期崩溃/失败
JNIEXPORT jint JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeTestPoisonedCall(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (g_jvmti == NULL) return -99;
    jint count = 0;
    jclass *classes = NULL;
    __try {
        jvmtiError e = g_fnGetLoadedClasses(g_jvmti, &count, &classes);
        if (e == JVMTI_ERROR_NONE && classes != NULL) {
            fprintf(stderr, "[RyjsAgent] test: poisoned object member call SUCCEEDED (bad!)\n");
            return count;
        }
        fprintf(stderr, "[RyjsAgent] test: poisoned object member call returned err=%d\n", (int)e);
        return (jint)e;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        fprintf(stderr, "[RyjsAgent] test: poisoned object member call CRASHED (as expected)\n");
        return -1000;
    }
}
#endif // RYJS_TEST_BUILD

// 零-JVMTI transform 通道开关（AsmHook 接管）：true=导出 hook 跑 dispatchTransform；false=只透传
JNIEXPORT void JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeSetExportTransform(JNIEnv *env, jclass clazz, jboolean on) {
    (void)env;
    (void)clazz;
    InterlockedExchange(&g_exportTransform, on ? 1 : 0);
    fprintf(stderr, "[RyjsAgent] export transform channel %s (zero-JVMTI AsmHook takeover)\n", on ? "ON" : "OFF");
}

// 类加载观察通道开关：true=导出 hook 在 define 成功后推送 (name, Class) 给 Java（零 env，替代枚举轮询）
JNIEXPORT void JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeSetClassObserver(JNIEnv *env, jclass clazz, jboolean on) {
    (void)env;
    (void)clazz;
    InterlockedExchange(&g_exportObserve, on ? 1 : 0);
    fprintf(stderr, "[RyjsAgent] class-observe channel %s (export hook push name+Class)\n", on ? "ON" : "OFF");
}

// isModifiable（redefine/retransform 前检查）
JNIEXPORT jboolean JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeIsModifiable(JNIEnv *env, jclass clazz, jclass target) {
    (void)clazz;
    if (g_jvmti == NULL || target == NULL) return JNI_FALSE;
    jboolean mod = JNI_FALSE;
    g_fnIsModifiableClass(g_jvmti, target, &mod);
    return mod;
}

// native 定义类（绕开 transform 链/普通 define——JNI DefineClass）
JNIEXPORT jclass JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeDefineClass(JNIEnv *env, jclass clazz, jstring name, jobject loader, jbyteArray bytes) {
    (void)clazz;
    if (bytes == NULL) return NULL;
    jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return NULL;
    jbyte *buf = env->GetByteArrayElements(bytes, NULL);
    if (buf == NULL) return NULL;
    const char *nameStr = (name != NULL) ? env->GetStringUTFChars(name, NULL) : NULL;
    jclass result = env->DefineClass(nameStr, (jclass)loader, (const jbyte *)buf, len);
    if (nameStr != NULL) env->ReleaseStringUTFChars(name, nameStr);
    env->ReleaseByteArrayElements(bytes, buf, JNI_ABORT);
    return result;
}

} // extern "C"

