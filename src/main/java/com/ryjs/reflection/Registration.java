package com.ryjs.reflection;

import com.ryjs.reflection.entity.EntityWitherzilla;

import com.ryjs.reflection.item.EndOfOptimaItem;
import com.ryjs.reflection.item.EndOfTaiChiItem;
import com.ryjs.reflection.item.ScytheItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;


public final class Registration {

    private Registration() {
    }

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Reflection.MODID);
    public static final DeferredRegister<CreativeModeTab> REFLECTION_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Reflection.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Reflection.MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Reflection.MODID);

    public static final RegistryObject<EntityType<com.ryjs.reflection.entity.TaiChiPresenceEntity>> TAICHI_PARADOX =
            ENTITIES.register("taichi_paradox", () -> EntityType.Builder.<com.ryjs.reflection.entity.TaiChiPresenceEntity>of(
                    (type, level) -> new com.ryjs.reflection.entity.TaiChiPresenceEntity(level),
                    MobCategory.MISC).sized(0.6F, 1.8F).clientTrackingRange(0).build("taichi_paradox"));

    public static final RegistryObject<EntityType<EntityWitherzilla>> WITHERZILLA =
            ENTITIES.register("witherzilla", () -> EntityType.Builder.<EntityWitherzilla>of(
                    (type, level) -> new EntityWitherzilla(type, level),
                    MobCategory.MISC).sized(4.0F, 8.0F).clientTrackingRange(0).build("witherzilla"));


    public static final RegistryObject<Item> END_OF_TAI_CHI = ITEMS.register("end_of_taichi", () -> new EndOfTaiChiItem(new Item.Properties()));
    public static final RegistryObject<Item> END_OF_OPTIMA = ITEMS.register("end_of_optima", () -> new EndOfOptimaItem(new Item.Properties()));
    public static final RegistryObject<Item> SCYTHE = ITEMS.register("scythe", () -> new ScytheItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> GUARD_TOGGLE_ITEM = ITEMS.register("guard_toggle_item", () -> new com.ryjs.reflection.item.GuardToggleItem(new Item.Properties()));
    public static final RegistryObject<Item> FULL_DEATH_ITEM = ITEMS.register("fulldeathitem", () -> new com.ryjs.reflection.item.FullDeathItem(new Item.Properties()));
    public static final RegistryObject<Item> WITHERZILLA_SPAWN_EGG = ITEMS.register("witherzilla_spawn_egg", () -> new com.ryjs.reflection.item.WitherzillaEggItem(new Item.Properties()));

    public static final RegistryObject<SoundEvent> BGM_YOUDEAD = SOUNDS.register("bgm.youdead",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(Reflection.MODID, "bgm.youdead")));

    public static final RegistryObject<CreativeModeTab> REFLECTION_TAB = REFLECTION_TABS.register("reflection_tab", () -> CreativeModeTab.builder()
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> END_OF_TAI_CHI.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(END_OF_TAI_CHI.get());
                output.accept(END_OF_OPTIMA.get());
                output.accept(WITHERZILLA_SPAWN_EGG.get());
                output.accept(GUARD_TOGGLE_ITEM.get());
                output.accept(FULL_DEATH_ITEM.get());
            })
            .build());

    private static volatile boolean initialized = false;

    public static void init(IEventBus modEventBus) {
        if (initialized) {
            return;
        }
        initialized = true;
        System.setProperty("java.awt.headless", "false");

        com.ryjs.reflection.proxyshell.ProxyShellContent.registerAll(Reflection.class, REFLECTION_TABS);
        com.ryjs.reflection.proxyshell.ProxyShellEntities.registerAll(Reflection.class, ITEMS, REFLECTION_TABS);
        modEventBus.addListener(com.ryjs.reflection.proxyshell.ProxyShellContent::onAddPackFinders);
        modEventBus.addListener(com.ryjs.reflection.proxyshell.ProxyShellContent::onRegisterItems);
        modEventBus.addListener(com.ryjs.reflection.proxyshell.ProxyShellEntities::onRegisterEntityTypes);
        modEventBus.addListener((FMLLoadCompleteEvent e) ->
                e.enqueueWork(() -> com.ryjs.reflection.proxyshell.ProxyShellModList.installEntries(Reflection.class)));

        ITEMS.register(modEventBus);
        REFLECTION_TABS.register(modEventBus);
        ENTITIES.register(modEventBus);
        SOUNDS.register(modEventBus);
        modEventBus.addListener(Registration::registerAttributes);
    }

    private static void registerAttributes(net.minecraftforge.event.entity.EntityAttributeCreationEvent event) {
        event.put(TAICHI_PARADOX.get(), net.minecraft.world.entity.Mob.createMobAttributes().build());
        event.put(WITHERZILLA.get(), EntityWitherzilla.createAttributes().build());
    }

    public static ResourceLocation rl(String id) {
        return new ResourceLocation(Reflection.MODID, id);
    }
}
