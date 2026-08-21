import cpw.mods.modlauncher.api.ITransformationService;
//冷知识，模块名对于加载顺序有奇效
open module Aa {
    requires java.desktop;
    requires java.instrument;
    requires java.management;
    requires jdk.unsupported;

    requires static com.google.gson;
    requires static cpw.mods.modlauncher;
    requires static cpw.mods.securejarhandler;
    requires static fmlcore;
    requires static fmlloader;
    requires static javafmllanguage;
    requires static net.minecraftforge.eventbus;
    requires static net.minecraftforge.forgespi;
    requires static org.apache.commons.lang3;
    requires static org.apache.logging.log4j;
    requires static org.apache.logging.log4j.core;
    requires static org.joml;
    requires static org.jetbrains.annotations;
    requires static org.lwjgl;
    requires static org.lwjgl.glfw;
    requires static org.lwjgl.opengl;
    requires static org.objectweb.asm;
    requires static org.objectweb.asm.commons;
    requires static org.objectweb.asm.tree;
    requires static org.objectweb.asm.tree.analysis;
    requires static org.slf4j;

    exports com.ryjs.coremod.ImmediateWindowProvider;

    provides net.minecraftforge.fml.loading.ImmediateWindowProvider with com.ryjs.coremod.ImmediateWindowProvider.EartyLoading;
    provides ITransformationService with com.ryjs.coremod.ReflectionCoremod;
}
