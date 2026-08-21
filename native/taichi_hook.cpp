#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <psapi.h>
#include <tlhelp32.h>

#include "detours/src/detours.h"

static void probeFillModules(void);

#include <jni.h>


extern "C" __declspec(dllexport) jlong JNICALL RyjsAgentFamilyProbe(void) {
    return 0x52A95C0DEBEEF123LL;
}

static JavaVM* g_jvm = NULL;
static jclass g_bridgeClass = NULL;
static jmethodID g_beforeSwapMethod = NULL;

typedef void (__cdecl *PFN_glfwSwapBuffers)(void* window);
static PFN_glfwSwapBuffers g_realSwapBuffers = NULL;

typedef void (__cdecl *PFN_glfwPostEmptyEvent)(void);
static PFN_glfwPostEmptyEvent g_realGlfwPostEmptyEvent = NULL; // 唤醒渲染线程事件循环（强制重绘用）


static volatile LONG g_mouseEject = 0;

#define GLFW_CURSOR 0x00033001
#define GLFW_CURSOR_NORMAL 0x00034001
#define GLFW_CURSOR_DISABLED 0x00034003

typedef void (__cdecl *PFN_glfwSetInputMode)(void* window, int mode, int value);
static PFN_glfwSetInputMode g_realGlfwSetInputMode = NULL;
typedef void (__cdecl *PFN_glfwSetCursorPos)(void* window, double x, double y);
static PFN_glfwSetCursorPos g_realGlfwSetCursorPos = NULL;

static void __cdecl hookedGlfwSetInputMode(void* window, int mode, int value) {
    if (g_mouseEject && mode == GLFW_CURSOR && value == GLFW_CURSOR_DISABLED) {
        value = GLFW_CURSOR_NORMAL; // 锁光标请求 → 弹回正常（可见可动）
    }
    g_realGlfwSetInputMode(window, mode, value);
}

static void __cdecl hookedGlfwSetCursorPos(void* window, double x, double y) {
    if (g_mouseEject) return; // 不拉回中心——鼠标自由
    g_realGlfwSetCursorPos(window, x, y);
}


static volatile LONG* g_defenseShared = NULL;       // [0]=flags，[1]=forceRedraw，[2]=mouseEject，[3]=swapGuard，[4]=glClearReentry，[5]=fullRedraw，[6]=osBlockFlags
static LONG g_defenseLocal = 0;                     // fallback：映射失败（单实例场景）
static LONG g_forceRedrawLocal = 0;
static LONG g_mouseEjectLocal = 0;
static LONG g_swapGuardLocal = 0;
static LONG g_glClearReentryLocal = 0;
static LONG g_fullRedrawLocal = 0;
static LONG g_osFlagsLocal = 0;                     // OS 出口封锁标志：bit0=禁退出 bit1=禁进程创建 bit2=禁加载 DLL
static LONG g_armedLocal = 0;                        // hook 安装武装标志（共享：双实例去重——2026-08-17）
static BOOL g_sharedState = FALSE;                  // 共享映射是否生效


#define g_mouseEject (*(g_sharedState ? g_defenseShared + 2 : &g_mouseEjectLocal))

#define g_swapGuard (*(g_sharedState ? g_defenseShared + 3 : &g_swapGuardLocal))

#define g_glClearReentry (*(g_sharedState ? g_defenseShared + 4 : &g_glClearReentryLocal))

#define g_fullRedraw (*(g_sharedState ? g_defenseShared + 5 : &g_fullRedrawLocal))

#define g_osFlags (*(g_sharedState ? g_defenseShared + 6 : &g_osFlagsLocal))

#define g_armedFlag (*(g_sharedState ? g_defenseShared + 7 : &g_armedLocal))


static volatile LONG* g_bridgeShared = NULL;

static void initBridgeShared(void) {
    if (g_bridgeShared) return;
    HANDLE h = CreateFileMappingA(INVALID_HANDLE_VALUE, NULL, PAGE_READWRITE, 0, 32,
                                  "Local\\TaiChiDefenseBridge");
    if (h) {
        DWORD err = GetLastError();
        void* p = MapViewOfFile(h, FILE_MAP_ALL_ACCESS, 0, 0, 32);
        if (p) {
            g_bridgeShared = (volatile LONG*)p;
            if (err != ERROR_ALREADY_EXISTS) {
                for (int i = 0; i < 7; i++) g_bridgeShared[i] = 0;
                fprintf(stderr, "[taichi_hook] bridge shared CREATED (h=%p view=%p)\n", (void*)h, p);
            } else {
                fprintf(stderr, "[taichi_hook] bridge shared OPENED (h=%p view=%p)\n", (void*)h, p);
            }
        } else {
            fprintf(stderr, "[taichi_hook] bridge shared MapViewOfFile FAILED (err=%lu)\n", GetLastError());
        }
    } else {
        fprintf(stderr, "[taichi_hook] bridge shared CreateFileMapping FAILED (err=%lu)\n", GetLastError());
    }
}

static void bridgePublish(jclass bridge, jmethodID beforeSwap, jmethodID glStackCheck) {
    if (!g_bridgeShared) return;
    uintptr_t a = (uintptr_t)bridge, b = (uintptr_t)beforeSwap, c = (uintptr_t)glStackCheck;
    g_bridgeShared[0] = (LONG)(a & 0xFFFFFFFF); g_bridgeShared[1] = (LONG)(a >> 32);
    g_bridgeShared[2] = (LONG)(b & 0xFFFFFFFF); g_bridgeShared[3] = (LONG)(b >> 32);
    g_bridgeShared[4] = (LONG)(c & 0xFFFFFFFF); g_bridgeShared[5] = (LONG)(c >> 32);
    g_bridgeShared[6] = 1; // ready 最后写（x86 TSO：读者先读 ready 再读数据，写序保证）
    fprintf(stderr, "[taichi_hook] beforeSwap callback published to shared bridge"
            " (bridge=0x%llX mid=0x%llX shared=%p [0..6]=%08X,%08X,%08X,%08X,%08X,%08X,%d)\n",
            (unsigned long long)a, (unsigned long long)b, (void*)g_bridgeShared,
            (unsigned)g_bridgeShared[0], (unsigned)g_bridgeShared[1],
            (unsigned)g_bridgeShared[2], (unsigned)g_bridgeShared[3],
            (unsigned)g_bridgeShared[4], (unsigned)g_bridgeShared[5], (int)g_bridgeShared[6]);

    {
        volatile LONG* sh = g_bridgeShared;
        CreateThread(NULL, 0, [](LPVOID p) -> DWORD {
            volatile LONG* s = (volatile LONG*)p;
            Sleep(300);
            fprintf(stderr, "[taichi_hook] bridge KEEP+300ms [0..6]=%08X,%08X,%08X,%08X,%08X,%08X,%d\n",
                    (unsigned)s[0], (unsigned)s[1], (unsigned)s[2], (unsigned)s[3],
                    (unsigned)s[4], (unsigned)s[5], (int)s[6]);
            Sleep(300);
            fprintf(stderr, "[taichi_hook] bridge KEEP+600ms [0..6]=%08X,%08X,%08X,%08X,%08X,%08X,%d\n",
                    (unsigned)s[0], (unsigned)s[1], (unsigned)s[2], (unsigned)s[3],
                    (unsigned)s[4], (unsigned)s[5], (int)s[6]);
            return 0;
        }, (LPVOID)sh, 0, NULL);
    }
}

static int bridgeRead(jclass* outBridge, jmethodID* outBeforeSwap, jmethodID* outStack) {
    if (!g_bridgeShared || !g_bridgeShared[6]) return 0;
    jclass bridge = (jclass)(uintptr_t)(((uintptr_t)(LONG)g_bridgeShared[1] << 32) | (LONG)g_bridgeShared[0]);
    jmethodID beforeSwap = (jmethodID)(uintptr_t)(((uintptr_t)(LONG)g_bridgeShared[3] << 32) | (LONG)g_bridgeShared[2]);
    jmethodID stack = (jmethodID)(uintptr_t)(((uintptr_t)(LONG)g_bridgeShared[5] << 32) | (LONG)g_bridgeShared[4]);

    if ((uintptr_t)bridge < 0x10000 || ((uintptr_t)bridge >> 48) == 0xFFFF) return 0;
    if ((uintptr_t)beforeSwap < 0x10000 || ((uintptr_t)beforeSwap >> 48) == 0xFFFF) return 0;
    *outBridge = bridge;
    *outBeforeSwap = beforeSwap;
    *outStack = stack;
    return 1;
}


extern "C" {
__declspec(dllexport) jclass taichiGetBridgeClass(void) { return g_bridgeClass; }
__declspec(dllexport) jmethodID taichiGetBeforeSwapMid(void) { return g_beforeSwapMethod; }
}


static int bridgeFromOtherInstance(jclass* outBridge, jmethodID* outBeforeSwap, jmethodID* outStack) {
    HMODULE mods[512];
    DWORD needed = 0;
    if (!EnumProcessModules(GetCurrentProcess(), mods, sizeof(mods), &needed)) return 0;
    int count = (int)(needed / sizeof(HMODULE));
    if (count > 512) count = 512;
    HMODULE self = NULL;
    GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                           | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       (LPCSTR)(uintptr_t)&taichiGetBridgeClass, &self);
    for (int i = 0; i < count; i++) {
        if (mods[i] == self || mods[i] == NULL) continue;
        char name[MAX_PATH] = {0};
        if (GetModuleBaseNameA(GetCurrentProcess(), mods[i], name, MAX_PATH) <= 0) continue;
        for (char* q = name; *q; q++) *q = (char)tolower((unsigned char)*q);
        if (strstr(name, "taichi_hook") == NULL) continue;
        typedef jclass (__cdecl *PFN_GC)(void);
        typedef jmethodID (__cdecl *PFN_GM)(void);
        PFN_GC gc = (PFN_GC)GetProcAddress(mods[i], "taichiGetBridgeClass");
        PFN_GM gm = (PFN_GM)GetProcAddress(mods[i], "taichiGetBeforeSwapMid");
        PFN_GM gs = (PFN_GM)GetProcAddress(mods[i], "taichiGetStackCheckMid");
        if (gc == NULL || gm == NULL) continue;
        jclass b = gc();
        jmethodID m = gm();
        jmethodID s = gs ? gs() : NULL;
        if (b == NULL || m == NULL) continue;
        if ((uintptr_t)b < 0x10000 || ((uintptr_t)b >> 48) == 0xFFFF) continue;
        if ((uintptr_t)m < 0x10000 || ((uintptr_t)m >> 48) == 0xFFFF) continue;
        *outBridge = b;
        *outBeforeSwap = m;
        *outStack = s;
        fprintf(stderr, "[taichi_hook] bridge from other instance %s (bridge=%p mid=%p)\n", name, (void*)b, (void*)m);
        return 1;
    }
    return 0;
}


static int isOsLoadWhitelisted(const wchar_t* path);


static volatile LONG g_forceRedrawRequested = 0;

#define g_defenseFlags      (*(g_defenseShared ? g_defenseShared : &g_defenseLocal))
#define g_forceRedrawEnabled (*(g_sharedState ? g_defenseShared + 1 : &g_forceRedrawLocal))


static void initSharedState(void) {
    if (g_defenseShared) return;
    HANDLE h = CreateFileMappingA(INVALID_HANDLE_VALUE, NULL, PAGE_READWRITE, 0, 64,
                                  "Local\\TaiChiDefenseState");
    if (h) {
        DWORD err = GetLastError();
        void* p = MapViewOfFile(h, FILE_MAP_ALL_ACCESS, 0, 0, 64);
        if (p) {
            g_defenseShared = (volatile LONG*)p;
            if (err != ERROR_ALREADY_EXISTS) {
                g_defenseShared[0] = 0;
                g_defenseShared[1] = 0;
                g_defenseShared[2] = 0;
                g_defenseShared[3] = 0;
                g_defenseShared[4] = 0;
                g_defenseShared[5] = 0;
                g_defenseShared[6] = 0;
                g_defenseShared[7] = 0; // armed 标志
            }
            g_sharedState = TRUE;
        }
    }
    if (!g_defenseShared) {
        g_defenseShared = &g_defenseLocal;
    }
}

static HWND g_mcHwndCached = NULL;   // MC 主窗口缓存（findMcWindow 首次命中后复用）

static inline BOOL protectOn(void) { return (g_defenseFlags & 1) != 0; }
static inline BOOL maxOn(void)     { return (g_defenseFlags & 2) != 0; }


typedef unsigned int   GLenum;
typedef unsigned int   GLbitfield;
typedef int            GLsizei;
typedef int            GLint;
typedef unsigned int   GLuint;
typedef float          GLclampf;
typedef float          GLfloat;
typedef unsigned char  GLubyte;

typedef void (APIENTRY *PFN_glDrawElements)(GLenum mode, GLsizei count, GLenum type, const void* indices);
typedef void (APIENTRY *PFN_glDrawArrays)(GLenum mode, GLint first, GLsizei count);
typedef void (APIENTRY *PFN_glClear)(GLbitfield mask);
typedef void (APIENTRY *PFN_glClearColor)(GLclampf r, GLclampf g, GLclampf b, GLclampf a);

typedef void (APIENTRY *PFN_glDrawPixels)(GLsizei width, GLsizei height, GLenum format, GLenum type, const void* pixels);
typedef void (APIENTRY *PFN_glBegin)(GLenum mode);
typedef void (APIENTRY *PFN_glBitmap)(GLsizei width, GLsizei height, GLfloat xorig, GLfloat yorig, GLfloat xmove, GLfloat ymove, const GLubyte* bitmap);
typedef void (APIENTRY *PFN_glCopyPixels)(GLint x, GLint y, GLsizei width, GLsizei height, GLenum type);
typedef void (APIENTRY *PFN_glDrawRangeElements)(GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const void* indices);
typedef BOOL (WINAPI  *PFN_wglSwapBuffers)(HDC hdc);

typedef void (APIENTRY *PFN_glDrawArraysInstanced)(GLenum, GLint, GLsizei, GLsizei);
typedef void (APIENTRY *PFN_glDrawElementsInstanced)(GLenum, GLsizei, GLenum, const void*, GLsizei);
typedef void (APIENTRY *PFN_glDrawElementsInstancedBaseVertex)(GLenum, GLsizei, GLenum, const void*, GLsizei, GLint);
typedef void (APIENTRY *PFN_glMultiDrawArrays)(GLenum, const GLint*, const GLsizei*, GLsizei);
typedef void (APIENTRY *PFN_glMultiDrawElements)(GLenum, const GLsizei*, GLenum, const void* const*, GLsizei);
typedef void (APIENTRY *PFN_glDrawArraysIndirect)(GLenum, const void*);
typedef void (APIENTRY *PFN_glDrawElementsIndirect)(GLenum, GLenum, const void*);
typedef void (APIENTRY *PFN_glDrawElementsBaseVertex)(GLenum, GLsizei, GLenum, const void*, GLint);
typedef void (APIENTRY *PFN_glMultiDrawElementsBaseVertex)(GLenum, const GLsizei*, GLenum, const void* const*, GLsizei, const GLint*);
typedef void (APIENTRY *PFN_glDrawArraysInstancedBaseInstance)(GLenum, GLint, GLsizei, GLsizei, GLuint);
typedef void (APIENTRY *PFN_glDrawElementsInstancedBaseInstance)(GLenum, GLsizei, GLenum, const void*, GLsizei, GLuint);
typedef void (APIENTRY *PFN_glDrawElementsInstancedBaseVertexBaseInstance)(GLenum, GLsizei, GLenum, const void*, GLsizei, GLint, GLuint);
typedef void (APIENTRY *PFN_glMultiDrawArraysIndirect)(GLenum, const void*, GLsizei, GLsizei);
typedef void (APIENTRY *PFN_glMultiDrawElementsIndirect)(GLenum, GLenum, const void*, GLsizei, GLsizei);
typedef void (APIENTRY *PFN_glDrawTransformFeedback)(GLenum, GLuint);
typedef void (APIENTRY *PFN_glDrawTransformFeedbackStream)(GLenum, GLuint, GLuint);
typedef void (APIENTRY *PFN_glDrawTransformFeedbackInstanced)(GLenum, GLuint, GLsizei);
typedef void (APIENTRY *PFN_glDrawTransformFeedbackStreamInstanced)(GLenum, GLuint, GLuint, GLsizei);
typedef void (APIENTRY *PFN_glBlitFramebuffer)(GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum);
typedef void (APIENTRY *PFN_glBlitNamedFramebuffer)(GLuint, GLuint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum);
typedef void (APIENTRY *PFN_glClearBufferfv)(GLenum, GLint, const GLfloat*);
typedef void (APIENTRY *PFN_glClearBufferiv)(GLenum, GLint, const GLint*);
typedef void (APIENTRY *PFN_glClearBufferuiv)(GLenum, GLint, const GLuint*);
typedef void (APIENTRY *PFN_glClearBufferfi)(GLenum, GLint, GLfloat, GLint);
typedef BOOL (WINAPI  *PFN_wglSwapLayerBuffers)(HDC hdc, UINT planes);
typedef BOOL (WINAPI  *PFN_wglMakeCurrent)(HDC hdc, HGLRC hglrc);

static PFN_glDrawElements g_realGlDrawElements = NULL;
static PFN_glDrawArrays   g_realGlDrawArrays = NULL;
static PFN_glClear        g_realGlClear = NULL;
static PFN_glClearColor   g_realGlClearColor = NULL;
static PFN_glDrawPixels   g_realGlDrawPixels = NULL;
static PFN_glBegin        g_realGlBegin = NULL;
static PFN_glBitmap       g_realGlBitmap = NULL;
static PFN_glCopyPixels   g_realGlCopyPixels = NULL;
static PFN_glDrawRangeElements g_realGlDrawRangeElements = NULL;
static PFN_wglSwapBuffers g_realWglSwapBuffers = NULL;
static PFN_glDrawArraysInstanced g_realGlDrawArraysInstanced = NULL;
static PFN_glDrawElementsInstanced g_realGlDrawElementsInstanced = NULL;
static PFN_glDrawElementsInstancedBaseVertex g_realGlDrawElementsInstancedBaseVertex = NULL;
static PFN_glMultiDrawArrays   g_realGlMultiDrawArrays = NULL;
static PFN_glMultiDrawElements g_realGlMultiDrawElements = NULL;
static PFN_glDrawArraysIndirect g_realGlDrawArraysIndirect = NULL;
static PFN_glDrawElementsIndirect g_realGlDrawElementsIndirect = NULL;
static PFN_glDrawElementsBaseVertex g_realGlDrawElementsBaseVertex = NULL;
static PFN_glMultiDrawElementsBaseVertex g_realGlMultiDrawElementsBaseVertex = NULL;
static PFN_glDrawArraysInstancedBaseInstance g_realGlDrawArraysInstancedBaseInstance = NULL;
static PFN_glDrawElementsInstancedBaseInstance g_realGlDrawElementsInstancedBaseInstance = NULL;
static PFN_glDrawElementsInstancedBaseVertexBaseInstance g_realGlDrawElementsInstancedBaseVertexBaseInstance = NULL;
static PFN_glMultiDrawArraysIndirect g_realGlMultiDrawArraysIndirect = NULL;
static PFN_glMultiDrawElementsIndirect g_realGlMultiDrawElementsIndirect = NULL;
static PFN_glDrawTransformFeedback g_realGlDrawTransformFeedback = NULL;
static PFN_glDrawTransformFeedbackStream g_realGlDrawTransformFeedbackStream = NULL;
static PFN_glDrawTransformFeedbackInstanced g_realGlDrawTransformFeedbackInstanced = NULL;
static PFN_glDrawTransformFeedbackStreamInstanced g_realGlDrawTransformFeedbackStreamInstanced = NULL;
static PFN_glBlitFramebuffer g_realGlBlitFramebuffer = NULL;
static PFN_glBlitNamedFramebuffer g_realGlBlitNamedFramebuffer = NULL;
static PFN_glClearBufferfv g_realGlClearBufferfv = NULL;
static PFN_glClearBufferiv g_realGlClearBufferiv = NULL;
static PFN_glClearBufferuiv g_realGlClearBufferuiv = NULL;
static PFN_glClearBufferfi g_realGlClearBufferfi = NULL;
static PFN_wglSwapLayerBuffers g_realWglSwapLayerBuffers = NULL;
static PFN_wglMakeCurrent      g_realWglMakeCurrent = NULL;


typedef BOOL (WINAPI *PFN_ExtTextOutW)(HDC, int, int, UINT, const RECT*, LPCWSTR, UINT, const INT*);
typedef BOOL (WINAPI *PFN_ExtTextOutA)(HDC, int, int, UINT, const RECT*, LPCSTR, UINT, const INT*);
typedef BOOL (WINAPI *PFN_TextOutW)(HDC, int, int, LPCWSTR, int);
typedef BOOL (WINAPI *PFN_TextOutA)(HDC, int, int, LPCSTR, int);
typedef BOOL (WINAPI *PFN_DrawTextW)(HDC, LPCWSTR, int, RECT*, UINT);
typedef BOOL (WINAPI *PFN_DrawTextA)(HDC, LPCSTR, int, RECT*, UINT);
typedef BOOL (WINAPI *PFN_SetPixelV)(HDC, int, int, COLORREF);
typedef BOOL (WINAPI *PFN_BitBlt)(HDC, int, int, int, int, HDC, int, int, DWORD);
typedef BOOL (WINAPI *PFN_StretchBlt)(HDC, int, int, int, int, HDC, int, int, int, int, DWORD);
typedef BOOL (WINAPI *PFN_AlphaBlend)(HDC, int, int, int, int, HDC, int, int, int, int, BLENDFUNCTION);
typedef BOOL (WINAPI *PFN_TransparentBlt)(HDC, int, int, int, int, HDC, int, int, int, int, UINT);
typedef BOOL (WINAPI *PFN_PatBlt)(HDC, int, int, int, int, DWORD);
typedef BOOL (WINAPI *PFN_FillRect)(HDC, const RECT*, HBRUSH);
typedef int  (WINAPI *PFN_SetDIBitsToDevice)(HDC, int, int, DWORD, DWORD, int, int, UINT, UINT, const void*, const BITMAPINFO*, UINT);
typedef int  (WINAPI *PFN_StretchDIBits)(HDC, int, int, int, int, int, int, int, int, const void*, const BITMAPINFO*, UINT, DWORD);
typedef BOOL (WINAPI *PFN_PlayEnhMetaFile)(HDC, HENHMETAFILE, const RECT*);

typedef BOOL (WINAPI *PFN_Rectangle)(HDC, int, int, int, int);
typedef BOOL (WINAPI *PFN_Ellipse)(HDC, int, int, int, int);
typedef BOOL (WINAPI *PFN_RoundRect)(HDC, int, int, int, int, int, int);
typedef BOOL (WINAPI *PFN_LineTo)(HDC, int, int);
typedef BOOL (WINAPI *PFN_Polyline)(HDC, const POINT*, int);
typedef BOOL (WINAPI *PFN_Polygon)(HDC, const POINT*, int);
typedef BOOL (WINAPI *PFN_PolyPolygon)(HDC, const POINT*, const int*, int);
typedef BOOL (WINAPI *PFN_FillRgn)(HDC, HRGN, HBRUSH);
typedef BOOL (WINAPI *PFN_FrameRgn)(HDC, HRGN, HBRUSH, int, int);
typedef BOOL (WINAPI *PFN_InvertRgn)(HDC, HRGN);
typedef BOOL (WINAPI *PFN_PaintRgn)(HDC, HRGN);
typedef BOOL (WINAPI *PFN_Arc)(HDC, int, int, int, int, int, int, int, int);
typedef BOOL (WINAPI *PFN_Chord)(HDC, int, int, int, int, int, int, int, int);
typedef BOOL (WINAPI *PFN_Pie)(HDC, int, int, int, int, int, int, int, int);
typedef BOOL (WINAPI *PFN_ExtFloodFill)(HDC, int, int, COLORREF, UINT);
typedef BOOL (WINAPI *PFN_PolyBezier)(HDC, const POINT*, DWORD);

typedef BOOL (WINAPI *PFN_FloodFill)(HDC, int, int, COLORREF);
typedef BOOL (WINAPI *PFN_PolylineTo)(HDC, const POINT*, DWORD);
typedef BOOL (WINAPI *PFN_PolyPolyline)(HDC, const POINT*, const DWORD*, DWORD);
typedef BOOL (WINAPI *PFN_PolyBezierTo)(HDC, const POINT*, DWORD);
typedef BOOL (WINAPI *PFN_PolyDraw)(HDC, const POINT*, const BYTE*, int);
typedef BOOL (WINAPI *PFN_InvertRect)(HDC, const RECT*);
typedef BOOL (WINAPI *PFN_FillPath)(HDC);
typedef BOOL (WINAPI *PFN_StrokePath)(HDC);
typedef BOOL (WINAPI *PFN_StrokeAndFillPath)(HDC);
typedef BOOL (WINAPI *PFN_GradientFill)(HDC, void*, ULONG, void*, ULONG, ULONG);
typedef BOOL (WINAPI *PFN_AngleArc)(HDC, int, int, DWORD, FLOAT, FLOAT);
typedef COLORREF (WINAPI *PFN_SetPixel)(HDC, int, int, COLORREF);
typedef int (WINAPI *PFN_FrameRect)(HDC, const RECT*, HBRUSH);
typedef LONG (WINAPI *PFN_TabbedTextOutW)(HDC, int, int, LPCWSTR, int, int, const INT*, int);
typedef LONG (WINAPI *PFN_TabbedTextOutA)(HDC, int, int, LPCSTR, int, int, const INT*, int);
typedef BOOL (WINAPI *PFN_DrawIcon)(HDC, int, int, HICON);
typedef BOOL (WINAPI *PFN_DrawIconEx)(HDC, int, int, HICON, int, int, UINT, HBRUSH, UINT);
typedef BOOL (WINAPI *PFN_DrawState)(HDC, HBRUSH, void*, LPARAM, WPARAM, int, int, int, int, UINT);
typedef BOOL (WINAPI *PFN_DrawEdge)(HDC, LPRECT, UINT, UINT);
typedef BOOL (WINAPI *PFN_DrawFrameControl)(HDC, LPRECT, UINT, UINT);
typedef BOOL (WINAPI *PFN_GrayString)(HDC, HBRUSH, void*, LPARAM, int, int, int, int, int, int);
typedef BOOL (WINAPI *PFN_PaintDesktop)(HDC);

