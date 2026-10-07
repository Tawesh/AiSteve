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
 * <p><b>It also builds the intermediate materials itself.</b> Asking for a wooden pickaxe when
 * the bag holds four oak_logs used to fail with "missing oak_planks, stick" - the recipe check
 * only ever looked at the <em>final</em> recipe, so the AI concluded it lacked materials and
 * went off to chop more trees, while the logs it already owned sat in the bag. Now the missing
 * ingredients are crafted from the bag first (log → planks → sticks), which is the same ladder a
 * player climbs and the whole point of "先用手上的东西".</p>
 *
 * <p><b>Parameters:</b> {@code item} (e.g. {@code oak_planks}, {@code crafting_table},
 * {@code stick}, {@code wooden_pickaxe}), {@code quantity} (final items wanted, default 1).</p>
 */
public class CraftItemAction extends BaseAction {

    private static final int MAX_TICKS = 600;              // 30 s
    private static final double TABLE_SEARCH_RADIUS = 24.0;
    private static final double TABLE_REACH = 4.5;

    /**
     * How deep the "build the missing ingredients first" ladder may go.
     *
     * <p>Vanilla needs two rungs for a tool (logs → planks → sticks); three leaves room for a
     * modded chain while still guaranteeing termination.</p>
     */
    private static final int MAX_INTERMEDIATE_DEPTH = 3;

