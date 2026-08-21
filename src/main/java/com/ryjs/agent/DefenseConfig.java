package com.ryjs.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;


public final class DefenseConfig {

    private DefenseConfig() {}

    public static final String KEY_PANEL_SHOW = "panel.show";
    public static final String KEY_INTERCEPT_HIGH_RISK = "intercept.highRisk";  // 拦截 exit/halt/exec/load 等高危接口
    public static final String KEY_INTERCEPT_COREMOD   = "intercept.coremod";   // 拦截 coremod 相关
    public static final String KEY_INTERCEPT_MIXIN     = "intercept.mixin";     // 拦截 mixin 相关
    public static final String KEY_INTERCEPT_REFLECTION= "intercept.reflection";// 拦截反射（反射黑名单/ReflectionFactory 等）
    public static final String KEY_INTERCEPT_UNSAFE    = "intercept.unsafe";    // 拦截 Unsafe 调用

    public static final String KEY_INTERCEPT_ALLRETURN = "intercept.allReturn"; // 启用 AllReturn transformer（对 mods 目标类清空危险方法）
    public static final String KEY_UNSAFE_NULLIFY_THEUNSAFE = "intercept.unsafe.nullifyTheUnsafe"; // 子开关：全局置空 theUnsafe（WIP，默认关）

    public static final String KEY_UNSAFE_BLACKLIST = "intercept.unsafe.blacklist"; // 反射黑名单（隐藏 Unsafe 非public成员 + theUnsafe）
    public static final String KEY_UNSAFE_BYTECODE  = "intercept.unsafe.bytecode";  // 字节码重写（UnsafeGuard 代理 + Transformer + retransform）
    public static final String KEY_UNSAFE_RETRANSFORM = "intercept.unsafe.retransform"; // 对已加载类批量 retransform（隔离排查用）


    public static final String KEY_HR_SYSTEM_EXIT   = "intercept.highRisk.systemExit";   // System.exit
    public static final String KEY_HR_RUNTIME_EXIT  = "intercept.highRisk.runtimeExit";  // Runtime.exit / Runtime.halt
    public static final String KEY_HR_EXEC          = "intercept.highRisk.exec";         // Runtime.exec
    public static final String KEY_HR_PROCESS_START = "intercept.highRisk.processStart"; // ProcessBuilder.start
    public static final String KEY_HR_SYSTEM_LOAD   = "intercept.highRisk.systemLoad";   // System.load / System.loadLibrary
    public static final String KEY_HR_RUNTIME_LOAD  = "intercept.highRisk.runtimeLoad";  // Runtime.load / Runtime.loadLibrary
    public static final String KEY_HR_ATTACH        = "intercept.highRisk.attach";       // VirtualMachine.attach
    public static final String KEY_HR_LOAD_AGENT    = "intercept.highRisk.loadAgent";    // loadAgent / loadAgent0

    public static final String KEY_FULL_CLEAN_METHOD      = "intercept.full.cleanMethod";       // 清空 /mods/ 非白名单 static void/float/boolean 方法体
    public static final String KEY_FULL_COEXIST_ALLRETURN = "intercept.full.coexistAllReturn"; // 共存 AllReturn（static/非static 差异注入 + super 回退）
    public static final String KEY_FULL_ANTI_EXIT         = "intercept.full.antiExit";         // 反退出：就地废掉 /mods/ 类的 System.exit/Runtime.halt/exec/ProcessBuilder


    public static final String KEY_PROXY_SHELL            = "intercept.proxyShell";             // 拦发现 + 空壳注册（可与其它项叠加）


    public static final String KEY_COMPAT_MODE = "compat.mode";

    public static final String KEY_FULL_FILTER_COREMOD = "intercept.fullFilterCoremod";

    public static final String KEY_FULL_FILTER_MIXIN = "intercept.fullFilterMixin";

    public static final String KEY_AGENT_SUICIDE = "agent.suicide";

    public static final String KEY_EARLY_DISPLAY = "early.display";

    public static final String KEY_HOOK_RESTORE_GUARD = "hook.restoreGuard";

    public static final String KEY_HOOK_RESTORE_CHANNEL_JA = "hook.restoreGuardChannelJavaagent";

    public static final String KEY_HOOK_RESTORE_CHANNEL_JVMTI = "hook.restoreGuardChannelJvmti";

    public static final String KEY_HOOK_JVMTI_BREAK = "hook.jvmtiBreak";

