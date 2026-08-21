package com.ryjs.coremod;



import com.ryjs.agent.DefenseAgent;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ReflectionCoremod implements ITransformationService {

    //已经移到ImmediateWindowProvider中

    static {

    }

    @Override
    public @NotNull String name() {
        return "! Reflection Coremod";
    }

    @Override
    public void initialize(IEnvironment environment) {
        // 服务生命周期回调
        System.out.println("[SPI] ReflectionCoremod.initialize 触发");
        com.ryjs.coremod.Agent.AgentUtil.filterTransformationServices();
        com.ryjs.coremod.Agent.AgentUtil.filterLaunchPlugins();
        CoexistenceCleaner.cleanThirdPartyCoremods(ReflectionCoremod.class);
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices)
            throws IncompatibleEnvironmentException {
        System.out.println("onLoad 触发, 同层服务=" + otherServices);
        com.ryjs.coremod.Agent.AgentUtil.filterTransformationServices();
        com.ryjs.coremod.Agent.AgentUtil.filterLaunchPlugins();
        CoexistenceCleaner.cleanThirdPartyCoremods(ReflectionCoremod.class);
    }

    @Override
    public @NotNull List<ITransformationService.Resource> beginScanning(IEnvironment environment) {
        com.ryjs.proxyshell.ProxyShellBlocker.pruneCandidateMods(ReflectionCoremod.class);
        return List.of();
    }

  @Override
    public @NotNull List<ITransformationService.Resource> completeScan(IModuleLayerManager layerManager) {
        com.ryjs.proxyshell.ProxyShellBlocker.pruneLoadingModList(ReflectionCoremod.class);
        return List.of();
    }

    @Override
    public @NotNull List<ITransformer> transformers() {
        return List.of();
    }

}