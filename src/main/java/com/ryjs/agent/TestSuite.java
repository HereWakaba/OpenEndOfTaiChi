package com.ryjs.agent;

import java.lang.reflect.Method;


public final class TestSuite {

    private TestSuite() {}

    static int passed = 0;
    static int failed = 0;

    // 是否打印通过用例的明细。false 时通过用例只累计不刷屏（贴合“静默”诉求）；失败始终详细打印。
    static boolean printPass = true;

    static void reset() { passed = 0; failed = 0; }

    static void banner(String title) {
        System.out.println("\n========== " + title + " ==========\n");
    }

    static void pass(String tag, String detail) {
        passed++;
        if (printPass) System.out.println("[" + tag + "] ✅ 通过 — " + detail);
    }

    static void fail(String tag, String detail) {
        failed++;
        // 失败始终醒目打印
        System.out.println("[" + tag + "] ❌❌❌ 失败 — " + detail);
    }

    static void info(String detail) {
        if (printPass) System.out.println("        · " + detail);
    }

    /**
     * 断言某“防御成功”返回值为 true（为 true 则计为通过，否则计为失败）。
     */
    static void expectTrue(String tag, boolean actual, String passDetail, String failDetail) {
        if (actual) pass(tag, passDetail);
        else fail(tag, failDetail);
    }

    /** 反射调用某类的无参静态方法，返回 boolean 结果。 */
    static boolean callStatic(String binaryClassName, String methodName) throws Throwable {
        Class<?> c = Class.forName(binaryClassName);
        Method m = c.getDeclaredMethod(methodName);
        return (Boolean) m.invoke(null);
    }

    /** 反射调用某类的单参（sun.misc.Unsafe）静态方法，返回 boolean 结果。 */
    static boolean callStaticWithUnsafe(String binaryClassName, String methodName, Object unsafe) throws Throwable {
        Class<?> c = Class.forName(binaryClassName);
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Method m = c.getDeclaredMethod(methodName, unsafeClass);
        return (Boolean) m.invoke(null, unsafe);
    }

    /** 汇总输出。 */
    static void summary() {
        banner("测试结果汇总");
        System.out.println(String.format("通过: %d    失败: %d    合计: %d", passed, failed, passed + failed));
        if (failed == 0) {
            System.out.println("✅✅✅ 全部测试通过 ✅✅✅");
        } else {
            System.out.println("❌ 存在 " + failed + " 个失败用例，请检查上方 ❌ 标记项");
        }
    }
}