    private Item targetItem;
    private int batchesWanted;
    private int batchesCrafted;
    /** 这次任务要的物品总数（不是批次数）——用于汇报与材料预留。 */
    private int desiredCount;
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
        desiredCount = Math.max(1, task.getIntParameter("quantity", 1));

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
                "背包里已经有 " + alreadyHas + " 个 " + friendlyName(targetItem)
                    + " 了（这次要 " + desiredCount + " 个），不用再合成");
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
                "做 " + friendlyName(targetItem) + " 需要一个工作台：附近没有现成的，"
                + "而我手上这些材料得留着做它本身。先再弄点木头/木板给我吧");
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
                // 【先用手上的东西】最终配方的材料不够 —— 先用背包里现有材料把缺的中间材料做出来
                // （原木 → 木板 → 木棍），而不是立刻放弃、跑去找新的原料。
                if (!craftMissingIngredients(recipe, MAX_INTERMEDIATE_DEPTH)) {
                    break;              // 背包里确实做不出来 → 交给上层如实报告缺什么
                }
                plan = planConsumption(recipe);
                if (plan == null) {
                    break;              // 做了中间材料还是不够（例如还差矿石）
                }
            }
            consume(plan);
            store(resultStack.copy());
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
        int perBatch = resultStack.getCount();
        int produced = craftedNow * perBatch;
        String name = friendlyName(targetItem);

        // 玩家要"1 个"，配方一批却出 4 个（木板/木棍/火把都是这样），看到的数字就会对不上。
        // 与其让这件事看起来像"合成数量失控"，不如把差额原因一起说清楚。
        String yieldNote = produced == desiredCount || perBatch <= 1
            ? ""
            : "（你要 " + desiredCount + " 个，这个配方每批产出 " + perBatch + " 个）";

        SteveMod.LOGGER.info("Steve '{}' crafted {}x {} ({} batch(es), asked for {})",
            steve.getSteveName(), produced, ActionUtils.itemName(targetItem), craftedNow,
            desiredCount);

        if (craftedNow < batchesWanted) {
            // 部分完成：够用的都做了，但没有可再尝试的（材料不够），因此不该触发重规划。
            result = ActionResult.partial(
                "合成了 " + produced + " 个 " + name + "（材料只够这么多）" + yieldNote);
        } else {
            result = ActionResult.success("合成了 " + produced + " 个 " + name + yieldNote);
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

    /** 把产物放进背包；放不下就掉在脚边（与挖矿、拾取的行为一致）。 */
    private void store(ItemStack stack) {
        SteveInventory inventory = steve.getInventory();
        if (inventory == null) {
            steve.spawnAtLocation(stack);
            return;
        }
        int leftover = inventory.addItem(stack);
        if (leftover > 0) {
            steve.spawnAtLocation(stack.copyWithCount(leftover));
        }
    }

    // ------------------------------------------------------------------
    // 用背包里现有的材料补做中间材料
    // ------------------------------------------------------------------

    /**
     * 把目标配方缺的**中间材料**用背包里现有的东西做出来，例如 原木 → 木板 → 木棍。
     *
     * <p>为什么需要它：配方检查原先只看最终配方，于是"背包里有 4 根原木，要一把木镐"会被判成
     * 缺材料（缺 oak_planks / stick），AI 便跑出去砍树 —— 玩家看到的就是"它明明有材料，
     * 却不用自己背包里的东西，反而去重新采集"。这里补上玩家本来就会做的那一步。</p>
     *
     * <p>约束：</p>
     * <ul>
     *   <li>只做配方**真正需要**的材料，不做别的；</li>
     *   <li>深度受限（{@link #MAX_INTERMEDIATE_DEPTH}），保证一定结束；</li>
     *   <li>需要 3×3 的中间配方只在**已有工作台**时才做 —— 不为了中间材料再造一个台子；</li>
     *   <li>没有配方的材料（矿石、小麦…）不动手，交给上层如实报告"缺什么"。</li>
     * </ul>
     *
     * @param depth remaining recursion budget
     * @return true when at least one missing ingredient was actually produced
     */
    private boolean craftMissingIngredients(CraftingRecipe recipe, int depth) {
        if (depth <= 0) {
            return false;
        }

        boolean producedAny = false;

        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty() || canFulfil(ingredient)) {
                continue;                       // 背包里已经有了，不用做
            }

            CraftingRecipe sub = findRecipeForIngredient(ingredient);
            if (sub == null) {
                continue;                       // 采集类材料，做不出来
            }
            if (!sub.canCraftInDimensions(2, 2) && !craftingTableAvailable()) {
                continue;                       // 需要工作台而手边没有，不乱造台子
            }

            Map<Item, Integer> plan = planConsumption(sub);
            if (plan == null) {
                // 子配方自己的材料也不够：再往下一层（例如做木棍需要木板，木板又需要原木）
                if (!craftMissingIngredients(sub, depth - 1)) {
                    continue;
                }
                plan = planConsumption(sub);
                if (plan == null) {
                    continue;
                }
            }

            ItemStack subResult = sub.getResultItem(steve.level().registryAccess());
            if (subResult.isEmpty()) {
                continue;
            }
            consume(plan);
            store(subResult.copy());
            producedAny = true;

            SteveMod.LOGGER.info("[CraftItemAction] 用背包里的材料先做了 {} 个 {}",
                subResult.getCount(), ActionUtils.itemName(subResult.getItem()));
        }

        return producedAny;
    }

    /** 背包里现在有没有能满足这个材料的物品。 */
    private boolean canFulfil(Ingredient ingredient) {
        SteveInventory inventory = steve.getInventory();
        if (inventory == null) {
            return false;
        }
        for (ItemStack stack : inventory.getStacks()) {
            if (!stack.isEmpty() && ingredient.test(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds a recipe that produces something this ingredient accepts.
     *
     * <p>Ingredients are often tags ("any planks"), so the accepted list is walked and the first
     * item with a usable recipe wins - scoring inside {@link #findRecipeFor} keeps that the
     * common vanilla variant rather than bamboo planks.</p>
     */
    private CraftingRecipe findRecipeForIngredient(Ingredient ingredient) {
        for (ItemStack option : ingredient.getItems()) {
            if (option.isEmpty()) {
                continue;
            }
            CraftingRecipe recipe = findRecipeFor(option.getItem());
            if (recipe != null) {
                return recipe;
            }
        }
        return null;
    }

    /** True when a working table is reachable (nearby, carried, or already chosen). */
    private boolean craftingTableAvailable() {
        if (tablePos != null) {
            return true;
        }
        SteveInventory inventory = steve.getInventory();
        if (inventory != null && inventory.count(Items.CRAFTING_TABLE) > 0) {
            return true;
        }
        return findNearbyCraftingTable() != null;
    }

    /** 当前背包的数量快照（物品 → 数量），供干跑模拟使用。 */
    private Map<Item, Integer> bagCounts() {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        SteveInventory inventory = steve.getInventory();
        if (inventory == null) {
            return counts;
        }
        for (ItemStack stack : inventory.getStacks()) {
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * 干跑：假设背包只有这些数量，还能不能做出 {@code item}？
     *
     * <p>只算材料、不管工作台 —— 调用它的地方（造工作台之前）正打算把台子做出来，
     * 所以"能不能用工作台"由调用方负责判断。</p>
     *
     * <p>成功时会把消耗**就地**从 {@code counts} 扣掉，因此调用方必须传副本；
     * 这样多个材料槽位才是真的在争同一批材料，而不是各自看到完整背包
     * （否则"需要 8 个木板、手上只有 4 个"会被误判成做得到）。</p>
     */
    private boolean canMakeFrom(Item item, Map<Item, Integer> counts, int depth) {
        Integer owned = counts.get(item);
        if (owned != null && owned > 0) {
            counts.merge(item, -1, Integer::sum);
            return true;
        }
        if (depth <= 0) {
            return false;
        }
        CraftingRecipe recipe = findRecipeFor(item);
        if (recipe == null) {
            return false;
        }
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            if (!canFulfilFrom(ingredient, counts, depth - 1)) {
                return false;
            }
        }
        return true;
    }

    /** 干跑版的 {@link #canFulfil}：从模拟数量里找，必要时先把材料做出来。 */
    private boolean canFulfilFrom(Ingredient ingredient, Map<Item, Integer> counts, int depth) {
        for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 0 && ingredient.test(new ItemStack(entry.getKey()))) {
                counts.merge(entry.getKey(), -1, Integer::sum);
                return true;
            }
        }
        if (depth <= 0) {
            return false;
        }
        for (ItemStack option : ingredient.getItems()) {
            if (option.isEmpty()) {
                continue;
            }
            // 只有成功才把这次消耗带回上层
            Map<Item, Integer> attempt = new LinkedHashMap<>(counts);
            if (canMakeFrom(option.getItem(), attempt, depth - 1)) {
                counts.clear();
                counts.putAll(attempt);
                return true;
            }
        }
        return false;
    }

    /** 从模拟数量里扣掉一批消耗；不够就返回 false（不修改）。 */
    private static boolean spend(Map<Item, Integer> counts, Map<Item, Integer> cost) {
        for (Map.Entry<Item, Integer> entry : cost.entrySet()) {
            if (counts.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return false;
            }
        }
        for (Map.Entry<Item, Integer> entry : cost.entrySet()) {
            counts.merge(entry.getKey(), -entry.getValue(), Integer::sum);
        }
        return true;
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
     * Makes sure a crafting table is reachable: use a nearby one, otherwise place a carried
     * one, otherwise build one - but only out of materials this job does not need.
     *
     * <p><b>Why the reservation matters.</b> The old version happily converted the Steve's own
     * planks into a table and placed it in the world. For a 3x3 recipe that needs those very
     * planks (a wooden pickaxe needs 3 of the 4 planks that also make the table), the job then
     * ran out of material and reported "missing materials" - <em>after</em> the AI had already
     * announced that it was crafting things and after it had handed items to the player. That is
     * one half of the "it gave me the stuff and still says it can't do it" contradiction, and it
     * is also why the amounts looked out of control: asking for one item silently cost four
     * extra planks.</p>
     *
     * <p>Now the target recipe's own materials are counted first and the table is only built
     * from the genuine surplus. When there is no surplus the craft fails honestly and the
     * reflection layer asks for more wood, exactly like a player would.</p>
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

        // 2) Carry one? Place it. (A table crafted by an earlier step comes back through here.)
        SteveInventory inventory = steve.getInventory();
        if (inventory != null && inventory.removeItem(Items.CRAFTING_TABLE, 1) > 0) {
            return placeTableNearby();
        }

        // 3) Craft one (4 planks). This is a 2x2 recipe, so no table needed for it -
        //    but the wood may already be spoken for by the job we are working on.
        CraftingRecipe tableRecipe = findRecipeFor(Items.CRAFTING_TABLE);
        if (tableRecipe == null) {
            return false;
        }

        // 【先判断再做】模拟一下：如果把这 4 块木板用在台子上，目标还做得出来吗？
        // 只看背包数量、真的动手之前就决定，所以不会出现"先把原木切成木板、然后才发现不该做"。
        if (!targetStillMakeableAfterTable(tableRecipe)) {
            SteveMod.LOGGER.info(
                "[CraftItemAction] 不拿木板去做工作台：剩下的材料就做不出 {} 了",
                ActionUtils.itemName(targetItem));
            return false;
        }

        // 木板不够时允许先用原木做成木板（玩家本来也是这么做的）
        Map<Item, Integer> tablePlan = planConsumption(tableRecipe);
        if (tablePlan == null) {
            if (craftMissingIngredients(tableRecipe, MAX_INTERMEDIATE_DEPTH)) {
                tablePlan = planConsumption(tableRecipe);
            }
        }
        if (tablePlan == null) {
            return false;
        }

        consume(tablePlan);
        store(new ItemStack(Items.CRAFTING_TABLE));
        SteveMod.LOGGER.info("Steve '{}' crafted a crafting table to work at",
            steve.getSteveName());

        return placeTableNearby();
    }

    /**
     * 造完工作台之后，原本要做的东西还做得出来吗？
     *
     * <p>这就替代了原来那版"预留目标配方直接材料"的算法，而且比它更准：干跑会连
     * "用原木先做木板、再用木板做木棍"这种多级链条一起算进去，于是
     * "4 根原木 → 1 个工作台 + 1 把木镐"这种完全正当的做法不会再被误判成材料不够。</p>
     *
     * @param tableRecipe 工作台配方（要扣掉的那 4 块木板）
     */
    private boolean targetStillMakeableAfterTable(CraftingRecipe tableRecipe) {
        Map<Item, Integer> simulated = bagCounts();
        if (simulated.isEmpty()) {
            return false;
        }

        Map<Item, Integer> tableCost = new LinkedHashMap<>();
        for (Ingredient ingredient : tableRecipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            // 用背包里真有的那种木板来扣（配方通常接受所有木板标签）
            Item picked = null;
            for (Map.Entry<Item, Integer> entry : simulated.entrySet()) {
                if (entry.getValue() > 0 && ingredient.test(new ItemStack(entry.getKey()))) {
                    picked = entry.getKey();
                    break;
                }
            }
            if (picked == null) {
                return false;                       // 连台子的木板都不够
            }
            tableCost.merge(picked, 1, Integer::sum);
        }

        if (!spend(simulated, tableCost)) {
            return false;
        }
        // 只看材料：台子正是这一步要弄出来的，所以这里不管 2×2 / 3×3
        return canMakeFrom(targetItem, simulated, MAX_INTERMEDIATE_DEPTH);
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
