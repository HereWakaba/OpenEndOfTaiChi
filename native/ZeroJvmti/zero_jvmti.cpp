/*
 * zero_jvmti.cpp — 零 jvmtiEnv 的 JVMTI 功能实现
 *
 * ================================================================
 * 架构:
 *
 *   启动 → Agent_OnLoad 或 init() → 获取 bootstrap jvmtiEnv (仅用于读函数表)
 *        → 安装 Detours Hook (JVM_DefineClassWithSource 等)
 *        → 从函数表包装器中提取成员函数地址
 *
 *   运行时:
 *   ┌────────────────────┬──────────────────────────────────┐
 *   │ ClassFileLoadHook  │ Detours → 零 JVMTI               │
 *   │ RedefineClasses    │ unwrap → NULL this 调成员函数     │
 *   │ RetransformClasses │ unwrap → NULL this 调成员函数     │
 *   │ GetAllLoadedClasses│ unwrap → NULL this 调成员函数     │
 *   │ GetInstances       │ bootstrap env (heap walk 需要 env)│
 *   └────────────────────┴──────────────────────────────────┘
 * ================================================================
 */

#include <windows.h>
#include <detours.h>
#include <psapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "jni.h"
#include "jvmti.h"
#include "zero_jvmti.h"

 // ================================================================
 // 全局状态
 // ================================================================

static HMODULE   g_jvm_dll = NULL;
static BYTE* g_text_start = NULL;
static SIZE_T    g_text_size = 0;
static jvmtiEnv* g_bootstrap = NULL;  // 仅用于: 读函数表 + GetInstances
static bool      g_ready = false;

static void kill_restore(void);

// ================================================================
// PE 解析: 找 .text 段
// ================================================================

