package com.steve.ai.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.steve.ai.SteveMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理AI玩家的动作能力权限
 * 控制哪些动作类型可以被执行
 */
public class ActionCapabilities {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("aisteve-capabilities.json");

    private static Map<String, Boolean> capabilities = new HashMap<>();

    // 默认所有能力都开启
    static {
        capabilities.put("mine", true);
        capabilities.put("build", true);
        capabilities.put("craft", true);
        capabilities.put("combat", true);
        capabilities.put("fish", true);
        capabilities.put("farm", true);
        capabilities.put("explore", true);
        capabilities.put("loot", true);
        capabilities.put("place", true);
        capabilities.put("use_item", true);
        capabilities.put("pickup", true);
        capabilities.put("give", true);
        capabilities.put("say", true);
        capabilities.put("pathfind", true);
        capabilities.put("follow", true);
        capabilities.put("gather", true);

        load();
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            @SuppressWarnings("unchecked")
            Map<String, Boolean> loaded = GSON.fromJson(reader, Map.class);
            if (loaded != null) {
                capabilities.putAll(loaded);
            }
            SteveMod.LOGGER.info("Loaded action capabilities from {}", CONFIG_PATH);
        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to load action capabilities", e);
        }
    }

    public static void save() {
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
            GSON.toJson(capabilities, writer);
            SteveMod.LOGGER.info("Saved action capabilities to {}", CONFIG_PATH);
        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to save action capabilities", e);
        }
    }

    public static boolean isActionAllowed(String actionName) {
        return capabilities.getOrDefault(actionName, true);
    }

    // Getter methods
    public static boolean canMine() { return capabilities.getOrDefault("mine", true); }
    public static boolean canBuild() { return capabilities.getOrDefault("build", true); }
    public static boolean canCraft() { return capabilities.getOrDefault("craft", true); }
    public static boolean canCombat() { return capabilities.getOrDefault("combat", true); }
    public static boolean canFish() { return capabilities.getOrDefault("fish", true); }
    public static boolean canFarm() { return capabilities.getOrDefault("farm", true); }
    public static boolean canExplore() { return capabilities.getOrDefault("explore", true); }
    public static boolean canLoot() { return capabilities.getOrDefault("loot", true); }

    // Setter methods
    public static void setCanMine(boolean value) { capabilities.put("mine", value); }
    public static void setCanBuild(boolean value) { capabilities.put("build", value); }
    public static void setCanCraft(boolean value) { capabilities.put("craft", value); }
    public static void setCanCombat(boolean value) { capabilities.put("combat", value); }
    public static void setCanFish(boolean value) { capabilities.put("fish", value); }
    public static void setCanFarm(boolean value) { capabilities.put("farm", value); }
    public static void setCanExplore(boolean value) { capabilities.put("explore", value); }
    public static void setCanLoot(boolean value) { capabilities.put("loot", value); }
}