typedef HDC (WINAPI *PFN_GetDC)(HWND);
typedef HDC (WINAPI *PFN_CreateDCA)(LPCSTR, LPCSTR, LPCSTR, const void*);
typedef HDC (WINAPI *PFN_CreateDCW)(LPCWSTR, LPCWSTR, LPCWSTR, const void*);

typedef BOOL (WINAPI *PFN_UpdateLayeredWindow)(HWND, HDC, const POINT*, const SIZE*, HDC, const POINT*, COLORREF, const BLENDFUNCTION*, DWORD);

static PFN_ExtTextOutW g_realExtTextOutW = NULL;
static PFN_ExtTextOutA g_realExtTextOutA = NULL;
static PFN_TextOutW    g_realTextOutW = NULL;
static PFN_TextOutA    g_realTextOutA = NULL;
static PFN_DrawTextW   g_realDrawTextW = NULL;
static PFN_DrawTextA   g_realDrawTextA = NULL;
static PFN_SetPixelV   g_realSetPixelV = NULL;
static PFN_BitBlt      g_realBitBlt = NULL;
static PFN_StretchBlt  g_realStretchBlt = NULL;
static PFN_AlphaBlend  g_realAlphaBlend = NULL;
static PFN_TransparentBlt g_realTransparentBlt = NULL;
static PFN_PatBlt      g_realPatBlt = NULL;
static PFN_FillRect    g_realFillRect = NULL;
static PFN_SetDIBitsToDevice g_realSetDIBitsToDevice = NULL;
static PFN_StretchDIBits     g_realStretchDIBits = NULL;
static PFN_PlayEnhMetaFile   g_realPlayEnhMetaFile = NULL;
static PFN_Rectangle  g_realRectangle = NULL;
static PFN_Ellipse    g_realEllipse = NULL;
static PFN_RoundRect  g_realRoundRect = NULL;
static PFN_LineTo     g_realLineTo = NULL;
static PFN_Polyline   g_realPolyline = NULL;
static PFN_Polygon    g_realPolygon = NULL;
static PFN_PolyPolygon g_realPolyPolygon = NULL;
static PFN_FillRgn    g_realFillRgn = NULL;
static PFN_FrameRgn   g_realFrameRgn = NULL;
static PFN_InvertRgn  g_realInvertRgn = NULL;
static PFN_PaintRgn   g_realPaintRgn = NULL;
static PFN_Arc        g_realArc = NULL;
static PFN_Chord      g_realChord = NULL;
static PFN_Pie        g_realPie = NULL;
static PFN_ExtFloodFill g_realExtFloodFill = NULL;
static PFN_PolyBezier g_realPolyBezier = NULL;
static PFN_FloodFill     g_realFloodFill = NULL;
static PFN_PolylineTo    g_realPolylineTo = NULL;
static PFN_PolyPolyline  g_realPolyPolyline = NULL;
static PFN_PolyBezierTo  g_realPolyBezierTo = NULL;
static PFN_PolyDraw      g_realPolyDraw = NULL;
static PFN_InvertRect    g_realInvertRect = NULL;
static PFN_FillPath      g_realFillPath = NULL;
static PFN_StrokePath    g_realStrokePath = NULL;
static PFN_StrokeAndFillPath g_realStrokeAndFillPath = NULL;
static PFN_GradientFill  g_realGradientFill = NULL;
static PFN_AngleArc      g_realAngleArc = NULL;
static PFN_SetPixel      g_realSetPixel = NULL;
static PFN_FrameRect     g_realFrameRect = NULL;
static PFN_TabbedTextOutW g_realTabbedTextOutW = NULL;
static PFN_TabbedTextOutA g_realTabbedTextOutA = NULL;
static PFN_DrawIcon      g_realDrawIcon = NULL;
static PFN_DrawIconEx    g_realDrawIconEx = NULL;
static PFN_DrawState     g_realDrawState = NULL;
static PFN_DrawEdge      g_realDrawEdge = NULL;
static PFN_DrawFrameControl g_realDrawFrameControl = NULL;
static PFN_GrayString    g_realGrayString = NULL;
static PFN_PaintDesktop  g_realPaintDesktop = NULL;
static PFN_GetDC         g_realGetDC = NULL;
static PFN_CreateDCA     g_realCreateDCA = NULL;
static PFN_CreateDCW     g_realCreateDCW = NULL;
static PFN_UpdateLayeredWindow g_realUpdateLayeredWindow = NULL;


typedef HRESULT (WINAPI *PFN_D3D11CreateDeviceAndSwapChain)(
    void* pAdapter, UINT DriverType, HMODULE Software, UINT Flags,
    const void* pFeatureLevels, UINT FeatureLevels, UINT SDKVersion,
    const void* pSwapChainDesc, void** ppSwapChain, void** ppDevice,
    void* pFeatureLevel, void** ppImmediateContext);
static PFN_D3D11CreateDeviceAndSwapChain g_realD3D11CreateDeviceAndSwapChain = NULL;


#define CREATE_SWAP_CHAIN_FOR_HWND_SLOT 15
typedef HRESULT (WINAPI *PFN_CreateDXGIFactory)(REFIID riid, void** ppFactory);
typedef HRESULT (WINAPI *PFN_CreateDXGIFactory1)(REFIID riid, void** ppFactory);
static PFN_CreateDXGIFactory  g_realCreateDXGIFactory = NULL;
static PFN_CreateDXGIFactory1 g_realCreateDXGIFactory1 = NULL;
static void* g_origSwapChainForHwnd = NULL;


#define MAX_HOOK_SNAPSHOTS 20
static void*     g_hookEntries[MAX_HOOK_SNAPSHOTS];
static unsigned char g_hookSnapshots[MAX_HOOK_SNAPSHOTS][16];
static void**    g_hookTrampolines[MAX_HOOK_SNAPSHOTS];
static void*     g_hookFunctions[MAX_HOOK_SNAPSHOTS];
static int       g_hookCount = 0;

static void captureHook(void* entry, void** trampoline, void* hooked) {
    if (!entry || g_hookCount >= MAX_HOOK_SNAPSHOTS) return;
    g_hookEntries[g_hookCount] = entry;
    memcpy(g_hookSnapshots[g_hookCount], entry, 16);
    g_hookTrampolines[g_hookCount] = trampoline;
    g_hookFunctions[g_hookCount] = hooked;
    g_hookCount++;
}

static void verifyHooks(void) {
    for (int i = 0; i < g_hookCount; i++) {
        if (memcmp(g_hookEntries[i], g_hookSnapshots[i], 16) != 0) {
            fprintf(stderr, "[taichi_hook] HOOK TAMPERED: entry #%d modified; re-attaching...\n", i);
            DetourTransactionBegin();
            DetourUpdateThread(GetCurrentThread());
            DetourAttach(g_hookTrampolines[i], g_hookFunctions[i]);
            DetourTransactionCommit();
            memcpy(g_hookSnapshots[i], g_hookEntries[i], 16);
        }
    }
}


typedef HMODULE (WINAPI *PFN_LoadLibraryExW)(LPCWSTR, HANDLE, DWORD);
typedef HMODULE (WINAPI *PFN_LoadLibraryW)(LPCWSTR);
typedef HMODULE (WINAPI *PFN_LoadLibraryExA)(LPCSTR, HANDLE, DWORD);
typedef HMODULE (WINAPI *PFN_LoadLibraryA)(LPCSTR);
static PFN_LoadLibraryExW g_realLoadLibraryExW = NULL;
static PFN_LoadLibraryW   g_realLoadLibraryW = NULL;
static PFN_LoadLibraryExA g_realLoadLibraryExA = NULL;
static PFN_LoadLibraryA   g_realLoadLibraryA = NULL;

static int isRenderLibName(const wchar_t* name) {
    if (!name) return 0;
    wchar_t lower[64];
    int n = 0;
    for (; name[n] && n < 63; n++) lower[n] = (wchar_t)towlower(name[n]);
    lower[n] = 0;
    return wcsstr(lower, L"d3d11.dll") || wcsstr(lower, L"dxgi.dll")
        || wcsstr(lower, L"opengl32.dll") || wcsstr(lower, L"gdi32.dll");
}


static int isMaxForbiddenLib(const wchar_t* name) {
    if (!name) return 0;
    wchar_t lower[64];
    int n = 0;
    for (; name[n] && n < 63; n++) lower[n] = (wchar_t)towlower(name[n]);
    lower[n] = 0;
    return wcsstr(lower, L"vulkan-1.dll") || wcsstr(lower, L"ddraw.dll")
        || wcsstr(lower, L"d3d10.dll") || wcsstr(lower, L"d2d1.dll") || wcsstr(lower, L"dwrite.dll");
}

static int isOutsideSystem(const wchar_t* path) {
    if (!path) return 0;
    if (!wcsstr(path, L"\\") && !wcsstr(path, L":")) return 0; // 纯文件名——系统搜索路径解析——不警告
    wchar_t sysDir[MAX_PATH];
    GetSystemDirectoryW(sysDir, MAX_PATH);
    return wcsnicmp(path, sysDir, wcslen(sysDir)) != 0;
}

static HMODULE WINAPI hookedLoadLibraryExW(LPCWSTR lpFileName, HANDLE hFile, DWORD dwFlags) {
    if ((g_osFlags & 4) && !isOsLoadWhitelisted(lpFileName)) {
        fprintf(stderr, "[taichi_hook] OS BLOCK: LoadLibraryExW %ls blocked (whitelist mode)\n", lpFileName ? lpFileName : L"<null>");
        return NULL;
    }
    if (maxOn() && isMaxForbiddenLib(lpFileName)) {
        fprintf(stderr, "[taichi_hook] MAX BLOCKED LoadLibraryExW: %ls\n", lpFileName);
        return NULL;
    }
    if (isRenderLibName(lpFileName) && isOutsideSystem(lpFileName)) {
        fprintf(stderr, "[taichi_hook] WARNING: render lib loaded outside System32: %ls\n", lpFileName);
    }
    return g_realLoadLibraryExW(lpFileName, hFile, dwFlags);
}

static HMODULE WINAPI hookedLoadLibraryW(LPCWSTR lpFileName) {
    if ((g_osFlags & 4) && !isOsLoadWhitelisted(lpFileName)) {
        fprintf(stderr, "[taichi_hook] OS BLOCK: LoadLibraryW %ls blocked (whitelist mode)\n", lpFileName ? lpFileName : L"<null>");
        return NULL;
    }
    if (maxOn() && isMaxForbiddenLib(lpFileName)) {
        fprintf(stderr, "[taichi_hook] MAX BLOCKED LoadLibraryW: %ls\n", lpFileName);
        return NULL;
    }
    if (isRenderLibName(lpFileName) && isOutsideSystem(lpFileName)) {
        fprintf(stderr, "[taichi_hook] WARNING: render lib loaded outside System32: %ls\n", lpFileName);
    }
    return g_realLoadLibraryW(lpFileName);
}


static HMODULE WINAPI hookedLoadLibraryExA(LPCSTR lpFileName, HANDLE hFile, DWORD dwFlags) {
    if ((g_osFlags & 4) && lpFileName) {
        wchar_t wide[260];
        if (MultiByteToWideChar(CP_ACP, 0, lpFileName, -1, wide, 260) > 0 && !isOsLoadWhitelisted(wide)) {
            fprintf(stderr, "[taichi_hook] OS BLOCK: LoadLibraryExA %s blocked (whitelist mode)\n", lpFileName);
            return NULL;
        }
    }
    if (maxOn() && lpFileName) {
        wchar_t wide[260];
        if (MultiByteToWideChar(CP_ACP, 0, lpFileName, -1, wide, 260) > 0 && isMaxForbiddenLib(wide)) {
            fprintf(stderr, "[taichi_hook] MAX BLOCKED LoadLibraryExA: %s\n", lpFileName);
            return NULL;
        }
    }
    return g_realLoadLibraryExA(lpFileName, hFile, dwFlags);
}

static HMODULE WINAPI hookedLoadLibraryA(LPCSTR lpFileName) {
    if ((g_osFlags & 4) && lpFileName) {
        wchar_t wide[260];
        if (MultiByteToWideChar(CP_ACP, 0, lpFileName, -1, wide, 260) > 0 && !isOsLoadWhitelisted(wide)) {
            fprintf(stderr, "[taichi_hook] OS BLOCK: LoadLibraryA %s blocked (whitelist mode)\n", lpFileName);
            return NULL;
        }
    }
    if (maxOn() && lpFileName) {
        wchar_t wide[260];
        if (MultiByteToWideChar(CP_ACP, 0, lpFileName, -1, wide, 260) > 0 && isMaxForbiddenLib(wide)) {
            fprintf(stderr, "[taichi_hook] MAX BLOCKED LoadLibraryA: %s\n", lpFileName);
            return NULL;
        }
    }
    return g_realLoadLibraryA(lpFileName);
}

static HWND    g_overlayWnd = NULL;
static HANDLE  g_overlayThread = NULL;
static DWORD   g_overlayThreadId = 0;
static volatile LONG g_overlayVisible = 0;
static HWND    g_mcHwnd = NULL;

// 当前帧像素缓冲（由 JNI push 写入，窗口线程读）
static CRITICAL_SECTION g_frameLock;
static int*    g_frameBits = NULL;       // 0xAARRGGBB，自上而下
static int     g_frameW = 0;
static int     g_frameH = 0;
static volatile LONG g_frameDirty = 0;
static BOOL    g_lockInit = FALSE;

static volatile LONG g_winX = 0;
static volatile LONG g_winY = 0;



static int g_beforeSwapRetry = 0; // 共享回调就绪前静默限频

static void callJavaBeforeSwap(void) {
    if (!g_jvm) return;
    jclass bridge = g_bridgeClass;
    jmethodID mid = g_beforeSwapMethod;
    if (!bridge || !mid) {
        // 双实例：武装实例从未被 nativeBind——优先走"跨实例导出通道"（进程内互调，零共享内存依赖；
        // 2026-08-17 实测共享映射读侧不一致）。FindClass 补注册跨 ClassLoader 失效，弃用。
        jmethodID stack = NULL;
        if (!bridgeFromOtherInstance(&bridge, &mid, &stack)) {
            if (bridgeRead(&bridge, &mid, &stack) == 0) {
                if (++g_beforeSwapRetry >= 600) {
                    g_beforeSwapRetry = 0;
                    fprintf(stderr, "[taichi_hook] beforeSwap bridge unavailable (export channel + shared both failed)\n");
                }
                return;
            }
        }
        // 缓存到本地（一次拿到后不再依赖外部——后续 swap 直接用本地）
        if (bridge != NULL && mid != NULL) {
            g_bridgeClass = bridge;
            g_beforeSwapMethod = mid;
            fprintf(stderr, "[taichi_hook] beforeSwap callback cached (bridge=%p mid=%p)\n",
                    (void*)bridge, (void*)mid);
        }
    }
    // 最终防线：指针异常绝不进 JNI（防 0xc0000005——2026-08-17 实测）
    if (bridge == NULL || mid == NULL
            || (uintptr_t)bridge < 0x10000 || ((uintptr_t)bridge >> 48) == 0xFFFF
            || (uintptr_t)mid < 0x10000 || ((uintptr_t)mid >> 48) == 0xFFFF) {
        if (++g_beforeSwapRetry >= 600) {
            g_beforeSwapRetry = 0;
            fprintf(stderr, "[taichi_hook] CJBS: final-guard rejected bridge=%p mid=%p (local bridgeClass=%p beforeSwapMid=%p)\n",
                    (void*)bridge, (void*)mid, (void*)g_bridgeClass, (void*)g_beforeSwapMethod);
        }
        return;
    }

    JNIEnv* env = NULL;
    int needDetach = 0;
    jint status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_8);

    if (status == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread((void**)&env, NULL) != JNI_OK) {
            if (++g_beforeSwapRetry >= 600) {
                g_beforeSwapRetry = 0;
                fprintf(stderr, "[taichi_hook] CJBS: attach failed\n");
            }
            return;
        }
        needDetach = 1;
    } else if (status != JNI_OK) {
        if (++g_beforeSwapRetry >= 600) {
            g_beforeSwapRetry = 0;
            fprintf(stderr, "[taichi_hook] CJBS: GetEnv status=%d\n", (int)status);
        }
        return;
    }

    env->CallStaticVoidMethod(bridge, mid);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    if (needDetach) {
        g_jvm->DetachCurrentThread();
    }
}





extern "C" static void deathGlRender(void);
static void ensureInstancedHooks(void);
static void buildGlWhitelist(void);

static void __cdecl hookedGlfwSwapBuffers(void* window) {

    if (InterlockedExchange(&g_swapGuard, 1) != 0) {
        g_realSwapBuffers(window);
        return;
    }    ensureInstancedHooks(); // 懒加载 Ext GL hook（swap 时上下文已当前，wglGetProcAddress 有效）
    buildGlWhitelist();     // 每帧全量刷新 GL 白名单（后期加载的 lwjgl*.dll 也入列——防 MC 原版被误拦 → 帧不清重影）
    callJavaBeforeSwap();
    deathGlRender(); // 死亡画面 C++ GL 直绘（swap 前最后时刻——MC 帧已全部画完）
    g_realSwapBuffers(window);
    InterlockedExchange(&g_swapGuard, 0);
}



static HWND findMcWindow(void); // 前置声明（定义在分层窗口实现区）


#include <intrin.h>
#include <psapi.h>

#define MAX_GL_WHITELIST_MODULES 16
static void*  g_glWhitelistBases[MAX_GL_WHITELIST_MODULES];
static size_t g_glWhitelistSizes[MAX_GL_WHITELIST_MODULES];
static int    g_glWhitelistCount = 0;
static int    g_glWhitelistBuilt = 0;

static void buildGlWhitelist(void) {
    g_glWhitelistBuilt = 1;
    g_glWhitelistCount = 0;
    HMODULE mods[512];
    DWORD needed = 0;
    if (!EnumProcessModules(GetCurrentProcess(), mods, sizeof(mods), &needed)) return;
    int n = (int)(needed / sizeof(HMODULE));
    if (n > 512) n = 512;
    for (int i = 0; i < n && g_glWhitelistCount < MAX_GL_WHITELIST_MODULES; i++) {
        char name[64];
        if (!GetModuleBaseNameA(GetCurrentProcess(), mods[i], name, sizeof(name))) continue;
        int isLwjgl = (strncmp(name, "lwjgl", 5) == 0);
        int isJvm   = (strcmp(name, "jvm.dll") == 0);
        if (!isLwjgl && !isJvm) continue;
        MODULEINFO mi; ZeroMemory(&mi, sizeof(mi));
        if (!GetModuleInformation(GetCurrentProcess(), mods[i], &mi, sizeof(mi))) continue;
        g_glWhitelistBases[g_glWhitelistCount] = (void*)mi.lpBaseOfDll;
        g_glWhitelistSizes[g_glWhitelistCount] = mi.SizeOfImage;
        g_glWhitelistCount++;
    }
}


static const char* moduleOfAddress(void* addr) {
    HMODULE mods[512];
    DWORD needed = 0;
    if (EnumProcessModules(GetCurrentProcess(), mods, sizeof(mods), &needed)) {
        int n = (int)(needed / sizeof(HMODULE));
        if (n > 512) n = 512;
        for (int i = 0; i < n; i++) {
            MODULEINFO mi; ZeroMemory(&mi, sizeof(mi));
            if (GetModuleInformation(GetCurrentProcess(), mods[i], &mi, sizeof(mi))) {
                if ((char*)addr >= (char*)mi.lpBaseOfDll &&
                    (char*)addr < (char*)mi.lpBaseOfDll + mi.SizeOfImage) {
                    static char nameBuf[64];
                    if (GetModuleBaseNameA(GetCurrentProcess(), mods[i], nameBuf, sizeof(nameBuf))) {
                        return nameBuf;
                    }
                    return "?";
                }
            }
        }
    }
    return NULL; // 不在任何模块（JIT 代码区）
}

static volatile LONG g_glBlockLogCounter = 0;
static void logGlBlock(const char* fn, void* ret) {
    if (InterlockedIncrement(&g_glBlockLogCounter) % 600 != 1) return; // 每 600 次拦打印 1 次（限频防刷屏）
    const char* mod = moduleOfAddress(ret);
    printf("[taichi_hook] BLOCKED %s (ret=%p in %s)\n", fn, ret, mod ? mod : "<JIT>");
    // 调用栈前 4 帧（定位调用者——BLOCKED glClear 的调用者在 taichi DLL 内时用）
    typedef USHORT (WINAPI *PFN_RtlCaptureStackBackTrace)(ULONG, ULONG, PVOID*, PULONG);
    static PFN_RtlCaptureStackBackTrace rtl = NULL;
    if (!rtl) {
        HMODULE nt = GetModuleHandleA("ntdll.dll");
        if (nt) rtl = (PFN_RtlCaptureStackBackTrace)GetProcAddress(nt, "RtlCaptureStackBackTrace");
        if (!rtl) return;
    }
    PVOID stack[4];
    USHORT n = rtl(1, 4, stack, NULL);
    for (USHORT i = 0; i < n; i++) {
        const char* m = moduleOfAddress(stack[i]);
        printf("  #%u: %p in %s\n", (unsigned)i, stack[i], m ? m : "<JIT>");
    }
}


static int isJavaGlCallRet(void* ret) {
    if (g_glWhitelistCount == 0) buildGlWhitelist(); // 首次/清零后构建（swap 时每帧全量刷新保证完整）
    for (int i = 0; i < g_glWhitelistCount; i++) {
        if ((char*)ret >= (char*)g_glWhitelistBases[i] &&
            (char*)ret < (char*)g_glWhitelistBases[i] + g_glWhitelistSizes[i]) return 1;
    }
    if (moduleOfAddress(ret) == NULL) return 1; // JIT 代码区：Java 直调裸函数指针，放行
    return 0; // 模块内非白名单：C++ 直调注入，拦
}

static jmethodID g_glStackCheckMethod = NULL; // TaiChiRenderControl.isNativeGlStackClean()Z
#define JIT_CHECK_CACHE_SIZE 64
static void* g_jitCheckAddrs[JIT_CHECK_CACHE_SIZE];
static int   g_jitCheckResults[JIT_CHECK_CACHE_SIZE];
static int   g_jitCheckIdx = 0;
static int   g_stackCheckRetry = 0;

static int checkJavaStackForJit(void* ret, int useCache) {
    if (useCache) {
        for (int i = 0; i < JIT_CHECK_CACHE_SIZE; i++) {
            if (g_jitCheckAddrs[i] == ret) return g_jitCheckResults[i];
        }
    }
    // 回调方法未注册时延迟重试（部署时序 / 双 DLL 实例分裂：执行判定的实例可能从未被 nativeBind——
    // 其 g_bridgeClass/g_glStackCheckMethod 均为 NULL。先读共享桥（双实例场景），再 FindClass 兜底）
    if (g_jvm && ++g_stackCheckRetry % 300 == 1) {
        JNIEnv* env = NULL;
        if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_8) == JNI_OK && env) {
            if (!g_bridgeClass || !g_glStackCheckMethod) {
                jclass sBridge = NULL;
                jmethodID sMid = NULL, sStack = NULL;
                if (bridgeRead(&sBridge, &sMid, &sStack) && sBridge && sStack) {
                    g_bridgeClass = sBridge;
                    g_glStackCheckMethod = sStack;
                }
            }
            if (!g_bridgeClass) {
                jclass ctrl = env->FindClass("com/ryjs/reflection/client/render/TaiChiRenderControl");
                if (env->ExceptionCheck()) env->ExceptionClear();
                if (ctrl) g_bridgeClass = (jclass)env->NewGlobalRef(ctrl);
            }
            if (g_bridgeClass && !g_glStackCheckMethod) {
                g_glStackCheckMethod = env->GetStaticMethodID(g_bridgeClass, "isNativeGlStackClean", "()Z");
                if (env->ExceptionCheck()) env->ExceptionClear();
                if (g_glStackCheckMethod) {
                    fprintf(stderr, "[taichi_hook] gl stack check callback registered (deferred)\n");
                }
            }
        }
    }
    int result = 1; // 默认干净（放行）
    if (g_jvm && g_bridgeClass && g_glStackCheckMethod) {
        JNIEnv* env = NULL;
        int needDetach = 0;
        jint status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_8);
        if (status == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread((void**)&env, NULL) != JNI_OK) return 1;
            needDetach = 1;
        } else if (status != JNI_OK) {
            return 1;
        }
        jboolean clean = env->CallStaticBooleanMethod(g_bridgeClass, g_glStackCheckMethod);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (needDetach) g_jvm->DetachCurrentThread();
        result = clean ? 1 : 0;
    }
    if (useCache) {
        int idx = g_jitCheckIdx++ % JIT_CHECK_CACHE_SIZE;
        g_jitCheckAddrs[idx] = ret;
        g_jitCheckResults[idx] = result;
    }
    return result;
}


static int isJavaGlCallOrClean(void* ret, int useCache) {
    if (g_glWhitelistCount == 0) buildGlWhitelist(); // 首次/清零后构建（swap 时每帧全量刷新保证完整——此处不再每调用全量）
    for (int i = 0; i < g_glWhitelistCount; i++) {
        if ((char*)ret >= (char*)g_glWhitelistBases[i] &&
            (char*)ret < (char*)g_glWhitelistBases[i] + g_glWhitelistSizes[i]) return 1;
    }
    if (moduleOfAddress(ret) == NULL) return checkJavaStackForJit(ret, useCache); // JIT 区：Java 查栈
    return 0; // 模块内非白名单：C++ 直调注入，拦
}

// MAX：一切绘制跳过（native 直调 GL 也画不出；glClear 也拦，画面由 swap 前强制清黑兜底）
// protect / 实时重绘（非 MAX）：仅放行 Java/LWJGL 来源（白名单模块内返回地址）——C++ 直调注入 → 拦
static void APIENTRY hookedGlDrawElements(GLenum mode, GLsizei count, GLenum type, const void* indices) {
    if (maxOn()) return;
    void* ret = _ReturnAddress(); // 必须在入口采集：真实调用者的返回地址
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) {
        logGlBlock("glDrawElements", ret);
        return;
    }
    g_realGlDrawElements(mode, count, type, indices);
}

