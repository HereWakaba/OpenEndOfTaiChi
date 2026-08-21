package com.ryjs.coremod;


public final class MainStageBootstrap {

    private MainStageBootstrap() {
    }

    // Main.main 回调 触发业务类全量预定义
    public static void onMainEnter() {
        com.ryjs.coremod.Agent.AgentUtil.defineEncryptedBusiness();
    }
}
