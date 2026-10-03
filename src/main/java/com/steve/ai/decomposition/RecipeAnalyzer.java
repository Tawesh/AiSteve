package com.steve.ai.decomposition;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.*;

/**
 * Analyzes Minecraft recipes to determine material requirements.
 * Uses the actual RecipeManager instead of hardcoded rules.
 */
public class RecipeAnalyzer {

    /**
     * Gets the materials required to craft an item.
     * Returns a map of Item -> quantity needed.
     */
    public static Map<Item, Integer> getRequiredMaterials(Item targetItem, SteveEntity steve) {
        CraftingRecipe recipe = findRecipe(targetItem, steve);
        if (recipe == null) {
            return Collections.emptyMap();
        }

        Map<Item, Integer> materials = new HashMap<>();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }

            // Get the first matching item from the ingredient
            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length > 0) {
                Item item = stacks[0].getItem();
                materials.merge(item, 1, Integer::sum);
            }
        }

        return materials;
    }

    /**
     * Checks if a recipe requires a crafting table (3x3 grid).
     */
    public static boolean requiresCraftingTable(Item targetItem, SteveEntity steve) {
        CraftingRecipe recipe = findRecipe(targetItem, steve);
        if (recipe == null) {
            return false;
        }
        return !recipe.canCraftInDimensions(2, 2);
    }

    /**
     * Finds the best crafting recipe for an item.
     * Prefers common/vanilla recipes over modded/alternative ones.
     */
    private static CraftingRecipe findRecipe(Item targetItem, SteveEntity steve) {
        List<CraftingRecipe> recipes = steve.level()
            .getRecipeManager()
            .getAllRecipesFor(RecipeType.CRAFTING);

        com.steve.ai.SteveMod.LOGGER.info("[RecipeAnalyzer] Searching recipes for item: {}", targetItem);
        com.steve.ai.SteveMod.LOGGER.info("[RecipeAnalyzer] Total crafting recipes available: {}", recipes.size());

        CraftingRecipe bestRecipe = null;
        int bestScore = -1;
        int matchingRecipeCount = 0;

        for (CraftingRecipe recipe : recipes) {
            if (recipe.getResultItem(steve.level().registryAccess()).getItem() != targetItem) {
                continue;
            }

            matchingRecipeCount++;

            // Score recipes: prefer common vanilla recipes
            int score = scoreRecipe(recipe, targetItem);

            // Log recipe details
            StringBuilder ingredients = new StringBuilder();
            for (net.minecraft.world.item.crafting.Ingredient ing : recipe.getIngredients()) {
                if (!ing.isEmpty()) {
                    net.minecraft.world.item.ItemStack[] stacks = ing.getItems();
                    if (stacks.length > 0) {
                        ingredients.append(stacks[0].getItem()).append(", ");
                    }
                }
            }

            com.steve.ai.SteveMod.LOGGER.info("[RecipeAnalyzer] Recipe #{}: {} -> {} x{} (score: {})",
                matchingRecipeCount,
                ingredients.toString(),
                targetItem,
                recipe.getResultItem(steve.level().registryAccess()).getCount(),
                score);

            if (score > bestScore) {
                bestScore = score;
                bestRecipe = recipe;
            }
        }

        if (bestRecipe != null) {
            com.steve.ai.SteveMod.LOGGER.info("[RecipeAnalyzer] Selected best recipe with score: {}", bestScore);
        } else {
            com.steve.ai.SteveMod.LOGGER.warn("[RecipeAnalyzer] No recipe found for: {}", targetItem);
        }

        return bestRecipe;
    }

    /**
     * Scores a recipe to prefer vanilla/common recipes over alternatives.
     * Higher score = better recipe.
     */
    private static int scoreRecipe(CraftingRecipe recipe, Item targetItem) {
        int score = 0;

        // Prefer recipes that produce more items
        score += recipe.getResultItem(null).getCount() * 10;

        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;

            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length == 0) continue;

            Item item = stacks[0].getItem();
            String itemName = item.toString();

            // Prefer planks over bamboo for sticks
            if (itemName.contains("planks")) {
                score += 100;
            }
            // Penalize bamboo recipes (less common)
            if (itemName.contains("bamboo")) {
                score -= 50;
            }
            // Prefer common materials
            if (itemName.contains("oak") || itemName.contains("cobblestone")) {
                score += 20;
            }
        }

        return score;
    }

    /**
     * Checks if an item can be crafted (has a recipe).
     */
    public static boolean isCraftable(Item item, SteveEntity steve) {
        return findRecipe(item, steve) != null;
    }

    /**
     * Gets a human-readable description of missing materials.
     */
    public static String describeMissingMaterials(Item targetItem, Map<Item, Integer> available,
                                                   SteveEntity steve) {
        Map<Item, Integer> required = getRequiredMaterials(targetItem, steve);
        if (required.isEmpty()) {
            return "No recipe found";
        }

        List<String> missing = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            Item item = entry.getKey();
            int need = entry.getValue();
            int have = available.getOrDefault(item, 0);

            if (have < need) {
                String itemName = ActionUtils.itemName(item);
                missing.add(itemName + " x" + (need - have));
            }
        }

        if (missing.isEmpty()) {
            return "All materials available";
        }

        return "Missing: " + String.join(", ", missing);
    }
}