static void APIENTRY hookedGlDrawArrays(GLenum mode, GLint first, GLsizei count) {
    if (maxOn()) return;
    void* ret = _ReturnAddress(); // 必须在入口采集：真实调用者的返回地址
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) {
        logGlBlock("glDrawArrays", ret);
        return;
    }
    g_realGlDrawArrays(mode, first, count);
}

// glClear 拦截（补 GL 直绘缺口）：<b>仅实时重绘模式生效</b>——普通防御（protectOn）不拦 glClear，
// 避免误拦原版清屏改变普通防御既有行为（画面堆叠）。
// （若实时重绘下原版清屏被误拦会画面堆叠——BLOCKED 日志会显示返回地址模块，据此补白名单）
static void APIENTRY hookedGlClear(GLbitfield mask) {
    if (g_glClearReentry) { // 重入（real 链指向另一实例的 hook）→ 直接透传真正原始，不再判定
        g_realGlClear(mask);
        return;
    }
    if (maxOn()) return; // MAX 全拦语义：清屏也不放行（画面由 swap 前强制清黑兜底）
    void* ret = _ReturnAddress(); // 必须在入口采集：真实调用者的返回地址
    if (g_forceRedrawEnabled && !isJavaGlCallOrClean(ret, 0)) {
        logGlBlock("glClear", ret);
        return;
    }
    g_glClearReentry = 1; // 放行：包重入标志（防双实例 real 链又进入另一实例的 hook → 误拦）
    g_realGlClear(mask);
    g_glClearReentry = 0;
}


static void APIENTRY hookedGlDrawPixels(GLsizei width, GLsizei height, GLenum format, GLenum type, const void* pixels) {
    if (maxOn()) return;
    void* ret = _ReturnAddress();
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock("glDrawPixels", ret); return; }
    g_realGlDrawPixels(width, height, format, type, pixels);
}
static void APIENTRY hookedGlBegin(GLenum mode) {
    if (maxOn()) return;
    void* ret = _ReturnAddress();
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock("glBegin", ret); return; }
    g_realGlBegin(mode);
}
static void APIENTRY hookedGlBitmap(GLsizei width, GLsizei height, GLfloat xorig, GLfloat yorig, GLfloat xmove, GLfloat ymove, const GLubyte* bitmap) {
    if (maxOn()) return;
    void* ret = _ReturnAddress();
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock("glBitmap", ret); return; }
    g_realGlBitmap(width, height, xorig, yorig, xmove, ymove, bitmap);
}
static void APIENTRY hookedGlCopyPixels(GLint x, GLint y, GLsizei width, GLsizei height, GLenum type) {
    if (maxOn()) return;
    void* ret = _ReturnAddress();
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock("glCopyPixels", ret); return; }
    g_realGlCopyPixels(x, y, width, height, type);
}
static void APIENTRY hookedGlDrawRangeElements(GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const void* indices) {
    if (maxOn()) return;
    void* ret = _ReturnAddress();
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock("glDrawRangeElements", ret); return; }
    g_realGlDrawRangeElements(mode, start, end, count, type, indices);
}

static void APIENTRY hookedGlDrawArraysInstanced(GLenum mode, GLint first, GLsizei count, GLsizei primcount) {
    if (maxOn()) return;
    void* ret = _ReturnAddress();
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock("glDrawArraysInstanced", ret); return; }
    g_realGlDrawArraysInstanced(mode, first, count, primcount);
}
static void APIENTRY hookedGlDrawElementsInstanced(GLenum mode, GLsizei count, GLenum type, const void* indices, GLsizei primcount) {
    if (maxOn()) return;
    void* ret = _ReturnAddress();
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock("glDrawElementsInstanced", ret); return; }
    g_realGlDrawElementsInstanced(mode, count, type, indices, primcount);
}
static void APIENTRY hookedGlDrawElementsInstancedBaseVertex(GLenum mode, GLsizei count, GLenum type, const void* indices, GLsizei primcount, GLint basevertex) {
    if (maxOn()) return;
    void* ret = _ReturnAddress();
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock("glDrawElementsInstancedBaseVertex", ret); return; }
    g_realGlDrawElementsInstancedBaseVertex(mode, count, type, indices, primcount, basevertex);
}
static void APIENTRY hookedGlMultiDrawArrays(GLenum mode, const GLint* first, const GLsizei* count, GLsizei drawcount) {
    if (maxOn()) return;
    void* ret = _ReturnAddress();
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock("glMultiDrawArrays", ret); return; }
    g_realGlMultiDrawArrays(mode, first, count, drawcount);
}
static void APIENTRY hookedGlMultiDrawElements(GLenum mode, const GLsizei* count, GLenum type, const void* const* indices, GLsizei drawcount) {
    if (maxOn()) return;
    void* ret = _ReturnAddress();
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock("glMultiDrawElements", ret); return; }
    g_realGlMultiDrawElements(mode, count, type, indices, drawcount);
}
static void APIENTRY hookedGlDrawArraysIndirect(GLenum mode, const void* indirect) {
    if (maxOn()) return;
    void* ret = _ReturnAddress();
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock("glDrawArraysIndirect", ret); return; }
    g_realGlDrawArraysIndirect(mode, indirect);
}
static void APIENTRY hookedGlDrawElementsIndirect(GLenum mode, GLenum type, const void* indirect) {
    if (maxOn()) return;
    void* ret = _ReturnAddress();
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock("glDrawElementsIndirect", ret); return; }
    g_realGlDrawElementsIndirect(mode, type, indirect);
}

#define EXT_GL_HOOK_V0(glname, name, params, callargs) \
static void APIENTRY hooked##name params { \
    if (maxOn()) return; \
    void* ret = _ReturnAddress(); \
    if ((protectOn() || g_forceRedrawEnabled) && !isJavaGlCallOrClean(ret, 1)) { logGlBlock(#glname, ret); return; } \
    g_real##name callargs; \
}
EXT_GL_HOOK_V0(glDrawElementsBaseVertex, GlDrawElementsBaseVertex, (GLenum mode, GLsizei count, GLenum type, const void* indices, GLint basevertex), (mode, count, type, indices, basevertex))
EXT_GL_HOOK_V0(glMultiDrawElementsBaseVertex, GlMultiDrawElementsBaseVertex, (GLenum mode, const GLsizei* count, GLenum type, const void* const* indices, GLsizei drawcount, const GLint* basevertex), (mode, count, type, indices, drawcount, basevertex))
EXT_GL_HOOK_V0(glDrawArraysInstancedBaseInstance, GlDrawArraysInstancedBaseInstance, (GLenum mode, GLint first, GLsizei count, GLsizei primcount, GLuint baseinstance), (mode, first, count, primcount, baseinstance))
EXT_GL_HOOK_V0(glDrawElementsInstancedBaseInstance, GlDrawElementsInstancedBaseInstance, (GLenum mode, GLsizei count, GLenum type, const void* indices, GLsizei primcount, GLuint baseinstance), (mode, count, type, indices, primcount, baseinstance))
EXT_GL_HOOK_V0(glDrawElementsInstancedBaseVertexBaseInstance, GlDrawElementsInstancedBaseVertexBaseInstance, (GLenum mode, GLsizei count, GLenum type, const void* indices, GLsizei primcount, GLint basevertex, GLuint baseinstance), (mode, count, type, indices, primcount, basevertex, baseinstance))
EXT_GL_HOOK_V0(glMultiDrawArraysIndirect, GlMultiDrawArraysIndirect, (GLenum mode, const void* indirect, GLsizei drawcount, GLsizei stride), (mode, indirect, drawcount, stride))
EXT_GL_HOOK_V0(glMultiDrawElementsIndirect, GlMultiDrawElementsIndirect, (GLenum mode, GLenum type, const void* indirect, GLsizei drawcount, GLsizei stride), (mode, type, indirect, drawcount, stride))
EXT_GL_HOOK_V0(glDrawTransformFeedback, GlDrawTransformFeedback, (GLenum mode, GLuint id), (mode, id))
EXT_GL_HOOK_V0(glDrawTransformFeedbackStream, GlDrawTransformFeedbackStream, (GLenum mode, GLuint id, GLuint stream), (mode, id, stream))
EXT_GL_HOOK_V0(glDrawTransformFeedbackInstanced, GlDrawTransformFeedbackInstanced, (GLenum mode, GLuint id, GLsizei primcount), (mode, id, primcount))
EXT_GL_HOOK_V0(glDrawTransformFeedbackStreamInstanced, GlDrawTransformFeedbackStreamInstanced, (GLenum mode, GLuint id, GLuint stream, GLsizei primcount), (mode, id, stream, primcount))
EXT_GL_HOOK_V0(glBlitFramebuffer, GlBlitFramebuffer, (GLint sx, GLint sy, GLint sw, GLint sh, GLint dx, GLint dy, GLint dw, GLint dh, GLbitfield mask, GLenum filter), (sx, sy, sw, sh, dx, dy, dw, dh, mask, filter))
EXT_GL_HOOK_V0(glBlitNamedFramebuffer, GlBlitNamedFramebuffer, (GLuint r, GLuint d, GLint sx, GLint sy, GLint sw, GLint sh, GLint dx, GLint dy, GLint dw, GLint dh, GLbitfield mask, GLenum filter), (r, d, sx, sy, sw, sh, dx, dy, dw, dh, mask, filter))
EXT_GL_HOOK_V0(glClearBufferfv, GlClearBufferfv, (GLenum buffer, GLint drawbuffer, const GLfloat* value), (buffer, drawbuffer, value))
EXT_GL_HOOK_V0(glClearBufferiv, GlClearBufferiv, (GLenum buffer, GLint drawbuffer, const GLint* value), (buffer, drawbuffer, value))
EXT_GL_HOOK_V0(glClearBufferuiv, GlClearBufferuiv, (GLenum buffer, GLint drawbuffer, const GLuint* value), (buffer, drawbuffer, value))
EXT_GL_HOOK_V0(glClearBufferfi, GlClearBufferfi, (GLenum buffer, GLint drawbuffer, GLfloat depth, GLint stencil), (buffer, drawbuffer, depth, stencil))


static volatile LONG g_extGlHooked = 0;
static void ensureInstancedHooks(void) {
    if (g_extGlHooked) return;
    InterlockedExchange(&g_extGlHooked, 1);
    typedef void* (APIENTRY *PFN_wglGetProcAddress_t)(LPCSTR);
    HMODULE hOpengl = GetModuleHandleA("opengl32.dll");
    if (!hOpengl) return;
    PFN_wglGetProcAddress_t wglGetProc = (PFN_wglGetProcAddress_t)GetProcAddress(hOpengl, "wglGetProcAddress");
    if (!wglGetProc) return;
    g_realGlDrawArraysInstanced = (PFN_glDrawArraysInstanced)wglGetProc("glDrawArraysInstanced");
    g_realGlDrawElementsInstanced = (PFN_glDrawElementsInstanced)wglGetProc("glDrawElementsInstanced");
    g_realGlDrawElementsInstancedBaseVertex = (PFN_glDrawElementsInstancedBaseVertex)wglGetProc("glDrawElementsInstancedBaseVertex");
    g_realGlMultiDrawArrays = (PFN_glMultiDrawArrays)wglGetProc("glMultiDrawArrays");
    g_realGlMultiDrawElements = (PFN_glMultiDrawElements)wglGetProc("glMultiDrawElements");
    g_realGlDrawArraysIndirect = (PFN_glDrawArraysIndirect)wglGetProc("glDrawArraysIndirect");
    g_realGlDrawElementsIndirect = (PFN_glDrawElementsIndirect)wglGetProc("glDrawElementsIndirect");
    g_realGlDrawElementsBaseVertex = (PFN_glDrawElementsBaseVertex)wglGetProc("glDrawElementsBaseVertex");
    g_realGlMultiDrawElementsBaseVertex = (PFN_glMultiDrawElementsBaseVertex)wglGetProc("glMultiDrawElementsBaseVertex");
    g_realGlDrawArraysInstancedBaseInstance = (PFN_glDrawArraysInstancedBaseInstance)wglGetProc("glDrawArraysInstancedBaseInstance");
    g_realGlDrawElementsInstancedBaseInstance = (PFN_glDrawElementsInstancedBaseInstance)wglGetProc("glDrawElementsInstancedBaseInstance");
    g_realGlDrawElementsInstancedBaseVertexBaseInstance = (PFN_glDrawElementsInstancedBaseVertexBaseInstance)wglGetProc("glDrawElementsInstancedBaseVertexBaseInstance");
    g_realGlMultiDrawArraysIndirect = (PFN_glMultiDrawArraysIndirect)wglGetProc("glMultiDrawArraysIndirect");
    g_realGlMultiDrawElementsIndirect = (PFN_glMultiDrawElementsIndirect)wglGetProc("glMultiDrawElementsIndirect");
    g_realGlDrawTransformFeedback = (PFN_glDrawTransformFeedback)wglGetProc("glDrawTransformFeedback");
    g_realGlDrawTransformFeedbackStream = (PFN_glDrawTransformFeedbackStream)wglGetProc("glDrawTransformFeedbackStream");
    g_realGlDrawTransformFeedbackInstanced = (PFN_glDrawTransformFeedbackInstanced)wglGetProc("glDrawTransformFeedbackInstanced");
    g_realGlDrawTransformFeedbackStreamInstanced = (PFN_glDrawTransformFeedbackStreamInstanced)wglGetProc("glDrawTransformFeedbackStreamInstanced");
    g_realGlBlitFramebuffer = (PFN_glBlitFramebuffer)wglGetProc("glBlitFramebuffer");
    g_realGlBlitNamedFramebuffer = (PFN_glBlitNamedFramebuffer)wglGetProc("glBlitNamedFramebuffer");
    g_realGlClearBufferfv = (PFN_glClearBufferfv)wglGetProc("glClearBufferfv");
    g_realGlClearBufferiv = (PFN_glClearBufferiv)wglGetProc("glClearBufferiv");
    g_realGlClearBufferuiv = (PFN_glClearBufferuiv)wglGetProc("glClearBufferuiv");
    g_realGlClearBufferfi = (PFN_glClearBufferfi)wglGetProc("glClearBufferfi");
    if (!g_realGlDrawArraysInstanced && !g_realGlDrawElementsInstanced && !g_realGlDrawElementsInstancedBaseVertex
        && !g_realGlMultiDrawArrays && !g_realGlMultiDrawElements && !g_realGlDrawArraysIndirect && !g_realGlDrawElementsIndirect
        && !g_realGlDrawElementsBaseVertex && !g_realGlMultiDrawElementsBaseVertex
        && !g_realGlDrawArraysInstancedBaseInstance && !g_realGlDrawElementsInstancedBaseInstance
        && !g_realGlDrawElementsInstancedBaseVertexBaseInstance && !g_realGlMultiDrawArraysIndirect && !g_realGlMultiDrawElementsIndirect
        && !g_realGlDrawTransformFeedback && !g_realGlDrawTransformFeedbackStream
        && !g_realGlDrawTransformFeedbackInstanced && !g_realGlDrawTransformFeedbackStreamInstanced
        && !g_realGlBlitFramebuffer && !g_realGlBlitNamedFramebuffer
        && !g_realGlClearBufferfv && !g_realGlClearBufferiv && !g_realGlClearBufferuiv && !g_realGlClearBufferfi) {
        return;
    }
    LONG error = DetourTransactionBegin();
    if (error != NO_ERROR) return;
    DetourUpdateThread(GetCurrentThread());
    if (g_realGlDrawArraysInstanced) DetourAttach(&(PVOID&)g_realGlDrawArraysInstanced, (PVOID)hookedGlDrawArraysInstanced);
    if (g_realGlDrawElementsInstanced) DetourAttach(&(PVOID&)g_realGlDrawElementsInstanced, (PVOID)hookedGlDrawElementsInstanced);
    if (g_realGlDrawElementsInstancedBaseVertex) DetourAttach(&(PVOID&)g_realGlDrawElementsInstancedBaseVertex, (PVOID)hookedGlDrawElementsInstancedBaseVertex);
    if (g_realGlMultiDrawArrays) DetourAttach(&(PVOID&)g_realGlMultiDrawArrays, (PVOID)hookedGlMultiDrawArrays);
    if (g_realGlMultiDrawElements) DetourAttach(&(PVOID&)g_realGlMultiDrawElements, (PVOID)hookedGlMultiDrawElements);
    if (g_realGlDrawArraysIndirect) DetourAttach(&(PVOID&)g_realGlDrawArraysIndirect, (PVOID)hookedGlDrawArraysIndirect);
    if (g_realGlDrawElementsIndirect) DetourAttach(&(PVOID&)g_realGlDrawElementsIndirect, (PVOID)hookedGlDrawElementsIndirect);
    if (g_realGlDrawElementsBaseVertex) DetourAttach(&(PVOID&)g_realGlDrawElementsBaseVertex, (PVOID)hookedGlDrawElementsBaseVertex);
    if (g_realGlMultiDrawElementsBaseVertex) DetourAttach(&(PVOID&)g_realGlMultiDrawElementsBaseVertex, (PVOID)hookedGlMultiDrawElementsBaseVertex);
    if (g_realGlDrawArraysInstancedBaseInstance) DetourAttach(&(PVOID&)g_realGlDrawArraysInstancedBaseInstance, (PVOID)hookedGlDrawArraysInstancedBaseInstance);
    if (g_realGlDrawElementsInstancedBaseInstance) DetourAttach(&(PVOID&)g_realGlDrawElementsInstancedBaseInstance, (PVOID)hookedGlDrawElementsInstancedBaseInstance);
    if (g_realGlDrawElementsInstancedBaseVertexBaseInstance) DetourAttach(&(PVOID&)g_realGlDrawElementsInstancedBaseVertexBaseInstance, (PVOID)hookedGlDrawElementsInstancedBaseVertexBaseInstance);
    if (g_realGlMultiDrawArraysIndirect) DetourAttach(&(PVOID&)g_realGlMultiDrawArraysIndirect, (PVOID)hookedGlMultiDrawArraysIndirect);
    if (g_realGlMultiDrawElementsIndirect) DetourAttach(&(PVOID&)g_realGlMultiDrawElementsIndirect, (PVOID)hookedGlMultiDrawElementsIndirect);
    if (g_realGlDrawTransformFeedback) DetourAttach(&(PVOID&)g_realGlDrawTransformFeedback, (PVOID)hookedGlDrawTransformFeedback);
    if (g_realGlDrawTransformFeedbackStream) DetourAttach(&(PVOID&)g_realGlDrawTransformFeedbackStream, (PVOID)hookedGlDrawTransformFeedbackStream);
    if (g_realGlDrawTransformFeedbackInstanced) DetourAttach(&(PVOID&)g_realGlDrawTransformFeedbackInstanced, (PVOID)hookedGlDrawTransformFeedbackInstanced);
    if (g_realGlDrawTransformFeedbackStreamInstanced) DetourAttach(&(PVOID&)g_realGlDrawTransformFeedbackStreamInstanced, (PVOID)hookedGlDrawTransformFeedbackStreamInstanced);
    if (g_realGlBlitFramebuffer) DetourAttach(&(PVOID&)g_realGlBlitFramebuffer, (PVOID)hookedGlBlitFramebuffer);
    if (g_realGlBlitNamedFramebuffer) DetourAttach(&(PVOID&)g_realGlBlitNamedFramebuffer, (PVOID)hookedGlBlitNamedFramebuffer);
    if (g_realGlClearBufferfv) DetourAttach(&(PVOID&)g_realGlClearBufferfv, (PVOID)hookedGlClearBufferfv);
    if (g_realGlClearBufferiv) DetourAttach(&(PVOID&)g_realGlClearBufferiv, (PVOID)hookedGlClearBufferiv);
    if (g_realGlClearBufferuiv) DetourAttach(&(PVOID&)g_realGlClearBufferuiv, (PVOID)hookedGlClearBufferuiv);
    if (g_realGlClearBufferfi) DetourAttach(&(PVOID&)g_realGlClearBufferfi, (PVOID)hookedGlClearBufferfi);
    error = DetourTransactionCommit();
    if (error == NO_ERROR) {
        printf("[taichi_hook] Ext GL hooks installed: inst=%p/%p/%p multi=%p/%p ind=%p/%p baseVtx=%p/%p tf=%p/%p/%p/%p blit=%p/%p\n",
               (void*)g_realGlDrawArraysInstanced, (void*)g_realGlDrawElementsInstanced, (void*)g_realGlDrawElementsInstancedBaseVertex,
               (void*)g_realGlMultiDrawArrays, (void*)g_realGlMultiDrawElements,
               (void*)g_realGlDrawArraysIndirect, (void*)g_realGlDrawElementsIndirect,
               (void*)g_realGlDrawElementsBaseVertex, (void*)g_realGlMultiDrawElementsBaseVertex,
               (void*)g_realGlDrawTransformFeedback, (void*)g_realGlDrawTransformFeedbackStream,
               (void*)g_realGlDrawTransformFeedbackInstanced, (void*)g_realGlDrawTransformFeedbackStreamInstanced,
               (void*)g_realGlBlitFramebuffer, (void*)g_realGlBlitNamedFramebuffer);
    }
}


static BOOL isMcWindowDc(HDC hdc);
extern "C" static void deathGdiRender(void);


typedef GLenum (APIENTRY* PFN_glGetErrorFn)(void);
typedef void   (APIENTRY* PFN_glFlushFn)(void);
static PFN_glGetErrorFn g_realGlGetError = NULL;
static PFN_glFlushFn    g_realGlFlush = NULL;
static volatile LONG g_fullRedrawDiag = 0;

static HDC     g_frMemDc = NULL;
static HBITMAP g_frBmp = NULL;
static HGDIOBJ g_frOldBmp = NULL;
static int     g_frW = 0, g_frH = 0;

static void frReleaseCache(void) {
    if (g_frMemDc) {
        if (g_frOldBmp) SelectObject(g_frMemDc, g_frOldBmp);
        DeleteDC(g_frMemDc);
        g_frMemDc = NULL;
    }
    if (g_frBmp) {
        DeleteObject(g_frBmp);
        g_frBmp = NULL;
    }
    g_frOldBmp = NULL;
    g_frW = g_frH = 0;
}


static void maxBlackPass(void) {
    if (!g_realBitBlt || !g_realPatBlt) return;
    HWND mc = g_mcHwndCached ? g_mcHwndCached : findMcWindow();
    if (!mc) return;
    RECT rc;
    if (!GetWindowRect(mc, &rc)) return;
    int w = rc.right - rc.left, h = rc.bottom - rc.top;
    if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return;
    HWND fg = GetForegroundWindow();
    if (!((fg == mc) || (fg && GetWindowThreadProcessId(fg, NULL) == GetCurrentProcessId()))) return;
    HDC screenDc = g_realGetDC(NULL); // 穿透画布封锁（自己通道用 real）
    if (!screenDc) return;
    if (g_frMemDc && (g_frW != w || g_frH != h)) frReleaseCache();
    if (!g_frMemDc) {
        g_frMemDc = CreateCompatibleDC(screenDc);
        g_frBmp = CreateCompatibleBitmap(screenDc, w, h);
        if (g_frMemDc && g_frBmp) {
            g_frOldBmp = SelectObject(g_frMemDc, g_frBmp);
            g_frW = w; g_frH = h;
        }
    }
    if (!g_frMemDc || !g_frBmp || g_frW != w || g_frH != h) {
        frReleaseCache();
        ReleaseDC(NULL, screenDc);
        return;
    }
    g_realPatBlt(g_frMemDc, 0, 0, w, h, BLACKNESS); // 黑填充
    g_realBitBlt(screenDc, rc.left, rc.top, w, h, g_frMemDc, 0, 0, SRCCOPY);
    ReleaseDC(NULL, screenDc);
    if (InterlockedIncrement(&g_fullRedrawDiag) % 600 == 1) {
        printf("[taichi_hook] maxBlackPass: %dx%d blacked\n", w, h);
    }
}

static void fullRedrawPass(void) {
    if (!g_realGlGetError) {
        HMODULE hGl = GetModuleHandleA("opengl32.dll");
        if (hGl) {
            g_realGlGetError = (PFN_glGetErrorFn)GetProcAddress(hGl, "glGetError");
            g_realGlFlush    = (PFN_glFlushFn)GetProcAddress(hGl, "glFlush");
        }
    }
    if (g_realGlGetError) {
        for (int i = 0; i < 16; i++) {
            if (g_realGlGetError() == 0) break; // GL_NO_ERROR
        }
    }
    if (g_realGlFlush) g_realGlFlush();

    if (maxOn()) { maxBlackPass(); return; } // MAX：兜底黑屏（拒绝一切绘制的最后兜底）

    if (!g_realBitBlt) return;
    HWND mc = g_mcHwndCached ? g_mcHwndCached : findMcWindow();
    if (!mc) return;
    RECT rc;
    if (!GetWindowRect(mc, &rc)) return;
    int w = rc.right - rc.left, h = rc.bottom - rc.top;
    if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return;

    // ④ 前台判定：MC（或同进程窗口）是前台才覆盖屏幕 DC——MC 被遮挡（看别的窗口）时跳过，
    //    防"MC 内容印到其他窗口上"（屏幕 DC 是最顶层——覆盖会把 MC 画到遮挡物之上）
    HWND fg = GetForegroundWindow();
    BOOL fgOk = (fg == mc) || (fg && GetWindowThreadProcessId(fg, NULL) == GetCurrentProcessId());

    HDC screenDc = g_realGetDC(NULL); // 穿透画布封锁（MAX 时 GetDC(NULL) 被拦——自己通道用 real）
    HDC winDc = g_realGetDC(mc);
    if (!screenDc || !winDc) {
        if (screenDc) ReleaseDC(NULL, screenDc);
        if (winDc) ReleaseDC(mc, winDc);
        return;
    }

    // ① 位图缓存：尺寸不变复用；变化（全屏切换）释放重建
    if (g_frMemDc && (g_frW != w || g_frH != h)) frReleaseCache();
    if (!g_frMemDc) {
        g_frMemDc = CreateCompatibleDC(screenDc);
        g_frBmp = CreateCompatibleBitmap(screenDc, w, h);
        if (g_frMemDc && g_frBmp) {
            g_frOldBmp = SelectObject(g_frMemDc, g_frBmp);
            g_frW = w; g_frH = h;
        }
    }
    if (!g_frMemDc || !g_frBmp || g_frW != w || g_frH != h) {
        frReleaseCache();
        ReleaseDC(NULL, screenDc);
        ReleaseDC(mc, winDc);
        return;
    }

    // ② 5 点采样（中心 + 四角内缩 4px）
    int px[5][2] = { { w / 2, h / 2 }, { 4, 4 }, { w - 4, 4 }, { 4, h - 4 }, { w - 4, h - 4 } };
    COLORREF before[5], probe[5], after[5];
    for (int i = 0; i < 5; i++) {
        before[i] = GetPixel(screenDc, rc.left + px[i][0], rc.top + px[i][1]);
    }
    g_realBitBlt(g_frMemDc, 0, 0, w, h, winDc, 0, 0, SRCCOPY); // 窗口内容读回缓存区
    for (int i = 0; i < 5; i++) {
        probe[i] = GetPixel(g_frMemDc, px[i][0], px[i][1]);
    }

    // 读回成功判定：中心非黑（某些驱动 GL 窗口 DC 读回黑 → 跳过覆盖，防黑块）
    BOOL readOk = probe[0] != 0x000000;
    int dirty = 0;
    for (int i = 0; i < 5; i++) {
        if (before[i] != probe[i]) dirty++;
    }
    BOOL covered = FALSE, verified = FALSE;
    if (readOk && dirty > 0 && fgOk) {                          // 有差异 + 读回成功 + 前台 → 覆盖清除
        g_realBitBlt(screenDc, rc.left, rc.top, w, h, g_frMemDc, 0, 0, SRCCOPY);
        covered = TRUE;
        // ③ covered 后验证：屏幕 DC 5 点应与读回内容一致
        verified = TRUE;
        for (int i = 0; i < 5; i++) {
            after[i] = GetPixel(screenDc, rc.left + px[i][0], rc.top + px[i][1]);
            if (after[i] != probe[i]) verified = FALSE;
        }
    }
    ReleaseDC(NULL, screenDc);
    ReleaseDC(mc, winDc);
    if (InterlockedIncrement(&g_fullRedrawDiag) % 600 == 1) {
        printf("[taichi_hook] fullRedrawPass: %dx%d dirty=%d covered=%d verified=%d fg=%d read=%d\n",
               w, h, dirty, covered, verified, fgOk, readOk);
    }
}

static BOOL WINAPI hookedWglSwapBuffers(HDC hdc) {
    if (maxOn()) {
        if (g_realGlClear && g_realGlClearColor) {
            g_glClearReentry = 1; // MAX 清屏包重入保护（real 链防进入另一实例 hook）
            g_realGlClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            g_realGlClear(0x4000 | 0x100); // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT
            g_glClearReentry = 0;
        }
    }
    BOOL ok = g_realWglSwapBuffers(hdc);
    // 全量重绘（redrawAll）/ MAX 兜底黑屏：swap 后执行——读回的是刚显示的帧（front），覆盖屏幕 DC
    // 与窗口显示永远一致 → 无帧差 → 不闪烁（swap 前执行会读回旧帧，屏幕 DC 与窗口错位一帧 → 交替闪烁）
    if (maxOn() || g_fullRedraw) fullRedrawPass();
    // 一次性强制重绘：标志置位 → 本帧（完整原版帧）已 swap 上屏，前台残留被替换——清除标志
    if (InterlockedExchange(&g_forceRedrawRequested, 0)) {
        printf("[taichi_hook] forced redraw completed\n");
    }
    // 死亡画面 GDI 通道（swap 后画窗口 DC——覆盖刚显示的 GL 帧；下一帧 swap 自然覆盖，零残留）
    deathGdiRender();
    // 注：不做“双 swap”——第二次 swap 会把 back 缓冲里的旧帧（前一帧内容）显示出来，产生残影；
    // 实时重绘的正确语义是“只放行 MC 绘制、拦非 MC 绘制调用”（g_forceRedrawEnabled 并入各 hook 判定），
    // 非 MC 绘制根本执行不了，无需覆盖。
    return ok;
}

// wglSwapLayerBuffers：层交换出口（另一个 swap 路径）——MAX 全拦
static BOOL WINAPI hookedWglSwapLayerBuffers(HDC hdc, UINT planes) {
    if (maxOn()) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    return g_realWglSwapLayerBuffers(hdc, planes);
}

// wglMakeCurrent 诊断：上下文切换（攻击者可能创建自己的 GL 上下文渲染）——防御开启时限频打印
static volatile LONG g_mcDiag = 0;
static BOOL WINAPI hookedWglMakeCurrent(HDC hdc, HGLRC hglrc) {
    if (maxOn() && InterlockedCompareExchange(&g_mcDiag, 1, 0) == 0) {
        HWND win = hdc ? WindowFromDC(hdc) : NULL;
        printf("[taichi_hook] wglMakeCurrent (MAX): hdc=%p hglrc=%p win=%p\n", (void*)hdc, (void*)hglrc, (void*)win);
    }
    return g_realWglMakeCurrent(hdc, hglrc);
}

// GDI：目标 DC 关联 MC 主窗口 + 防御开 → 跳过（原版 MC 渲染全 GL，不误伤其他窗口）
static BOOL isMcWindowDc(HDC hdc) {
    HWND wnd = WindowFromDC(hdc);
    if (!wnd) return FALSE;
    if (g_mcHwndCached == NULL) {
        g_mcHwndCached = findMcWindow();
    }
    return wnd == g_mcHwndCached;
}


static BOOL isScreenDc(HDC hdc) {
    if (WindowFromDC(hdc)) return FALSE;
    return GetObjectType(hdc) == OBJ_DC;
}


static BOOL rectHitsMcWindow(int x, int y, int w, int h) {
    if (g_mcHwndCached == NULL) g_mcHwndCached = findMcWindow();
    if (!g_mcHwndCached) return FALSE;
    RECT mc;
    if (!GetWindowRect(g_mcHwndCached, &mc)) return FALSE;
    if (w <= 0) w = 1;
    if (h <= 0) h = 1;
    return x < mc.right && x + w > mc.left && y < mc.bottom && y + h > mc.top;
}


static BOOL pointHitsMcWindow(int x, int y) {
    if (g_mcHwndCached == NULL) g_mcHwndCached = findMcWindow();
    if (!g_mcHwndCached) return FALSE;
    RECT mc;
    if (!GetWindowRect(g_mcHwndCached, &mc)) return FALSE;
    return x >= mc.left && x < mc.right && y >= mc.top && y < mc.bottom;
}


static volatile LONG g_gdiDiag[64] = {0};
static void gdiDiag(int idx, const char* name, HDC hdc, int x, int y, int w, int h) {
    if (InterlockedCompareExchange(&g_gdiDiag[idx], 1, 0) == 0) {
        HWND win = WindowFromDC(hdc);
        printf("[taichi_hook] GDI BLOCKED: %s flags=%ld hdc=%p win=%p type=%ld mcWinDC=%d screenDC=%d [%d,%d %dx%d] shared=%s\n",
               name, (long)g_defenseFlags, (void*)hdc, (void*)win,
               (long)GetObjectType(hdc), isMcWindowDc(hdc), isScreenDc(hdc),
               x, y, w, h, g_sharedState ? "yes" : "no");
    }
}


static BOOL gdiMaxBlock(void) { return maxOn(); }

static BOOL WINAPI hookedExtTextOutW(HDC hdc, int x, int y, UINT options, const RECT* lprect,
                                     LPCWSTR lpString, UINT c, const INT* lpDx) {
    if (gdiMaxBlock()) { gdiDiag(0, "ExtTextOutW", hdc, x, y, 0, 0); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc)) {
        if (lprect) {
            if (rectHitsMcWindow(lprect->left, lprect->top,
                                 lprect->right - lprect->left, lprect->bottom - lprect->top)) return TRUE;
        } else if (pointHitsMcWindow(x, y)) {
            return TRUE;
        }
    }
    return g_realExtTextOutW(hdc, x, y, options, lprect, lpString, c, lpDx);
}