    public static final String KEY_HOOK_JVMTI_BLAST = "hook.jvmtiBlast";

    public static final String KEY_HOOK_FULL_BLOCK = "hook.fullBlock";

    public static final String KEY_DEV_ZEROJVMTI_SIM = "dev.zeroJvmtiSim";

    public static final String KEY_DEV_ZEROJVMTI_KILL = "dev.zeroJvmtiKill";

    public static final String KEY_DEV_ZEROJVMTI_LATE = "dev.zeroJvmtiLate";


    private static final Map<String, Boolean> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put(KEY_PANEL_SHOW, true);
        DEFAULTS.put(KEY_INTERCEPT_HIGH_RISK, false);
        DEFAULTS.put(KEY_INTERCEPT_COREMOD, false);
        DEFAULTS.put(KEY_INTERCEPT_MIXIN, false);
        DEFAULTS.put(KEY_INTERCEPT_REFLECTION, false);
        DEFAULTS.put(KEY_INTERCEPT_UNSAFE, false);
        DEFAULTS.put(KEY_INTERCEPT_ALLRETURN, false);
        DEFAULTS.put(KEY_FULL_FILTER_COREMOD, false);
        DEFAULTS.put(KEY_FULL_FILTER_MIXIN, true);
        DEFAULTS.put(KEY_UNSAFE_NULLIFY_THEUNSAFE, false);
        DEFAULTS.put(KEY_UNSAFE_BLACKLIST, false);
        DEFAULTS.put(KEY_UNSAFE_BYTECODE, false);
        DEFAULTS.put(KEY_UNSAFE_RETRANSFORM, false);
        DEFAULTS.put(KEY_HR_SYSTEM_EXIT, false);
        DEFAULTS.put(KEY_HR_RUNTIME_EXIT, false);
        DEFAULTS.put(KEY_HR_EXEC, false);
        DEFAULTS.put(KEY_HR_PROCESS_START, false);
        DEFAULTS.put(KEY_HR_SYSTEM_LOAD, false);
        DEFAULTS.put(KEY_HR_RUNTIME_LOAD, false);
        DEFAULTS.put(KEY_HR_ATTACH, false);
        DEFAULTS.put(KEY_HR_LOAD_AGENT, false);
        DEFAULTS.put(KEY_FULL_CLEAN_METHOD, false);
        DEFAULTS.put(KEY_FULL_COEXIST_ALLRETURN, false);
        DEFAULTS.put(KEY_FULL_ANTI_EXIT, false);
        DEFAULTS.put(KEY_PROXY_SHELL, false);
        DEFAULTS.put(KEY_COMPAT_MODE, false);
        DEFAULTS.put(KEY_AGENT_SUICIDE, false);
        DEFAULTS.put(KEY_EARLY_DISPLAY, true);
        DEFAULTS.put(KEY_HOOK_RESTORE_GUARD, false);
        DEFAULTS.put(KEY_HOOK_JVMTI_BREAK, true);
        DEFAULTS.put(KEY_HOOK_JVMTI_BLAST, false);
        DEFAULTS.put(KEY_HOOK_FULL_BLOCK, false);
        DEFAULTS.put(KEY_DEV_ZEROJVMTI_SIM, false);
        DEFAULTS.put(KEY_DEV_ZEROJVMTI_KILL, false);
        DEFAULTS.put(KEY_DEV_ZEROJVMTI_LATE, false);
    }

    private static final Map<String, Boolean> VALUES = new LinkedHashMap<>(DEFAULTS);
    private static volatile boolean loaded = false;

    private static Path resolveConfigFile() {
        Path config = Paths.get("config");
        if (Files.isDirectory(config)) {
            return config.resolve("defense.cfg");
        }
        return Paths.get("defense.cfg");
    }


    public static synchronized void load() {
        if (loaded) return;
        Path file = resolveConfigFile();
        try {
            if (Files.exists(file)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
                for (String key : DEFAULTS.keySet()) {
                    String v = props.getProperty(key);
                    if (v != null) {
                        VALUES.put(key, Boolean.parseBoolean(v.trim()));
                    }
                }
                DefenseAgent.log("[Defense] 已加载配置: " + file.toAbsolutePath());
            } else {
                writeDefault(file);
                DefenseAgent.log("[Defense] 未找到配置，已生成默认配置: " + file.toAbsolutePath());
            }
        } catch (Throwable t) {
            DefenseAgent.warn("[Defense] 读取配置失败，使用默认值: " + t.getMessage());
        }
        loaded = true;
    }

    private static void writeDefault(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        StringBuilder sb = new StringBuilder();
        sb.append("# 所有开关均为布尔值 修改后重启生效。\n");
        sb.append("#\n");
        sb.append("# panel.show          : true(默认)=下次启动弹出配置面板并阻塞供勾选；false=不弹面板、直接用现有配置继续\n");
        sb.append("# intercept.highRisk  : 拦截 System.exit/Runtime.halt/exec/load 等高危系统接口\n");
        sb.append("# intercept.coremod   : 拦截 coremod 相关调用\n");
        sb.append("# intercept.mixin     : 拦截 mixin 相关调用\n");
        sb.append("# intercept.reflection: 拦截反射（ReflectionFactory / 反射黑名单）\n");
        sb.append("# intercept.unsafe    : 拦截 Unsafe 调用（代理层）\n");
        sb.append("# intercept.allSuper : 启用 AllSuper transformer（对 mods 目标类清空危险方法；默认关）\n");
        sb.append("# intercept.full.cleanMethod       : 全部拦截模式-清空方法体（清空 /mods/ 非白名单 static void/float/boolean；保留 init/clinit；默认关）\n");
        sb.append("# intercept.full.coexistAllReturn  : 全部拦截模式-共存 AllReturn（static/非static 差异注入 + super 回退；与普通 allReturn 互斥；默认关）\n");
        sb.append("# intercept.full.antiExit          : 全部拦截模式-反退出（就地废掉非平台类的 System.exit/Runtime.halt/exec/ProcessBuilder；默认关）\n");
        sb.append("# intercept.proxyShell             : 代理注册空壳模式-阻止非白名单 mod 被 Forge/SPI 发现（任何类不进 JVM）并把其物品空壳挂进创造栏；只做加法、与其它防御项可叠加不互斥；默认关\n");
        sb.append("# hook.restoreGuard               : 类还原守卫-对抗'还原原版类'攻击（对方把类还原成原版抹掉我方 Hook 再自己 Hook）；链尾仲裁+原版对比还原+5s 高频重注入+redefine 兜底；默认关\n");
        sb.append("# hook.jvmtiBreak                 : JVMTI 封锁（拒止外部 Agent）——RyjsAgent 加载即 hook jvmti 函数表/成员/GetEnv，外部 agent 加能力/设回调/开关事件/改类/枚举/销毁环境全拒；我方 nativeTool* 零通道（成员直调+env 自愈）不受影响；默认开\n");
        sb.append("# hook.jvmtiBlast                 : JVMTI 斩断（零通道免疫）——Dispose env + 表 blast 自废式极端模式；我方 nativeTool* 零通道（成员直调+env 自愈）免疫；不可逆重启恢复；默认关\n");
        sb.append("# intercept.unsafe.nullifyTheUnsafe : 全局置空 Unsafe.theUnsafe（高杀伤、会连带影响 Forge/lambda；WIP待加追溯来源；默认关）\n");
        sb.append("# intercept.unsafe.blacklist : Unsafe 反射黑名单（隔离排查用；默认开）\n");
        sb.append("# intercept.unsafe.bytecode  : Unsafe 字节码重写/代理/retransform（隔离排查用；默认开）\n");
        sb.append("# intercept.unsafe.retransform : 对已加载类批量 retransform（会破坏含 lambda 的已加载类、导致 EventBus 崩溃；默认关，仅拦新加载类）\n");
        sb.append("\n");
        for (Map.Entry<String, Boolean> e : DEFAULTS.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** 将当前内存值（VALUES）写回文件。缺失的键用默认值补齐。 */
    private static void writeCurrent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        StringBuilder sb = new StringBuilder();
        sb.append("# Reflection 防御配置（由配置面板写入）\n");
        sb.append("# 所有开关均为布尔值（true/false）。\n\n");
        for (String key : DEFAULTS.keySet()) {
            boolean v = VALUES.getOrDefault(key, DEFAULTS.get(key));
            sb.append(key).append('=').append(v).append('\n');
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** 读取某开关（未加载时先触发加载）。兼容模式开启时：面板功能全关（互斥）——仅面板自身与兼容模式开关本身不受影响。 */
    public static boolean isEnabled(String key) {
        if (!loaded) load();
        if (VALUES.getOrDefault(KEY_COMPAT_MODE, false)
                && !KEY_PANEL_SHOW.equals(key)
                && !KEY_COMPAT_MODE.equals(key)) {
            return false;
        }
        Boolean v = VALUES.get(key);
        return v != null ? v : DEFAULTS.getOrDefault(key, false);
    }

    /** 兼容模式开关。开启时：① 面板功能全关（互斥，读时拦截）② 启动 ReinjectGuard（后手重注入）。 */
    public static void setCompatMode(boolean on) {
        if (!loaded) load();
        VALUES.put(KEY_COMPAT_MODE, on);
        save();
        DefenseAgent.log("[Defense] 兼容模式: " + (on ? "开启（面板功能全关，走重注入策略）" : "关闭"));
        if (on) {
            com.ryjs.core.CoreBridge.reinjectGuardStart(com.ryjs.coremod.Agent.AgentUtil.INST);
        }
    }

    public static boolean compatMode() {
        if (!loaded) load();
        return VALUES.getOrDefault(KEY_COMPAT_MODE, false);
    }

    /** 完整过滤 Coremod（重启带 premain）——isEnabled 控制（兼容模式下自动关）。 */
    public static boolean fullFilterCoremod() {
        return isEnabled(KEY_FULL_FILTER_COREMOD);
    }

    /** 完整过滤 Mixin（premain 打飞）——isEnabled 控制（兼容模式下自动关）。 */
    public static boolean fullFilterMixin() {
        return isEnabled(KEY_FULL_FILTER_MIXIN);
    }

    /** 检测到外部 agent 参数时是否自爆（直接退出）。默认 false=降级模式（防御 Hook 不运行）。 */
    public static boolean agentSuicide() {
        return isEnabled(KEY_AGENT_SUICIDE);
    }

    /** 早期加载画面接管开关（DisplayWindow 注入）。默认 true——窗口一出现即显示我们的画面。 */
    public static boolean earlyDisplay() {
        return isEnabled(KEY_EARLY_DISPLAY);
    }

    /** 类还原守卫开关：对抗还原攻击（链尾仲裁 + 原版对比还原 + 高频重注入 + redefine 兜底）。默认关。
     *  打爆 JVMTI（不 Dispose 版）env 保留——回调/transform 链活，类还原不受影响（不互斥）。 */
    public static boolean restoreGuard() {
        return isEnabled(KEY_HOOK_RESTORE_GUARD);
    }

    /** 类还原通道（已废弃通道选择——2026-08-16 零-JVMTI 化）：恒返回 "auto"。
     *  实际链路 = 观察名单（导出 hook 实时推送）+ 零通道 redefine/retransform（成员直连）+ cb transform，
     *  INST/JVMTI 仅作内部兜底。旧配置键（javaagent/jvmti）保留兼容但不再生效。 */
    public static String restoreGuardChannel() {
        return "auto";
    }

    /** 殴打 JVMTIEnv 断链开关：默认开（纯 native——我方 DLL 加载即武装；关=解除 hook，用于观察外部 agent 行为）。 */
    public static boolean jvmtiBreak() {
        return isEnabled(KEY_HOOK_JVMTI_BREAK);
    }

    /** 打爆 JVMTI（不 Dispose 版）：表打爆 + 双封锁 + instrument 拒——env 保留（我方功能不受影响）。默认关。 */
    public static boolean jvmtiBlast() {
        return isEnabled(KEY_HOOK_JVMTI_BLAST) || fullBlock();
    }

    /** 全部阻止模式（完整封锁）：jvmtiBreak+jvmtiBlast 一体化——全表 seal + env 毒化 + 全链路封锁。
     *  面板新增正式模式；与 DevMode 演练解耦（DevMode 仅测试用）。 */
    public static boolean fullBlock() {
        return isEnabled(KEY_HOOK_FULL_BLOCK);
    }

    /** DevMode：ZeroJvmti 先手模拟（RyjsAgent 武装前初始化对手 DLL——复现"对方先手"）。默认关。 */
    public static boolean zeroJvmtiSim() {
        return isEnabled(KEY_DEV_ZEROJVMTI_SIM);
    }

    /** DevMode：先手模拟附带 KillJvmti（对方四层拦截 + 5ms watchdog——写战演练）。默认关。 */
    public static boolean zeroJvmtiKill() {
        return isEnabled(KEY_DEV_ZEROJVMTI_KILL);
    }

    public static boolean zeroJvmtiLate() {
        return isEnabled(KEY_DEV_ZEROJVMTI_LATE);
    }

    /** 下次启动是否显示配置面板：true(默认)=弹面板并阻塞；false=跳过面板、直接用现有配置继续。 */
    public static boolean showPanel()          { return isEnabled(KEY_PANEL_SHOW); }

    public static boolean interceptHighRisk()  { return isEnabled(KEY_INTERCEPT_HIGH_RISK); }
    public static boolean interceptCoremod()   { return isEnabled(KEY_INTERCEPT_COREMOD); }
    public static boolean interceptMixin()     { return isEnabled(KEY_INTERCEPT_MIXIN); }
    public static boolean interceptReflection(){ return isEnabled(KEY_INTERCEPT_REFLECTION); }
    public static boolean interceptUnsafe()    { return isEnabled(KEY_INTERCEPT_UNSAFE); }
    public static boolean interceptAllReturn() { return isEnabled(KEY_INTERCEPT_ALLRETURN) && !fullInterceptMode(); }
    // 全部拦截模式（保留 init/clinit）：薅自 Diamond 的清空方法体 + 共存 AllReturn；开启后普通 AllReturn 被上面这行强制关闭，避免两套 AllReturn 双重注入冲突。
    public static boolean fullCleanMethod()      { return isEnabled(KEY_FULL_CLEAN_METHOD); }
    public static boolean fullCoexistAllReturn() { return isEnabled(KEY_FULL_COEXIST_ALLRETURN); }

    public static boolean fullAntiExit()         { return isEnabled(KEY_FULL_ANTI_EXIT); }
    /** 代理注册空壳模式：阻止非白名单 mod 被 Forge/SPI 发现（类不进 JVM）+ 空壳注册进创造栏；只做加法，与其它防御项可叠加、不互斥。 */
    public static boolean proxyShell()           { return isEnabled(KEY_PROXY_SHELL); }
    /** 全部拦截模式是否开启（两子项任一为真）。 */
    public static boolean fullInterceptMode()    { return fullCleanMethod() || fullCoexistAllReturn(); }
    public static boolean nullifyTheUnsafe()   { return isEnabled(KEY_UNSAFE_NULLIFY_THEUNSAFE); }
    public static boolean unsafeBlacklist()    { return isEnabled(KEY_UNSAFE_BLACKLIST); }
    public static boolean unsafeBytecode()     { return isEnabled(KEY_UNSAFE_BYTECODE); }
    public static boolean unsafeRetransform()  { return isEnabled(KEY_UNSAFE_RETRANSFORM); }
    // 高危子开关：均需总开关 interceptHighRisk() 也为 true 才生效。
    public static boolean hrSystemExit()   { return interceptHighRisk() && isEnabled(KEY_HR_SYSTEM_EXIT); }
    public static boolean hrRuntimeExit()  { return interceptHighRisk() && isEnabled(KEY_HR_RUNTIME_EXIT); }
    public static boolean hrExec()         { return interceptHighRisk() && isEnabled(KEY_HR_EXEC); }
    public static boolean hrProcessStart() { return interceptHighRisk() && isEnabled(KEY_HR_PROCESS_START); }
    public static boolean hrSystemLoad()   { return interceptHighRisk() && isEnabled(KEY_HR_SYSTEM_LOAD); }
    public static boolean hrRuntimeLoad()  { return interceptHighRisk() && isEnabled(KEY_HR_RUNTIME_LOAD); }
    public static boolean hrAttach()       { return interceptHighRisk() && isEnabled(KEY_HR_ATTACH); }
    public static boolean hrLoadAgent()    { return interceptHighRisk() && isEnabled(KEY_HR_LOAD_AGENT); }

    // ===== 供配置面板使用的读/写 API =====
    /** 直接读取某键的当前值（不叠加总开关逻辑，供面板展示勾选状态）。 */
    public static boolean getRaw(String key) { return isEnabled(key); }

    /** 面板修改内存中的值（未写盘）。 */
    public static void setValue(String key, boolean value) {
        if (!loaded) load();
        VALUES.put(key, value);
    }

    /** 将当前内存中的所有值写回 defense.cfg。 */
    public static synchronized void save() {
        Path file = resolveConfigFile();
        try {
            writeCurrent(file);
            DefenseAgent.log("[Defense] 配置已保存: " + file.toAbsolutePath());
        } catch (Throwable t) {
            DefenseAgent.warn("[Defense] 保存配置失败: " + t.getMessage());
        }
    }

    /** 确保配置已加载（供面板在读取前调用）。 */
    public static void ensureLoaded() { if (!loaded) load(); }
}
