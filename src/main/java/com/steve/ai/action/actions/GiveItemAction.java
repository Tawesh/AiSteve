package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.ActionUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Hands items from the Steve's inventory to the nearest player.
 *
 * <p>Makes the AI feel like a real teammate: you ask for a mutton chop, it goes and
 * gets one, then walks back and gives it to you.</p>
 *
 * <p><b>Parameters:</b></p>
 * <ul>
 *   <li>{@code item} - item id to hand over, e.g. {@code cooked_mutton}. <b>Required</b>:
 *       use the literal {@code all} (or {@code count <= 0}) to hand over the whole backpack.
 *       A missing name is a mistake, not a licence to dump everything.</li>
 *   <li>{@code count} (optional) - how many to hand over (default 1, never "everything")</li>
 *   <li>{@code player} (optional) - target player name (default: nearest player)</li>
 * </ul>
 */
public class GiveItemAction extends BaseAction {

    private static final double GIVE_RANGE = 4.0;
    private static final int MAX_TICKS = 900; // 45 seconds
    /** How long "I already gave you that" stays valid (2 minutes). */
    private static final long RECENT_DELIVERY_WINDOW = 20L * 120;

    private String itemName;
    private int requestedCount;
    private int ticksRunning;

    public GiveItemAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        // 没有物品名就不再默认 "all"。
        // 老代码写的是 getStringParameter("item", "all")，于是模型漏掉 item 参数时
        // "给我" 就变成了"把身上所有东西都塞给你" —— 玩家反馈的"它把一堆多余的东西
        // 也给了我"正是这条路径。要全交必须明说 all。
        itemName = task.getStringParameter("item", "");
        if (itemName == null || itemName.isBlank()) {
            result = ActionResult.giveUp("没有告诉我要给你什么，我不乱塞东西：你想要哪个？");
            return;
        }

        // Support both "count" and "quantity" parameters for LLM compatibility
        // Default to 1 if not specified (user usually wants 1 item, not all)
        requestedCount = task.getIntParameter("count", 1);
        if (requestedCount == 1 && task.getParameters().containsKey("quantity")) {
            requestedCount = task.getIntParameter("quantity", 1);
        }

        // Only give all items if explicitly requested with "all" or count <= 0
        if ("all".equalsIgnoreCase(itemName) || requestedCount <= 0) {
            requestedCount = -1;  // -1 means give all
        }

        ticksRunning = 0;
        steve.setFlying(false);