static BOOL WINAPI hookedExtTextOutA(HDC hdc, int x, int y, UINT options, const RECT* lprect,
                                     LPCSTR lpString, UINT c, const INT* lpDx) {
    if (gdiMaxBlock()) { gdiDiag(1, "ExtTextOutA", hdc, x, y, 0, 0); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc)) {
        if (lprect) {
            if (rectHitsMcWindow(lprect->left, lprect->top,
                                 lprect->right - lprect->left, lprect->bottom - lprect->top)) return TRUE;
        } else if (pointHitsMcWindow(x, y)) {
            return TRUE;
        }
    }
    return g_realExtTextOutA(hdc, x, y, options, lprect, lpString, c, lpDx);
}

static BOOL WINAPI hookedTextOutW(HDC hdc, int x, int y, LPCWSTR lpString, int c) {
    if (gdiMaxBlock()) { gdiDiag(2, "TextOutW", hdc, x, y, 0, 0); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && pointHitsMcWindow(x, y)) return TRUE;
    return g_realTextOutW(hdc, x, y, lpString, c);
}

static BOOL WINAPI hookedTextOutA(HDC hdc, int x, int y, LPCSTR lpString, int c) {
    if (gdiMaxBlock()) { gdiDiag(3, "TextOutA", hdc, x, y, 0, 0); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && pointHitsMcWindow(x, y)) return TRUE;
    return g_realTextOutA(hdc, x, y, lpString, c);
}

static BOOL WINAPI hookedSetPixelV(HDC hdc, int x, int y, COLORREF crColor) {
    if (gdiMaxBlock()) { gdiDiag(4, "SetPixelV", hdc, x, y, 0, 0); return FALSE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return FALSE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && pointHitsMcWindow(x, y)) return FALSE;
    return g_realSetPixelV(hdc, x, y, crColor);
}

static BOOL WINAPI hookedBitBlt(HDC hdcDest, int x, int y, int w, int h,
                                HDC hdcSrc, int srcX, int srcY, DWORD rop) {
    if (gdiMaxBlock()) { gdiDiag(5, "BitBlt", hdcDest, x, y, w, h); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdcDest)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdcDest) && rectHitsMcWindow(x, y, w, h)) {
        gdiDiag(5, "BitBlt-screenHit", hdcDest, x, y, w, h);
        return TRUE;
    }
    return g_realBitBlt(hdcDest, x, y, w, h, hdcSrc, srcX, srcY, rop);
}

static BOOL WINAPI hookedStretchBlt(HDC hdcDest, int x, int y, int w, int h,
                                    HDC hdcSrc, int sx, int sy, int sw, int sh, DWORD rop) {
    if (gdiMaxBlock()) { gdiDiag(6, "StretchBlt", hdcDest, x, y, w, h); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdcDest)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdcDest) && rectHitsMcWindow(x, y, w, h)) return TRUE;
    return g_realStretchBlt(hdcDest, x, y, w, h, hdcSrc, sx, sy, sw, sh, rop);
}

static BOOL WINAPI hookedAlphaBlend(HDC hdcDest, int x, int y, int w, int h,
                                    HDC hdcSrc, int sx, int sy, int sw, int sh, BLENDFUNCTION bf) {
    if (gdiMaxBlock()) { gdiDiag(7, "AlphaBlend", hdcDest, x, y, w, h); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdcDest)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdcDest) && rectHitsMcWindow(x, y, w, h)) return TRUE;
    return g_realAlphaBlend(hdcDest, x, y, w, h, hdcSrc, sx, sy, sw, sh, bf);
}

static BOOL WINAPI hookedTransparentBlt(HDC hdcDest, int x, int y, int w, int h,
                                        HDC hdcSrc, int sx, int sy, int sw, int sh, UINT crTransparent) {
    if (gdiMaxBlock()) { gdiDiag(8, "TransparentBlt", hdcDest, x, y, w, h); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdcDest)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdcDest) && rectHitsMcWindow(x, y, w, h)) return TRUE;
    return g_realTransparentBlt(hdcDest, x, y, w, h, hdcSrc, sx, sy, sw, sh, crTransparent);
}

static BOOL WINAPI hookedPatBlt(HDC hdc, int x, int y, int w, int h, DWORD rop) {
    if (gdiMaxBlock()) { gdiDiag(9, "PatBlt", hdc, x, y, w, h); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && rectHitsMcWindow(x, y, w, h)) return TRUE;
    return g_realPatBlt(hdc, x, y, w, h, rop);
}

static BOOL WINAPI hookedFillRect(HDC hdc, const RECT* lprc, HBRUSH hbr) {
    if (gdiMaxBlock()) { gdiDiag(10, "FillRect", hdc, lprc ? lprc->left : 0, lprc ? lprc->top : 0,
                                 lprc ? (lprc->right - lprc->left) : 0, lprc ? (lprc->bottom - lprc->top) : 0); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && lprc
        && rectHitsMcWindow(lprc->left, lprc->top, lprc->right - lprc->left, lprc->bottom - lprc->top)) return TRUE;
    return g_realFillRect(hdc, lprc, hbr);
}

static BOOL WINAPI hookedDrawTextW(HDC hdc, LPCWSTR lpchText, int cchText, RECT* lprc, UINT format) {
    if (gdiMaxBlock()) { gdiDiag(11, "DrawTextW", hdc, lprc ? lprc->left : 0, lprc ? lprc->top : 0,
                                 lprc ? (lprc->right - lprc->left) : 0, lprc ? (lprc->bottom - lprc->top) : 0); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && lprc
        && rectHitsMcWindow(lprc->left, lprc->top, lprc->right - lprc->left, lprc->bottom - lprc->top)) return TRUE;
    return g_realDrawTextW(hdc, lpchText, cchText, lprc, format);
}

static BOOL WINAPI hookedDrawTextA(HDC hdc, LPCSTR lpchText, int cchText, RECT* lprc, UINT format) {
    if (gdiMaxBlock()) { gdiDiag(12, "DrawTextA", hdc, lprc ? lprc->left : 0, lprc ? lprc->top : 0,
                                 lprc ? (lprc->right - lprc->left) : 0, lprc ? (lprc->bottom - lprc->top) : 0); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && lprc
        && rectHitsMcWindow(lprc->left, lprc->top, lprc->right - lprc->left, lprc->bottom - lprc->top)) return TRUE;
    return g_realDrawTextA(hdc, lpchText, cchText, lprc, format);
}

static int WINAPI hookedSetDIBitsToDevice(HDC hdc, int x, int y, DWORD dx, DWORD dy,
                                          int SrcX, int SrcY, UINT Scan, UINT NumScans,
                                          const void* Bits, const BITMAPINFO* BitsInfo, UINT Usage) {
    if (gdiMaxBlock()) { gdiDiag(13, "SetDIBitsToDevice", hdc, x, y, (int)dx, (int)dy); return 0; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return 0;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && rectHitsMcWindow(x, y, (int)dx, (int)dy)) return 0;
    return g_realSetDIBitsToDevice(hdc, x, y, dx, dy, SrcX, SrcY, Scan, NumScans, Bits, BitsInfo, Usage);
}

static int WINAPI hookedStretchDIBits(HDC hdc, int x, int y, int w, int h,
                                      int SrcX, int SrcY, int sw, int sh,
                                      const void* Bits, const BITMAPINFO* BitsInfo, UINT Usage, DWORD rop) {
    if (gdiMaxBlock()) { gdiDiag(14, "StretchDIBits", hdc, x, y, w, h); return 0; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return 0;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && rectHitsMcWindow(x, y, w, h)) return 0;
    return g_realStretchDIBits(hdc, x, y, w, h, SrcX, SrcY, sw, sh, Bits, BitsInfo, Usage, rop);
}

static BOOL WINAPI hookedPlayEnhMetaFile(HDC hdc, HENHMETAFILE hmf, const RECT* lprect) {
    if (gdiMaxBlock()) { gdiDiag(16, "PlayEnhMetaFile", hdc,
                                 lprect ? lprect->left : 0, lprect ? lprect->top : 0,
                                 lprect ? (lprect->right - lprect->left) : 0, lprect ? (lprect->bottom - lprect->top) : 0); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && lprect
        && rectHitsMcWindow(lprect->left, lprect->top, lprect->right - lprect->left, lprect->bottom - lprect->top)) return TRUE;
    return g_realPlayEnhMetaFile(hdc, hmf, lprect);
}


#define GDI_HOOK_B(diagidx, name, params, hdcarg, rx, ry, rw, rh, callargs) \
static BOOL WINAPI hooked##name params { \
    if (gdiMaxBlock()) { gdiDiag(diagidx, #name, hdcarg, rx, ry, rw, rh); return TRUE; } \
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdcarg)) return TRUE; \
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdcarg) && rectHitsMcWindow(rx, ry, rw, rh)) return TRUE; \
    return g_real##name callargs; \
}

GDI_HOOK_B(17, Rectangle, (HDC hdc, int l, int t, int r, int b), hdc, l, t, r - l, b - t, (hdc, l, t, r, b))
GDI_HOOK_B(18, Ellipse, (HDC hdc, int l, int t, int r, int b), hdc, l, t, r - l, b - t, (hdc, l, t, r, b))
GDI_HOOK_B(19, RoundRect, (HDC hdc, int l, int t, int r, int b, int ew, int eh), hdc, l, t, r - l, b - t, (hdc, l, t, r, b, ew, eh))
GDI_HOOK_B(20, LineTo, (HDC hdc, int x, int y), hdc, x, y, 1, 1, (hdc, x, y))
GDI_HOOK_B(21, Polyline, (HDC hdc, const POINT* pts, int n), hdc, pts && n ? pts[0].x : 0, pts && n ? pts[0].y : 0, 1, 1, (hdc, pts, n))
GDI_HOOK_B(22, Polygon, (HDC hdc, const POINT* pts, int n), hdc, pts && n ? pts[0].x : 0, pts && n ? pts[0].y : 0, 1, 1, (hdc, pts, n))
GDI_HOOK_B(23, PolyPolygon, (HDC hdc, const POINT* pts, const int* counts, int n), hdc, pts && n ? pts[0].x : 0, pts && n ? pts[0].y : 0, 1, 1, (hdc, pts, counts, n))
GDI_HOOK_B(24, FillRgn, (HDC hdc, HRGN hrgn, HBRUSH hbr), hdc, 0, 0, 1, 1, (hdc, hrgn, hbr))
GDI_HOOK_B(25, FrameRgn, (HDC hdc, HRGN hrgn, HBRUSH hbr, int w, int h), hdc, 0, 0, 1, 1, (hdc, hrgn, hbr, w, h))
GDI_HOOK_B(26, InvertRgn, (HDC hdc, HRGN hrgn), hdc, 0, 0, 1, 1, (hdc, hrgn))
GDI_HOOK_B(27, PaintRgn, (HDC hdc, HRGN hrgn), hdc, 0, 0, 1, 1, (hdc, hrgn))
GDI_HOOK_B(28, Arc, (HDC hdc, int l, int t, int r, int b, int x1, int y1, int x2, int y2), hdc, l, t, r - l, b - t, (hdc, l, t, r, b, x1, y1, x2, y2))
GDI_HOOK_B(29, Chord, (HDC hdc, int l, int t, int r, int b, int x1, int y1, int x2, int y2), hdc, l, t, r - l, b - t, (hdc, l, t, r, b, x1, y1, x2, y2))
GDI_HOOK_B(30, Pie, (HDC hdc, int l, int t, int r, int b, int x1, int y1, int x2, int y2), hdc, l, t, r - l, b - t, (hdc, l, t, r, b, x1, y1, x2, y2))
GDI_HOOK_B(31, ExtFloodFill, (HDC hdc, int x, int y, COLORREF cr, UINT type), hdc, x, y, 1, 1, (hdc, x, y, cr, type))
GDI_HOOK_B(32, PolyBezier, (HDC hdc, const POINT* pts, DWORD n), hdc, pts && n ? pts[0].x : 0, pts && n ? pts[0].y : 0, 1, 1, (hdc, pts, n))


GDI_HOOK_B(33, FloodFill, (HDC hdc, int x, int y, COLORREF cr), hdc, x, y, 1, 1, (hdc, x, y, cr))
GDI_HOOK_B(34, PolylineTo, (HDC hdc, const POINT* pts, DWORD n), hdc, pts && n ? pts[0].x : 0, pts && n ? pts[0].y : 0, 1, 1, (hdc, pts, n))
GDI_HOOK_B(35, PolyPolyline, (HDC hdc, const POINT* pts, const DWORD* counts, DWORD n), hdc, pts && n ? pts[0].x : 0, pts && n ? pts[0].y : 0, 1, 1, (hdc, pts, counts, n))
GDI_HOOK_B(36, PolyBezierTo, (HDC hdc, const POINT* pts, DWORD n), hdc, pts && n ? pts[0].x : 0, pts && n ? pts[0].y : 0, 1, 1, (hdc, pts, n))
GDI_HOOK_B(37, PolyDraw, (HDC hdc, const POINT* pts, const BYTE* types, int n), hdc, pts && n ? pts[0].x : 0, pts && n ? pts[0].y : 0, 1, 1, (hdc, pts, types, n))
GDI_HOOK_B(38, InvertRect, (HDC hdc, const RECT* lprc), hdc, lprc ? lprc->left : 0, lprc ? lprc->top : 0, lprc ? (lprc->right - lprc->left) : 1, lprc ? (lprc->bottom - lprc->top) : 1, (hdc, lprc))
GDI_HOOK_B(39, FillPath, (HDC hdc), hdc, 0, 0, 1, 1, (hdc))
GDI_HOOK_B(40, StrokePath, (HDC hdc), hdc, 0, 0, 1, 1, (hdc))
GDI_HOOK_B(41, StrokeAndFillPath, (HDC hdc), hdc, 0, 0, 1, 1, (hdc))
GDI_HOOK_B(42, GradientFill, (HDC hdc, void* pVertex, ULONG nVertex, void* pMesh, ULONG nMesh, ULONG mode), hdc, 0, 0, 1, 1, (hdc, pVertex, nVertex, pMesh, nMesh, mode))
GDI_HOOK_B(43, AngleArc, (HDC hdc, int x, int y, DWORD r, FLOAT start, FLOAT sweep), hdc, x, y, (int)r, (int)r, (hdc, x, y, r, start, sweep))


static COLORREF WINAPI hookedSetPixel(HDC hdc, int x, int y, COLORREF cr) {
    if (gdiMaxBlock()) { gdiDiag(44, "SetPixel", hdc, x, y, 1, 1); return (COLORREF)-1; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return (COLORREF)-1;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && pointHitsMcWindow(x, y)) return (COLORREF)-1;
    return g_realSetPixel(hdc, x, y, cr);
}
static int WINAPI hookedFrameRect(HDC hdc, const RECT* lprc, HBRUSH hbr) {
    if (gdiMaxBlock()) { gdiDiag(45, "FrameRect", hdc, lprc ? lprc->left : 0, lprc ? lprc->top : 0,
                                 lprc ? (lprc->right - lprc->left) : 1, lprc ? (lprc->bottom - lprc->top) : 1); return 0; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return 0;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && lprc
        && rectHitsMcWindow(lprc->left, lprc->top, lprc->right - lprc->left, lprc->bottom - lprc->top)) return 0;
    return g_realFrameRect(hdc, lprc, hbr);
}
static LONG WINAPI hookedTabbedTextOutW(HDC hdc, int x, int y, LPCWSTR str, int c, int nTab, const INT* tabs, int tabOrigin) {
    if (gdiMaxBlock()) { gdiDiag(46, "TabbedTextOutW", hdc, x, y, 1, 1); return 0; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return 0;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && pointHitsMcWindow(x, y)) return 0;
    return g_realTabbedTextOutW(hdc, x, y, str, c, nTab, tabs, tabOrigin);
}
static LONG WINAPI hookedTabbedTextOutA(HDC hdc, int x, int y, LPCSTR str, int c, int nTab, const INT* tabs, int tabOrigin) {
    if (gdiMaxBlock()) { gdiDiag(47, "TabbedTextOutA", hdc, x, y, 1, 1); return 0; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return 0;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && pointHitsMcWindow(x, y)) return 0;
    return g_realTabbedTextOutA(hdc, x, y, str, c, nTab, tabs, tabOrigin);
}


static BOOL WINAPI hookedDrawIcon(HDC hdc, int x, int y, HICON icon) {
    if (gdiMaxBlock()) { gdiDiag(48, "DrawIcon", hdc, x, y, 1, 1); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && pointHitsMcWindow(x, y)) return TRUE;
    return g_realDrawIcon(hdc, x, y, icon);
}
static BOOL WINAPI hookedDrawIconEx(HDC hdc, int x, int y, HICON icon, int w, int h, UINT step, HBRUSH brush, UINT flags) {
    if (gdiMaxBlock()) { gdiDiag(49, "DrawIconEx", hdc, x, y, w, h); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && rectHitsMcWindow(x, y, w, h)) return TRUE;
    return g_realDrawIconEx(hdc, x, y, icon, w, h, step, brush, flags);
}
static BOOL WINAPI hookedDrawState(HDC hdc, HBRUSH brush, void* proc, LPARAM lData, WPARAM wData, int x, int y, int w, int h, UINT flags) {
    if (gdiMaxBlock()) { gdiDiag(50, "DrawState", hdc, x, y, w, h); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && rectHitsMcWindow(x, y, w, h)) return TRUE;
    return g_realDrawState(hdc, brush, proc, lData, wData, x, y, w, h, flags);
}
static BOOL WINAPI hookedDrawEdge(HDC hdc, LPRECT rc, UINT edge, UINT flags) {
    if (gdiMaxBlock()) { gdiDiag(51, "DrawEdge", hdc, rc ? rc->left : 0, rc ? rc->top : 0,
                                 rc ? (rc->right - rc->left) : 1, rc ? (rc->bottom - rc->top) : 1); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && rc
        && rectHitsMcWindow(rc->left, rc->top, rc->right - rc->left, rc->bottom - rc->top)) return TRUE;
    return g_realDrawEdge(hdc, rc, edge, flags);
}
static BOOL WINAPI hookedDrawFrameControl(HDC hdc, LPRECT rc, UINT type, UINT state) {
    if (gdiMaxBlock()) { gdiDiag(52, "DrawFrameControl", hdc, rc ? rc->left : 0, rc ? rc->top : 0,
                                 rc ? (rc->right - rc->left) : 1, rc ? (rc->bottom - rc->top) : 1); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && rc
        && rectHitsMcWindow(rc->left, rc->top, rc->right - rc->left, rc->bottom - rc->top)) return TRUE;
    return g_realDrawFrameControl(hdc, rc, type, state);
}
static BOOL WINAPI hookedGrayString(HDC hdc, HBRUSH brush, void* proc, LPARAM lData, int x, int y, int w, int h, int cx, int cy) {
    if (gdiMaxBlock()) { gdiDiag(53, "GrayString", hdc, x, y, w, h); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isMcWindowDc(hdc)) return TRUE;
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc) && rectHitsMcWindow(x, y, w, h)) return TRUE;
    return g_realGrayString(hdc, brush, proc, lData, x, y, w, h, cx, cy);
}
static BOOL WINAPI hookedPaintDesktop(HDC hdc) {
    if (gdiMaxBlock()) { gdiDiag(54, "PaintDesktop", hdc, 0, 0, 1, 1); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && isScreenDc(hdc)) return TRUE;
    return g_realPaintDesktop(hdc);
}


