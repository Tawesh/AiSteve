package com.steve.ai.client;

import com.steve.ai.SteveMod;
import com.steve.ai.client.gui.MainSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles keyboard input for opening the config GUI
 */
@Mod.EventBusSubscriber(modid = SteveMod.MODID, value = Dist.CLIENT)
public class KeyInputHandler {

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.screen == null && ClientSetup.CONFIG_KEY.consumeClick()) {
            minecraft.setScreen(new MainSettingsScreen(null));
        }
    }
}
