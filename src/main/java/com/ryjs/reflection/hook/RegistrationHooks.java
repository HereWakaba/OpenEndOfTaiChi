package com.ryjs.reflection.hook;

import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;


public final class RegistrationHooks {

    private RegistrationHooks() {
    }

    @AsmHook(targetClass = "net/minecraftforge/common/ForgeMod",
            targetMethod = "<init>",
            targetDescriptor = "(Lnet/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext;)V",
            mode = HookMode.HEAD)
    public static void onForgeModConstruct(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext context) {
        System.out.println("[RegistrationHooks] ForgeMod 构造器 hook 触发");
        try {
            com.ryjs.reflection.Registration.init(context.getModEventBus());
            System.out.println("[RegistrationHooks] 注册热注入完成");
        } catch (Throwable t) {
            System.err.println("[RegistrationHooks] 注册热注入失败: " + t);
        }
    }
}
