package com.ryjs.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import sun.misc.Unsafe;
import sun.reflect.ReflectionFactory;

//Java Side DevMode
//MC环境过于诡异
//单JAva环境我未遇到问题
public class TestDefense {

    public static void main(String[] args) throws Throwable {
        TestSuite.banner("防御部署");
        // 部署阶段打开 VERBOSE，便于确认各层就绪；随后组内按需开启 DEBUG_TRANSFORM。
        DefenseAgent.VERBOSE = true;
        DefenseAgent.applyDefenses();
        DefenseAgent.VERBOSE = false;

        TestSuite.reset();

        // 组一：反射 / 单例
        TestSuite.banner("组一：反射 / 单例防御");
        testTheUnsafeFieldReflection();
        testReflectionFactoryDirectCall();
        testReflectionFactoryReflectionCall();
        testSoleInstanceFieldReflection();
        testUnsafeGetUnsafeReturnsNull();

        // 组二：高危拦截（FakeMod）
        TestSuite.banner("组二：高危系统接口拦截（com.evilmod.FakeMod，非白名单）");
        DefenseAgent.DEBUG_TRANSFORM = true;
        // 先不杀进程的用例，再 exit/halt
        highRisk("TEST9 exec", "attemptRuntimeExec", "exec 返回 null", "exec 真实执行");
        highRisk("TEST10 start", "attemptProcessBuilderStart", "start 返回 null", "start 真实启动进程");
        highRisk("TEST11 loadLibrary", "attemptSystemLoadLibrary", "loadLibrary 静默 no-op", "loadLibrary 真实执行");
        highRisk("TEST12 load", "attemptSystemLoad", "load 静默 no-op", "load 真实执行");
        highRisk("TEST6 exit", "attemptSystemExit", "进程未退出，exit 被静默拦截", "进程被 exit 终止");
        highRisk("TEST7 rtExit", "attemptRuntimeExit", "进程未退出，Runtime.exit 被静默拦截", "进程被 Runtime.exit 终止");
        highRisk("TEST8 halt", "attemptRuntimeHalt", "进程未退出，Runtime.halt 被静默拦截", "进程被 halt 终止");
        DefenseAgent.DEBUG_TRANSFORM = false;

        // 组三~五：专题类
        TestMultiPackage.runAll();
        TestVariantStack.runAll();
        TestWhitelist.runAll();

        // 组六：诊断
        diagnostics();

        TestSuite.summary();
        if (TestSuite.failed != 0) {
            // 让 CI/gradle 感知失败
            throw new AssertionError("存在 " + TestSuite.failed + " 个失败用例");
        }
    }

    // ---- 组二 FakeMod 高危调用（反射） ----
    private static void highRisk(String tag, String method, String passMsg, String failMsg) {
        try {
            boolean intercepted = TestSuite.callStatic("com.evilmod.FakeMod", method);
            TestSuite.expectTrue(tag, intercepted, passMsg, failMsg);
        } catch (Throwable t) {
            TestSuite.fail(tag, "调用异常: " + t);
        }
    }

    // ============================================================
    // 组一：反射 / 单例防御
    // ============================================================
    static void testTheUnsafeFieldReflection() {
        String tag = "TEST1 反射取 Unsafe.theUnsafe";
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object u = f.get(null);
            if (u == null) TestSuite.pass(tag, "字段可见但值为 null（已置空）");
            else TestSuite.fail(tag, "拿到了实例: " + u);
        } catch (NoSuchFieldException e) {
            TestSuite.pass(tag, "字段已被反射黑名单隐藏");
        } catch (Exception e) {
            TestSuite.pass(tag, "被其它异常阻断: " + e.getClass().getSimpleName());
        }
    }

    static void testReflectionFactoryDirectCall() {
        String tag = "TEST2 直调 getReflectionFactory";
        Object factory = ReflectionFactory.getReflectionFactory();
        if (factory == null) TestSuite.pass(tag, "返回 null（字节码改写 + 单例置空生效）");
        else TestSuite.fail(tag, "返回了有效实例: " + factory);
    }

    static void testReflectionFactoryReflectionCall() {
        String tag = "TEST3 反射调 getReflectionFactory";
        try {
            Method m = ReflectionFactory.class.getDeclaredMethod("getReflectionFactory");
            m.setAccessible(true);
            Object factory = m.invoke(null);
            if (factory == null) TestSuite.pass(tag, "反射调用返回 null");
            else TestSuite.fail(tag, "拿到了实例: " + factory);
        } catch (NoSuchMethodException e) {
            TestSuite.pass(tag, "方法已被反射黑名单隐藏");
        } catch (Exception e) {
            TestSuite.pass(tag, "被其它异常阻断: " + e.getClass().getSimpleName());
        }
    }

    static void testSoleInstanceFieldReflection() {
        String tag = "TEST4 反射取 soleInstance";
        try {
            Field f = ReflectionFactory.class.getDeclaredField("soleInstance");
            f.setAccessible(true);
            Object instance = f.get(null);
            if (instance == null) TestSuite.pass(tag, "字段可见但值为 null（已置空）");
            else TestSuite.fail(tag, "拿到了实例: " + instance);
        } catch (NoSuchFieldException e) {
            TestSuite.pass(tag, "字段已被反射黑名单隐藏");
        } catch (Exception e) {
            TestSuite.pass(tag, "被其它异常阻断: " + e.getClass().getSimpleName());
        }
    }

    static void testUnsafeGetUnsafeReturnsNull() {
        String tag = "TEST5 Unsafe.getUnsafe()";
        try {
            Unsafe u = Unsafe.getUnsafe();
            if (u == null) TestSuite.pass(tag, "返回 null（单例已置空）");
            else TestSuite.fail(tag, "返回了有效实例: " + u);
        } catch (SecurityException e) {
            TestSuite.pass(tag, "调用被拒绝: " + e.getMessage());
        }
    }

    // ============================================================
    // 组六：诊断
    // ============================================================
    static void diagnostics() {
        TestSuite.banner("组六：防御机制诊断信息");
        TestSuite.info("配置 reflection=" + DefenseConfig.interceptReflection()
            + " unsafe=" + DefenseConfig.interceptUnsafe()
            + " highRisk=" + DefenseConfig.interceptHighRisk()
            + " coremod=" + DefenseConfig.interceptCoremod()
            + " mixin=" + DefenseConfig.interceptMixin());

        int guardMethods = DefenseAgent.GUARD_METHODS.size();
        if (DefenseAgent.guardBuilt && guardMethods > 0) {
            TestSuite.pass("DIAG-UnsafeGuard", "代理方法数 = " + guardMethods);
        } else {
            TestSuite.fail("DIAG-UnsafeGuard", "代理类未就绪（guardBuilt=" + DefenseAgent.guardBuilt + "）");
        }

        int redirects = DefenseAgent.HIGH_RISK_REDIRECTS.size();
        if (redirects > 0) TestSuite.pass("DIAG-HighRisk", "高危重定向条目数 = " + redirects);
        else TestSuite.fail("DIAG-HighRisk", "高危重定向表为空");
    }
}
