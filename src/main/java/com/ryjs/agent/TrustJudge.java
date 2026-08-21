package com.ryjs.agent;

import java.lang.StackWalker.StackFrame;
import java.security.CodeSource;


public final class TrustJudge {

    private TrustJudge() {}

    // mods 目录的绝对路径（规范化、小写、统一分隔符），由 DefenseAgent 在初始化时注入。
    // 为空表示无法定位 mods 目录，此时保守放行（避免误伤，宁可漏拦不可错杀启动流程）。
    static volatile String modsDirNormalized = null;

    private static final StackWalker WALKER =
        StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** 由 DefenseAgent 注入 mods 目录路径。 */
    public static void setModsDir(String absolutePath) {
        modsDirNormalized = normalize(absolutePath);
    }

    private static String normalize(String path) {
        if (path == null) return null;
        String p = path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        // 去掉可能的 file:/ 前缀与 union: 前缀
        if (p.startsWith("union:")) p = p.substring(6);
        if (p.startsWith("file:")) p = p.substring(5);
        while (p.startsWith("/") && p.length() > 2 && p.charAt(2) == ':') p = p.substring(1); // /C:/... → C:/...
        return p;
    }

    /**
     * 判定当前调用是否来自可信来源。
     * @return true=可信（放行），false=不可信（拦截）
     */
    public static boolean isCallerTrusted() {
        Class<?> caller = findDecisiveCaller();
        boolean trusted = caller != null && isClassTrusted(caller);
        if (!trusted && DEBUG) {
            printInterceptTrace(caller);
        }
        return trusted;
    }

    // 拦截时是否打印调用笺（决定性帧 + 完整调用栈）。默认开，便于在真实环境确认拦截生效。
    public static volatile boolean DEBUG = true;

    private static void printInterceptTrace(Class<?> caller) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Defense/intercept] 拦截不可信调用 — 决定性帧: ");
        if (caller == null) {
            sb.append("<未找到业务帧>");
        } else {
            sb.append(caller.getName());
            try {
                CodeSource cs = caller.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) sb.append("  来源: ").append(cs.getLocation());
            } catch (Throwable ignored) {}
        }
        sb.append('\n');
        // 完整调用栈（跳过取栈自身帧）
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.equals("java.lang.Thread") && e.getMethodName().equals("getStackTrace")) continue;
            sb.append("    at ").append(cn).append('.').append(e.getMethodName())
              .append('(').append(e.getFileName()).append(':').append(e.getLineNumber()).append(")\n");
        }
        System.out.println(sb);
    }

    /**
     * 供 UnsafeGuard 代理类调用：不可信则抛 SecurityException，可信则正常返回。
     * 专门做成 void 方法，以便生成的字节码只需一条 INVOKESTATIC 就能接入。
     */
    public static void checkCallerOrThrow() {
        if (!isCallerTrusted()) {
            throw new SecurityException("Unauthorized Unsafe call");
        }
    }

    /** 找到调用栈中第一个真正的业务调用帧的 Class。 */
    private static Class<?> findDecisiveCaller() {
        return WALKER.walk(frames -> frames
            .map(StackFrame::getDeclaringClass)
            .filter(TrustJudge::isDecisiveFrame)
            .findFirst()
            .orElse(null));
    }

    /** 是否为“决定性帧”：排除防御自身、取栈与反射框架帧。 */
    private static boolean isDecisiveFrame(Class<?> c) {
        String n = c.getName();
        if (n.startsWith("com.ryjs.agent.")) return false;     // 防御自身（含 TrustJudge/UnsafeGuard/HighRiskGuard）
        if (n.equals("java.lang.Thread")) return false;         // 取栈帧
        if (n.startsWith("java.lang.invoke.")) return false;    // MethodHandle 框架
        if (n.startsWith("jdk.internal.reflect.")) return false;// 反射框架
        if (n.startsWith("java.lang.reflect.")) return false;   // 反射框架
        if (n.startsWith("sun.reflect.")) return false;
        return true;
    }

    /** 判断某个类的物理来源是否可信（不在 mods 目录即可信）。 */
    public static boolean isClassTrusted(Class<?> c) {
        String mods = modsDirNormalized;
        if (mods == null) return true; // 无法定位 mods 目录 → 保守放行，不阻断启动
        try {
            java.security.ProtectionDomain pd = c.getProtectionDomain();
            if (pd == null) return true;              // 引导层/核心类无 PD → 可信
            CodeSource cs = pd.getCodeSource();
            if (cs == null || cs.getLocation() == null) return true; // 无来源信息 → 视为核心类，可信
            String loc = normalize(cs.getLocation().getPath());
            if (loc == null) return true;
            // 来源落在 mods 目录下 → 外部 mod → 不可信
            return !loc.startsWith(mods);
        } catch (Throwable t) {
            // 判定过程异常时保守放行，避免误杀启动流程
            return true;
        }
    }
}
