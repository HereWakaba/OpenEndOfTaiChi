package com.ryjs.agent;


public final class TestVariantStack {

    private TestVariantStack() {}

    static void runAll() {
        TestSuite.banner("变形调用栈拦截（net.hackmod，嵌套/lambda/新线程）");
        DefenseAgent.DEBUG_TRANSFORM = true;

        safe("VS1 嵌套私有方法exit", "attemptExitViaNestedCall",
            "深层嵌套调用的 exit 被静默拦截", "嵌套调用 exit 真实执行");
        safe("VS2 lambda内exit", "attemptExitViaLambda",
            "lambda 内的 exit 被静默拦截", "lambda 内 exit 真实执行");
        safe("VS3 lambda内exec", "attemptExecViaLambda",
            "lambda 内的 exec 被拦截返回 null", "lambda 内 exec 真实执行");
        safe("VS4 新线程内exit", "attemptExitViaNewThread",
            "新线程内的 exit 被静默拦截，进程存活", "新线程内 exit 真实终止进程");

        DefenseAgent.DEBUG_TRANSFORM = false;
    }

    private static void safe(String tag, String method, String passMsg, String failMsg) {
        try {
            boolean intercepted = TestSuite.callStatic("net.hackmod.NestedAttacker", method);
            TestSuite.expectTrue(tag, intercepted, passMsg, failMsg);
        } catch (Throwable t) {
            TestSuite.fail(tag, "调用异常: " + t);
        }
    }
}