static volatile LONG g_canvasDiag = 0;
static HDC WINAPI hookedGetDC(HWND hwnd) {
    if (maxOn() && hwnd == NULL) { // MAX：拒绝屏幕 DC（GetDC(NULL)/GetDC(GetDesktopWindow())）
        if (InterlockedIncrement(&g_canvasDiag) % 300 == 1) {
            printf("[taichi_hook] GetDC(NULL) BLOCKED (MAX)\n");
        }
        return NULL;
    }
    return g_realGetDC(hwnd);
}
static HDC WINAPI hookedCreateDCA(LPCSTR driver, LPCSTR device, LPCSTR output, const void* init) {
    if (maxOn()) { // MAX：拒绝一切 DC 创建（屏幕/打印机/内存画布一律不给）
        if (InterlockedIncrement(&g_canvasDiag) % 300 == 1) {
            printf("[taichi_hook] CreateDCA BLOCKED (MAX) driver=%s\n", driver ? driver : "(null)");
        }
        return NULL;
    }
    return g_realCreateDCA(driver, device, output, init);
}
static HDC WINAPI hookedCreateDCW(LPCWSTR driver, LPCWSTR device, LPCWSTR output, const void* init) {
    if (maxOn()) {
        if (InterlockedIncrement(&g_canvasDiag) % 300 == 1) {
            printf("[taichi_hook] CreateDCW BLOCKED (MAX)\n");
        }
        return NULL;
    }
    return g_realCreateDCW(driver, device, output, init);
}

static BOOL WINAPI hookedUpdateLayeredWindow(HWND hwnd, HDC hdcDst, const POINT* pptDst, const SIZE* psize,
                                             HDC hdcSrc, const POINT* pptSrc, COLORREF crKey,
                                             const BLENDFUNCTION* pblend, DWORD dwFlags) {
    if (hwnd == g_overlayWnd) { // 自己的 overlay（TaiChiOverlayWndClass）白名单
        return g_realUpdateLayeredWindow(hwnd, hdcDst, pptDst, psize, hdcSrc, pptSrc, crKey, pblend, dwFlags);
    }
    if (gdiMaxBlock()) { gdiDiag(15, "UpdateLayeredWindow", hdcDst,
                                 pptDst ? pptDst->x : 0, pptDst ? pptDst->y : 0,
                                 psize ? psize->cx : 0, psize ? psize->cy : 0); return TRUE; }
    if ((protectOn() || g_forceRedrawEnabled) && pptDst && psize
        && rectHitsMcWindow(pptDst->x, pptDst->y, psize->cx, psize->cy)) return TRUE;
    return g_realUpdateLayeredWindow(hwnd, hdcDst, pptDst, psize, hdcSrc, pptSrc, crKey, pblend, dwFlags);
}


#define DXGI_PRESENT_SLOT 8
#define DXGI_GETDESC_SLOT 12

typedef HRESULT (STDMETHODCALLTYPE *PFN_Present)(void* self, UINT sync, UINT flags);

#define MAX_PATCHED_SWAPCHAINS 32
static void*       g_patchedChains[MAX_PATCHED_SWAPCHAINS];
static PFN_Present g_origPresents[MAX_PATCHED_SWAPCHAINS];
static int         g_patchedCount = 0;

// DXGI_SWAP_CHAIN_DESC.OutputWindow 偏移 48（x64：BufferDesc 28 + SampleDesc 8 + BufferUsage 4 + BufferCount 4 = 44 → HWND 8 对齐 → 48）
struct DXGI_MIN_SWAP_DESC { char pad[48]; HWND OutputWindow; };
typedef HRESULT (STDMETHODCALLTYPE *PFN_GetDesc)(void* self, DXGI_MIN_SWAP_DESC* desc);

static BOOL isMcSwapChain(void* self) {
    if (!self) return FALSE;
    if (g_mcHwndCached == NULL) g_mcHwndCached = findMcWindow();
    if (!g_mcHwndCached) return FALSE;
    void** vtbl = *(void***)self;
    if (!vtbl || !vtbl[DXGI_GETDESC_SLOT]) return FALSE;
    DXGI_MIN_SWAP_DESC desc;
    ZeroMemory(&desc, sizeof(desc));
    if (((PFN_GetDesc)vtbl[DXGI_GETDESC_SLOT])(self, &desc) != S_OK) return FALSE;
    return desc.OutputWindow == g_mcHwndCached;
}

static volatile LONG g_presentBlockCounter = 0;
static volatile LONG g_d3dBlockCounter = 0;

static HRESULT STDMETHODCALLTYPE hookedPresent(void* self, UINT sync, UINT flags) {
    // 防御 / 实时重绘开 + 交换链目标 = MC 窗口 → 压制（假装成功，画面不更新——已存在覆盖层也画不出）
    if ((protectOn() || g_forceRedrawEnabled) && isMcSwapChain(self)) {
        if (InterlockedIncrement(&g_presentBlockCounter) % 300 == 1) { // 限频：每 300 次压打印 1 次
            printf("[taichi_hook] Blocked D3D Present on MC window (defense on)\n");
        }
        return S_OK;
    }
    for (int i = 0; i < g_patchedCount; i++) {
        if (g_patchedChains[i] == self) {
            return g_origPresents[i](self, sync, flags);
        }
    }
    return 0x887A0001; // 未记录（理论上不发生）——拒绝
}

static void patchSwapChainVtable(void* swapChain) {
    if (!swapChain) return;
    void** vtbl = *(void***)swapChain;
    if (!vtbl || !vtbl[DXGI_PRESENT_SLOT]) return;
    if (vtbl[DXGI_PRESENT_SLOT] == (void*)hookedPresent) return; // 已 patch
    if (g_patchedCount >= MAX_PATCHED_SWAPCHAINS) return;
    DWORD oldProtect;
    if (VirtualProtect(&vtbl[DXGI_PRESENT_SLOT], sizeof(void*), PAGE_READWRITE, &oldProtect)) {
        g_origPresents[g_patchedCount] = (PFN_Present)vtbl[DXGI_PRESENT_SLOT];
        g_patchedChains[g_patchedCount] = swapChain;
        g_patchedCount++;
        vtbl[DXGI_PRESENT_SLOT] = (void*)hookedPresent;
        VirtualProtect(&vtbl[DXGI_PRESENT_SLOT], sizeof(void*), oldProtect, &oldProtect);
        printf("[taichi_hook] DXGI swap chain Present patched (%p)\n", swapChain);
    }
}

// D3D：D3D11CreateDeviceAndSwapChain 的目标窗口 == MC 主窗口 + 防御开 → 拒绝
// DXGI_SWAP_CHAIN_DESC.OutputWindow 偏移 48（x64：BufferDesc 28 + SampleDesc 8 + BufferUsage 4 + BufferCount 4 = 44 → HWND 8 对齐 → 48）
static HRESULT WINAPI hookedD3D11CreateDeviceAndSwapChain(
    void* pAdapter, UINT DriverType, HMODULE Software, UINT Flags,
    const void* pFeatureLevels, UINT FeatureLevels, UINT SDKVersion,
    const void* pSwapChainDesc, void** ppSwapChain, void** ppDevice,
    void* pFeatureLevel, void** ppImmediateContext) {
    if ((protectOn() || g_forceRedrawEnabled) && pSwapChainDesc) {
        HWND target = *(HWND*)((const char*)pSwapChainDesc + 48);
        if (g_mcHwndCached == NULL) g_mcHwndCached = findMcWindow();
        if (target != NULL && target == g_mcHwndCached) {
            if (InterlockedIncrement(&g_d3dBlockCounter) % 60 == 1) { // 限频：攻击每帧重试创建，防刷屏
                fprintf(stderr, "[taichi_hook] Blocked D3D11 swap chain on MC window (defense on)\n");
            }
            return 0x887A0001; // DXGI_ERROR_INVALID_CALL
        }
    }
    HRESULT hr = g_realD3D11CreateDeviceAndSwapChain(
        pAdapter, DriverType, Software, Flags, pFeatureLevels, FeatureLevels, SDKVersion,
        pSwapChainDesc, ppSwapChain, ppDevice, pFeatureLevel, ppImmediateContext);
    // 创建成功（无论防御开关）→ patch Present——防御开时已存在交换链也画不出
    if (SUCCEEDED(hr) && ppSwapChain && *ppSwapChain) {
        patchSwapChainVtable(*ppSwapChain);
    }
    return hr;
}

// CreateSwapChainForHwnd（vtable 补丁目标）：protect 开 + 目标窗口 = MC → 拒绝（D3D/D2D 共用出口）
static HRESULT STDMETHODCALLTYPE hookedCreateSwapChainForHwnd(
    void* factory, HWND hwnd, const void* desc, const void* fullscreen,
    const void* restrictOut, void** swapchain) {
    if ((protectOn() || g_forceRedrawEnabled) && g_mcHwndCached == NULL) g_mcHwndCached = findMcWindow();
    if ((protectOn() || g_forceRedrawEnabled) && hwnd != NULL && hwnd == g_mcHwndCached) {
        fprintf(stderr, "[taichi_hook] Blocked CreateSwapChainForHwnd on MC window (defense on)\n");
        return 0x887A0001; // DXGI_ERROR_INVALID_CALL
    }
    typedef HRESULT (STDMETHODCALLTYPE *PFN_Orig)(void*, HWND, const void*, const void*, const void*, void**);
    HRESULT hr = ((PFN_Orig)g_origSwapChainForHwnd)(factory, hwnd, desc, fullscreen, restrictOut, swapchain);
    // 创建成功（无论防御开关）→ patch Present——防御开时已存在交换链也画不出
    if (SUCCEEDED(hr) && swapchain && *swapchain) {
        patchSwapChainVtable(*swapchain);
    }
    return hr;
}

// 替换工厂 vtable 的 CreateSwapChainForHwnd 项（内存写，VirtualProtect RW）
// 先 QueryInterface 验证工厂实现 IDXGIFactory2——否则 vtbl[15] 越界写会写坏内存导致崩溃
static void patchFactoryVtable(void* factory) {
    if (!factory) return;
    void** vtbl = *(void***)factory;
    if (!vtbl || !vtbl[CREATE_SWAP_CHAIN_FOR_HWND_SLOT]) return;
    if (vtbl[CREATE_SWAP_CHAIN_FOR_HWND_SLOT] == (void*)hookedCreateSwapChainForHwnd) return;
    // IID_IDXGIFactory2 { 50ec83d2-f552-4be1-a3a2-1a7e5d601cd0 }
    IID iidFactory2;
    iidFactory2.Data1 = 0x50ec83d2; iidFactory2.Data2 = 0xf552;
    iidFactory2.Data3 = 0x4be1; iidFactory2.Data4[0] = 0xa3; iidFactory2.Data4[1] = 0xa2;
    iidFactory2.Data4[2] = 0x1a; iidFactory2.Data4[3] = 0x7e; iidFactory2.Data4[4] = 0x5d;
    iidFactory2.Data4[5] = 0x60; iidFactory2.Data4[6] = 0x1c; iidFactory2.Data4[7] = 0xd0;
    typedef HRESULT (STDMETHODCALLTYPE *PFN_QI)(void*, const IID&, void**);
    void* f2 = NULL;
    if (FAILED(((PFN_QI)vtbl[0])(factory, iidFactory2, &f2))) {
        fprintf(stderr, "[taichi_hook] Factory is not IDXGIFactory2; skip vtable patch\n");
        return;
    }
    g_origSwapChainForHwnd = vtbl[CREATE_SWAP_CHAIN_FOR_HWND_SLOT];
    DWORD oldProtect;
    if (VirtualProtect(&vtbl[CREATE_SWAP_CHAIN_FOR_HWND_SLOT], sizeof(void*), PAGE_READWRITE, &oldProtect)) {
        vtbl[CREATE_SWAP_CHAIN_FOR_HWND_SLOT] = (void*)hookedCreateSwapChainForHwnd;
        VirtualProtect(&vtbl[CREATE_SWAP_CHAIN_FOR_HWND_SLOT], sizeof(void*), oldProtect, &oldProtect);
        printf("[taichi_hook] DXGI factory vtable patched (CreateSwapChainForHwnd)\n");
    }
}

static HRESULT WINAPI hookedCreateDXGIFactory(REFIID riid, void** ppFactory) {
    HRESULT hr = g_realCreateDXGIFactory(riid, ppFactory);
    if (SUCCEEDED(hr) && ppFactory && *ppFactory) patchFactoryVtable(*ppFactory);
    return hr;
}

static HRESULT WINAPI hookedCreateDXGIFactory1(REFIID riid, void** ppFactory) {
    HRESULT hr = g_realCreateDXGIFactory1(riid, ppFactory);
    if (SUCCEEDED(hr) && ppFactory && *ppFactory) patchFactoryVtable(*ppFactory);
    return hr;
}



// 查找 MC 主窗口（本进程、可见、有标题的顶层窗）
static HWND findMcWindow(void) {
    DWORD pid = GetCurrentProcessId();
    HWND hwnd = NULL;
    HWND best = NULL;
    while ((hwnd = FindWindowExA(NULL, hwnd, NULL, NULL)) != NULL) {
        DWORD wpid = 0;
        GetWindowThreadProcessId(hwnd, &wpid);
        if (wpid != pid) continue;
        if (!IsWindowVisible(hwnd)) continue;
        if (GetWindow(hwnd, GW_OWNER) != NULL) continue; // 跳过子窗
        RECT rc; GetWindowRect(hwnd, &rc);
        if ((rc.right - rc.left) < 100 || (rc.bottom - rc.top) < 100) continue;
        best = hwnd;
        break;
    }
    return best;
}

