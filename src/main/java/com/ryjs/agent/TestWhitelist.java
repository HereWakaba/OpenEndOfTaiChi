package com.ryjs.agent;


public final class TestWhitelist {

    private TestWhitelist() {}

    static void runAll() {
        TestSuite.banner("白名单放行对照组（com.ryjs.trusted，应被放行=真实执行）");
        DefenseAgent.DEBUG_TRANSFORM = true;

        allowed("WL1 System.loadLibrary放行", "expectLoadLibraryAllowed",
            "可信调用者被正确放行（观测到 UnsatisfiedLinkError）", "被误拦截（静默返回），放行逻辑失效");
        allowed("WL2 Runtime.loadLibrary放行", "expectRuntimeLoadLibraryAllowed",
            "可信调用者被正确放行（观测到 UnsatisfiedLinkError）", "被误拦截，放行逻辑失效");

        DefenseAgent.DEBUG_TRANSFORM = false;
    }

    private static void allowed(String tag, String method, String passMsg, String failMsg) {
        try {
            boolean correctlyAllowed = TestSuite.callStatic("com.ryjs.trusted.TrustedCaller", method);
            TestSuite.expectTrue(tag, correctlyAllowed, passMsg, failMsg);
        } catch (Throwable t) {
            TestSuite.fail(tag, "调用异常: " + t);
        }
    }
}
