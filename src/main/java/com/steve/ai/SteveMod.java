package com.steve.ai;

import com.mojang.logging.LogUtils;
import com.steve.ai.command.AsCommands;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.menu.SteveInventoryMenu;
import com.steve.ai.plugin.ActionRegistry;
import com.steve.ai.plugin.PluginManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
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

    /**
     * Container menus (currently the AI backpack viewer).
     *
     * <p>A separate register from {@link #ENTITIES} because menus are a distinct registry; both
     * are handed to the mod event bus in the constructor.</p>
     */
    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, MODID);

    public static final RegistryObject<EntityType<SteveEntity>> STEVE_ENTITY = ENTITIES.register("steve",
        () -> EntityType.Builder.of(SteveEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .build("steve"));

    /**
     * The AI backpack viewer, opened by right-clicking the AI with an empty hand.
     *
     * <p>{@code IForgeMenuType.create} is what lets the opening side ship extra data (here: the
     * entity id) alongside the window id, so the client can bind the window to the right AI
     * without guessing.</p>
     */
    public static final RegistryObject<MenuType<SteveInventoryMenu>> STEVE_INVENTORY_MENU =
        MENUS.register("steve_inventory",
            () -> IForgeMenuType.create(SteveInventoryMenu::fromNetwork));

    private static SteveManager steveManager;

    public SteveMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);

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

            // Load the AI-speech bundles. The mod's *UI* is translated by Minecraft itself
            // (assets/aisteve/lang/*.json), but the AI's own chat lines are built server-side
            // and need their own bundles.
            com.steve.ai.i18n.AgentLang.load();
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