// 把当前帧通过 UpdateLayeredWindow 贴到分层窗口（预乘 alpha）
// 定位/尺寸完全用 Java 经 GLFW 传来的窗口客户区左上角(g_winX/Y)与 framebuffer 尺寸(w x h)，
// 不再由 C 层自己 findMcWindow/ClientToScreen（会找错窗口→跑左上角），也不做多余缩放（1:1 直贴）。
static void blitFrameToLayered(void) {
    if (!g_overlayWnd) return;
    EnterCriticalSection(&g_frameLock);
    int w = g_frameW, h = g_frameH;
    if (!g_frameBits || w <= 0 || h <= 0) { LeaveCriticalSection(&g_frameLock); return; }

    int dstX = (int)g_winX;
    int dstY = (int)g_winY;

    // 32bpp DIB（framebuffer 物理像素，直接作为最终位图，不缩放）
    BITMAPINFO bmi; ZeroMemory(&bmi, sizeof(bmi));
    bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bmi.bmiHeader.biWidth = w;
    bmi.bmiHeader.biHeight = -h;   // 负 = 自上而下
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 32;
    bmi.bmiHeader.biCompression = BI_RGB;

    void* dibBits = NULL;
    HDC screenDC = g_realGetDC(NULL); // 穿透画布封锁（MAX 时自己通道仍可用）
    HDC memDC = CreateCompatibleDC(screenDC);
    HBITMAP dib = CreateDIBSection(memDC, &bmi, DIB_RGB_COLORS, &dibBits, NULL, 0);
    if (dib && dibBits) {
        // 源为 0xAARRGGBB；填入 DIB（BGRA 字节序），预乘 alpha
        unsigned int* s = (unsigned int*)g_frameBits;
        unsigned int* d = (unsigned int*)dibBits;
        for (int i = 0; i < w * h; i++) {
            unsigned int p = s[i];
            unsigned int a = (p >> 24) & 0xFF;
            unsigned int r = (p >> 16) & 0xFF;
            unsigned int g = (p >> 8) & 0xFF;
            unsigned int b = p & 0xFF;
            r = r * a / 255; g = g * a / 255; b = b * a / 255;
            d[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        HBITMAP oldBmp = (HBITMAP)SelectObject(memDC, dib);

        POINT ptDst = { dstX, dstY };
        SIZE sz = { w, h };
        POINT ptSrc = { 0, 0 };
        BLENDFUNCTION bf = { AC_SRC_OVER, 0, 255, AC_SRC_ALPHA };
        UpdateLayeredWindow(g_overlayWnd, screenDC, &ptDst, &sz,
                            memDC, &ptSrc, 0, &bf, ULW_ALPHA);

        SelectObject(memDC, oldBmp);
    }
    if (dib) DeleteObject(dib);
    if (memDC) DeleteDC(memDC);
    if (screenDC) ReleaseDC(NULL, screenDC);

    LeaveCriticalSection(&g_frameLock);
}

static LRESULT CALLBACK overlayWndProc(HWND hWnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_TIMER:
        if (InterlockedCompareExchange(&g_overlayVisible, 1, 1) == 1) {
            // 每帧重新抢占置顶：即使被其他 topmost 窗口压下也能抢回最上层，不需提权
            SetWindowPos(hWnd, HWND_TOPMOST, 0, 0, 0, 0,
                         SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOSENDCHANGING);
            InterlockedExchange(&g_frameDirty, 0);
            blitFrameToLayered();
        }
        return 0;
    case WM_DESTROY:
        return 0;
    }
    return DefWindowProc(hWnd, msg, wp, lp);
}

static DWORD WINAPI overlayThreadProc(LPVOID param) {
    (void)param;
    WNDCLASSEXA wc; ZeroMemory(&wc, sizeof(wc));
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = overlayWndProc;
    wc.hInstance = GetModuleHandleA(NULL);
    wc.lpszClassName = "TaiChiOverlayWndClass";
    RegisterClassExA(&wc);

    // WS_EX_LAYERED 分层 + WS_EX_TRANSPARENT 点击穿透 + WS_EX_TOPMOST 置顶 + WS_EX_TOOLWINDOW 不上任务栏
    g_overlayWnd = CreateWindowExA(
        WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
        "TaiChiOverlayWndClass", "TaiChiOverlay",
        WS_POPUP,
        0, 0, 100, 100,
        NULL, NULL, wc.hInstance, NULL);
    if (!g_overlayWnd) {
        fprintf(stderr, "[taichi_hook] CreateWindowEx overlay failed: %lu\n", GetLastError());
        return 1;
    }

    // 初始隐藏；由 nativeSetOverlayVisible 控制显隐
    ShowWindow(g_overlayWnd, SW_HIDE);
    SetTimer(g_overlayWnd, 1, 16, NULL); // ~60fps 刷新

    MSG msg;
    while (GetMessage(&msg, NULL, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }
    return 0;
}

static void ensureOverlayThread(void) {
    if (!g_lockInit) {
        InitializeCriticalSection(&g_frameLock);
        g_lockInit = TRUE;
    }
    if (g_overlayThread) return;
    g_overlayThread = CreateThread(NULL, 0, overlayThreadProc, NULL, 0, &g_overlayThreadId);
}



static HMODULE findGlfwModule(void) {
    HMODULE hGlfw = GetModuleHandleA("glfw.dll");
    if (hGlfw) return hGlfw;
    hGlfw = GetModuleHandleA("glfw3.dll");
    if (hGlfw) return hGlfw;
    hGlfw = GetModuleHandleA("lwjgl_glfw.dll");
    if (hGlfw) return hGlfw;

    // Enumerate all modules to find one containing "glfw"
    HMODULE mods[1024];
    DWORD cb;
    if (EnumProcessModules(GetCurrentProcess(), mods, sizeof(mods), &cb)) {
        for (DWORD i = 0; i < cb / sizeof(HMODULE); i++) {
            char name[MAX_PATH];
            if (GetModuleFileNameA(mods[i], name, MAX_PATH)) {
                // lowercase search
                for (char* p = name; *p; p++) *p = (char)tolower((unsigned char)*p);
                if (strstr(name, "glfw")) {
                    printf("[taichi_hook] Found GLFW module: %s\n", name);
                    return mods[i];
                }
            }
        }
    }
    return NULL;
}

static int installHook(void) {
    HMODULE hGlfw = findGlfwModule();
    if (!hGlfw) {
        fprintf(stderr, "[taichi_hook] ERROR: Cannot find GLFW module\n");
        return 0;
    }

    g_realSwapBuffers = (PFN_glfwSwapBuffers)GetProcAddress(hGlfw, "glfwSwapBuffers");
    if (!g_realSwapBuffers) {
        fprintf(stderr, "[taichi_hook] ERROR: glfwSwapBuffers not found\n");
        return 0;
    }
    printf("[taichi_hook] glfwSwapBuffers at %p\n", (void*)g_realSwapBuffers);
    // 强制重绘用：唤醒渲染线程事件循环（渲染循环若阻塞在 glfwWaitEvents，post 空事件即醒来跑下一帧）
    g_realGlfwPostEmptyEvent = (PFN_glfwPostEmptyEvent)GetProcAddress(hGlfw, "glfwPostEmptyEvent");
    if (g_realGlfwPostEmptyEvent) {
        printf("[taichi_hook] glfwPostEmptyEvent at %p\n", (void*)g_realGlfwPostEmptyEvent);
    }
    // 死亡鼠标弹出（FullDeath）：glfwSetInputMode/glfwSetCursorPos——解除 MC 鼠标锁定
    g_realGlfwSetInputMode = (PFN_glfwSetInputMode)GetProcAddress(hGlfw, "glfwSetInputMode");
    g_realGlfwSetCursorPos = (PFN_glfwSetCursorPos)GetProcAddress(hGlfw, "glfwSetCursorPos");
    if (g_realGlfwSetInputMode) printf("[taichi_hook] glfwSetInputMode at %p\n", (void*)g_realGlfwSetInputMode);
    if (g_realGlfwSetCursorPos) printf("[taichi_hook] glfwSetCursorPos at %p\n", (void*)g_realGlfwSetCursorPos);

    // --- 防御 hook 目标解析（opengl32/gdi32/d3d11；拿不到就跳过该项，不阻塞主 hook） ---
    HMODULE hOpengl = GetModuleHandleA("opengl32.dll");
    if (!hOpengl) hOpengl = LoadLibraryA("opengl32.dll");
    if (hOpengl) {
        g_realGlDrawElements = (PFN_glDrawElements)GetProcAddress(hOpengl, "glDrawElements");
        g_realGlDrawArrays   = (PFN_glDrawArrays)GetProcAddress(hOpengl, "glDrawArrays");
        g_realGlClear        = (PFN_glClear)GetProcAddress(hOpengl, "glClear");
        g_realGlClearColor   = (PFN_glClearColor)GetProcAddress(hOpengl, "glClearColor");
        g_realWglSwapBuffers = (PFN_wglSwapBuffers)GetProcAddress(hOpengl, "wglSwapBuffers");
        g_realWglSwapLayerBuffers = (PFN_wglSwapLayerBuffers)GetProcAddress(hOpengl, "wglSwapLayerBuffers");
        g_realWglMakeCurrent = (PFN_wglMakeCurrent)GetProcAddress(hOpengl, "wglMakeCurrent");
        printf("[taichi_hook] GL hooks: drawElements=%p drawArrays=%p clear=%p wglSwapBuffers=%p\n",
               (void*)g_realGlDrawElements, (void*)g_realGlDrawArrays,
               (void*)g_realGlClear, (void*)g_realWglSwapBuffers);
    }

    HMODULE hGdi = GetModuleHandleA("gdi32.dll");
    if (!hGdi) hGdi = LoadLibraryA("gdi32.dll");
    if (hGdi) {
        g_realExtTextOutW = (PFN_ExtTextOutW)GetProcAddress(hGdi, "ExtTextOutW");
        g_realExtTextOutA = (PFN_ExtTextOutA)GetProcAddress(hGdi, "ExtTextOutA");
        g_realTextOutW    = (PFN_TextOutW)GetProcAddress(hGdi, "TextOutW");
        g_realTextOutA    = (PFN_TextOutA)GetProcAddress(hGdi, "TextOutA");
        g_realSetPixelV   = (PFN_SetPixelV)GetProcAddress(hGdi, "SetPixelV");
        g_realBitBlt      = (PFN_BitBlt)GetProcAddress(hGdi, "BitBlt");
        g_realStretchBlt  = (PFN_StretchBlt)GetProcAddress(hGdi, "StretchBlt");
        g_realPatBlt      = (PFN_PatBlt)GetProcAddress(hGdi, "PatBlt");
        g_realFillRect    = (PFN_FillRect)GetProcAddress(hGdi, "FillRect");
        g_realSetDIBitsToDevice = (PFN_SetDIBitsToDevice)GetProcAddress(hGdi, "SetDIBitsToDevice");
        g_realStretchDIBits     = (PFN_StretchDIBits)GetProcAddress(hGdi, "StretchDIBits");
        g_realPlayEnhMetaFile   = (PFN_PlayEnhMetaFile)GetProcAddress(hGdi, "PlayEnhMetaFile");
        g_realRectangle  = (PFN_Rectangle)GetProcAddress(hGdi, "Rectangle");
        g_realEllipse    = (PFN_Ellipse)GetProcAddress(hGdi, "Ellipse");
        g_realRoundRect  = (PFN_RoundRect)GetProcAddress(hGdi, "RoundRect");
        g_realLineTo     = (PFN_LineTo)GetProcAddress(hGdi, "LineTo");
        g_realPolyline   = (PFN_Polyline)GetProcAddress(hGdi, "Polyline");
        g_realPolygon    = (PFN_Polygon)GetProcAddress(hGdi, "Polygon");
        g_realPolyPolygon = (PFN_PolyPolygon)GetProcAddress(hGdi, "PolyPolygon");
        g_realFillRgn    = (PFN_FillRgn)GetProcAddress(hGdi, "FillRgn");
        g_realFrameRgn   = (PFN_FrameRgn)GetProcAddress(hGdi, "FrameRgn");
        g_realInvertRgn  = (PFN_InvertRgn)GetProcAddress(hGdi, "InvertRgn");
        g_realPaintRgn   = (PFN_PaintRgn)GetProcAddress(hGdi, "PaintRgn");
        g_realArc        = (PFN_Arc)GetProcAddress(hGdi, "Arc");
        g_realChord      = (PFN_Chord)GetProcAddress(hGdi, "Chord");
        g_realPie        = (PFN_Pie)GetProcAddress(hGdi, "Pie");
        g_realExtFloodFill = (PFN_ExtFloodFill)GetProcAddress(hGdi, "ExtFloodFill");
        g_realPolyBezier = (PFN_PolyBezier)GetProcAddress(hGdi, "PolyBezier");

        g_realFloodFill     = (PFN_FloodFill)GetProcAddress(hGdi, "FloodFill");
        g_realPolylineTo    = (PFN_PolylineTo)GetProcAddress(hGdi, "PolylineTo");
        g_realPolyPolyline  = (PFN_PolyPolyline)GetProcAddress(hGdi, "PolyPolyline");
        g_realPolyBezierTo  = (PFN_PolyBezierTo)GetProcAddress(hGdi, "PolyBezierTo");
        g_realPolyDraw      = (PFN_PolyDraw)GetProcAddress(hGdi, "PolyDraw");
        g_realInvertRect    = (PFN_InvertRect)GetProcAddress(hGdi, "InvertRect");
        g_realFillPath      = (PFN_FillPath)GetProcAddress(hGdi, "FillPath");
        g_realStrokePath    = (PFN_StrokePath)GetProcAddress(hGdi, "StrokePath");
        g_realStrokeAndFillPath = (PFN_StrokeAndFillPath)GetProcAddress(hGdi, "StrokeAndFillPath");
        g_realGradientFill  = (PFN_GradientFill)GetProcAddress(hGdi, "GradientFill");
        g_realAngleArc      = (PFN_AngleArc)GetProcAddress(hGdi, "AngleArc");
        g_realSetPixel      = (PFN_SetPixel)GetProcAddress(hGdi, "SetPixel");
        g_realFrameRect     = (PFN_FrameRect)GetProcAddress(hGdi, "FrameRect");
        g_realTabbedTextOutW = (PFN_TabbedTextOutW)GetProcAddress(hGdi, "TabbedTextOutW");
        g_realTabbedTextOutA = (PFN_TabbedTextOutA)GetProcAddress(hGdi, "TabbedTextOutA");
        g_realCreateDCA      = (PFN_CreateDCA)GetProcAddress(hGdi, "CreateDCA");
        g_realCreateDCW      = (PFN_CreateDCW)GetProcAddress(hGdi, "CreateDCW");
        printf("[taichi_hook] GDI hooks: ExtTextOutW=%p TextOutW=%p SetPixelV=%p BitBlt=%p StretchBlt=%p PatBlt=%p FillRect=%p SetDIBits=%p StretchDIBits=%p vec=%p/%p/%p/%p/%p\n",
               (void*)g_realExtTextOutW, (void*)g_realTextOutW, (void*)g_realSetPixelV,
               (void*)g_realBitBlt, (void*)g_realStretchBlt, (void*)g_realPatBlt,
               (void*)g_realFillRect, (void*)g_realSetDIBitsToDevice, (void*)g_realStretchDIBits,
               (void*)g_realRectangle, (void*)g_realEllipse, (void*)g_realRoundRect, (void*)g_realLineTo, (void*)g_realPolygon);
    }

    // AlphaBlend/TransparentBlt 在 msimg32.dll（gdi32 不导出）
    HMODULE hMsimg = GetModuleHandleA("msimg32.dll");
    if (!hMsimg) hMsimg = LoadLibraryA("msimg32.dll");
    if (hMsimg) {
        g_realAlphaBlend  = (PFN_AlphaBlend)GetProcAddress(hMsimg, "AlphaBlend");
        g_realTransparentBlt = (PFN_TransparentBlt)GetProcAddress(hMsimg, "TransparentBlt");
        printf("[taichi_hook] MSIMG32 hooks: AlphaBlend=%p TransparentBlt=%p\n",
               (void*)g_realAlphaBlend, (void*)g_realTransparentBlt);
    }

    HMODULE hUser32 = GetModuleHandleA("user32.dll");
    if (hUser32) {
        g_realUpdateLayeredWindow = (PFN_UpdateLayeredWindow)GetProcAddress(hUser32, "UpdateLayeredWindow");
        g_realDrawTextW = (PFN_DrawTextW)GetProcAddress(hUser32, "DrawTextW");
        g_realDrawTextA = (PFN_DrawTextA)GetProcAddress(hUser32, "DrawTextA");

        g_realDrawIcon      = (PFN_DrawIcon)GetProcAddress(hUser32, "DrawIcon");
        g_realDrawIconEx    = (PFN_DrawIconEx)GetProcAddress(hUser32, "DrawIconEx");
        g_realDrawState     = (PFN_DrawState)GetProcAddress(hUser32, "DrawState");
        g_realDrawEdge      = (PFN_DrawEdge)GetProcAddress(hUser32, "DrawEdge");
        g_realDrawFrameControl = (PFN_DrawFrameControl)GetProcAddress(hUser32, "DrawFrameControl");
        g_realGrayString    = (PFN_GrayString)GetProcAddress(hUser32, "GrayString");
        g_realPaintDesktop  = (PFN_PaintDesktop)GetProcAddress(hUser32, "PaintDesktop");
        g_realGetDC         = (PFN_GetDC)GetProcAddress(hUser32, "GetDC");
        printf("[taichi_hook] USER32 hooks: UpdateLayeredWindow=%p DrawTextW=%p DrawTextA=%p icons=%p/%p/%p/%p/%p/%p/%p GetDC=%p\n",
               (void*)g_realUpdateLayeredWindow, (void*)g_realDrawTextW, (void*)g_realDrawTextA,
               (void*)g_realDrawIcon, (void*)g_realDrawIconEx, (void*)g_realDrawState,
               (void*)g_realDrawEdge, (void*)g_realDrawFrameControl, (void*)g_realGrayString,
               (void*)g_realPaintDesktop, (void*)g_realGetDC);
    }

    HMODULE hD3d11 = GetModuleHandleA("d3d11.dll");
    if (!hD3d11) hD3d11 = LoadLibraryA("d3d11.dll");
    if (hD3d11) {
        g_realD3D11CreateDeviceAndSwapChain =
            (PFN_D3D11CreateDeviceAndSwapChain)GetProcAddress(hD3d11, "D3D11CreateDeviceAndSwapChain");
        printf("[taichi_hook] D3D11CreateDeviceAndSwapChain=%p\n",
               (void*)g_realD3D11CreateDeviceAndSwapChain);
    }

    HMODULE hDxgi = GetModuleHandleA("dxgi.dll");
    if (!hDxgi) hDxgi = LoadLibraryA("dxgi.dll");
    if (hDxgi) {
        g_realCreateDXGIFactory  = (PFN_CreateDXGIFactory)GetProcAddress(hDxgi, "CreateDXGIFactory");
        g_realCreateDXGIFactory1 = (PFN_CreateDXGIFactory1)GetProcAddress(hDxgi, "CreateDXGIFactory1");
        printf("[taichi_hook] CreateDXGIFactory=%p CreateDXGIFactory1=%p\n",
               (void*)g_realCreateDXGIFactory, (void*)g_realCreateDXGIFactory1);
    }

    // LoadLibrary 四件套已由 installOsBlockHooks 在 JNI_OnLoad 挂载。
    // 此处禁止再取地址/再挂——否则会把 g_realLoadLibrary* 覆盖为被补丁的入口 →
    // 跳板自环 → 无限递归栈溢出（2026-08-17 实测事故：PROBE VEH 0xC00000FD @hookedLoadLibraryA）。

    // Detours handles RIP-relative relocation automatically
    LONG error = DetourTransactionBegin();
    if (error != NO_ERROR) {
        fprintf(stderr, "[taichi_hook] DetourTransactionBegin failed: %ld\n", error);
        return 0;
    }

    DetourUpdateThread(GetCurrentThread());

    error = DetourAttach(&(PVOID&)g_realSwapBuffers, (PVOID)hookedGlfwSwapBuffers);
    if (error != NO_ERROR) {
        fprintf(stderr, "[taichi_hook] DetourAttach(glfwSwapBuffers) failed: %ld\n", error);
        DetourTransactionAbort();
        return 0;
    }
    if (g_realGlfwSetInputMode)
        DetourAttach(&(PVOID&)g_realGlfwSetInputMode, (PVOID)hookedGlfwSetInputMode);
    if (g_realGlfwSetCursorPos)
        DetourAttach(&(PVOID&)g_realGlfwSetCursorPos, (PVOID)hookedGlfwSetCursorPos);

    if (g_realGlDrawElements)   DetourAttach(&(PVOID&)g_realGlDrawElements,   (PVOID)hookedGlDrawElements);
    if (g_realGlDrawArrays)     DetourAttach(&(PVOID&)g_realGlDrawArrays,     (PVOID)hookedGlDrawArrays);
    if (g_realGlClear)          DetourAttach(&(PVOID&)g_realGlClear,          (PVOID)hookedGlClear);
    if (g_realWglSwapBuffers)   DetourAttach(&(PVOID&)g_realWglSwapBuffers,   (PVOID)hookedWglSwapBuffers);
    if (g_realWglSwapLayerBuffers) DetourAttach(&(PVOID&)g_realWglSwapLayerBuffers, (PVOID)hookedWglSwapLayerBuffers);
    if (g_realWglMakeCurrent)   DetourAttach(&(PVOID&)g_realWglMakeCurrent,   (PVOID)hookedWglMakeCurrent);
    if (g_realExtTextOutW)      DetourAttach(&(PVOID&)g_realExtTextOutW,      (PVOID)hookedExtTextOutW);
    if (g_realExtTextOutA)      DetourAttach(&(PVOID&)g_realExtTextOutA,      (PVOID)hookedExtTextOutA);
    if (g_realTextOutW)         DetourAttach(&(PVOID&)g_realTextOutW,         (PVOID)hookedTextOutW);
    if (g_realTextOutA)         DetourAttach(&(PVOID&)g_realTextOutA,         (PVOID)hookedTextOutA);
    if (g_realDrawTextW)        DetourAttach(&(PVOID&)g_realDrawTextW,        (PVOID)hookedDrawTextW);
    if (g_realDrawTextA)        DetourAttach(&(PVOID&)g_realDrawTextA,        (PVOID)hookedDrawTextA);
    if (g_realSetPixelV)        DetourAttach(&(PVOID&)g_realSetPixelV,        (PVOID)hookedSetPixelV);
    if (g_realBitBlt)           DetourAttach(&(PVOID&)g_realBitBlt,           (PVOID)hookedBitBlt);
    if (g_realStretchBlt)       DetourAttach(&(PVOID&)g_realStretchBlt,       (PVOID)hookedStretchBlt);
    if (g_realAlphaBlend)       DetourAttach(&(PVOID&)g_realAlphaBlend,       (PVOID)hookedAlphaBlend);
    if (g_realTransparentBlt)   DetourAttach(&(PVOID&)g_realTransparentBlt,   (PVOID)hookedTransparentBlt);
    if (g_realPatBlt)           DetourAttach(&(PVOID&)g_realPatBlt,           (PVOID)hookedPatBlt);
    if (g_realFillRect)         DetourAttach(&(PVOID&)g_realFillRect,         (PVOID)hookedFillRect);
    if (g_realSetDIBitsToDevice) DetourAttach(&(PVOID&)g_realSetDIBitsToDevice, (PVOID)hookedSetDIBitsToDevice);
    if (g_realStretchDIBits)    DetourAttach(&(PVOID&)g_realStretchDIBits,    (PVOID)hookedStretchDIBits);
    if (g_realPlayEnhMetaFile)  DetourAttach(&(PVOID&)g_realPlayEnhMetaFile,  (PVOID)hookedPlayEnhMetaFile);
    if (g_realRectangle)        DetourAttach(&(PVOID&)g_realRectangle,        (PVOID)hookedRectangle);
    if (g_realEllipse)          DetourAttach(&(PVOID&)g_realEllipse,          (PVOID)hookedEllipse);
    if (g_realRoundRect)        DetourAttach(&(PVOID&)g_realRoundRect,        (PVOID)hookedRoundRect);
    if (g_realLineTo)           DetourAttach(&(PVOID&)g_realLineTo,           (PVOID)hookedLineTo);
    if (g_realPolyline)         DetourAttach(&(PVOID&)g_realPolyline,         (PVOID)hookedPolyline);
    if (g_realPolygon)          DetourAttach(&(PVOID&)g_realPolygon,          (PVOID)hookedPolygon);
    if (g_realPolyPolygon)      DetourAttach(&(PVOID&)g_realPolyPolygon,      (PVOID)hookedPolyPolygon);
    if (g_realFillRgn)          DetourAttach(&(PVOID&)g_realFillRgn,          (PVOID)hookedFillRgn);
    if (g_realFrameRgn)         DetourAttach(&(PVOID&)g_realFrameRgn,         (PVOID)hookedFrameRgn);
    if (g_realInvertRgn)        DetourAttach(&(PVOID&)g_realInvertRgn,        (PVOID)hookedInvertRgn);
    if (g_realPaintRgn)         DetourAttach(&(PVOID&)g_realPaintRgn,         (PVOID)hookedPaintRgn);
    if (g_realArc)              DetourAttach(&(PVOID&)g_realArc,              (PVOID)hookedArc);
    if (g_realChord)            DetourAttach(&(PVOID&)g_realChord,            (PVOID)hookedChord);
    if (g_realPie)              DetourAttach(&(PVOID&)g_realPie,              (PVOID)hookedPie);
    if (g_realExtFloodFill)     DetourAttach(&(PVOID&)g_realExtFloodFill,     (PVOID)hookedExtFloodFill);
    if (g_realPolyBezier)       DetourAttach(&(PVOID&)g_realPolyBezier,       (PVOID)hookedPolyBezier);
    if (g_realUpdateLayeredWindow)
        DetourAttach(&(PVOID&)g_realUpdateLayeredWindow, (PVOID)hookedUpdateLayeredWindow);
    if (g_realD3D11CreateDeviceAndSwapChain)
        DetourAttach(&(PVOID&)g_realD3D11CreateDeviceAndSwapChain, (PVOID)hookedD3D11CreateDeviceAndSwapChain);
    if (g_realCreateDXGIFactory)
        DetourAttach(&(PVOID&)g_realCreateDXGIFactory, (PVOID)hookedCreateDXGIFactory);
    if (g_realCreateDXGIFactory1)
        DetourAttach(&(PVOID&)g_realCreateDXGIFactory1, (PVOID)hookedCreateDXGIFactory1);
    // LoadLibrary 不再在此挂载（JNI_OnLoad 的 installOsBlockHooks 已挂；二次挂载=跳板自环）

    error = DetourTransactionCommit();
    if (error != NO_ERROR) {
        fprintf(stderr, "[taichi_hook] DetourTransactionCommit failed: %ld\n", error);
        return 0;
    }

    // 安装成功后保存各 hook 入口快照（供完整性周期校验/对抗还原绕过）
    g_hookCount = 0;
    captureHook((void*)GetProcAddress(hGlfw, "glfwSwapBuffers"), (void**)&g_realSwapBuffers, (void*)hookedGlfwSwapBuffers);
    captureHook((void*)GetProcAddress(hGlfw, "glfwSetInputMode"), (void**)&g_realGlfwSetInputMode, (void*)hookedGlfwSetInputMode);
    captureHook((void*)GetProcAddress(hGlfw, "glfwSetCursorPos"), (void**)&g_realGlfwSetCursorPos, (void*)hookedGlfwSetCursorPos);
    if (hOpengl) {
        captureHook((void*)GetProcAddress(hOpengl, "glDrawElements"), (void**)&g_realGlDrawElements, (void*)hookedGlDrawElements);
        captureHook((void*)GetProcAddress(hOpengl, "glDrawArrays"), (void**)&g_realGlDrawArrays, (void*)hookedGlDrawArrays);
        captureHook((void*)GetProcAddress(hOpengl, "glClear"), (void**)&g_realGlClear, (void*)hookedGlClear);
        captureHook((void*)GetProcAddress(hOpengl, "glDrawPixels"), (void**)&g_realGlDrawPixels, (void*)hookedGlDrawPixels);
        captureHook((void*)GetProcAddress(hOpengl, "glBegin"), (void**)&g_realGlBegin, (void*)hookedGlBegin);
        captureHook((void*)GetProcAddress(hOpengl, "glBitmap"), (void**)&g_realGlBitmap, (void*)hookedGlBitmap);
        captureHook((void*)GetProcAddress(hOpengl, "glCopyPixels"), (void**)&g_realGlCopyPixels, (void*)hookedGlCopyPixels);
        captureHook((void*)GetProcAddress(hOpengl, "glDrawRangeElements"), (void**)&g_realGlDrawRangeElements, (void*)hookedGlDrawRangeElements);
        captureHook((void*)GetProcAddress(hOpengl, "wglSwapBuffers"), (void**)&g_realWglSwapBuffers, (void*)hookedWglSwapBuffers);
        captureHook((void*)GetProcAddress(hOpengl, "wglSwapLayerBuffers"), (void**)&g_realWglSwapLayerBuffers, (void*)hookedWglSwapLayerBuffers);
        captureHook((void*)GetProcAddress(hOpengl, "wglMakeCurrent"), (void**)&g_realWglMakeCurrent, (void*)hookedWglMakeCurrent);
    }
    if (hGdi) {
        captureHook((void*)GetProcAddress(hGdi, "ExtTextOutW"), (void**)&g_realExtTextOutW, (void*)hookedExtTextOutW);
        captureHook((void*)GetProcAddress(hGdi, "ExtTextOutA"), (void**)&g_realExtTextOutA, (void*)hookedExtTextOutA);
        captureHook((void*)GetProcAddress(hGdi, "TextOutW"), (void**)&g_realTextOutW, (void*)hookedTextOutW);
        captureHook((void*)GetProcAddress(hGdi, "TextOutA"), (void**)&g_realTextOutA, (void*)hookedTextOutA);
        captureHook((void*)GetProcAddress(hGdi, "SetPixelV"), (void**)&g_realSetPixelV, (void*)hookedSetPixelV);
        captureHook((void*)GetProcAddress(hGdi, "BitBlt"), (void**)&g_realBitBlt, (void*)hookedBitBlt);
        captureHook((void*)GetProcAddress(hGdi, "StretchBlt"), (void**)&g_realStretchBlt, (void*)hookedStretchBlt);
        captureHook((void*)GetProcAddress(hGdi, "PatBlt"), (void**)&g_realPatBlt, (void*)hookedPatBlt);
        captureHook((void*)GetProcAddress(hGdi, "FillRect"), (void**)&g_realFillRect, (void*)hookedFillRect);
        captureHook((void*)GetProcAddress(hGdi, "SetDIBitsToDevice"), (void**)&g_realSetDIBitsToDevice, (void*)hookedSetDIBitsToDevice);
        captureHook((void*)GetProcAddress(hGdi, "StretchDIBits"), (void**)&g_realStretchDIBits, (void*)hookedStretchDIBits);
        captureHook((void*)GetProcAddress(hGdi, "PlayEnhMetaFile"), (void**)&g_realPlayEnhMetaFile, (void*)hookedPlayEnhMetaFile);
        captureHook((void*)GetProcAddress(hGdi, "Rectangle"), (void**)&g_realRectangle, (void*)hookedRectangle);
        captureHook((void*)GetProcAddress(hGdi, "Ellipse"), (void**)&g_realEllipse, (void*)hookedEllipse);
        captureHook((void*)GetProcAddress(hGdi, "RoundRect"), (void**)&g_realRoundRect, (void*)hookedRoundRect);
        captureHook((void*)GetProcAddress(hGdi, "LineTo"), (void**)&g_realLineTo, (void*)hookedLineTo);
        captureHook((void*)GetProcAddress(hGdi, "Polyline"), (void**)&g_realPolyline, (void*)hookedPolyline);
        captureHook((void*)GetProcAddress(hGdi, "Polygon"), (void**)&g_realPolygon, (void*)hookedPolygon);
        captureHook((void*)GetProcAddress(hGdi, "PolyPolygon"), (void**)&g_realPolyPolygon, (void*)hookedPolyPolygon);
        captureHook((void*)GetProcAddress(hGdi, "FillRgn"), (void**)&g_realFillRgn, (void*)hookedFillRgn);
        captureHook((void*)GetProcAddress(hGdi, "FrameRgn"), (void**)&g_realFrameRgn, (void*)hookedFrameRgn);
        captureHook((void*)GetProcAddress(hGdi, "InvertRgn"), (void**)&g_realInvertRgn, (void*)hookedInvertRgn);
        captureHook((void*)GetProcAddress(hGdi, "PaintRgn"), (void**)&g_realPaintRgn, (void*)hookedPaintRgn);
        captureHook((void*)GetProcAddress(hGdi, "Arc"), (void**)&g_realArc, (void*)hookedArc);
        captureHook((void*)GetProcAddress(hGdi, "Chord"), (void**)&g_realChord, (void*)hookedChord);
        captureHook((void*)GetProcAddress(hGdi, "Pie"), (void**)&g_realPie, (void*)hookedPie);
        captureHook((void*)GetProcAddress(hGdi, "ExtFloodFill"), (void**)&g_realExtFloodFill, (void*)hookedExtFloodFill);
        captureHook((void*)GetProcAddress(hGdi, "PolyBezier"), (void**)&g_realPolyBezier, (void*)hookedPolyBezier);

        captureHook((void*)GetProcAddress(hGdi, "FloodFill"), (void**)&g_realFloodFill, (void*)hookedFloodFill);
        captureHook((void*)GetProcAddress(hGdi, "PolylineTo"), (void**)&g_realPolylineTo, (void*)hookedPolylineTo);
        captureHook((void*)GetProcAddress(hGdi, "PolyPolyline"), (void**)&g_realPolyPolyline, (void*)hookedPolyPolyline);
        captureHook((void*)GetProcAddress(hGdi, "PolyBezierTo"), (void**)&g_realPolyBezierTo, (void*)hookedPolyBezierTo);
        captureHook((void*)GetProcAddress(hGdi, "PolyDraw"), (void**)&g_realPolyDraw, (void*)hookedPolyDraw);
        captureHook((void*)GetProcAddress(hGdi, "InvertRect"), (void**)&g_realInvertRect, (void*)hookedInvertRect);
        captureHook((void*)GetProcAddress(hGdi, "FillPath"), (void**)&g_realFillPath, (void*)hookedFillPath);
        captureHook((void*)GetProcAddress(hGdi, "StrokePath"), (void**)&g_realStrokePath, (void*)hookedStrokePath);
        captureHook((void*)GetProcAddress(hGdi, "StrokeAndFillPath"), (void**)&g_realStrokeAndFillPath, (void*)hookedStrokeAndFillPath);
        captureHook((void*)GetProcAddress(hGdi, "GradientFill"), (void**)&g_realGradientFill, (void*)hookedGradientFill);
        captureHook((void*)GetProcAddress(hGdi, "AngleArc"), (void**)&g_realAngleArc, (void*)hookedAngleArc);
        captureHook((void*)GetProcAddress(hGdi, "SetPixel"), (void**)&g_realSetPixel, (void*)hookedSetPixel);
        captureHook((void*)GetProcAddress(hGdi, "FrameRect"), (void**)&g_realFrameRect, (void*)hookedFrameRect);
        captureHook((void*)GetProcAddress(hGdi, "TabbedTextOutW"), (void**)&g_realTabbedTextOutW, (void*)hookedTabbedTextOutW);
        captureHook((void*)GetProcAddress(hGdi, "TabbedTextOutA"), (void**)&g_realTabbedTextOutA, (void*)hookedTabbedTextOutA);
        captureHook((void*)GetProcAddress(hGdi, "CreateDCA"), (void**)&g_realCreateDCA, (void*)hookedCreateDCA);
        captureHook((void*)GetProcAddress(hGdi, "CreateDCW"), (void**)&g_realCreateDCW, (void*)hookedCreateDCW);
    }
    if (hMsimg && g_realAlphaBlend) {
        captureHook((void*)GetProcAddress(hMsimg, "AlphaBlend"), (void**)&g_realAlphaBlend, (void*)hookedAlphaBlend);
        captureHook((void*)GetProcAddress(hMsimg, "TransparentBlt"), (void**)&g_realTransparentBlt, (void*)hookedTransparentBlt);
    }
    if (hUser32 && g_realUpdateLayeredWindow) {
        captureHook((void*)GetProcAddress(hUser32, "UpdateLayeredWindow"), (void**)&g_realUpdateLayeredWindow, (void*)hookedUpdateLayeredWindow);
        captureHook((void*)GetProcAddress(hUser32, "DrawTextW"), (void**)&g_realDrawTextW, (void*)hookedDrawTextW);
        captureHook((void*)GetProcAddress(hUser32, "DrawTextA"), (void**)&g_realDrawTextA, (void*)hookedDrawTextA);

        captureHook((void*)GetProcAddress(hUser32, "DrawIcon"), (void**)&g_realDrawIcon, (void*)hookedDrawIcon);
        captureHook((void*)GetProcAddress(hUser32, "DrawIconEx"), (void**)&g_realDrawIconEx, (void*)hookedDrawIconEx);
        captureHook((void*)GetProcAddress(hUser32, "DrawState"), (void**)&g_realDrawState, (void*)hookedDrawState);
        captureHook((void*)GetProcAddress(hUser32, "DrawEdge"), (void**)&g_realDrawEdge, (void*)hookedDrawEdge);
        captureHook((void*)GetProcAddress(hUser32, "DrawFrameControl"), (void**)&g_realDrawFrameControl, (void*)hookedDrawFrameControl);
        captureHook((void*)GetProcAddress(hUser32, "GrayString"), (void**)&g_realGrayString, (void*)hookedGrayString);
        captureHook((void*)GetProcAddress(hUser32, "PaintDesktop"), (void**)&g_realPaintDesktop, (void*)hookedPaintDesktop);
        captureHook((void*)GetProcAddress(hUser32, "GetDC"), (void**)&g_realGetDC, (void*)hookedGetDC);
    }
    if (hD3d11) {
        captureHook((void*)GetProcAddress(hD3d11, "D3D11CreateDeviceAndSwapChain"),
                    (void**)&g_realD3D11CreateDeviceAndSwapChain, (void*)hookedD3D11CreateDeviceAndSwapChain);
    }
    if (hDxgi) {
        captureHook((void*)GetProcAddress(hDxgi, "CreateDXGIFactory"), (void**)&g_realCreateDXGIFactory, (void*)hookedCreateDXGIFactory);
        captureHook((void*)GetProcAddress(hDxgi, "CreateDXGIFactory1"), (void**)&g_realCreateDXGIFactory1, (void*)hookedCreateDXGIFactory1);
    }
    // LoadLibrary 四件套已挪到 installOsBlockHooks（OS 出口封锁区，JNI_OnLoad 即装——不依赖 GLFW 时序）
    probeFillModules(); // 探针：GL/GLFW 等模块此刻已进进程，刷新缓存供崩溃地址解析
    printf("[taichi_hook] Hook installed successfully via Detours! trampoline=%p (snapshots=%d)\n",
           (void*)g_realSwapBuffers, g_hookCount);
    return 1;
}


struct ProbeModule { ULONG_PTR base; ULONG_PTR size; char name[64]; };
static ProbeModule g_probeMods[384];
static int g_probeModCount = 0;
static LONG g_vehLogCount = 0;
static int g_exitProbeJvmHalt = 0, g_exitProbeExitProcess = 0, g_exitProbeNtTerminate = 0, g_exitProbeRtlExit = 0;

static void probeWriteLine(const char* s) {
    DWORD w = 0;
    WriteFile(GetStdHandle(STD_ERROR_HANDLE), s, (DWORD)strlen(s), &w, NULL);
}

static void probeFillModules(void) {
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, GetCurrentProcessId());
    if (snap == INVALID_HANDLE_VALUE) return;
    MODULEENTRY32W me; me.dwSize = sizeof(me);
    int n = 0;
    if (Module32FirstW(snap, &me)) {
        do {
            if (n >= 384) break;
            g_probeMods[n].base = (ULONG_PTR)me.modBaseAddr;
            g_probeMods[n].size = me.modBaseSize;
            WideCharToMultiByte(CP_ACP, 0, me.szModule, -1, g_probeMods[n].name, 64, NULL, NULL);
            g_probeMods[n].name[63] = 0;
            n++;
        } while (Module32NextW(snap, &me));
    }
    g_probeModCount = n;
    CloseHandle(snap);
}

static void probeResolve(ULONG_PTR addr, char* out, int cap) {
    for (int i = 0; i < g_probeModCount; i++) {
        if (addr >= g_probeMods[i].base && addr < g_probeMods[i].base + g_probeMods[i].size) {
            _snprintf_s(out, cap, _TRUNCATE, "%s+0x%llX", g_probeMods[i].name,
                        (unsigned long long)(addr - g_probeMods[i].base));
            return;
        }
    }
    _snprintf_s(out, cap, _TRUNCATE, "0x%llX (no module)", (unsigned long long)addr);
}

static void probeLogAddr(const char* tag, ULONG_PTR addr) {
    char line[320];
    char mod[128];
    probeResolve(addr, mod, 128);
    _snprintf_s(line, sizeof(line), _TRUNCATE, "[taichi_hook] PROBE %s caller=%s\n", tag, mod);
    probeWriteLine(line);
}

static LONG WINAPI probeVeh(EXCEPTION_POINTERS* ep) {
    DWORD code = ep->ExceptionRecord->ExceptionCode;
    if (code != EXCEPTION_ACCESS_VIOLATION && code != EXCEPTION_STACK_OVERFLOW
            && code != 0xC0000374L  && code != 0xC0000409L ) {
        return EXCEPTION_CONTINUE_SEARCH;
    }
    if (InterlockedIncrement(&g_vehLogCount) > 12) return EXCEPTION_CONTINUE_SEARCH;
    char line[320];
    char ripMod[128];
    ULONG_PTR rip = ep->ContextRecord ? ep->ContextRecord->Rip : 0;
    ULONG_PTR fault = 0;
    if (code == EXCEPTION_ACCESS_VIOLATION && ep->ExceptionRecord->NumberParameters >= 2) {
        fault = (ULONG_PTR)ep->ExceptionRecord->ExceptionInformation[1];
    }
    probeResolve(rip, ripMod, 128);
    _snprintf_s(line, sizeof(line), _TRUNCATE,
                "[taichi_hook] PROBE VEH: code=0x%lX rip=%s fault=0x%llX tid=%lu\n",
                code, ripMod, (unsigned long long)fault, GetCurrentThreadId());
    probeWriteLine(line);
    return EXCEPTION_CONTINUE_SEARCH;
}

static void probeInstall(void) {
    probeFillModules();
    AddVectoredExceptionHandler(1, probeVeh);
    fprintf(stderr, "[taichi_hook] PROBE armed: VEH crash logger + module cache (%d modules)\n", g_probeModCount);
}


typedef void (WINAPI *PFN_RtlExitUserProcess)(LONG);
static PFN_RtlExitUserProcess g_realRtlExitUserProcess = NULL;

static void WINAPI hookedRtlExitUserProcess(LONG status) {
    if (g_exitProbeRtlExit < 8) {
        g_exitProbeRtlExit++;
        probeLogAddr("RtlExitUserProcess", (ULONG_PTR)_ReturnAddress());
    }
    if (g_osFlags & 1) {
        fprintf(stderr, "[taichi_hook] OS BLOCK: RtlExitUserProcess(0x%lX) blocked\n", (long)status);
        return;
    }
    g_realRtlExitUserProcess(status);
}



typedef void (JNICALL *PFN_JVM_Halt)(jint);
typedef void (WINAPI *PFN_ExitProcess)(UINT);
typedef LONG (WINAPI *PFN_NtTerminateProcess)(HANDLE, LONG); // NTSTATUS = LONG（x64 下 NTAPI=WINAPI）
typedef BOOL (WINAPI *PFN_CreateProcessW)(LPCWSTR, LPWSTR, LPSECURITY_ATTRIBUTES, LPSECURITY_ATTRIBUTES,
                                          BOOL, DWORD, LPVOID, LPCWSTR, LPSTARTUPINFOW, LPPROCESS_INFORMATION);
typedef BOOL (WINAPI *PFN_CreateProcessA)(LPCSTR, LPSTR, LPSECURITY_ATTRIBUTES, LPSECURITY_ATTRIBUTES,
                                          BOOL, DWORD, LPVOID, LPCSTR, LPSTARTUPINFOA, LPPROCESS_INFORMATION);
typedef HINSTANCE (WINAPI *PFN_ShellExecuteW)(HWND, LPCWSTR, LPCWSTR, LPCWSTR, LPCWSTR, INT);
typedef UINT (WINAPI *PFN_WinExec)(LPCSTR, UINT);

static PFN_JVM_Halt g_realJvmHalt = NULL;
static PFN_ExitProcess g_realExitProcess = NULL;
static PFN_NtTerminateProcess g_realNtTerminateProcess = NULL;
static PFN_CreateProcessW g_realCreateProcessW = NULL;
static PFN_CreateProcessA g_realCreateProcessA = NULL;
static PFN_ShellExecuteW g_realShellExecuteW = NULL;
static PFN_WinExec g_realWinExec = NULL;

static void JNICALL hookedJvmHalt(jint code) {
    if (g_exitProbeJvmHalt < 8) {
        g_exitProbeJvmHalt++;
        probeLogAddr("JVM_Halt", (ULONG_PTR)_ReturnAddress());
    }
    if (g_osFlags & 1) {
        fprintf(stderr, "[taichi_hook] OS BLOCK: JVM_Halt(%d) blocked\n", (int)code);
        return; // 不退出——Java 侧关闭钩子尚未运行，游戏完整存活
    }
    g_realJvmHalt(code);
}

static void WINAPI hookedExitProcess(UINT code) {
    if (g_exitProbeExitProcess < 8) {
        g_exitProbeExitProcess++;
        probeLogAddr("ExitProcess", (ULONG_PTR)_ReturnAddress());
    }
    if (g_osFlags & 1) {
        fprintf(stderr, "[taichi_hook] OS BLOCK: ExitProcess(%u) blocked\n", code);
        return;
    }
    g_realExitProcess(code);
}

static LONG WINAPI hookedNtTerminateProcess(HANDLE h, LONG s) {
    if (g_exitProbeNtTerminate < 8) {
        g_exitProbeNtTerminate++;
        probeLogAddr("NtTerminateProcess", (ULONG_PTR)_ReturnAddress());
    }
    if (g_osFlags & 1) {
        fprintf(stderr, "[taichi_hook] OS BLOCK: NtTerminateProcess blocked\n");
        return 0xC0000022L; // STATUS_ACCESS_DENIED
    }
    return g_realNtTerminateProcess(h, s);
}

static BOOL WINAPI hookedCreateProcessW(LPCWSTR app, LPWSTR cmd, LPSECURITY_ATTRIBUTES pa, LPSECURITY_ATTRIBUTES ta,
                                        BOOL inherit, DWORD flags, LPVOID env, LPCWSTR dir,
                                        LPSTARTUPINFOW si, LPPROCESS_INFORMATION pi) {
    if (g_osFlags & 2) {
        fprintf(stderr, "[taichi_hook] OS BLOCK: CreateProcessW blocked\n");
        SetLastError(ERROR_ACCESS_DENIED);
        return FALSE;
    }
    return g_realCreateProcessW(app, cmd, pa, ta, inherit, flags, env, dir, si, pi);
}

static BOOL WINAPI hookedCreateProcessA(LPCSTR app, LPSTR cmd, LPSECURITY_ATTRIBUTES pa, LPSECURITY_ATTRIBUTES ta,
                                        BOOL inherit, DWORD flags, LPVOID env, LPCSTR dir,
                                        LPSTARTUPINFOA si, LPPROCESS_INFORMATION pi) {
    if (g_osFlags & 2) {
        fprintf(stderr, "[taichi_hook] OS BLOCK: CreateProcessA blocked\n");
        SetLastError(ERROR_ACCESS_DENIED);
        return FALSE;
    }
    return g_realCreateProcessA(app, cmd, pa, ta, inherit, flags, env, dir, si, pi);
}

static HINSTANCE WINAPI hookedShellExecuteW(HWND hwnd, LPCWSTR op, LPCWSTR file, LPCWSTR params, LPCWSTR dir, INT show) {
    if (g_osFlags & 2) {
        fprintf(stderr, "[taichi_hook] OS BLOCK: ShellExecuteW blocked\n");
        return (HINSTANCE)(ULONG_PTR)5; // SE_ERR_ACCESSDENIED
    }
    return g_realShellExecuteW(hwnd, op, file, params, dir, show);
}

static UINT WINAPI hookedWinExec(LPCSTR cmd, UINT show) {
    if (g_osFlags & 2) {
        fprintf(stderr, "[taichi_hook] OS BLOCK: WinExec blocked\n");
        return 0;
    }
    return g_realWinExec(cmd, show);
}


static int isOsLoadWhitelisted(const wchar_t* path) {
    if (path == NULL) return 0;

    bool hasPath = wcsstr(path, L"\\") != NULL || wcsstr(path, L"/") != NULL || wcsstr(path, L":") != NULL;
    if (!hasPath) return 1;
    static wchar_t g_jdkRootW[MAX_PATH] = {0};
    if (g_jdkRootW[0] == 0) {
        HMODULE jvm = GetModuleHandleA("jvm.dll");
        if (jvm != NULL) {
            char p[MAX_PATH] = {0};
            if (GetModuleFileNameA(jvm, p, MAX_PATH) > 0) {
                char* b = strstr(p, "\\bin\\");
                if (b != NULL) {
                    b[1] = '\0';
                    MultiByteToWideChar(CP_ACP, 0, p, -1, g_jdkRootW, MAX_PATH);
                }
            }
        }
    }
    wchar_t sysDir[MAX_PATH];
    if (GetSystemDirectoryW(sysDir, MAX_PATH) > 0 && _wcsnicmp(path, sysDir, wcslen(sysDir)) == 0) return 1;
    if (g_jdkRootW[0] != 0 && _wcsnicmp(path, g_jdkRootW, wcslen(g_jdkRootW)) == 0) return 1;

    {
        wchar_t cwd[MAX_PATH];
        if (GetCurrentDirectoryW(MAX_PATH, cwd) > 0) {
            size_t l = wcslen(cwd);
            if (_wcsnicmp(path, cwd, l) == 0) return 1;
            // 上级目录（截到最后一个反斜杠）
            wchar_t parent[MAX_PATH];
            wcsncpy(parent, cwd, MAX_PATH - 1);
            parent[MAX_PATH - 1] = 0;
            wchar_t* slash = wcsrchr(parent, L'\\');
            if (slash != NULL) {
                *slash = 0;
                size_t pl = wcslen(parent);
                if (pl > 0 && _wcsnicmp(path, parent, pl) == 0) return 1;
            }
        }
    }
    wchar_t lower[MAX_PATH];
    int n = 0;
    for (; path[n] != 0 && n < MAX_PATH - 1; n++) lower[n] = towlower(path[n]);
    lower[n] = 0;

    const wchar_t* name = wcsrchr(lower, L'\\');
    if (name == NULL) name = wcsrchr(lower, L'/');
    name = (name != NULL) ? name + 1 : lower;

    // 家族前缀白名单：我们的 DLL basename 以这些开头 → 放行（支持随机后缀）
    // ryjsagent*.dll / taichi_hook*.dll / ryjs_util*.dll
    const wchar_t* familyPrefixes[] = { L"ryjsagent", L"taichi_hook", L"ryjs_util" };
    for (int i = 0; i < 3; i++) {
        size_t prefixLen = wcslen(familyPrefixes[i]);
        if (_wcsnicmp(name, familyPrefixes[i], prefixLen) == 0) {
            return 1;  // 前缀匹配 → 放行
        }
    }

    return 0;
}


static LONG g_loadHooksAttached = 0;


static void installOsBlockHooks(void) {
    HMODULE jvm = GetModuleHandleA("jvm.dll");
    HMODULE k32 = GetModuleHandleA("kernel32.dll");
    HMODULE ntd = GetModuleHandleA("ntdll.dll");
    HMODULE sh = GetModuleHandleA("shell32.dll");
    g_realJvmHalt = jvm ? (PFN_JVM_Halt)GetProcAddress(jvm, "JVM_Halt") : NULL;
    g_realExitProcess = k32 ? (PFN_ExitProcess)GetProcAddress(k32, "ExitProcess") : NULL;
    g_realNtTerminateProcess = ntd ? (PFN_NtTerminateProcess)GetProcAddress(ntd, "NtTerminateProcess") : NULL;
    g_realCreateProcessW = k32 ? (PFN_CreateProcessW)GetProcAddress(k32, "CreateProcessW") : NULL;
    g_realCreateProcessA = k32 ? (PFN_CreateProcessA)GetProcAddress(k32, "CreateProcessA") : NULL;
    g_realShellExecuteW = sh ? (PFN_ShellExecuteW)GetProcAddress(sh, "ShellExecuteW") : NULL;
    g_realWinExec = k32 ? (PFN_WinExec)GetProcAddress(k32, "WinExec") : NULL;
    int n = 0;
    DetourTransactionBegin();
    DetourUpdateThread(GetCurrentThread());
    LONG err = NO_ERROR;
    if (err == NO_ERROR && g_realJvmHalt) { err = DetourAttach(&(PVOID&)g_realJvmHalt, (PVOID)hookedJvmHalt); if (err == NO_ERROR) n++; }
    if (err == NO_ERROR && g_realExitProcess) { err = DetourAttach(&(PVOID&)g_realExitProcess, (PVOID)hookedExitProcess); if (err == NO_ERROR) n++; }
    if (err == NO_ERROR && g_realNtTerminateProcess) { err = DetourAttach(&(PVOID&)g_realNtTerminateProcess, (PVOID)hookedNtTerminateProcess); if (err == NO_ERROR) n++; }
    if (err == NO_ERROR && ntd) {
        g_realRtlExitUserProcess = (PFN_RtlExitUserProcess)GetProcAddress(ntd, "RtlExitUserProcess");
        if (g_realRtlExitUserProcess) { err = DetourAttach(&(PVOID&)g_realRtlExitUserProcess, (PVOID)hookedRtlExitUserProcess); if (err == NO_ERROR) n++; }
    }
    if (err == NO_ERROR && g_realCreateProcessW) { err = DetourAttach(&(PVOID&)g_realCreateProcessW, (PVOID)hookedCreateProcessW); if (err == NO_ERROR) n++; }
    if (err == NO_ERROR && g_realCreateProcessA) { err = DetourAttach(&(PVOID&)g_realCreateProcessA, (PVOID)hookedCreateProcessA); if (err == NO_ERROR) n++; }
    if (err == NO_ERROR && g_realShellExecuteW) { err = DetourAttach(&(PVOID&)g_realShellExecuteW, (PVOID)hookedShellExecuteW); if (err == NO_ERROR) n++; }
    if (err == NO_ERROR && g_realWinExec) { err = DetourAttach(&(PVOID&)g_realWinExec, (PVOID)hookedWinExec); if (err == NO_ERROR) n++; }
    // LoadLibrary 四件套（白名单制——JNI_OnLoad 即装，不依赖 GLFW；captureHook 登记进 taichi 的完整性校验链）
    // 幂等：同一实例内只允许挂一次（二次 attach 的跳板会指回自身 hook → 无限递归栈溢出）
    {
        HMODULE hKernel = GetModuleHandleA("kernel32.dll");
        if (hKernel != NULL && err == NO_ERROR && InterlockedCompareExchange(&g_loadHooksAttached, 1, 0) == 0) {
            void* e = (void*)GetProcAddress(hKernel, "LoadLibraryExW");
            g_realLoadLibraryExW = (PFN_LoadLibraryExW)e;
            if (e != NULL) { err = DetourAttach(&(PVOID&)g_realLoadLibraryExW, (PVOID)hookedLoadLibraryExW); if (err == NO_ERROR) n++; captureHook(e, (void**)&g_realLoadLibraryExW, (void*)hookedLoadLibraryExW); }
            e = (void*)GetProcAddress(hKernel, "LoadLibraryW");
            g_realLoadLibraryW = (PFN_LoadLibraryW)e;
            if (e != NULL && err == NO_ERROR) { err = DetourAttach(&(PVOID&)g_realLoadLibraryW, (PVOID)hookedLoadLibraryW); if (err == NO_ERROR) n++; captureHook(e, (void**)&g_realLoadLibraryW, (void*)hookedLoadLibraryW); }
            e = (void*)GetProcAddress(hKernel, "LoadLibraryExA");
            g_realLoadLibraryExA = (PFN_LoadLibraryExA)e;
            if (e != NULL && err == NO_ERROR) { err = DetourAttach(&(PVOID&)g_realLoadLibraryExA, (PVOID)hookedLoadLibraryExA); if (err == NO_ERROR) n++; captureHook(e, (void**)&g_realLoadLibraryExA, (void*)hookedLoadLibraryExA); }
            e = (void*)GetProcAddress(hKernel, "LoadLibraryA");
            g_realLoadLibraryA = (PFN_LoadLibraryA)e;
            if (e != NULL && err == NO_ERROR) { err = DetourAttach(&(PVOID&)g_realLoadLibraryA, (PVOID)hookedLoadLibraryA); if (err == NO_ERROR) n++; captureHook(e, (void**)&g_realLoadLibraryA, (void*)hookedLoadLibraryA); }
        }
    }
    if (err == NO_ERROR && DetourTransactionCommit() == NO_ERROR) {
        fprintf(stderr, "[taichi_hook] OS block hooks installed: %d (JVM_Halt=%d)\n", n, g_realJvmHalt ? 1 : 0);
    } else {
        DetourTransactionAbort();
        fprintf(stderr, "[taichi_hook] OS block hook install failed (err=%ld)\n", err);
    }
}


extern "C" {
JNIEXPORT void JNICALL
Java_com_ryjs_core_JvmtiBridge_nativeSetOsBlock(JNIEnv* env, jclass cls,
                                                jboolean exitBlock, jboolean spawnBlock, jboolean loadBlock) {
    (void)env; (void)cls;
    LONG flags = (exitBlock ? 1 : 0) | (spawnBlock ? 2 : 0) | (loadBlock ? 4 : 0);
    InterlockedExchange(&g_osFlags, flags);
    fprintf(stderr, "[taichi_hook] OS block flags=0x%X (exit=%d spawn=%d load=%d)\n",
            (int)flags, exitBlock ? 1 : 0, spawnBlock ? 1 : 0, loadBlock ? 1 : 0);
}
} // extern "C"


static DWORD WINAPI hookInstallThreadProc(LPVOID param) {
    (void)param;
    for (int i = 0; i < 600; i++) {   // 最多 ~30s（50ms×600）
        if (findGlfwModule() != NULL) {
            installHook();            // 内部再取一次模块并 DetourAttach；装上即退出
            return 0;
        }
        Sleep(50);
    }
    fprintf(stderr, "[taichi_hook] GLFW never appeared in time; hook not installed.\n");
    return 1;
}

static void removeHook(void) {
    if (!g_realSwapBuffers) return;

    DetourTransactionBegin();
    DetourUpdateThread(GetCurrentThread());
    DetourDetach(&(PVOID&)g_realSwapBuffers, (PVOID)hookedGlfwSwapBuffers);
    DetourTransactionCommit();

    printf("[taichi_hook] Hook removed\n");
}



extern "C" {


JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    initSharedState(); // 防御标志共享区（双 DLL 实例同步——必须在 hook 安装前就绪）
    initBridgeShared(); // 共享回调区（武装实例读路径；nativeBind 实例写路径——幂等）
    if (InterlockedCompareExchange(&g_armedFlag, 1, 0) != 0) {
        // 双实例去重（2026-08-17）：另一实例已武装（SERVICE 层 EartyLoading 或 GAME 层先到者）。
        // 本实例保留共享区读写能力（flags/swapGuard 等），跳过 hook 安装与探针——防重复挂载链变深。
        printf("[taichi_hook] hooks already armed by another instance - skip install (shared armed flag)\n");
        return JNI_VERSION_1_8;
    }
    probeInstall();     // 死亡探针：VEH 崩溃记录 + 模块缓存（必须在任何 hook 安装前就绪）
    installOsBlockHooks(); // OS 出口封锁（进程退出/进程创建/DLL 加载——不依赖 GLFW，立即武装）
    printf("[taichi_hook] JNI_OnLoad: DLL loaded; starting GLFW poll thread to install hook...\n");
    CreateThread(NULL, 0, hookInstallThreadProc, NULL, 0, NULL);
    return JNI_VERSION_1_8;
}

JNIEXPORT jboolean JNICALL
Java_com_ryjs_reflection_client_render_TaiChiRenderBridge_nativeBind(JNIEnv* env, jclass cls) {
    (void)cls;
    env->GetJavaVM(&g_jvm);   // JNI_OnLoad 已设，这里冗余兑底

    // 逻辑/回调已拆到 TaiChiRenderControl（native 方法仍留在 TaiChiRenderBridge），beforeSwap 用 FindClass 定位。
    jclass ctrl = env->FindClass("com/ryjs/reflection/client/render/TaiChiRenderControl");
    if (!ctrl) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        fprintf(stderr, "[taichi_hook] Cannot find TaiChiRenderControl\n");
        return JNI_FALSE;
    }
    g_bridgeClass = (jclass)env->NewGlobalRef(ctrl);
    g_beforeSwapMethod = env->GetStaticMethodID(ctrl, "beforeSwap", "()V");
    if (!g_beforeSwapMethod) {
        fprintf(stderr, "[taichi_hook] Cannot find TaiChiRenderControl.beforeSwap()V\n");
        return JNI_FALSE;
    }
    g_glStackCheckMethod = env->GetStaticMethodID(ctrl, "isNativeGlStackClean", "()Z");
    if (!g_glStackCheckMethod) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        fprintf(stderr, "[taichi_hook] WARNING: TaiChiRenderControl.isNativeGlStackClean()Z not found (old Java?)\n");
    }

    // 发布到共享桥区：双实例场景下武装实例（SERVICE 层）从共享读取本回调执行（2026-08-17）
    initBridgeShared();
    bridgePublish(g_bridgeClass, g_beforeSwapMethod, g_glStackCheckMethod);

    // hook 已由 JNI_OnLoad 的轮询线程负责安装；此处只注册回调 + 启分层窗口线程。
    // 若回调注册前 hook 已触发，callJavaBeforeSwap 会因 g_beforeSwapMethod==NULL 静默 return，安全。
    printf("[taichi_hook] beforeSwap callback registered (hook install handled by JNI_OnLoad).\n");

    ensureOverlayThread();

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_ryjs_reflection_client_render_TaiChiRenderBridge_nativeSetDefenseFlags(
        JNIEnv* env, jclass cls, jint flags) {
    (void)env; (void)cls;
    InterlockedExchange(&g_defenseFlags, flags);
}


