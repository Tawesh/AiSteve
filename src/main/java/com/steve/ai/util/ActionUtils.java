package com.steve.ai.util;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helpers for the action layer.
 *
 * <p>Name resolution lives here <b>once</b>. Previously every action carried its own private
 * {@code parseBlock}, so aliases like {@code wood} or {@code 木头} worked in some actions and
 * silently failed in others - which produced confusing errors such as
 * {@code Invalid block type: meat}. Centralising it keeps behaviour consistent everywhere.</p>
 */
public class ActionUtils {

    /**
     * Friendly names -> real block ids.
     *
     * <p>Covers the loose wording the LLM and players actually use ("砍点木头", "搞点石头").</p>
     */
    private static final Map<String, String> BLOCK_ALIASES = new HashMap<>();

    static {
        // Wood
        BLOCK_ALIASES.put("wood", "oak_log");
        BLOCK_ALIASES.put("log", "oak_log");
        BLOCK_ALIASES.put("tree", "oak_log");
        BLOCK_ALIASES.put("木头", "oak_log");
        BLOCK_ALIASES.put("原木", "oak_log");
        BLOCK_ALIASES.put("planks", "oak_planks");
        BLOCK_ALIASES.put("木板", "oak_planks");
        BLOCK_ALIASES.put("leaves", "oak_leaves");

        // Stone family
        BLOCK_ALIASES.put("stone", "stone");
        BLOCK_ALIASES.put("石头", "stone");
        BLOCK_ALIASES.put("cobblestone", "cobblestone");
        BLOCK_ALIASES.put("cobble", "cobblestone");
        BLOCK_ALIASES.put("圆石", "cobblestone");

        // Soil family
        BLOCK_ALIASES.put("dirt", "dirt");
        BLOCK_ALIASES.put("泥土", "dirt");
        BLOCK_ALIASES.put("sand", "sand");
        BLOCK_ALIASES.put("沙子", "sand");
        BLOCK_ALIASES.put("gravel", "gravel");
        BLOCK_ALIASES.put("grass", "grass_block");

        // Ores (bare resource name -> ore block)
        BLOCK_ALIASES.put("iron", "iron_ore");
        BLOCK_ALIASES.put("铁", "iron_ore");
        BLOCK_ALIASES.put("coal", "coal_ore");
        BLOCK_ALIASES.put("煤", "coal_ore");
        BLOCK_ALIASES.put("gold", "gold_ore");
        BLOCK_ALIASES.put("金", "gold_ore");
        BLOCK_ALIASES.put("diamond", "diamond_ore");
        BLOCK_ALIASES.put("钻石", "diamond_ore");
        BLOCK_ALIASES.put("copper", "copper_ore");
        BLOCK_ALIASES.put("铜", "copper_ore");
        BLOCK_ALIASES.put("redstone", "redstone_ore");
        BLOCK_ALIASES.put("红石", "redstone_ore");
        BLOCK_ALIASES.put("lapis", "lapis_ore");
        BLOCK_ALIASES.put("emerald", "emerald_ore");
        BLOCK_ALIASES.put("绿宝石", "emerald_ore");
    }

    /** Friendly item names -> real item ids. */
    private static final Map<String, String> ITEM_ALIASES = new HashMap<>();

    static {
        ITEM_ALIASES.put("打火石", "flint_and_steel");
        ITEM_ALIASES.put("flintandsteel", "flint_and_steel");
        ITEM_ALIASES.put("flint_steel", "flint_and_steel");
        ITEM_ALIASES.put("lighter", "flint_and_steel");

        ITEM_ALIASES.put("羊排", "cooked_mutton");
        ITEM_ALIASES.put("熟羊排", "cooked_mutton");
        ITEM_ALIASES.put("mutton_chop", "cooked_mutton");
        ITEM_ALIASES.put("生羊排", "mutton");
        ITEM_ALIASES.put("牛肉", "cooked_beef");
        ITEM_ALIASES.put("熟牛肉", "cooked_beef");
        ITEM_ALIASES.put("猪肉", "cooked_porkchop");
        ITEM_ALIASES.put("熟猪排", "cooked_porkchop");
        ITEM_ALIASES.put("鸡肉", "cooked_chicken");
        ITEM_ALIASES.put("熟鸡肉", "cooked_chicken");

        ITEM_ALIASES.put("木头", "oak_log");
        ITEM_ALIASES.put("原木", "oak_log");
        ITEM_ALIASES.put("wood", "oak_log");
        ITEM_ALIASES.put("log", "oak_log");
    }

