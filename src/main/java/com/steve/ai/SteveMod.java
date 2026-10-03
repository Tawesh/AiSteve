package com.steve.ai;

import com.mojang.logging.LogUtils;
import com.steve.ai.command.AsCommands;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.plugin.ActionRegistry;
import com.steve.ai.plugin.PluginManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(SteveMod.MODID)
public class SteveMod {
    /** Forge mod id. Also determines the config file name (aisteve-common.toml). */
    public static final String MODID = "aisteve";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITIES = 
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    public static final RegistryObject<EntityType<SteveEntity>> STEVE_ENTITY = ENTITIES.register("steve",
        () -> EntityType.Builder.of(SteveEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .build("steve"));

    private static SteveManager steveManager;

    public SteveMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ENTITIES.register(modEventBus);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SteveConfig.SPEC);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::entityAttributes);

        MinecraftForge.EVENT_BUS.register(this);

        steveManager = new SteveManager();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Discover and load the built-in action plugins (mine, place, use_item, pickup,
        // give, say, ...) via SPI. Previously this was never invoked, so every action
        // silently fell back to the legacy switch; wiring it up keeps the plugin
        // architecture authoritative.
        event.enqueueWork(() -> {
            PluginManager.getInstance().loadPlugins(
                ActionRegistry.getInstance(),
                new com.steve.ai.di.SimpleServiceContainer());

            // Initialize action capabilities configuration
            com.steve.ai.config.ActionCapabilities.load();
            LOGGER.info("Action capabilities system initialized");

            // Cache the behaviour settings so per-tick code never touches the config spec,
            // and so editing them in the settings GUI takes effect without a restart.
            com.steve.ai.config.RuntimeSettings.refresh();
        });
    }

    private void entityAttributes(EntityAttributeCreationEvent event) {
        event.put(STEVE_ENTITY.get(), SteveEntity.createAttributes().build());
    }

    @SubscribeEvent
    public void onCommandRegister(RegisterCommandsEvent event) {
        AsCommands.register(event.getDispatcher());
    }

    public static SteveManager getSteveManager() {
        return steveManager;
    }
}