JNIEXPORT void JNICALL
Java_com_ryjs_reflection_client_render_TaiChiRenderBridge_nativeDeathMouseEject(
        JNIEnv* env, jclass cls, jint on) {
    (void)env; (void)cls;
    InterlockedExchange(&g_mouseEject, on ? 1 : 0);
    printf("[taichi_hook] death mouse eject %s\n", on ? "ON" : "OFF");
}

JNIEXPORT void JNICALL
Java_com_ryjs_reflection_client_render_TaiChiRenderBridge_nativeForceRedraw(
        JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    InterlockedExchange(&g_forceRedrawRequested, 1);
    if (g_realGlfwPostEmptyEvent) {
        g_realGlfwPostEmptyEvent();
    }
    printf("[taichi_hook] force redraw requested\n");
}


JNIEXPORT void JNICALL
Java_com_ryjs_reflection_client_render_TaiChiRenderBridge_nativeSetForceRedraw(
        JNIEnv* env, jclass cls, jboolean enabled) {
    (void)env; (void)cls;
    InterlockedExchange(&g_forceRedrawEnabled, enabled ? 1 : 0);
    printf("[taichi_hook] realtime redraw %s\n", enabled ? "ON" : "OFF");
}


JNIEXPORT void JNICALL
Java_com_ryjs_reflection_client_render_TaiChiRenderBridge_nativeSetFullRedraw(
        JNIEnv* env, jclass cls, jboolean enabled) {
    (void)env; (void)cls;
    InterlockedExchange(&g_fullRedraw, enabled ? 1 : 0);
    printf("[taichi_hook] full redraw %s\n", enabled ? "ON" : "OFF");
}