    /**
     * Find the nearest player to a Steve entity.
     *
     * @param steve The Steve entity
     * @return The nearest player, or null if no players found
     */
    public static Player findNearestPlayer(SteveEntity steve) {
        List<? extends Player> players = steve.level().players();

        if (players.isEmpty()) {
            return null;
        }

        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
                continue;
            }

            double distance = steve.distanceTo(player);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    /**
     * Resolves a block name (with aliases and optional namespace) into a Block.
     *
     * @param blockName e.g. "wood", "木头", "iron", "minecraft:stone"
     * @return the Block, or {@code Blocks.AIR} when the name is unknown
     */
    public static Block parseBlock(String blockName) {
        if (blockName == null || blockName.isBlank()) {
            return Blocks.AIR;
        }

        String name = blockName.trim().toLowerCase().replace(" ", "_");

        String alias = BLOCK_ALIASES.get(name);
        if (alias != null) {
            name = alias;
        }

        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }

        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null) {
            return Blocks.AIR;
        }
        return BuiltInRegistries.BLOCK.get(id);
    }

    /**
     * Resolves an item name (with aliases and optional namespace) into an Item.
     * Supports fuzzy matching: "boat" → "oak_boat", "pickaxe" → "wooden_pickaxe"
     *
     * @param itemName e.g. "flint_and_steel", "打火石", "boat", "pickaxe"
     * @return the Item, or {@code Items.AIR} when the name is unknown
     */
    public static Item parseItem(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return Items.AIR;
        }

        String name = itemName.trim().toLowerCase().replace(" ", "_");

        // Try alias lookup first
        String alias = ITEM_ALIASES.get(name);
        if (alias != null) {
            name = alias;
        }

        // Try exact match
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }

        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id != null) {
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item != Items.AIR) {
                return item;
            }
        }

        // Fuzzy match: find first item containing the search term
        String searchTerm = name.replace("minecraft:", "");
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key != null && key.getPath().contains(searchTerm)) {
                return item;
            }
        }

        return Items.AIR;
    }

    /**
     * Human/LLM readable name for a block, e.g. {@code oak_log} rather than
     * {@code Block{minecraft:oak_log}}.
     */
    public static String blockName(Block block) {
        if (block == null) {
            return "unknown";
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key != null ? key.getPath() : block.toString();
    }

    /** True when the block is a tree trunk (log / wood / nether stem). */
    public static boolean isLog(Block block) {
        String path = blockName(block);
        return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem");
    }

    /**
     * Human/LLM readable name for an item, e.g. {@code cooked_mutton}.
     */
    public static String itemName(Item item) {
        if (item == null) {
            return "unknown";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key != null ? key.getPath() : item.toString();
    }

    /**
     * Picks the tool a real player would use for this block, if the Steve owns one.
     *
     * <p>Axes for wood, shovels for soil, pickaxes for everything else. Returns
     * {@link ItemStack#EMPTY} when nothing suitable is carried - bare hands are legal in
     * vanilla and far better than conjuring a tool out of nowhere.</p>
     */
    public static ItemStack findBestTool(com.steve.ai.entity.SteveInventory inventory, Block block) {
        if (inventory == null || inventory.isEmpty()) {
            return ItemStack.EMPTY;
        }

        List<Item> preference = toolPreferenceFor(block);
        for (Item tool : preference) {
            if (inventory.has(tool)) {
                return new ItemStack(tool);
            }
        }
        return ItemStack.EMPTY;
    }

    /** Ordered tool candidates (best first) for the given block. */
    public static List<Item> toolPreferenceFor(Block block) {
        String name = blockName(block);

        if (isLog(block) || name.contains("planks") || name.contains("_door")
            || name.contains("chest") || name.contains("crafting_table")) {
            return List.of(Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
                Items.STONE_AXE, Items.WOODEN_AXE);
        }

        if (name.matches(".*(dirt|sand|gravel|grass|clay|soul_soil|snow|path).*")) {
            return List.of(Items.NETHERITE_SHOVEL, Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL,
                Items.STONE_SHOVEL, Items.WOODEN_SHOVEL);
        }

        return List.of(Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE,
            Items.STONE_PICKAXE, Items.WOODEN_PICKAXE);
    }
}
