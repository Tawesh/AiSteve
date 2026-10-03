package com.steve.ai.decomposition;

import com.steve.ai.action.Task;
import com.steve.ai.context.WorldContext;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.ActionUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates that all preconditions for a task are met before execution.
 */
public class PreconditionValidator {

    /**
     * Validates a list of tasks against the current world context.
     * Returns a list of missing requirements.
     */
    public static List<String> validate(List<Task> tasks, WorldContext worldContext, SteveEntity steve) {
        List<String> missingRequirements = new ArrayList<>();

        for (Task task : tasks) {
            switch (task.getAction()) {
                case "craft" -> validateCraft(task, worldContext, steve, missingRequirements);
                case "place" -> validatePlace(task, worldContext, missingRequirements);
                case "use_item" -> validateUseItem(task, worldContext, missingRequirements);
                case "give" -> validateGive(task, worldContext, missingRequirements);
                case "fish" -> validateFish(task, worldContext, missingRequirements);
            }
        }

        return missingRequirements;
    }

    private static void validateCraft(Task task, WorldContext worldContext, SteveEntity steve,
                                       List<String> missing) {
        String itemName = task.getStringParameter("item", "");
        int quantity = task.getIntParameter("quantity", 1);

        com.steve.ai.SteveMod.LOGGER.info("[PreconditionValidator] Validating craft: item='{}', quantity={}", itemName, quantity);

        Item item = ActionUtils.parseItem(itemName);
        if (item == null) {
            com.steve.ai.SteveMod.LOGGER.error("[PreconditionValidator] Unknown item for crafting: {}", itemName);
            missing.add("Unknown item for crafting: " + itemName);
            return;
        }

        Map<Item, Integer> inventory = worldContext.getSteveState().getInventory();

        // First check: do we already have the target item in inventory?
        int existingCount = inventory.getOrDefault(item, 0);
        if (existingCount >= quantity) {
            com.steve.ai.SteveMod.LOGGER.info("[PreconditionValidator] Already have {} {} in inventory, skip validation",
                existingCount, itemName);
            return; // No need to craft, already have enough
        }

        // Use RecipeAnalyzer to get actual recipe requirements
        Map<Item, Integer> required = RecipeAnalyzer.getRequiredMaterials(item, steve);

        if (required.isEmpty()) {
            com.steve.ai.SteveMod.LOGGER.error("[PreconditionValidator] No crafting recipe for: {}", itemName);
            missing.add("No crafting recipe for: " + itemName);
            return;
        }

        com.steve.ai.SteveMod.LOGGER.info("[PreconditionValidator] Recipe requires {} materials", required.size());

        // Check each required material (only for 1 batch, CraftItemAction handles batch calculation)
        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            Item requiredItem = entry.getKey();
            int requiredCount = entry.getValue(); // Only check for 1 batch
            int availableCount = inventory.getOrDefault(requiredItem, 0);

            String requiredName = ActionUtils.itemName(requiredItem);
            com.steve.ai.SteveMod.LOGGER.info("[PreconditionValidator] Material '{}': need {}, have {}",
                requiredName, requiredCount, availableCount);

            if (availableCount < requiredCount) {
                String missingMsg = String.format("Not enough %s (have %d, need %d)",
                    requiredName, availableCount, requiredCount);
                com.steve.ai.SteveMod.LOGGER.warn("[PreconditionValidator] {}", missingMsg);
                missing.add(missingMsg);
            }
        }

        // Check if crafting table is needed
        if (RecipeAnalyzer.requiresCraftingTable(item, steve)) {
            com.steve.ai.SteveMod.LOGGER.info("[PreconditionValidator] Recipe requires crafting table");
            net.minecraft.core.BlockPos table = worldContext.findNearby(
                net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
            if (table == null && !inventory.containsKey(net.minecraft.world.item.Items.CRAFTING_TABLE)) {
                com.steve.ai.SteveMod.LOGGER.warn("[PreconditionValidator] No crafting table available");
                missing.add("Not enough crafting_table (have 0, need 1)");
            }
        }
    }

    private static void validatePlace(Task task, WorldContext worldContext, List<String> missing) {
        String blockName = task.getStringParameter("block", "");
        Block block = ActionUtils.parseBlock(blockName);

        if (block == null) {
            missing.add("Unknown block: " + blockName);
            return;
        }

        // Check if we have the block in inventory
        Item blockItem = block.asItem();
        if (!worldContext.hasInInventory(blockItem, 1)) {
            missing.add("Missing block in inventory: " + blockName);
        }
    }

    private static void validateUseItem(Task task, WorldContext worldContext, List<String> missing) {
        String itemName = task.getStringParameter("item", "");
        Item item = ActionUtils.parseItem(itemName);

        if (item == null) {
            missing.add("Unknown item: " + itemName);
            return;
        }

        if (!worldContext.hasInInventory(item, 1)) {
            missing.add("Missing item in inventory: " + itemName);
        }
    }

    private static void validateGive(Task task, WorldContext worldContext, List<String> missing) {
        String itemName = task.getStringParameter("item", "");
        if ("all".equals(itemName)) {
            return; // Can always give "all"
        }

        Item item = ActionUtils.parseItem(itemName);
        if (item == null) {
            missing.add("Unknown item: " + itemName);
            return;
        }

        int count = task.getIntParameter("count", 1);
        if (!worldContext.hasInInventory(item, count)) {
            int actualCount = worldContext.getSteveState().getInventory().getOrDefault(item, 0);
            missing.add(String.format("Not enough %s (have %d, need %d)",
                itemName, actualCount, count));
        }
    }

    private static void validateFish(Task task, WorldContext worldContext, List<String> missing) {
        // Check if we have a fishing rod
        Item fishingRod = net.minecraft.world.item.Items.FISHING_ROD;
        if (!worldContext.hasInInventory(fishingRod, 1)) {
            missing.add("Need fishing rod to fish");
        }
    }
}
