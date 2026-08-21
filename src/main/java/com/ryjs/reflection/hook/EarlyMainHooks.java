package com.ryjs.reflection.hook;

import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.hook.hook.HookResult;


public final class EarlyMainHooks {

    private EarlyMainHooks() {
    }

    /** 主类入口已执行。 */
    public static volatile boolean mainEntered = false;

    @AsmHook(targetClass = "net/minecraft/client/main/Main", targetMethod = "main",
            targetDescriptor = "([Ljava/lang/String;)V",
            mode = HookMode.HEAD)
    public static HookResult<Void> onMainEnter(String[] args) {
        mainEntered = true;
        try {
            com.ryjs.coremod.MainStageBootstrap.onMainEnter();
        } catch (Throwable ignored) {
        }
        return HookResult.pass();
    }
}
