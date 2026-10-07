package com.steve.ai.client;

import com.steve.ai.SteveMod;
import com.steve.ai.client.gui.MainSettingsScreen;
import com.steve.ai.client.gui.SteveInventoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import com.steve.ai.entity.SteveEntity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side setup for entity renderers and other client-only initialization
 */
@Mod.EventBusSubscriber(modid = SteveMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {

    private static final ResourceLocation STEVE_TEXTURE = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    // Key mapping for opening config GUI
    public static final KeyMapping CONFIG_KEY = new KeyMapping(
        "key.aisteve.config",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_K,
        "key.categories.aisteve"
    );

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // Register key handler
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(KeyInputHandler.class);

            // Bind the AI backpack viewer's screen to its menu type.
            //
            // Forge 1.20.1 has no RegisterMenuScreensEvent (that arrived in later versions), so
            // this is done through MenuScreens.register inside client setup - which is also why
            // it must not run on a dedicated server: this whole class is @Dist.CLIENT only.
            MenuScreens.register(SteveMod.STEVE_INVENTORY_MENU.get(), SteveInventoryScreen::new);
        });
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(CONFIG_KEY);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SteveMod.STEVE_ENTITY.get(), context ->
            new HumanoidMobRenderer<SteveEntity, PlayerModel<SteveEntity>>(
                context,
                new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false),
                0.5F
            ) {
                @Override
                public ResourceLocation getTextureLocation(SteveEntity entity) {
                    return STEVE_TEXTURE;
                }
            }
        );
    }
}