JNIEXPORT void JNICALL
Java_com_ryjs_reflection_client_render_TaiChiRenderBridge_nativeGlAttack(
        JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    // 诊断：确认调用频率（FullDeath 死亡画面渲染路径——若死亡画面持续渲染会每帧调）
    static volatile LONG diagCnt = 0;
    if (InterlockedIncrement(&diagCnt) % 600 == 1) {
        printf("[taichi_hook] nativeGlAttack called (total=%ld)\n", (long)diagCnt);
    }
    typedef void (APIENTRY *PFN_glClear_t)(GLbitfield mask);
    typedef void (APIENTRY *PFN_glClearColor_t)(GLclampf r, GLclampf g, GLclampf b, GLclampf a);
    HMODULE hOpengl = GetModuleHandleA("opengl32.dll");
    if (!hOpengl) return;
    PFN_glClearColor_t clearColor = (PFN_glClearColor_t)GetProcAddress(hOpengl, "glClearColor");
    PFN_glClear_t clear = (PFN_glClear_t)GetProcAddress(hOpengl, "glClear");
    if (!clear || !clearColor) return;
    clearColor(0.0f, 0.0f, 0.0f, 1.0f);
    clear(0x4000); // GL_COLOR_BUFFER_BIT
}



JNIEXPORT void JNICALL
Java_com_ryjs_reflection_client_render_TaiChiRenderBridge_nativeSetOverlayVisible(
        JNIEnv* env, jclass cls, jboolean visible) {
    (void)env; (void)cls;
    ensureOverlayThread();
    InterlockedExchange(&g_overlayVisible, visible ? 1 : 0);
    if (g_overlayWnd) {
        if (visible) {
            ShowWindow(g_overlayWnd, SW_SHOWNOACTIVATE);
            SetWindowPos(g_overlayWnd, HWND_TOPMOST, 0, 0, 0, 0,
                         SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        } else {
            ShowWindow(g_overlayWnd, SW_HIDE);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_ryjs_reflection_client_render_TaiChiRenderBridge_nativePushFrame(
        JNIEnv* env, jclass cls, jintArray pixels, jint width, jint height, jint winX, jint winY) {
    (void)cls;
    if (!pixels || width <= 0 || height <= 0) return;
    ensureOverlayThread();

    jsize len = env->GetArrayLength(pixels);
    if (len < width * height) return;

    // 窗口客户区屏幕坐标（GLFW 权威值）
    InterlockedExchange(&g_winX, winX);
    InterlockedExchange(&g_winY, winY);

    EnterCriticalSection(&g_frameLock);
    if (g_frameW != width || g_frameH != height || !g_frameBits) {
        if (g_frameBits) { free(g_frameBits); g_frameBits = NULL; }
        g_frameBits = (int*)malloc((size_t)width * height * sizeof(int));
        g_frameW = width;
        g_frameH = height;
    }
    if (g_frameBits) {
        env->GetIntArrayRegion(pixels, 0, width * height, (jint*)g_frameBits);
        InterlockedExchange(&g_frameDirty, 1);
    }
    LeaveCriticalSection(&g_frameLock);
}


typedef int GLint;
typedef unsigned int GLuint;
typedef unsigned char GLboolean;
typedef float GLfloat;
typedef char GLchar;
typedef intptr_t GLsizeiptr;
typedef unsigned int GLenum;

#define GL_FALSE 0
#define GL_TRIANGLES 0x0004
#define GL_SRC_ALPHA 0x0302
#define GL_ONE_MINUS_SRC_ALPHA 0x0303
#define GL_FLOAT 0x1406
#define GL_UNSIGNED_BYTE 0x1401
#define GL_TEXTURE_2D 0x0DE1
#define GL_RGBA 0x1908
#define GL_RGBA8 0x8058
#define GL_LINEAR 0x2601
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_TEXTURE_WRAP_S 0x2802
#define GL_TEXTURE_WRAP_T 0x2803
#define GL_CLAMP_TO_EDGE 0x812F
#define GL_ARRAY_BUFFER 0x8892
#define GL_ARRAY_BUFFER_BINDING 0x8894
#define GL_STREAM_DRAW 0x88E0
#define GL_VERTEX_ARRAY_BINDING 0x85B5
#define GL_ACTIVE_TEXTURE 0x84E0
#define GL_TEXTURE0 0x84C0
#define GL_TEXTURE_BINDING_2D 0x8069
#define GL_BLEND_SRC 0x0C01
#define GL_BLEND_DST 0x0C02
#define GL_UNPACK_ALIGNMENT 0x0CF5
#define GL_CURRENT_PROGRAM 0x8B8D
#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_COMPILE_STATUS 0x8B81
#define GL_LINK_STATUS 0x8B82
#define GL_INFO_LOG_LENGTH 0x8B84
#define GL_BLEND 0x0BE2
#define GL_DEPTH_TEST 0x0B71


typedef GLuint (APIENTRY *PFN_DG_CREATESHADER)(GLenum);
typedef void (APIENTRY *PFN_DG_SHADERSOURCE)(GLuint, GLsizei, const GLchar* const*, const GLint*);
typedef void (APIENTRY *PFN_DG_COMPILESHADER)(GLuint);
typedef void (APIENTRY *PFN_DG_GETSHADERIV)(GLuint, GLenum, GLint*);
typedef void (APIENTRY *PFN_DG_GETSHADERINFOLOG)(GLuint, GLsizei, GLsizei*, GLchar*);
typedef GLuint (APIENTRY *PFN_DG_CREATEPROGRAM)(void);
typedef void (APIENTRY *PFN_DG_ATTACHSHADER)(GLuint, GLuint);
typedef void (APIENTRY *PFN_DG_LINKPROGRAM)(GLuint);
typedef void (APIENTRY *PFN_DG_GETPROGRAMIV)(GLuint, GLenum, GLint*);
typedef void (APIENTRY *PFN_DG_GETPROGRAMINFOLOG)(GLuint, GLsizei, GLsizei*, GLchar*);
typedef void (APIENTRY *PFN_DG_USEPROGRAM)(GLuint);
typedef void (APIENTRY *PFN_DG_DELETESHADER)(GLuint);
typedef void (APIENTRY *PFN_DG_GENVERTEXARRAYS)(GLsizei, GLuint*);
typedef void (APIENTRY *PFN_DG_BINDVERTEXARRAY)(GLuint);
typedef void (APIENTRY *PFN_DG_GENBUFFERS)(GLsizei, GLuint*);
typedef void (APIENTRY *PFN_DG_BINDBUFFER)(GLenum, GLuint);
typedef void (APIENTRY *PFN_DG_BUFFERDATA)(GLenum, GLsizeiptr, const void*, GLenum);
typedef void (APIENTRY *PFN_DG_ENABLEVERTEXATTRIBARRAY)(GLuint);
typedef void (APIENTRY *PFN_DG_VERTEXATTRIBPOINTER)(GLuint, GLint, GLenum, GLboolean, GLsizei, const void*);
typedef GLint (APIENTRY *PFN_DG_GETUNIFORMLOCATION)(GLuint, const GLchar*);
typedef void (APIENTRY *PFN_DG_UNIFORM1I)(GLint, GLint);
typedef void (APIENTRY *PFN_DG_ACTIVETEXTURE)(GLenum);
typedef void (APIENTRY *PFN_DG_BINDTEXTURE)(GLenum, GLuint);
typedef void (APIENTRY *PFN_DG_TEXIMAGE2D)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void*);
typedef void (APIENTRY *PFN_DG_TEXPARAMETERI)(GLenum, GLenum, GLint);
typedef void (APIENTRY *PFN_DG_DRAWARRAYS)(GLenum, GLint, GLsizei);
typedef void (APIENTRY *PFN_DG_GENTEXTURES)(GLsizei, GLuint*);
typedef void (APIENTRY *PFN_DG_GETINTEGERV)(GLenum, GLint*);
typedef GLboolean (APIENTRY *PFN_DG_ISENABLED)(GLenum);
typedef void (APIENTRY *PFN_DG_ENABLE)(GLenum);
typedef void (APIENTRY *PFN_DG_DISABLE)(GLenum);
typedef void (APIENTRY *PFN_DG_BLENDFUNC)(GLenum, GLenum);
typedef void (APIENTRY *PFN_DG_PIXELSTOREI)(GLenum, GLint);

static PFN_DG_CREATESHADER g_dgCreateShader = NULL;
static PFN_DG_SHADERSOURCE g_dgShaderSource = NULL;
static PFN_DG_COMPILESHADER g_dgCompileShader = NULL;
static PFN_DG_GETSHADERIV g_dgGetShaderiv = NULL;
static PFN_DG_GETSHADERINFOLOG g_dgGetShaderInfoLog = NULL;
static PFN_DG_CREATEPROGRAM g_dgCreateProgram = NULL;
static PFN_DG_ATTACHSHADER g_dgAttachShader = NULL;
static PFN_DG_LINKPROGRAM g_dgLinkProgram = NULL;
static PFN_DG_GETPROGRAMIV g_dgGetProgramiv = NULL;
static PFN_DG_GETPROGRAMINFOLOG g_dgGetProgramInfoLog = NULL;
static PFN_DG_USEPROGRAM g_dgUseProgram = NULL;
static PFN_DG_DELETESHADER g_dgDeleteShader = NULL;
static PFN_DG_GENVERTEXARRAYS g_dgGenVertexArrays = NULL;
static PFN_DG_BINDVERTEXARRAY g_dgBindVertexArray = NULL;
static PFN_DG_GENBUFFERS g_dgGenBuffers = NULL;
static PFN_DG_BINDBUFFER g_dgBindBuffer = NULL;
static PFN_DG_BUFFERDATA g_dgBufferData = NULL;
static PFN_DG_ENABLEVERTEXATTRIBARRAY g_dgEnableVertexAttribArray = NULL;
static PFN_DG_VERTEXATTRIBPOINTER g_dgVertexAttribPointer = NULL;
static PFN_DG_GETUNIFORMLOCATION g_dgGetUniformLocation = NULL;
static PFN_DG_UNIFORM1I g_dgUniform1i = NULL;
static PFN_DG_ACTIVETEXTURE g_dgActiveTexture = NULL;
static PFN_DG_BINDTEXTURE g_dgBindTexture = NULL;
static PFN_DG_TEXIMAGE2D g_dgTexImage2D = NULL;
static PFN_DG_TEXPARAMETERI g_dgTexParameteri = NULL;
static PFN_DG_DRAWARRAYS g_dgDrawArrays = NULL;
static PFN_DG_GENTEXTURES g_dgGenTextures = NULL;
static PFN_DG_GETINTEGERV g_dgGetIntegerv = NULL;
static PFN_DG_ISENABLED g_dgIsEnabled = NULL;
static PFN_DG_ENABLE g_dgEnable = NULL;
static PFN_DG_DISABLE g_dgDisable = NULL;
static PFN_DG_BLENDFUNC g_dgBlendFunc = NULL;
static PFN_DG_PIXELSTOREI g_dgPixelStorei = NULL;
static int g_dgProcsLoaded = 0;


static void* dgGetProc(const char* name) {
    HMODULE h = GetModuleHandleA("opengl32.dll");
    if (h) {
        void* p = (void*)GetProcAddress(h, name);
        if (p) return p;
    }
    typedef PROC (APIENTRY *PFN_wglGetProcAddress_t)(LPCSTR);
    static PFN_wglGetProcAddress_t fn = NULL;
    if (!fn) {
        if (!h) return NULL;
        fn = (PFN_wglGetProcAddress_t)GetProcAddress(h, "wglGetProcAddress");
        if (!fn) return NULL;
    }
    return (void*)fn(name);
}

static void dgLoadProcs(void) {
    if (g_dgProcsLoaded) return;
    g_dgCreateShader = (PFN_DG_CREATESHADER)dgGetProc("glCreateShader");
    g_dgShaderSource = (PFN_DG_SHADERSOURCE)dgGetProc("glShaderSource");
    g_dgCompileShader = (PFN_DG_COMPILESHADER)dgGetProc("glCompileShader");
    g_dgGetShaderiv = (PFN_DG_GETSHADERIV)dgGetProc("glGetShaderiv");
    g_dgGetShaderInfoLog = (PFN_DG_GETSHADERINFOLOG)dgGetProc("glGetShaderInfoLog");
    g_dgCreateProgram = (PFN_DG_CREATEPROGRAM)dgGetProc("glCreateProgram");
    g_dgAttachShader = (PFN_DG_ATTACHSHADER)dgGetProc("glAttachShader");
    g_dgLinkProgram = (PFN_DG_LINKPROGRAM)dgGetProc("glLinkProgram");
    g_dgGetProgramiv = (PFN_DG_GETPROGRAMIV)dgGetProc("glGetProgramiv");
    g_dgGetProgramInfoLog = (PFN_DG_GETPROGRAMINFOLOG)dgGetProc("glGetProgramInfoLog");
    g_dgUseProgram = (PFN_DG_USEPROGRAM)dgGetProc("glUseProgram");
    g_dgDeleteShader = (PFN_DG_DELETESHADER)dgGetProc("glDeleteShader");
    g_dgGenVertexArrays = (PFN_DG_GENVERTEXARRAYS)dgGetProc("glGenVertexArrays");
    g_dgBindVertexArray = (PFN_DG_BINDVERTEXARRAY)dgGetProc("glBindVertexArray");
    g_dgGenBuffers = (PFN_DG_GENBUFFERS)dgGetProc("glGenBuffers");
    g_dgBindBuffer = (PFN_DG_BINDBUFFER)dgGetProc("glBindBuffer");
    g_dgBufferData = (PFN_DG_BUFFERDATA)dgGetProc("glBufferData");
    g_dgEnableVertexAttribArray = (PFN_DG_ENABLEVERTEXATTRIBARRAY)dgGetProc("glEnableVertexAttribArray");
    g_dgVertexAttribPointer = (PFN_DG_VERTEXATTRIBPOINTER)dgGetProc("glVertexAttribPointer");
    g_dgGetUniformLocation = (PFN_DG_GETUNIFORMLOCATION)dgGetProc("glGetUniformLocation");
    g_dgUniform1i = (PFN_DG_UNIFORM1I)dgGetProc("glUniform1i");
    g_dgActiveTexture = (PFN_DG_ACTIVETEXTURE)dgGetProc("glActiveTexture");
    g_dgBindTexture = (PFN_DG_BINDTEXTURE)dgGetProc("glBindTexture");
    g_dgTexImage2D = (PFN_DG_TEXIMAGE2D)dgGetProc("glTexImage2D");
    g_dgTexParameteri = (PFN_DG_TEXPARAMETERI)dgGetProc("glTexParameteri");
    g_dgDrawArrays = (PFN_DG_DRAWARRAYS)dgGetProc("glDrawArrays");
    g_dgGenTextures = (PFN_DG_GENTEXTURES)dgGetProc("glGenTextures");
    g_dgGetIntegerv = (PFN_DG_GETINTEGERV)dgGetProc("glGetIntegerv");
    g_dgIsEnabled = (PFN_DG_ISENABLED)dgGetProc("glIsEnabled");
    g_dgEnable = (PFN_DG_ENABLE)dgGetProc("glEnable");
    g_dgDisable = (PFN_DG_DISABLE)dgGetProc("glDisable");
    g_dgBlendFunc = (PFN_DG_BLENDFUNC)dgGetProc("glBlendFunc");
    g_dgPixelStorei = (PFN_DG_PIXELSTOREI)dgGetProc("glPixelStorei");
    g_dgProcsLoaded = g_dgCreateShader && g_dgCreateProgram && g_dgUseProgram && g_dgDrawArrays
        && g_dgGenVertexArrays && g_dgTexImage2D ? 1 : 0;
    if (!g_dgProcsLoaded) {
        static int warned = 0;
        if (!warned) {
            warned = 1;
            printf("[taichi_hook] death GL: 函数指针获取失败，GL 直绘通道禁用（仅警告一次）\n");
        }
    }
}

static volatile LONG g_deathGlEnabled = 0;
static unsigned char* g_deathPixels = NULL;
static size_t g_deathPixSize = 0;
static int g_deathW = 0, g_deathH = 0;


static int dgValidBlendFactor(int v) {
    switch (v) {
        case 0: case 1:
        case 0x0300: case 0x0301:
        case 0x0302: case 0x0303:
        case 0x0304: case 0x0305:
        case 0x0306: case 0x0307:
        case 0x0308:
        case 0x8001: case 0x8002:
        case 0x8003: case 0x8004:
        case 0x88F9: case 0x88FA:
        case 0x88FB: case 0x88FC:
            return 1;
        default:
            return 0;
    }
}


static void deathGlRender(void) {
    if (maxOn()) return; // MAX：拦截（死亡画面也被压制）
    if (!g_deathGlEnabled || !g_deathPixels || g_deathW <= 0 || g_deathH <= 0) return;
    dgLoadProcs();
    if (!g_dgProcsLoaded) return;

    static GLuint dgProg = 0, dgVao = 0, dgVbo = 0, dgTex = 0;
    if (dgProg == 0) {
        const char* vs = "#version 150\nin vec2 aPos; in vec2 aUV; out vec2 vUV;\nvoid main(){ vUV=aUV; gl_Position=vec4(aPos,0.0,1.0); }";
        const char* fs = "#version 150\nuniform sampler2D uTex; in vec2 vUV; out vec4 frag;\nvoid main(){ frag=texture(uTex,vUV); }";
        GLuint v = g_dgCreateShader(GL_VERTEX_SHADER);
        g_dgShaderSource(v, 1, &vs, NULL);
        g_dgCompileShader(v);
        GLuint f = g_dgCreateShader(GL_FRAGMENT_SHADER);
        g_dgShaderSource(f, 1, &fs, NULL);
        g_dgCompileShader(f);
        dgProg = g_dgCreateProgram();
        g_dgAttachShader(dgProg, v);
        g_dgAttachShader(dgProg, f);
        g_dgLinkProgram(dgProg);
        g_dgDeleteShader(v);
        g_dgDeleteShader(f);
        GLint ok = 0;
        g_dgGetProgramiv(dgProg, GL_LINK_STATUS, &ok);
        if (!ok) { printf("[taichi_hook] death GL shader link failed\n"); return; }
        g_dgGenVertexArrays(1, &dgVao);
        g_dgGenBuffers(1, &dgVbo);
        g_dgGenTextures(1, &dgTex);
        g_dgBindVertexArray(dgVao);
        g_dgBindBuffer(GL_ARRAY_BUFFER, dgVbo);
        g_dgEnableVertexAttribArray(0);
        g_dgVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 16, (const void*)0);
        g_dgEnableVertexAttribArray(1);
        g_dgVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 16, (const void*)8);
        g_dgBindVertexArray(0);
    }

    GLint prevProg = 0, prevVao = 0, prevVbo = 0, prevTex = 0, prevActive = 0;
    GLint prevBlendSrc = 0, prevBlendDst = 0, prevUnpack = 0;
    GLboolean prevBlend = GL_FALSE, prevDepth = GL_FALSE;
    g_dgGetIntegerv(GL_CURRENT_PROGRAM, &prevProg);
    g_dgGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prevVao);
    g_dgGetIntegerv(GL_ARRAY_BUFFER_BINDING, &prevVbo);
    g_dgGetIntegerv(GL_ACTIVE_TEXTURE, &prevActive);
    g_dgGetIntegerv(GL_TEXTURE_BINDING_2D, &prevTex);
    g_dgGetIntegerv(GL_BLEND_SRC, &prevBlendSrc);
    g_dgGetIntegerv(GL_BLEND_DST, &prevBlendDst);
    g_dgGetIntegerv(GL_UNPACK_ALIGNMENT, &prevUnpack);
    prevBlend = g_dgIsEnabled(GL_BLEND);
    prevDepth = g_dgIsEnabled(GL_DEPTH_TEST);

    g_dgDisable(GL_DEPTH_TEST);
    g_dgEnable(GL_BLEND);
    g_dgBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    g_dgPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    g_dgActiveTexture(GL_TEXTURE0);
    g_dgBindTexture(GL_TEXTURE_2D, dgTex);
    g_dgTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, g_deathW, g_deathH, 0, GL_RGBA, GL_UNSIGNED_BYTE, g_deathPixels);
    g_dgTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    g_dgTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    g_dgTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    g_dgTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    float quad[24] = {
        -1.0f,-1.0f, 0.0f,0.0f,   1.0f,-1.0f, 1.0f,0.0f,   -1.0f,1.0f, 0.0f,1.0f,
        -1.0f,1.0f, 0.0f,1.0f,    1.0f,-1.0f, 1.0f,0.0f,    1.0f,1.0f, 1.0f,1.0f
    };
    g_dgUseProgram(dgProg);
    g_dgUniform1i(g_dgGetUniformLocation(dgProg, "uTex"), 0);
    g_dgBindVertexArray(dgVao);
    g_dgBindBuffer(GL_ARRAY_BUFFER, dgVbo);
    g_dgBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STREAM_DRAW);

    if (g_realGlDrawArrays) {
        g_realGlDrawArrays(GL_TRIANGLES, 0, 6);
    } else {
        g_dgDrawArrays(GL_TRIANGLES, 0, 6); // hook 未装（理论不发生）——回退
    }

    g_dgUseProgram(prevProg);
    g_dgBindVertexArray(prevVao);
    g_dgBindBuffer(GL_ARRAY_BUFFER, prevVbo);
    g_dgActiveTexture(prevActive);
    g_dgBindTexture(GL_TEXTURE_2D, prevTex);
    g_dgPixelStorei(GL_UNPACK_ALIGNMENT, prevUnpack);

    g_dgBlendFunc(dgValidBlendFactor(prevBlendSrc) ? prevBlendSrc : 1,
                  dgValidBlendFactor(prevBlendDst) ? prevBlendDst : 0);
    if (!prevBlend) g_dgDisable(GL_BLEND);
    if (prevDepth) g_dgEnable(GL_DEPTH_TEST);
}


extern "C" static void deathGdiRender(void) {
    if (maxOn()) return; // MAX：拦截
    if (!g_deathGlEnabled || !g_deathPixels || g_deathW <= 0 || g_deathH <= 0) return;
    if (!g_realBitBlt) return; // hook 未装（无原始入口）——跳过
    if (g_mcHwndCached == NULL) g_mcHwndCached = findMcWindow();
    if (!g_mcHwndCached) return;

    HDC winDC = g_realGetDC(g_mcHwndCached); // 穿透画布封锁（窗口 DC——MAX 下死亡通道已被 maxOn 拦，此处统一用 real）
    if (!winDC) return;
    HDC memDC = CreateCompatibleDC(winDC);
    if (!memDC) { ReleaseDC(g_mcHwndCached, winDC); return; }

    BITMAPINFO bmi; ZeroMemory(&bmi, sizeof(bmi));
    bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bmi.bmiHeader.biWidth = g_deathW;
    bmi.bmiHeader.biHeight = -g_deathH; // 顶向下（位图首行 = 窗口顶部）
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 32;
    bmi.bmiHeader.biCompression = BI_RGB;
    void* bits = NULL;
    HBITMAP dib = CreateDIBSection(memDC, &bmi, DIB_RGB_COLORS, &bits, NULL, 0);
    if (dib && bits) {

        const unsigned char* s = g_deathPixels;
        unsigned char* d = (unsigned char*)bits;
        for (int i = 0; i < g_deathW * g_deathH; i++) {
            d[0] = s[2]; d[1] = s[1]; d[2] = s[0]; d[3] = s[3];
            s += 4; d += 4;
        }
        HBITMAP oldBmp = (HBITMAP)SelectObject(memDC, dib);

        g_realBitBlt(winDC, 0, 0, g_deathW, g_deathH, memDC, 0, 0, SRCCOPY);
        SelectObject(memDC, oldBmp);
    }
    if (dib) DeleteObject(dib);
    DeleteDC(memDC);
    ReleaseDC(g_mcHwndCached, winDC);
}

JNIEXPORT void JNICALL
Java_com_ryjs_reflection_client_render_TaiChiRenderBridge_nativeSetDeathGl(
        JNIEnv* env, jclass cls, jint on) {
    (void)env; (void)cls;
    InterlockedExchange(&g_deathGlEnabled, on ? 1 : 0);
    printf("[taichi_hook] death GL %s\n", on ? "ON" : "OFF");
}

JNIEXPORT void JNICALL
Java_com_ryjs_reflection_client_render_TaiChiRenderBridge_nativeDeathGlFrame(
        JNIEnv* env, jclass cls, jintArray pixels, jint w, jint h) {
    (void)cls;
    if (!pixels || w <= 0 || h <= 0) return;
    jsize len = env->GetArrayLength(pixels);
    if (len < (jsize)(w * h)) return;
    size_t need = (size_t)w * h * 4;
    if (need != g_deathPixSize) {
        if (g_deathPixels) { free(g_deathPixels); g_deathPixels = NULL; }
        g_deathPixels = (unsigned char*)malloc(need);
        g_deathPixSize = need;
    }
    if (!g_deathPixels) return;
    jint* src = env->GetIntArrayElements(pixels, NULL);
    if (!src) return;
    for (int i = 0; i < w * h; i++) {
        unsigned v = (unsigned)src[i];
        g_deathPixels[i * 4 + 0] = (unsigned char)(v >> 16);
        g_deathPixels[i * 4 + 1] = (unsigned char)(v >> 8);
        g_deathPixels[i * 4 + 2] = (unsigned char)(v);
        g_deathPixels[i * 4 + 3] = (unsigned char)(v >> 24);
    }
    env->ReleaseIntArrayElements(pixels, src, JNI_ABORT);
    g_deathW = w;
    g_deathH = h;
}

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID reserved) {
    (void)hModule; (void)reserved;
    if (reason == DLL_PROCESS_DETACH) {
        probeWriteLine("[taichi_hook] PROBE PROCESS_DETACH (normal unload / clean exit path)\n");
        removeHook();
    }
    return TRUE;
}

} // extern "C"