        if (steve.getInventory().isEmpty()) {
            // 背包是空的：如果刚刚交过同一个东西，那这活儿其实已经干完了；
            // 否则才是真的没东西可给。
            Item wanted = "all".equalsIgnoreCase(itemName) ? null : ActionUtils.parseItem(itemName);
            if (wanted != null && wanted != Items.AIR
                && steve.wasGivenToPlayerRecently(wanted, RECENT_DELIVERY_WINDOW)) {
                result = ActionResult.partial(
                    "刚才已经把 " + displayName() + " 给你了，我身上没有多的了");
            } else {
                result = ActionResult.failure("I have nothing to give");
            }
        }
    }

    @Override
    protected void onTick() {
        if (result != null) {
            return;
        }

        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Could not reach the player to hand over items");
            return;
        }

        Player player = ActionUtils.findNearestPlayer(steve);
        if (player == null) {
            result = ActionResult.failure("No player nearby to give items to");
            return;
        }

        if (steve.distanceTo(player) > GIVE_RANGE) {
            steve.getNavigation().moveTo(player, 1.2);
            return;
        }

        steve.getNavigation().stop();
        steve.getLookControl().setLookAt(player);

        // 目标物品先解析清楚：认不出来就如实说，不要"静默地什么都没给"。
        Item parsed = null;
        if (!"all".equalsIgnoreCase(itemName)) {
            parsed = ActionUtils.parseItem(itemName);
            if (parsed == Items.AIR) {
                result = ActionResult.giveUp("我不认识物品 '" + itemName + "'");
                return;
            }
        }

        int given = giveTo(player);
        if (given > 0) {
            if (parsed != null) {
                steve.recordGivingToPlayer(parsed);
            }
            result = ActionResult.success("给了玩家 " + given + " 个 " + displayName());
            return;
        }

        // 背包里没有这个物品了。有两种完全不同的情况，不能都当成失败：
        //   (a) 我根本没有过 —— 这是真失败，应该让上层重新想办法；
        //   (b) 我刚才已经把东西交给你了 —— 目标其实已经完成，
        //       再报一次"做不了"就是玩家看到的自相矛盾。
        if (parsed != null && steve.wasGivenToPlayerRecently(parsed, RECENT_DELIVERY_WINDOW)) {
            result = ActionResult.partial(
                "刚才已经把 " + displayName() + " 给你了，我身上现在没有了");
            return;
        }

        result = ActionResult.failure("我身上没有 " + displayName() + "，给不了玩家");
    }

    /** 给玩家看的名字。 */
    private String displayName() {
        if ("all".equalsIgnoreCase(itemName)) {
            return "东西";
        }
        try {
            return new ItemStack(ActionUtils.parseItem(itemName)).getHoverName().getString();
        } catch (Exception e) {
            return itemName;
        }
    }

    /**
     * Transfers the requested items into the player's inventory.
     *
     * <p><b>The count has to be captured before handing the stack over.</b>
     * {@code Player#Inventory#add(ItemStack)} does not copy what it is given - it consumes the
     * stack in place, leaving it holding only the part that did <em>not</em> fit. Reading the
     * count afterwards therefore returned 0 on the normal path, which produced three symptoms at
     * once:</p>
     * <ul>
     *   <li>the player received the items but {@code removeItem(item, 0)} took nothing out of the
     *       AI's bag, so the items were duplicated;</li>
     *   <li>{@code given} stayed 0, so the "stop once asked for N" condition never fired and the
     *       loop kept handing over <em>every</em> matching stack;</li>
     *   <li>the caller saw {@code given == 0} and reported failure, which triggered reflection, a
     *       replan and another round of giving - more duplication.</li>
     * </ul>
     * The amount is now fixed up front and the tally uses what {@link SteveInventory#removeItem}
     * actually removed, so the AI's own count can never disagree with what the player received.</p>
     *
     * @return how many items actually left the AI's bag
     */
    private int giveTo(Player player) {
        int given = 0;
        Item wanted = "all".equalsIgnoreCase(itemName) ? null : ActionUtils.parseItem(itemName);

        if (wanted != null && wanted != net.minecraft.world.item.Items.AIR
            && !steve.getInventory().has(wanted)) {
            return 0;
        }

        // Copy the list because we mutate the inventory while iterating
        for (ItemStack stack : new java.util.ArrayList<>(steve.getInventory().getStacks())) {
            if (wanted != null && !stack.is(wanted)) {
                continue;
            }
            if (requestedCount > 0 && given >= requestedCount) {
                break;
            }

            Item item = stack.getItem();
            int amount = stack.getCount();
            if (requestedCount > 0) {
                amount = Math.min(amount, requestedCount - given);
            }
            if (amount <= 0) {
                break;
            }

            // Give away a copy, because `add` destroys the stack it is handed.
            ItemStack toGive = stack.copyWithCount(amount);
            boolean stored = player.getInventory().add(toGive);
            if (!stored) {
                // Whatever did not fit lands at the player's feet; it still left the AI's bag.
                player.drop(toGive, false);
            }

            // Take exactly what we meant to hand over - never what `add` happened to leave behind.
            int removed = steve.getInventory().removeItem(item, amount);
            given += removed;

            SteveMod.LOGGER.info("Steve '{}' gave {}x {} to {} (asked for {}{})",
                steve.getSteveName(), removed, item, player.getName().getString(),
                requestedCount < 0 ? "everything" : String.valueOf(requestedCount),
                removed < amount ? ", bag held fewer" : "");

            if (wanted == null && steve.getInventory().isEmpty()) {
                break;
            }
        }

        return given;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Give " + itemName + " to player";
    }
}
