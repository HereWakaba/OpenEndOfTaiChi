#include <jni.h>
#include <jvmti.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <windows.h>

static jvmtiEnv *g_jvmti = NULL;
static jrawMonitorID g_lock = NULL;
static FILE *g_log = NULL;
static volatile long long g_seq = 0;
static int g_chain = 0;
static int g_halt = 0;
static int g_stop = 0;
static int g_firstDone = 0;
static volatile int g_count = 0;

/* built-in module allowlist (exact match) */
static const char *kModules[] = {
    "bb", "aa", "z", "P", "p", "AA", "Aa", "Cc", "Gg", "Oo", "aA",
    "reflection", "aom", "fmlloader", "cpw.mods.modlauncher", NULL
};

static char *dupStr(const char *s) {
    if (s == NULL) return NULL;
    size_t n = strlen(s) + 1;
    char *p = (char *)malloc(n);
    if (p != NULL) memcpy(p, s, n);
    return p;
}

static int moduleAllowed(const char *name) {
    if (name == NULL) return 0;
    for (int i = 0; kModules[i] != NULL; i++) {
        if (strcmp(name, kModules[i]) == 0) return 1;
    }
    return 0;
}

static int sigInteresting(const char *sig) {
    if (sig == NULL) return 0;
    /* probe jars use package == module name (bb/P, aA/P, ...) and are covered by the
       module allowlist above; this catches reflection / zombis (com.ryjs.*) only. */
    return strstr(sig, "Lcom/ryjs/") != NULL;
}

static void writeLine(const char *sig, const char *moduleName) {
    fprintf(g_log, "%07lld [prepare] %s module=%s\n",
            (long long)g_seq++, sig, moduleName != NULL ? moduleName : "<unnamed>");
    fflush(g_log);
}

