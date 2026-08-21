package com.ryjs.coremod.ImmediateWindowProvider;

import com.ryjs.agent.DefenseAgent;
import com.ryjs.coremod.CoexistenceCleaner;
import com.ryjs.coremod.ModClassScanner;
import com.ryjs.coremod.ReflectionCoremod;
import net.minecraftforge.fml.loading.ImmediateWindowProvider;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.*;

public class EartyLoading implements ImmediateWindowProvider {

    public static Set<String> targetClasses = new HashSet<>();

    public static volatile boolean agentDetected = false;


    private static boolean detectExternalAgent() {
        try {
            if (Boolean.getBoolean("reflection.filterRestarted")) {
                return false;
            }
            java.util.List<String> args =
                    java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String a : args) {
                String low = a.toLowerCase();
                if (low.startsWith("-javaagent") || low.startsWith("-agentpath") || low.startsWith("-agentlib")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static {
        try {
            com.ryjs.agent.DefenseConfig.ensureLoaded();
            if (detectExternalAgent()) {
                agentDetected = true;
                System.out.println("");
                System.out.println("检测到外部 agent 参数 请使用官方环境对抗");
                if (com.ryjs.agent.DefenseConfig.agentSuicide()) {
                    System.out.println("配置为自爆模式 运行half eixt");
                    Runtime.getRuntime().halt(0);
                    System.exit(0);
                }
                System.out.println("降级模式：防御 Hook 逻辑不运行（防御/幻象/渲染注入全部跳过）");
                System.out.println("");
            }
        } catch (Throwable t) {
            System.out.println("agent 检测异常: " + t);
        }

        if (!agentDetected) {
        try {
            com.ryjs.agent.NativePreloader.preload(ReflectionCoremod.class);
        } catch (Throwable e) {
            System.out.println("dll死了，客户端阶段会重试:" + e);
        }

        if (!Boolean.getBoolean("reflection.filterRestarted")) {
            try {
                com.ryjs.agent.DefenseConfigScreen.showBlocking();
            } catch (Throwable e) {
                System.out.println("配置面板弹出失败，按现有配置继续: " + e);
            }
        } else {
            System.out.println("[FullFilter] 过滤重启进程——跳过配置面板（premain 已按配置过滤）");
        }
        try {
            com.ryjs.agent.FullFilterRestarter.maybeRestart();
        } catch (Throwable ignored) {
        }

        com.ryjs.coremod.Agent.AgentUtil.start();
        try {
            com.ryjs.core.JvmtiBridge.isAvailable();
        } catch (Throwable t) {
            System.err.println("[EartyLoading] JvmtiBridge 触发失败: " + t);
        }
        try {
            DefenseAgent.applyDefenses();
        } catch (Throwable e) {
            System.out.println("防御加载失败了，请检查日志");
            throw new RuntimeException(e);
        }

        targetClasses.addAll(ModClassScanner.scan(ReflectionCoremod.class));

        try {
            com.ryjs.agent.CompatWhitelist.warmup();
            com.ryjs.proxyshell.ProxyShellBlocker.preload();
            Class.forName("com.ryjs.coremod.Agent.transformers.AllReturn$ClassInfo");
            Class.forName("com.ryjs.reflection.client.render.TaiChiManualRenderer");

            com.ryjs.asm.SafeClassWriter.setInstrumentation(com.ryjs.coremod.Agent.AgentUtil.getInst());
        } catch (Throwable ignored) {
            System.out.println("卧槽，这几把咋还能加载失败?" + ignored);
        }
        try {
            Class.forName("com.ryjs.agent.MixinBlocker");
            com.ryjs.agent.MixinBlocker.preload();
            com.ryjs.agent.MixinBlocker.install(com.ryjs.coremod.Agent.AgentUtil.getInst());
        } catch (Throwable t) {
            System.err.println("MixinBlocker 注册失败: " + t);
        }
        CoexistenceCleaner.cleanAll(ReflectionCoremod.class, 1000L);
        }
    }
    @Override
    public String name() {
        return "";
    }

    @Override
    public Runnable initialize(String[] arguments) {
        return null;
    }

    @Override
    public void updateFramebufferSize(IntConsumer width, IntConsumer height) {
    }

    @Override
    public long setupMinecraftWindow(IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitor) {
        return 0;
    }

    @Override
    public boolean positionWindow(Optional<Object> monitor, IntConsumer widthSetter, IntConsumer heightSetter, IntConsumer xSetter, IntConsumer ySetter) {
        return false;
    }

    @Override
    public <T> Supplier<T> loadingOverlay(Supplier<?> mc, Supplier<?> ri, Consumer<Optional<Throwable>> ex, boolean fade) {
        return null;
    }

    @Override
    public void updateModuleReads(ModuleLayer layer) {

    }

    @Override
    public void periodicTick() {

    }

    @Override
    public String getGLVersion() {
        return "";
    }
}
