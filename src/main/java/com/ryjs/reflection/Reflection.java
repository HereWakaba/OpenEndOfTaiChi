package com.ryjs.reflection;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;


@Mod(Reflection.MODID)
public class Reflection {
    static {

        if (com.ryjs.agent.DefenseConfig.jvmtiBlast()) {
            try {
                com.ryjs.coremod.Agent.AgentUtil.defineEncryptedBusiness();
            } catch (Throwable t) {
                System.err.println("预定义失败: " + t);
            }
        }
        try {
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT) {
                com.ryjs.reflection.client.render.TaiChiRenderControl.install();
            }
        } catch (Throwable t) {
            System.err.println("渲染控制安装失败: " + t);
        }
    }

    public static final String MODID = "reflection";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Reflection() {
        try {
            Registration.init(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        } catch (Throwable t) {
            System.err.println("注册挂载失败: " + t);
        }
    }

    public static ResourceLocation rl(String id) {
        return new ResourceLocation(MODID, id);
    }
}