static void JNICALL cbClassPrepare(jvmtiEnv *jvmti, JNIEnv *env,
                                   jthread thread, jclass klass) {
    char *sig = NULL;
    if (jvmti->GetClassSignature(klass, &sig, NULL) != JVMTI_ERROR_NONE || sig == NULL) {
        return;
    }

    char *moduleName = NULL;
    jobject loader = NULL;
    /* package name: skip leading '[' of array sigs, then between 'L' and last '/' */
    const char *s = sig;
    while (*s == '[') s++;
    if (*s == 'L') {
        const char *start = s + 1;
        const char *slash = strrchr(start, '/');
        size_t len = slash != NULL ? (size_t)(slash - start) : 0;
        char *pkg = (char *)malloc(len + 1);
        if (pkg != NULL) {
            memcpy(pkg, start, len);
            pkg[len] = '\0';
            for (size_t i = 0; i < len; i++) {
                if (pkg[i] == '/') pkg[i] = '.'; /* GetNamedModule wants dotted names */
            }
            jobject module = NULL;
            jvmtiError lerr = jvmti->GetClassLoader(klass, &loader);
            if (lerr == JVMTI_ERROR_NONE &&
                jvmti->GetNamedModule(loader, pkg, &module) == JVMTI_ERROR_NONE && module != NULL) {
                jclass modClass = env->GetObjectClass(module);
                if (modClass != NULL) {
                    jmethodID getName = env->GetMethodID(modClass, "getName", "()Ljava/lang/String;");
                    if (getName != NULL) {
                        jstring js = (jstring)env->CallObjectMethod(module, getName);
                        if (js != NULL) {
                            const char *cs = env->GetStringUTFChars(js, NULL);
                            if (cs != NULL) {
                                moduleName = dupStr(cs);
                                env->ReleaseStringUTFChars(js, cs);
                            }
                            env->DeleteLocalRef(js);
                        }
                    }
                    env->DeleteLocalRef(modClass);
                }
                env->DeleteLocalRef(module);
            }
            free(pkg);
        }
    }
    if (loader != NULL) {
        env->DeleteLocalRef(loader);
    }

    if (sigInteresting(sig) || moduleAllowed(moduleName)) {
        jvmti->RawMonitorEnter(g_lock);
        writeLine(sig, moduleName);
        jvmti->RawMonitorExit(g_lock);

        /* first interesting class: optional chain restart / self halt */
        if (!g_firstDone && (g_chain || g_halt)) {
            g_firstDone = 1;
            if (g_chain) {
                wchar_t *cmdline = GetCommandLineW();
                STARTUPINFOW si;
                PROCESS_INFORMATION pi;
                ZeroMemory(&si, sizeof(si));
                si.cb = sizeof(si);
                ZeroMemory(&pi, sizeof(pi));
                BOOL ok = CreateProcessW(NULL, cmdline, NULL, NULL, FALSE,
                                         CREATE_NEW_CONSOLE, NULL, NULL, &si, &pi);
                jvmti->RawMonitorEnter(g_lock);
                fprintf(g_log, "chain: relaunch %s\n", ok ? "OK" : "FAILED");
                fflush(g_log);
                jvmti->RawMonitorExit(g_lock);
                if (ok) {
                    CloseHandle(pi.hThread);
                    CloseHandle(pi.hProcess);
                }
            }
            TerminateProcess(GetCurrentProcess(), 0);
        }

        /* stop=N: terminate after N interesting classes have been prepared */
        if (g_stop > 0) {
            int c = ++g_count;
            if (c >= g_stop) {
                jvmti->RawMonitorEnter(g_lock);
                fprintf(g_log, "stop: reached %d interesting classes, terminating\n", c);
                fflush(g_log);
                jvmti->RawMonitorExit(g_lock);
                TerminateProcess(GetCurrentProcess(), 0);
            }
        }
    }

    free(moduleName);
    jvmti->Deallocate((unsigned char *)sig);
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    (void)vm;
    (void)reserved;
    if (vm->GetEnv((void **)&g_jvmti, JVMTI_VERSION_1_2) != JNI_OK) {
        return JNI_ERR;
    }

    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    if (g_jvmti->AddCapabilities(&caps) != JVMTI_ERROR_NONE) {
        return JNI_ERR;
    }

    jvmtiEventCallbacks cb;
    memset(&cb, 0, sizeof(cb));
    cb.ClassPrepare = &cbClassPrepare;
    if (g_jvmti->SetEventCallbacks(&cb, sizeof(cb)) != JVMTI_ERROR_NONE) {
        return JNI_ERR;
    }
    if (g_jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, NULL) != JVMTI_ERROR_NONE) {
        return JNI_ERR;
    }
    if (g_jvmti->CreateRawMonitor("clp_lock", &g_lock) != JVMTI_ERROR_NONE) {
        return JNI_ERR;
    }

    /* options: "logPath[,chain][,halt]" */
    const char *logPath = "E:\\Minecraft\\Reflection\\classload_order.log";
    if (options != NULL && options[0] != '\0') {
        char *buf = dupStr(options);
        if (buf != NULL) {
            char *tok = strtok(buf, ",");
            if (tok != NULL && tok[0] != '\0') {
                logPath = dupStr(tok);
            }
            while ((tok = strtok(NULL, ",")) != NULL) {
                if (strcmp(tok, "chain") == 0) g_chain = 1;
                else if (strcmp(tok, "halt") == 0) g_halt = 1;
                else if (strncmp(tok, "stop=", 5) == 0) g_stop = atoi(tok + 5);
            }
            free(buf);
        }
    }
    /* append: repeated launches must accumulate (8-10 runs per experiment) */
    g_log = fopen(logPath, "a");
    if (g_log == NULL) {
        return JNI_ERR;
    }
    time_t now = time(NULL);
    fprintf(g_log, "\n===== ClassLoadProbe run start %lld chain=%d halt=%d =====\n",
            (long long)now, g_chain, g_halt);
    fflush(g_log);
    return JNI_OK;
}
