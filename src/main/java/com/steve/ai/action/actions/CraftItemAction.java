package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveInventory;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crafts items using the real recipe list, materials from the inventory and - when the recipe
 * needs one - a crafting table.
 *
 * <p>Implemented natively rather than faked: the Steve looks the recipe up, checks it actually
 * holds the ingredients, walks to (or builds) a crafting table if the recipe is 3x3, consumes
 * the materials and puts the product in its inventory.</p>
 *
 * <p>This turns the AI from "asks the player for everything" into something that can bootstrap
 * its own tools: chop a tree, craft planks, craft a crafting table, craft a wooden pickaxe,
 * then go mining - the same chain a player follows.</p>
 *
 * <p><b>Parameters:</b> {@code item} (e.g. {@code oak_planks}, {@code crafting_table},
 * {@code stick}, {@code wooden_pickaxe}), {@code quantity} (batches, default 1).</p>
 */
public class CraftItemAction extends BaseAction {

    private static final int MAX_TICKS = 600;              // 30 s
    private static final double TABLE_SEARCH_RADIUS = 24.0;
    private static final double TABLE_REACH = 4.5;

    private Item targetItem;
    private int batchesWanted;
    private int batchesCrafted;

    private int ticksRunning;
    private BlockPos tablePos;          // crafting table we plan to use, if any
    private boolean needsTable;
    private boolean workDone;

    public CraftItemAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String itemName = task.getStringParameter("item");
        int desiredCount = Math.max(1, task.getIntParameter("quantity", 1));

        batchesCrafted = 0;
        ticksRunning = 0;
        workDone = false;
        tablePos = null;
        needsTable = false;

        steve.setFlying(false);

        targetItem = ActionUtils.parseItem(itemName);
        if (targetItem == Items.AIR) {
            result = ActionResult.failure("I don't know the item '" + itemName + "'");
            return;
        }

        CraftingRecipe recipe = findRecipe();
        if (recipe == null) {
            result = ActionResult.failure(
                ActionUtils.itemName(targetItem) + " can't be crafted at a crafting table");
            return;
        }

        // Calculate how many batches needed to get desired item count
        // Each batch produces resultStack.getCount() items
        int itemsPerBatch = recipe.getResultItem(steve.level().registryAccess()).getCount();

        // 只补差额：背包里已经有的算数。
        // 没有这一步时，模型重试"要 1 个木棍"就会每次都再合一批（4 个），
        // 于是玩家看到的就是"我只要 1 个，它却做了一堆"。
        int alreadyHas = steve.getInventory() == null ? 0 : steve.getInventory().count(targetItem);
        int stillNeeded = Math.max(0, desiredCount - alreadyHas);

        if (stillNeeded == 0) {
            result = ActionResult.success(
                "背包里已经有 " + alreadyHas + " 个 " + friendlyName(targetItem) + " 了，不用再合成");
            return;
        }

        batchesWanted = (int) Math.ceil((double) stillNeeded / itemsPerBatch);

        SteveMod.LOGGER.info(
            "[CraftItemAction] 想要 {} 个 {}，已有 {}，还需 {}，每批产出 {}，共 {} 批",
            desiredCount, ActionUtils.itemName(targetItem), alreadyHas, stillNeeded,
            itemsPerBatch, batchesWanted);

        // 3x3 recipes need a table; 2x2 ones can be made in the inventory grid.
        needsTable = !recipe.canCraftInDimensions(2, 2);

        if (needsTable && !ensureCraftingTableAvailable()) {
            result = ActionResult.failure(
                "I need a crafting table for " + ActionUtils.itemName(targetItem)
                    + ", and I don't have one nearby or the planks to make one.");
            return;
        }

        if (needsTable && tablePos != null && steve.blockPosition().distSqr(tablePos)
            > TABLE_REACH * TABLE_REACH) {
            // Walk over first; crafting happens in onTick once we arrive.
            return;
        }

