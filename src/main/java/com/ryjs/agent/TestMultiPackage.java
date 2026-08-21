package com.ryjs.agent;


public final class TestMultiPackage {

    private TestMultiPackage() {}

    static void runAll() {
        TestSuite.banner("多包外部模拟拦截（org.badmod / com.thirdparty，均非白名单）");
        DefenseAgent.DEBUG_TRANSFORM = true;


        safe("MP1 badmod.exec", "org.badmod.EvilPlugin", "attemptRuntimeExec",
            "exec 被拦截返回 null", "exec 真实执行");
        safe("MP2 badmod.loadLibrary", "org.badmod.EvilPlugin", "attemptSystemLoadLibrary",
            "loadLibrary 静默 no-op", "loadLibrary 真实执行");

        // ---- com.thirdparty.UnsafeAbuser：exec 各重载 ----
        safe("MP3 thirdparty.exec[]", "com.thirdparty.UnsafeAbuser", "attemptExecArray",
            "exec(String[]) 被拦截", "exec(String[]) 真实执行");
        safe("MP4 thirdparty.exec+env", "com.thirdparty.UnsafeAbuser", "attemptExecWithEnv",
            "exec(cmd,env) 被拦截", "exec(cmd,env) 真实执行");
        safe("MP5 thirdparty.exec-full", "com.thirdparty.UnsafeAbuser", "attemptExecFull",
            "exec(cmd,env,dir) 被拦截", "exec(cmd,env,dir) 真实执行");
        safe("MP6 thirdparty.rtLoadLibrary", "com.thirdparty.UnsafeAbuser", "attemptRuntimeLoadLibrary",
            "Runtime.loadLibrary 静默 no-op", "Runtime.loadLibrary 真实执行");
        safe("MP7 thirdparty.rtLoad", "com.thirdparty.UnsafeAbuser", "attemptRuntimeLoad",
            "Runtime.load 静默 no-op", "Runtime.load 真实执行");

        // ---- 危险的进程退出放最后 ----
        safe("MP8 badmod.exit", "org.badmod.EvilPlugin", "attemptSystemExit",
            "进程未退出，exit 被静默拦截", "进程被 exit 终止");
        safe("MP9 badmod.halt", "org.badmod.EvilPlugin", "attemptRuntimeHalt",
            "进程未退出，halt 被静默拦截", "进程被 halt 终止");

        DefenseAgent.DEBUG_TRANSFORM = false;
    }

    private static void safe(String tag, String cls, String method, String passMsg, String failMsg) {
        try {
            boolean intercepted = TestSuite.callStatic(cls, method);
            TestSuite.expectTrue(tag, intercepted, passMsg, failMsg);
        } catch (Throwable t) {
            TestSuite.fail(tag, "调用异常: " + t);
        }
    }
}