static bool pe_find_text(HMODULE mod, BYTE** start, SIZE_T* size) {
    BYTE* base = (BYTE*)mod;
    PIMAGE_DOS_HEADER dos = (PIMAGE_DOS_HEADER)base;
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return false;
    PIMAGE_NT_HEADERS64 nt = (PIMAGE_NT_HEADERS64)(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return false;
    PIMAGE_SECTION_HEADER sec = IMAGE_FIRST_SECTION(nt);
    for (int i = 0; i < nt->FileHeader.NumberOfSections; i++) {
        if (memcmp(sec[i].Name, ".text", 5) == 0) {
            *start = base + sec[i].VirtualAddress;
            *size = sec[i].Misc.VirtualSize;
            return true;
        }
    }
    return false;
}

static bool is_in_text(const void* p) {
    BYTE* a = (BYTE*)p;
    return a >= g_text_start && a < g_text_start + g_text_size;
}

// ================================================================
// x64 包装器反汇编: 提取成员函数地址
// ================================================================
// 策略: 三个包装器 (Redefine, Retransform, GetLoaded) 有很多公共的
// 辅助调用 (trace/jfr/validation)。真正的成员函数调用是每个包装器里
// 独特的那个。我们收集所有调用目标, 取交集, 每个包装器里第一个"非公共"
// 的调用就是成员函数。

#define MAX_CALLS 32
typedef struct {
    void* targets[MAX_CALLS];
    int   offsets[MAX_CALLS];
    int   count;
} CallList;

static void collect_calls(void* wrapper_addr, CallList* list) {
    BYTE* code = (BYTE*)wrapper_addr;
    list->count = 0;

    for (int i = 0; i < 512 && list->count < MAX_CALLS; i++) {
        void* target = NULL;
        if (code[i] == 0xE8 || code[i] == 0xE9) {
            int32_t rel = *(int32_t*)(code + i + 1);
            target = code + i + 5 + rel;
        }
        else if (code[i] == 0xFF && code[i+1] == 0x15) {
            int32_t disp = *(int32_t*)(code + i + 2);
            target = *(void**)(code + i + 6 + disp);
        }
        else if (code[i] == 0xFF && code[i+1] == 0x25) {
            int32_t disp = *(int32_t*)(code + i + 2);
            target = *(void**)(code + i + 6 + disp);
        }
        if (target != NULL && is_in_text(target)) {
            // 去重: 同一个 target 只记第一次
            bool dup = false;
            for (int j = 0; j < list->count; j++) {
                if (list->targets[j] == target) { dup = true; break; }
            }
            if (!dup) {
                list->targets[list->count] = target;
                list->offsets[list->count] = i;
                list->count++;
            }
        }
        if (code[i] == 0xCC && code[i+1] == 0xCC && code[i+2] == 0xCC && i > 52) break;
    }
}

static bool is_common(void* t, CallList* lists, int n) {
    for (int i = 0; i < n; i++) {
        bool found = false;
        for (int j = 0; j < lists[i].count; j++) {
            if (lists[i].targets[j] == t) { found = true; break; }
        }
        if (!found) return false;
    }
    return true;
}

// 从列表里取第一个非公共的调用
static void* pick_member(CallList* mine, CallList* all[], int n) {
    // 从前到后 (按 offset 排序, 即出现顺序)
    for (int i = 0; i < mine->count; i++) {
        void* t = mine->targets[i];
        // 统计在几个列表中出现
        int cnt = 0;
        for (int k = 0; k < n; k++) {
            for (int j = 0; j < all[k]->count; j++) {
                if (all[k]->targets[j] == t) { cnt++; break; }
            }
        }
        if (cnt == 1) {  // 只在我自己的列表中出现
            printf("  pick non-common @%d->%p\n", mine->offsets[i], t);
            return t;
        }
    }
    // 退回: 第一个调用
    return mine->count > 0 ? mine->targets[0] : NULL;
}

// ================================================================
// 成员函数类型 (this 显式化, x64 __fastcall: this=RCX)
// ================================================================

typedef jvmtiError(*RedefineClasses_fn)(void*, jint, const jvmtiClassDefinition*);
typedef jvmtiError(*RetransformClasses_fn)(void*, jint, const jclass*);
typedef jvmtiError(*GetLoadedClasses_fn)(void*, jint*, jclass**);

static RedefineClasses_fn   g_RedefineClasses = NULL;
static RetransformClasses_fn g_RetransformClasses = NULL;
static GetLoadedClasses_fn  g_GetLoadedClasses = NULL;

// ================================================================
// 获取 bootstrap jvmtiEnv
// ================================================================

static bool acquire_bootstrap(void) {
    if (g_bootstrap != NULL) return true;

    typedef jint(JNICALL* GetVMs_t)(JavaVM**, jsize, jsize*);
    GetVMs_t getVMs = (GetVMs_t)GetProcAddress(g_jvm_dll, "JNI_GetCreatedJavaVMs");
    if (getVMs == NULL) return false;

    JavaVM* vm = NULL;
    jsize n = 0;
    if (getVMs(&vm, 1, &n) != JNI_OK || n == 0) return false;

    jvmtiEnv* jvmti = NULL;
    if (vm->GetEnv((void**)&jvmti, JVMTI_VERSION_1_2) != JNI_OK) return false;

    // 申请能力 (运行时能拿多少算多少)
    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_tag_objects = 1;
    jvmti->AddCapabilities(&caps);

    g_bootstrap = jvmti;
    return true;
}

// ================================================================
// 从函数表提取所有需要的成员函数
// ================================================================

static bool extract_all_members(void) {
    if (!acquire_bootstrap()) return false;
    if (g_text_start == NULL) return false;

    const struct jvmtiInterface_1_* ft = g_bootstrap->functions;

    // 收集三个包装器的所有调用
    CallList redef, retr, getld;
    collect_calls((void*)ft->RedefineClasses, &redef);
    collect_calls((void*)ft->RetransformClasses, &retr);
    collect_calls((void*)ft->GetLoadedClasses, &getld);

    printf("[zero] Redefine calls: ");
    for (int i = 0; i < redef.count; i++) printf("@%d->%p ", redef.offsets[i], redef.targets[i]);
    printf("\n[zero] Retransform calls: ");
    for (int i = 0; i < retr.count; i++) printf("@%d->%p ", retr.offsets[i], retr.targets[i]);
    printf("\n[zero] GetLoaded calls: ");
    for (int i = 0; i < getld.count; i++) printf("@%d->%p ", getld.offsets[i], getld.targets[i]);
    printf("\n");

    CallList* all[] = { &redef, &retr, &getld };

    printf("[zero] Redefine: ");
    g_RedefineClasses = (RedefineClasses_fn)pick_member(&redef, all, 3);
    printf("[zero] Retransform: ");
    g_RetransformClasses = (RetransformClasses_fn)pick_member(&retr, all, 3);
    printf("[zero] GetLoaded: ");
    g_GetLoadedClasses = (GetLoadedClasses_fn)pick_member(&getld, all, 3);

    printf("[zero] RedefineClasses member=%p\n", g_RedefineClasses);
    printf("[zero] RetransformClasses member=%p\n", g_RetransformClasses);
    printf("[zero] GetLoadedClasses member=%p\n", g_GetLoadedClasses);

    return g_RedefineClasses && g_RetransformClasses && g_GetLoadedClasses;
}

// ================================================================
// Detours: Hook JVM 导出函数 → ClassFileLoadHook
// ================================================================

typedef jclass(JNICALL* JVM_DefineClassWithSource_t)(
    JNIEnv*, const char*, jobject, const jbyte*, jsize, jobject, const char*);
typedef jclass(JNICALL* JVM_LookupDefineClass_t)(
    JNIEnv*, jclass, const char*, const jbyte*, jsize, jobject,
    jboolean, int, jobject);

static JVM_DefineClassWithSource_t Real_DefineClassWithSource = NULL;
static JVM_LookupDefineClass_t     Real_LookupDefineClass = NULL;

static ZeroClassLoadHook g_hook = NULL;
static void* g_hook_data = NULL;

static void* det_alloc(size_t n) { return malloc(n); }
static void  det_free(void* p) { if (p) free(p); }

static void apply_hook(JNIEnv* env, jobject loader,
    const jbyte** buf, jsize* len,
    unsigned char** modified, jint* mod_len) {
    *modified = NULL; *mod_len = 0;
    if (g_hook == NULL || *buf == NULL || *len <= 0) return;

    int r = g_hook(env, loader,
        (const unsigned char*)*buf, (jint)*len,
        modified, mod_len, g_hook_data);
    if (r == 0 && *modified != NULL && *mod_len > 0) {
        *buf = (const jbyte*)*modified;
        *len = (jsize)*mod_len;
    }
}

static jclass JNICALL Mine_DefineClassWithSource(
    JNIEnv* env, const char* name, jobject loader,
    const jbyte* buf, jsize len, jobject pd, const char* src)
{
    unsigned char* mod = NULL; jint mod_len = 0;
    const jbyte* final_buf = buf; jsize final_len = len;
    apply_hook(env, loader, &final_buf, &final_len, &mod, &mod_len);
    jclass r = Real_DefineClassWithSource(env, name, loader, final_buf, final_len, pd, src);
    det_free(mod);
    return r;
}

static jclass JNICALL Mine_LookupDefineClass(
    JNIEnv* env, jclass lookup, const char* name,
    const jbyte* buf, jsize len, jobject pd,
    jboolean init, int flags, jobject classData)
{
    unsigned char* mod = NULL; jint mod_len = 0;
    const jbyte* final_buf = buf; jsize final_len = len;
    apply_hook(env, NULL, &final_buf, &final_len, &mod, &mod_len);
    jclass r = Real_LookupDefineClass(env, lookup, name, final_buf, final_len,
        pd, init, flags, classData);
    det_free(mod);
    return r;
}

static bool install_detours_hooks(void) {
    Real_DefineClassWithSource = (JVM_DefineClassWithSource_t)
        GetProcAddress(g_jvm_dll, "JVM_DefineClassWithSource");
    Real_LookupDefineClass = (JVM_LookupDefineClass_t)
        GetProcAddress(g_jvm_dll, "JVM_LookupDefineClass");

    if (Real_DefineClassWithSource == NULL) {
        fprintf(stderr, "[zero] JVM_DefineClassWithSource not found\n");
        return false;
    }

    DetourTransactionBegin();
    DetourUpdateThread(GetCurrentThread());
    DetourAttach(&(PVOID&)Real_DefineClassWithSource, Mine_DefineClassWithSource);
    if (Real_LookupDefineClass)
        DetourAttach(&(PVOID&)Real_LookupDefineClass, Mine_LookupDefineClass);

    LONG err = DetourTransactionCommit();
    printf("[zero] Detours hooks installed, status=%ld\n", err);
    return err == NO_ERROR;
}

// ================================================================
// 公开 API
// ================================================================

int ZeroJvmti_Init(JNIEnv* env) {
    if (g_ready) return 0;

    g_jvm_dll = GetModuleHandleA("jvm.dll");
    if (g_jvm_dll == NULL) return -1;

    if (!pe_find_text(g_jvm_dll, &g_text_start, &g_text_size)) return -2;
    printf("[zero] jvm.dll .text: %p - %p\n", g_text_start, g_text_start + g_text_size);

    if (!acquire_bootstrap()) return -3;
    if (!extract_all_members()) return -4;
    if (!install_detours_hooks()) return -5;

    g_ready = true;
    printf("[zero] init complete\n");
    return 0;
}

void ZeroJvmti_Shutdown(void) {
    if (!g_ready) return;
    kill_restore();
    DetourTransactionBegin();
    DetourUpdateThread(GetCurrentThread());
    DetourDetach(&(PVOID&)Real_DefineClassWithSource, Mine_DefineClassWithSource);
    if (Real_LookupDefineClass)
        DetourDetach(&(PVOID&)Real_LookupDefineClass, Mine_LookupDefineClass);
    DetourTransactionCommit();
    g_ready = false;
}

// ── 1. ClassFileLoadHook ───────────────────────────────
void ZeroJvmti_SetClassLoadHook(ZeroClassLoadHook hook, void* data) {
    g_hook = hook; g_hook_data = data;
}

// ── 2. GetAllLoadedClasses ─────────────────────────────
// 注意: GetLoadedClasses 的成员函数内部使用了 env 的 Allocate+JNI handle,
// 所以不能 NULL this。这里用 C++ 包装器调用 bootstrap env。
int ZeroJvmti_GetAllLoadedClasses(JNIEnv* env, jint* count, jclass** classes) {
    if (g_bootstrap == NULL) return -1;
    jvmtiError err = g_bootstrap->GetLoadedClasses(count, classes);
    return (err == JVMTI_ERROR_NONE) ? 0 : (int)err;
}

// ── 3. GetInstancesOfClass ─────────────────────────────
// 用 class_tag 机制: 给目标类打 TAG_CLASS,
// 堆遍历回调中 class_tag==TAG_CLASS 的即该类的实例。

static const jlong TAG_CLASS    = 0xBEEF77770001LL;
static const jlong TAG_INSTANCE = 0xBEEF77770002LL;

static jint JNICALL tag_instance_callback(
    jlong class_tag, jlong size, jlong* tag_ptr, void* user_data)
{
    (void)size; (void)user_data;
    if (class_tag == TAG_CLASS) {
        *tag_ptr = TAG_INSTANCE;
    }
    return JVMTI_VISIT_OBJECTS;
}

int ZeroJvmti_GetInstances(JNIEnv* env, jclass target,
    jint* count, jobject** instances) {
    if (g_bootstrap == NULL || target == NULL) return -1;

    // 1. Tag 目标类
    g_bootstrap->SetTag(target, TAG_CLASS);

    // 2. 遍历堆: class_tag==TAG_CLASS 的对象被打上 TAG_INSTANCE
    g_bootstrap->IterateOverHeap(JVMTI_HEAP_OBJECT_EITHER,
                                  (jvmtiHeapObjectCallback)tag_instance_callback, NULL);

    // 3. 取出所有打上 TAG_INSTANCE 的对象
    jlong tags[] = { TAG_INSTANCE };
    jint n = 0; jobject* objs = NULL;
    g_bootstrap->GetObjectsWithTags(1, tags, &n, &objs, NULL);

    // 4. 清理 tags
    g_bootstrap->SetTag(target, 0);
    if (objs) {
        for (jint i = 0; i < n; i++) g_bootstrap->SetTag(objs[i], 0);
    }

    *count = n; *instances = objs;
    return 0;
}

// ── 4. RedefineClass ───────────────────────────────────
int ZeroJvmti_RedefineClass(JNIEnv* env, jclass target,
    const unsigned char* new_bytes, jint new_len) {
    if (!g_RedefineClasses) return -1;
    jvmtiClassDefinition def;
    def.klass = target; def.class_byte_count = new_len;
    def.class_bytes = (unsigned char*)new_bytes;
    jvmtiError err = g_RedefineClasses(NULL, 1, &def);
    return (err == JVMTI_ERROR_NONE) ? 0 : (int)err;
}

// ── 5. RetransformClass ────────────────────────────────
int ZeroJvmti_RetransformClass(JNIEnv* env, jclass target) {
    if (!g_RetransformClasses) return -1;
    jvmtiError err = g_RetransformClasses(NULL, 1, &target);
    return (err == JVMTI_ERROR_NONE) ? 0 : (int)err;
}

// ── 6. KillJvmti (加固版) ────────────────────────────────
// 四层拦截 + watchdog 防写回, 全程只改内存不动磁盘:
//
//   L1: jvmtiInterface_1_ 函数表槽位 → DLL 内 stub (返回 UNMODIFIABLE_CLASS)
//       所有 jvmtiEnv 共享同一函数表, 一处改写全进程生效。
//   L2: Redefine/Retransform 成员函数入口 → Detours 跳转 kill hook
//       (覆盖"绕过 L1 直接调成员函数"的路径)。
//   L3: JVM_RedefineClasses 导出函数 → Detours 跳转 (JDK17 无此导出, 自动跳过)
//   L4: 函数表槽位原值 (包装器) 入口 → Detours 跳转
//       (对手恢复槽位后调用链落到包装器, 同样被拦)。
//   W : watchdog 线程每 5ms 校验 L1-L4 (timeBeginPeriod 保证真实周期),
//       发现被写回/被替换立即重写, 并统计每处被恢复的次数。
//
// 对抗写回: 对手必须同时在 5ms 窗口内恢复 槽位值+包装器+成员函数 三处
// 才能注入一个类 (一次 RedefineClasses 本身要几十 ms) → 实际不可完成;
// 磁盘文件未变, 本 DLL 可无限次重写; 用户态无绝对胜局, 但绕过成本被
// 推到"必须逆向本 DLL 并持续对抗"的级别。

static jvmtiError JNICALL Kill_Slot_Redefine(jvmtiEnv* env, jint count, const jvmtiClassDefinition* defs) {
    (void)env; (void)count; (void)defs;
    return JVMTI_ERROR_UNMODIFIABLE_CLASS;
}

static jvmtiError JNICALL Kill_Slot_Retransform(jvmtiEnv* env, jint count, const jclass* classes) {
    (void)env; (void)count; (void)classes;
    return JVMTI_ERROR_UNMODIFIABLE_CLASS;
}

// 完整封锁：全表通用拒绝 stub（对称性封堵验证——模拟"最狠的对手"：表上任何槽都不暴露
// 真成员、无内部 call，后手者扫描提取不到任何地址。与 RyjsAgent seal 表等价。）
static jvmtiError JNICALL Kill_Slot_Generic(jvmtiEnv* env, ...) {
    (void)env;
    return JVMTI_ERROR_NOT_AVAILABLE;
}

static jvmtiError Kill_Redefine(void* self, jint count, const jvmtiClassDefinition* defs) {
    (void)self; (void)count; (void)defs;
    return JVMTI_ERROR_UNMODIFIABLE_CLASS;
}

static jvmtiError Kill_Retransform(void* self, jint count, const jclass* classes) {
    (void)self; (void)count; (void)classes;
    return JVMTI_ERROR_UNMODIFIABLE_CLASS;
}

static jclass JNICALL Kill_JVM_RedefineClasses(JNIEnv* env, jclass cls, jint count,
    const jvmtiClassDefinition* defs) {
    (void)cls; (void)count; (void)defs;
    jclass e = env->FindClass("java/lang/UnsupportedOperationException");
    if (e != NULL) env->ThrowNew(e, "redefine disabled");
    return NULL;
}

// ── watchdog 数据 ──────────────────────────────────────
typedef struct {
    void*  addr;
    BYTE   orig[16];   // attach 前的原始字节 (恢复用)
    BYTE   hook[16];   // attach 后的跳转字节 (修复用)
    SIZE_T len;        // 被改写的字节数
} KillEntry;

// 拦截点布局: [0] JVM_RedefineClasses 导出 (JDK17 无, 跳过)
//             [1] Redefine 成员函数  [2] Retransform 成员函数
//             [3] Redefine 包装器    [4] Retransform 包装器
// 包装器 = 函数表槽位原值; 对手恢复槽位后调用链落到包装器, 同样被 hook。
static KillEntry g_kill_entries[5];
static int       g_kill_entry_count = 0;

static void* g_slot_redef = NULL;
static void* g_slot_retr  = NULL;
static void* g_slot_redef_orig = NULL;
static void* g_slot_retr_orig  = NULL;

static HANDLE g_kill_stop = NULL;
static HANDLE g_kill_thread = NULL;
static bool   g_killed = false;

static PVOID s_orig_redefine = NULL;
static PVOID s_orig_retransform = NULL;
static PVOID s_orig_jvm_redef = NULL;
static PVOID s_orig_wrapper_redef = NULL;
static PVOID s_orig_wrapper_retr = NULL;

// 修复统计: 每处被对手恢复后 watchdog 重装的次数
static volatile LONG g_repair_total = 0;
static volatile LONG g_repair_slot_redef = 0;
static volatile LONG g_repair_slot_retr = 0;
static volatile LONG g_repair_entries[5] = {0,0,0,0,0};

// 高精度定时: timeBeginPeriod(1) 让 WaitForSingleObject 的短等待真实生效
// (winmm 动态加载, 不改链接配置)
static void (*p_timeBeginPeriod)(UINT) = NULL;
static void (*p_timeEndPeriod)(UINT) = NULL;

static void init_highres_timer(void) {
    HMODULE m = LoadLibraryA("winmm.dll");
    if (m != NULL) {
        p_timeBeginPeriod = (void(*)(UINT))GetProcAddress(m, "timeBeginPeriod");
        p_timeEndPeriod = (void(*)(UINT))GetProcAddress(m, "timeEndPeriod");
        if (p_timeBeginPeriod != NULL) p_timeBeginPeriod(1);
    }
}

static void exit_highres_timer(void) {
    if (p_timeEndPeriod != NULL) p_timeEndPeriod(1);
    p_timeBeginPeriod = NULL;
    p_timeEndPeriod = NULL;
}

static void force_write(void* addr, const void* bytes, SIZE_T n) {
    DWORD old = 0;
    if (VirtualProtect(addr, n, PAGE_EXECUTE_READWRITE, &old)) {
        memcpy(addr, bytes, n);
        VirtualProtect(addr, n, old, &old);
    }
}

// 写指针值: 直接 force_write(&fn) 会把函数开头指令字节当数据复制,
// 必须先把指针值放进变量再取地址。
static void force_write_ptr(void* addr, void* value) {
    force_write(addr, &value, sizeof(value));
}

static bool entry_intact(const KillEntry* e) {
    BYTE cur[16];
    memcpy(cur, e->addr, e->len);
    return memcmp(cur, e->hook, e->len) == 0;
}

static void verify_and_repair(void) {
    for (int i = 0; i < g_kill_entry_count; i++) {
        if (!entry_intact(&g_kill_entries[i])) {
            force_write(g_kill_entries[i].addr, g_kill_entries[i].hook, g_kill_entries[i].len);
            InterlockedIncrement(&g_repair_entries[i]);
            InterlockedIncrement(&g_repair_total);
        }
    }
    if (g_slot_redef != NULL && *(void**)g_slot_redef != (void*)Kill_Slot_Redefine) {
        force_write_ptr(g_slot_redef, (void*)Kill_Slot_Redefine);
        InterlockedIncrement(&g_repair_slot_redef);
        InterlockedIncrement(&g_repair_total);
    }
    if (g_slot_retr != NULL && *(void**)g_slot_retr != (void*)Kill_Slot_Retransform) {
        force_write_ptr(g_slot_retr, (void*)Kill_Slot_Retransform);
        InterlockedIncrement(&g_repair_slot_retr);
        InterlockedIncrement(&g_repair_total);
    }
}

#define KILL_WATCHDOG_INTERVAL_MS 5
#define KILL_WATCHDOG_PRINT_MS    500

static DWORD WINAPI kill_watchdog(LPVOID param) {
    (void)param;
    DWORD last_print = GetTickCount();
    LONG  last_total = 0;
    while (WaitForSingleObject(g_kill_stop, KILL_WATCHDOG_INTERVAL_MS) == WAIT_TIMEOUT) {
        verify_and_repair();
        DWORD now = GetTickCount();
        if (now - last_print >= KILL_WATCHDOG_PRINT_MS) {
            LONG t = g_repair_total;
            if (t != last_total) {
                printf("[zero] watchdog repair: total=%ld slot_r=%ld slot_t=%ld"
                    " e0=%ld e1=%ld e2=%ld e3=%ld e4=%ld\n",
                    t, g_repair_slot_redef, g_repair_slot_retr,
                    g_repair_entries[0], g_repair_entries[1], g_repair_entries[2],
                    g_repair_entries[3], g_repair_entries[4]);
                last_total = t;
            }
            last_print = now;
        }
    }
    return 0;
}

static void compute_hook_bytes(KillEntry* e) {
    BYTE after[16];
    memcpy(after, e->addr, sizeof(after));
    SIZE_T last = 0;
    for (SIZE_T i = 0; i < sizeof(after); i++) {
        if (after[i] != e->orig[i]) last = i + 1;
    }
    e->len = (last > 0) ? last : 5;
    memcpy(e->hook, e->addr, e->len);
}

int ZeroJvmti_KillJvmti(void) {
    if (g_killed) return 0;
    if (!g_ready) return -1;

    // L1: 全表槽位 → 通用拒绝 stub（完整封锁：连 GetLoadedClasses/GetClassSignature 等
    // 全部 seal——后手者一个都提取不到；与 RyjsAgent seal 表等价）
    const struct jvmtiInterface_1_* ft = g_bootstrap->functions;
    g_slot_redef = (void*)&ft->RedefineClasses;
    g_slot_retr  = (void*)&ft->RetransformClasses;
    g_slot_redef_orig = *(void**)g_slot_redef;
    g_slot_retr_orig  = *(void**)g_slot_retr;
    printf("[zero] kill: bootstrap functions=%p redef_slot_orig=%p retr_slot_orig=%p\n",
        ft, g_slot_redef_orig, g_slot_retr_orig);
    ULONG64* vt = (ULONG64*)ft;
    // jvmtiInterface_1_ 真实字段数 = sizeof/8（注释编号≠字段号！AddCapabilities 在字段
    // 141、SetEventCallbacks 121、RetransformClasses 151——2026-08-18 B 场景实测 6 成员
    // 仍可提取，根因即 seal 用注释编号 1..119 只写了前 120 字段）
    int nslots = (int)(sizeof(struct jvmtiInterface_1_) / 8);
    for (int i = 1; i < nslots; i++) { // 槽 0 是 reserved=NULL，跳过
        force_write_ptr(&vt[i], (void*)Kill_Slot_Generic);
    }
    printf("[zero] kill: FULL TABLE SEALED (%d slots -> generic reject)\n", nslots - 1);
    printf("[zero] post-seal spot check: AddCapabilities=%p SetEventCallbacks=%p GetErrorName=%p Dispose=%p GenEvents=%p Retransform=%p\n",
        (void*)ft->AddCapabilities, (void*)ft->SetEventCallbacks, (void*)ft->GetErrorName,
        (void*)ft->DisposeEnvironment, (void*)ft->GenerateEvents, (void*)ft->RetransformClasses);

    // 收集入口: 导出 + 两个成员函数 + 两个包装器 (attach 前存原始字节)
    KillEntry* e = g_kill_entries;
    g_kill_entry_count = 0;

    void* jvm_redef = (void*)GetProcAddress(g_jvm_dll, "JVM_RedefineClasses");
    if (jvm_redef != NULL) {
        e[g_kill_entry_count].addr = jvm_redef;
        memcpy(e[g_kill_entry_count].orig, jvm_redef, sizeof(e[0].orig));
        g_kill_entry_count++;
    }
    if (g_RedefineClasses != NULL) {
        e[g_kill_entry_count].addr = (void*)g_RedefineClasses;
        memcpy(e[g_kill_entry_count].orig, g_RedefineClasses, sizeof(e[0].orig));
        g_kill_entry_count++;
    }
    if (g_RetransformClasses != NULL) {
        e[g_kill_entry_count].addr = (void*)g_RetransformClasses;
        memcpy(e[g_kill_entry_count].orig, g_RetransformClasses, sizeof(e[0].orig));
        g_kill_entry_count++;
    }
    // 包装器 (槽位原值): 对手恢复槽位后, 调用链会落到这里
    if (g_slot_redef_orig != NULL) {
        e[g_kill_entry_count].addr = g_slot_redef_orig;
        memcpy(e[g_kill_entry_count].orig, g_slot_redef_orig, sizeof(e[0].orig));
        g_kill_entry_count++;
    }
    if (g_slot_retr_orig != NULL) {
        e[g_kill_entry_count].addr = g_slot_retr_orig;
        memcpy(e[g_kill_entry_count].orig, g_slot_retr_orig, sizeof(e[0].orig));
        g_kill_entry_count++;
    }

    // 断掉本 DLL 自己的直调通道
    s_orig_redefine = (PVOID)g_RedefineClasses;
    s_orig_retransform = (PVOID)g_RetransformClasses;
    s_orig_jvm_redef = jvm_redef;
    s_orig_wrapper_redef = (PVOID)g_slot_redef_orig;
    s_orig_wrapper_retr = (PVOID)g_slot_retr_orig;
    g_RedefineClasses = NULL;
    g_RetransformClasses = NULL;

    // L2/L3: Detours 装跳转 (成员函数 + 包装器 + 导出)
    DetourTransactionBegin();
    DetourUpdateThread(GetCurrentThread());
    if (s_orig_redefine)
        DetourAttach(&s_orig_redefine, (PVOID)Kill_Redefine);
    if (s_orig_retransform)
        DetourAttach(&s_orig_retransform, (PVOID)Kill_Retransform);
    if (s_orig_wrapper_redef)
        DetourAttach(&s_orig_wrapper_redef, (PVOID)Kill_Redefine);
    if (s_orig_wrapper_retr)
        DetourAttach(&s_orig_wrapper_retr, (PVOID)Kill_Retransform);
    if (s_orig_jvm_redef)
        DetourAttach(&s_orig_jvm_redef, (PVOID)Kill_JVM_RedefineClasses);
    LONG err = DetourTransactionCommit();

    if (err != NO_ERROR) {
        for (int i = 0; i < g_kill_entry_count; i++)
            force_write(e[i].addr, e[i].orig, sizeof(e[i].orig));
        force_write(g_slot_redef, &g_slot_redef_orig, sizeof(void*));
        force_write(g_slot_retr, &g_slot_retr_orig, sizeof(void*));
        g_kill_entry_count = 0;
        g_slot_redef = g_slot_retr = NULL;
        g_RedefineClasses = (RedefineClasses_fn)s_orig_redefine;
        g_RetransformClasses = (RetransformClasses_fn)s_orig_retransform;
        printf("[zero] kill detour commit failed: %ld\n", err);
        return -2;
    }

    // 记录跳转字节 + 启动 watchdog
    for (int i = 0; i < g_kill_entry_count; i++)
        compute_hook_bytes(&e[i]);

    init_highres_timer();
    g_kill_stop = CreateEventW(NULL, TRUE, FALSE, NULL);
    g_kill_thread = CreateThread(NULL, 0, kill_watchdog, NULL, 0, NULL);

    g_killed = true;
    printf("[zero] jvmti redefine/retransform disabled "
        "(4 layers + watchdog@%dms, %d hooks)\n",
        KILL_WATCHDOG_INTERVAL_MS, g_kill_entry_count);
    return 0;
}

// ── 6b. KillJvmti 恢复 (由 Shutdown 调用) ─────────────────
static void kill_restore(void) {
    if (!g_killed) return;

    if (g_kill_stop != NULL) {
        SetEvent(g_kill_stop);
        if (g_kill_thread != NULL)
            WaitForSingleObject(g_kill_thread, 2000);
        CloseHandle(g_kill_thread);
        CloseHandle(g_kill_stop);
        g_kill_thread = NULL;
        g_kill_stop = NULL;
    }

    for (int i = 0; i < g_kill_entry_count; i++)
        force_write(g_kill_entries[i].addr, g_kill_entries[i].orig, g_kill_entries[i].len);
    if (g_slot_redef != NULL) force_write(g_slot_redef, &g_slot_redef_orig, sizeof(void*));
    if (g_slot_retr != NULL)  force_write(g_slot_retr, &g_slot_retr_orig, sizeof(void*));

    exit_highres_timer();
    g_kill_entry_count = 0;
    g_slot_redef = g_slot_retr = NULL;
    g_killed = false;
    printf("[zero] jvmti kill restored (total repairs: %ld)\n", g_repair_total);
}
