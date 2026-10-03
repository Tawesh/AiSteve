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
 *   <li>{@code item} - item id to hand over, e.g. {@code cooked_mutton}. Use {@code all}
 *       to give everything the Steve is carrying.</li>
 *   <li>{@code count} (optional) - how many to hand over (default: everything of that item)</li>
 *   <li>{@code player} (optional) - target player name (default: nearest player)</li>
 * </ul>
 */
public class GiveItemAction extends BaseAction {

    private static final double GIVE_RANGE = 4.0;
    private static final int MAX_TICKS = 900; // 45 seconds

    private String itemName;
    private int requestedCount;
    private int ticksRunning;

    public GiveItemAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        itemName = task.getStringParameter("item", "all");
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
            result = ActionResult.failure("I have nothing to give");
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
        if (!"all".equalsIgnoreCase(itemName)) {
            Item parsed = ActionUtils.parseItem(itemName);
            if (parsed == Items.AIR) {
                result = ActionResult.giveUp("我不认识物品 '" + itemName + "'");
                return;
            }
        }

        int given = giveTo(player);
        if (given > 0) {
            result = ActionResult.success("给了玩家 " + given + " 个 " + displayName());
        } else {
            result = ActionResult.failure("我身上没有 " + displayName() + "，给不了玩家");
        }
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
     * @return how many items were handed over
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

            ItemStack toGive = stack.copy();
            if (requestedCount > 0) {
                toGive.setCount(Math.min(toGive.getCount(), requestedCount - given));
            }

            boolean stored = player.getInventory().add(toGive);
            if (!stored) {
                // Player inventory full -> drop it at the player's feet
                player.drop(toGive, false);
            }
            steve.getInventory().removeItem(toGive.getItem(), toGive.getCount());
            given += toGive.getCount();

            SteveMod.LOGGER.info("Steve '{}' gave {}x {} to {}",
                steve.getSteveName(), toGive.getCount(), toGive.getItem(), player.getName().getString());

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