        doWork();
    }

    @Override
    protected void onTick() {
        if (result != null || workDone) {
            return;
        }

        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Took too long trying to craft "
                + ActionUtils.itemName(targetItem));
            return;
        }

        // Still walking to the table?
        if (needsTable && tablePos != null) {
            BlockState state = steve.level().getBlockState(tablePos);
            if (state.getBlock() != Blocks.CRAFTING_TABLE) {
                // Somebody removed it
                tablePos = null;
                if (!ensureCraftingTableAvailable()) {
                    result = ActionResult.failure("The crafting table I was using is gone");
                    return;
                }
            }

            if (steve.blockPosition().distSqr(tablePos) > TABLE_REACH * TABLE_REACH) {
                steve.getNavigation().moveTo(
                    tablePos.getX() + 0.5, tablePos.getY(), tablePos.getZ() + 0.5, 1.1);
                return;
            }
            steve.getNavigation().stop();
        }

        doWork();
    }

    // ------------------------------------------------------------------
    // Core crafting
    // ------------------------------------------------------------------

    /** Crafts as many batches as possible, then reports. */
    private void doWork() {
        CraftingRecipe recipe = findRecipe();
        if (recipe == null) {
            result = ActionResult.failure(
                ActionUtils.itemName(targetItem) + " can't be crafted");
            return;
        }

        ItemStack resultStack = recipe.getResultItem(steve.level().registryAccess());
        if (resultStack.isEmpty()) {
            result = ActionResult.failure("That recipe produces nothing");
            return;
        }

        if (needsTable) {
            steve.getLookControl().setLookAt(
                tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5);
        }
        steve.swing(InteractionHand.MAIN_HAND, true);

        int craftedNow = 0;
        while (craftedNow < batchesWanted) {
            Map<Item, Integer> plan = planConsumption(recipe);
            if (plan == null) {
                break;      // missing materials
            }
            consume(plan);

            SteveInventory inventory = steve.getInventory();
            ItemStack produced = resultStack.copy();
            if (inventory == null) {
                steve.spawnAtLocation(produced);
            } else {
                int leftover = inventory.addItem(produced);
                if (leftover > 0) {
                    steve.spawnAtLocation(produced.copyWithCount(leftover));
                }
            }
            craftedNow++;
            batchesCrafted++;
        }

        workDone = true;

        if (craftedNow == 0) {
            result = ActionResult.failure(missingMaterialsReport(recipe));
            return;
        }

        // itemsPerBatch, not batches - reporting "4x stick" when it actually produced 16 was
        // part of why the AI's own reports did not match what the player saw in their inventory.
        int produced = craftedNow * resultStack.getCount();
        String name = friendlyName(targetItem);

        SteveMod.LOGGER.info("Steve '{}' crafted {}x {} ({} batch(es))",
            steve.getSteveName(), produced, ActionUtils.itemName(targetItem), craftedNow);

        if (craftedNow < batchesWanted) {
            // 部分完成：够用的都做了，但没有可再尝试的（材料不够），因此不该触发重规划。
            result = ActionResult.partial(
                "合成了 " + produced + " 个 " + name + "（材料只够这么多）");
        } else {
            result = ActionResult.success("合成了 " + produced + " 个 " + name);
        }
    }

    /** 尽量取本地化名字，失败时退回注册表 id。 */
    private static String friendlyName(Item item) {
        try {
            return new ItemStack(item).getHoverName().getString();
        } catch (Exception e) {
            return ActionUtils.itemName(item);
        }
    }

    /**
     * Works out which inventory items satisfy the recipe.
     *
     * @return item -> amount to consume, or {@code null} when materials are missing
     */
    private Map<Item, Integer> planConsumption(CraftingRecipe recipe) {
        SteveInventory inventory = steve.getInventory();
        if (inventory == null) {
            return null;
        }

        List<ItemStack> stacks = inventory.getStacks();
        // Remaining count per slot, so two ingredients can share a stack type correctly.
        int[] available = new int[stacks.size()];
        for (int i = 0; i < stacks.size(); i++) {
            available[i] = stacks.get(i).getCount();
        }

        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        Map<Item, Integer> plan = new LinkedHashMap<>();

        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;   // empty slot in a shaped recipe
            }

            boolean satisfied = false;
            for (int i = 0; i < stacks.size(); i++) {
                if (available[i] <= 0) {
                    continue;
                }
                ItemStack candidate = stacks.get(i);
                if (ingredient.test(candidate)) {
                    available[i]--;
                    plan.merge(candidate.getItem(), 1, Integer::sum);
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                return null;
            }
        }
        return plan;
    }

    private void consume(Map<Item, Integer> plan) {
        SteveInventory inventory = steve.getInventory();
        if (inventory == null) {
            return;
        }
        for (Map.Entry<Item, Integer> entry : plan.entrySet()) {
            inventory.removeItem(entry.getKey(), entry.getValue());
        }
    }

    /** Builds a human/LLM readable list of what is missing. */
    private String missingMaterialsReport(CraftingRecipe recipe) {
        List<String> missing = new ArrayList<>();
        SteveInventory inventory = steve.getInventory();

        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean has = false;
            if (inventory != null) {
                for (ItemStack stack : inventory.getStacks()) {
                    if (ingredient.test(stack)) {
                        has = true;
                        break;
                    }
                }
            }
            if (!has) {
                ItemStack[] options = ingredient.getItems();
                if (options.length > 0) {
                    missing.add(ActionUtils.itemName(options[0].getItem()));
                }
            }
        }

        if (missing.isEmpty()) {
            return "I don't have the materials to craft " + ActionUtils.itemName(targetItem);
        }
        return "I'm missing materials for " + ActionUtils.itemName(targetItem) + ": "
            + String.join(", ", missing) + ". Please give me some or let me gather them.";
    }

    /**
     * Makes sure a crafting table is reachable: use a nearby one, otherwise craft a table from
     * planks and place it.
     *
     * @return true when a usable table is (or will be) available in {@link #tablePos}
     */
    private boolean ensureCraftingTableAvailable() {
        // 1) Any table already nearby?
        BlockPos existing = findNearbyCraftingTable();
        if (existing != null) {
            tablePos = existing;
            return true;
        }

        // 2) Carry one? Place it.
        SteveInventory inventory = steve.getInventory();
        if (inventory != null && inventory.removeItem(Items.CRAFTING_TABLE, 1) > 0) {
            return placeTableNearby();
        }

        // 3) Craft one (4 planks). This is a 2x2 recipe, so no table needed for it.
        CraftingRecipe tableRecipe = findRecipeFor(Items.CRAFTING_TABLE);
        if (tableRecipe == null) {
            return false;
        }
        Map<Item, Integer> plan = planConsumption(tableRecipe);
        if (plan == null) {
            return false;
        }
        consume(plan);
        if (steve.getInventory() != null) {
            steve.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE));
        }
        SteveMod.LOGGER.info("Steve '{}' crafted a crafting table to work at",
            steve.getSteveName());

        return placeTableNearby();
    }

    /** Places a crafting table on solid ground next to the Steve. */
    private boolean placeTableNearby() {
        BlockPos base = steve.blockPosition();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos candidate = base.offset(dx, 0, dz);
                if (!steve.level().getBlockState(candidate).isAir()) {
                    continue;
                }
                BlockPos below = candidate.below();
                if (!steve.level().getBlockState(below).isSolid()) {
                    continue;
                }
                steve.level().setBlock(candidate, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                tablePos = candidate;
                steve.getLookControl().setLookAt(
                    candidate.getX() + 0.5, candidate.getY() + 0.5, candidate.getZ() + 0.5);
                SteveMod.LOGGER.info("Steve '{}' placed a crafting table at {}",
                    steve.getSteveName(), candidate);
                return true;
            }
        }
        return false;
    }

    private BlockPos findNearbyCraftingTable() {
        BlockPos center = steve.blockPosition();
        int r = (int) TABLE_SEARCH_RADIUS;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        int minY = Math.max(steve.level().getMinBuildHeight(), center.getY() - 6);
        int maxY = Math.min(steve.level().getMaxBuildHeight() - 1, center.getY() + 6);

        for (int y = minY; y <= maxY; y++) {
            int dy = y - center.getY();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d2 = (double) dx * dx + (double) dz * dz + (double) dy * dy;
                    if (d2 > (double) r * r || d2 >= bestDist) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    if (steve.level().getBlockState(pos).getBlock() == Blocks.CRAFTING_TABLE) {
                        best = pos;
                        bestDist = d2;
                    }
                }
            }
        }
        return best;
    }

    private CraftingRecipe findRecipe() {
        return findRecipeFor(targetItem);
    }

    private CraftingRecipe findRecipeFor(Item item) {
        // Use RecipeAnalyzer to select the best recipe (prefers vanilla/common recipes)
        List<CraftingRecipe> candidates = new ArrayList<>();
        for (Recipe<?> recipe : steve.level().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (recipe instanceof CraftingRecipe crafting
                && crafting.getResultItem(steve.level().registryAccess()).is(item)) {
                candidates.add(crafting);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Score recipes to prefer vanilla/common ones
        CraftingRecipe bestRecipe = null;
        int bestScore = -1;

        for (CraftingRecipe recipe : candidates) {
            int score = scoreRecipe(recipe, item);
            SteveMod.LOGGER.info("[CraftItemAction] Recipe for {}: score={}",
                ActionUtils.itemName(item), score);

            if (score > bestScore) {
                bestScore = score;
                bestRecipe = recipe;
            }
        }

        if (bestRecipe != null) {
            SteveMod.LOGGER.info("[CraftItemAction] Selected recipe for {} with score: {}",
                ActionUtils.itemName(item), bestScore);
        }

        return bestRecipe;
    }

    /**
     * Scores a recipe to prefer vanilla/common ingredients.
     * Higher score = better recipe to use.
     */
    private int scoreRecipe(CraftingRecipe recipe, Item targetItem) {
        int score = 0;

        // Prefer recipes with higher output count
        score += recipe.getResultItem(steve.level().registryAccess()).getCount() * 10;

        // Check ingredients and adjust score
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;

            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length == 0) continue;

            Item item = stacks[0].getItem();
            String itemName = item.toString();

            // Strongly prefer planks for stick recipes
            if (itemName.contains("planks")) {
                score += 100;
            }

            // Penalize bamboo recipes
            if (itemName.contains("bamboo")) {
                score -= 50;
            }

            // Prefer common vanilla materials
            if (itemName.contains("oak") || itemName.contains("cobblestone")) {
                score += 20;
            }
        }

        return score;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Craft " + ActionUtils.itemName(targetItem)
            + " (" + batchesCrafted + "/" + batchesWanted + ")";
    }

    /** Readable item id, kept local so logging never throws on a null item. */
    private static String describe(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key != null ? key.getPath() : String.valueOf(item);
    }
}
