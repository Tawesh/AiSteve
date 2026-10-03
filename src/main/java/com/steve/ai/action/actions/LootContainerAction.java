package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveInventory;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks to a nearby container (chest, barrel, hopper, furnace, shulker box...) and takes
 * items out of it - the "open the village chest and grab some food" behaviour.
 *
 * <p>This is what makes the AI able to satisfy requests the world already provides, instead
 * of only knowing how to hunt. Asked for food in a village it can now loot bread, carrots
 * and potatoes; asked for building material it can raid a chest rather than mining from
 * scratch.</p>
 *
 * <p><b>Parameters:</b></p>
 * <ul>
 *   <li>{@code item} (optional) - only take this item; omit to take everything useful</li>
 *   <li>{@code limit} (optional) - stop after this many items (default: no limit)</li>
 *   <li>{@code x}/{@code y}/{@code z} (optional) - a specific container to open</li>
 * </ul>
 */
public class LootContainerAction extends BaseAction {

    private static final double SEARCH_RADIUS = 28.0;
    private static final double REACH = 4.5;
    private static final int MAX_TICKS = 2400;              // 2 minutes
    private static final int NO_PROGRESS_LIMIT = 600;       // 30 s without progress
    /** How many items to consider when summarising what was taken. */
    private static final int MAX_REPORTED_STACKS = 6;

    private String itemFilter;
    private int limit;
    private BlockPos targetContainer;

    private int ticksRunning;
    private int ticksSinceProgress;
    private int lootedCount;
    private final List<String> taken = new ArrayList<>();

    public LootContainerAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        itemFilter = task.getStringParameter("item", null);
        limit = task.getIntParameter("limit", -1);
        ticksRunning = 0;
        ticksSinceProgress = 0;
        lootedCount = 0;
        taken.clear();

        steve.setFlying(false);

        // Explicit coordinates?
        Object xObj = task.getParameter("x");
        Object yObj = task.getParameter("y");
        Object zObj = task.getParameter("z");
        if (xObj instanceof Number x && yObj instanceof Number y && zObj instanceof Number z) {
            BlockPos explicit = new BlockPos(x.intValue(), y.intValue(), z.intValue());
            if (isLootable(explicit)) {
                targetContainer = explicit;
            } else {
                result = ActionResult.failure(
                    "There is no container at " + explicit.toShortString());
                return;
            }
        } else if (!findNearestContainer()) {
            result = ActionResult.failure(
                "No chest or other container within " + (int) SEARCH_RADIUS
                    + " blocks. I need to explore to find one.");
            return;
        }

        SteveMod.LOGGER.info("Steve '{}' heading to container at {}",
            steve.getSteveName(), targetContainer);
    }

    @Override
    protected void onTick() {
        if (result != null) {
            return;
        }

        ticksRunning++;
        ticksSinceProgress++;

        if (ticksRunning > MAX_TICKS) {
            finish("Ran out of time getting to the container");
            return;
        }
        if (ticksSinceProgress > NO_PROGRESS_LIMIT) {
            finish("Could not reach the container");
            return;
        }

        if (targetContainer == null || !isLootable(targetContainer)) {
            // Container disappeared -> look for another one
            if (!findNearestContainer()) {
                finish(lootedCount > 0 ? null : "No container left to open");
            }
            return;
        }

        double distance = Math.sqrt(steve.blockPosition().distSqr(targetContainer));
        if (distance > REACH) {
            steve.getNavigation().moveTo(
                targetContainer.getX() + 0.5, targetContainer.getY(), targetContainer.getZ() + 0.5, 1.1);
            return;
        }

        openAndLoot(targetContainer);
    }

    /** Empties (the requested part of) the container into the Steve's inventory. */
    private void openAndLoot(BlockPos pos) {
        steve.getNavigation().stop();
        steve.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        BlockEntity blockEntity = steve.level().getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            targetContainer = null;
            return;
        }

        // A little feedback so it reads as "the AI opened a chest"
        steve.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        steve.level().playSound(null, pos, SoundEvents.CHEST_OPEN,
            SoundSource.BLOCKS, 0.7f, 1.0f);

        // "food" / "all" / "any" mean "just take everything" - the AI rarely knows the exact
        // item id of whatever is inside a village chest.
        Item wanted = null;
        if (itemFilter != null && !itemFilter.isBlank()) {
            String lower = itemFilter.trim().toLowerCase();
            boolean takeEverything = lower.equals("food") || lower.equals("all")
                || lower.equals("any") || lower.equals("食物") || lower.isEmpty();
            if (!takeEverything) {
                wanted = ActionUtils.parseItem(itemFilter);
                if (wanted == net.minecraft.world.item.Items.AIR) {
                    wanted = null;   // unknown id -> fall back to taking everything
                }
            }
        }
        SteveInventory inventory = steve.getInventory();

        int takenThisVisit = 0;

        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (limit > 0 && lootedCount >= limit) {
                break;
            }

            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (wanted != null && wanted != net.minecraft.world.item.Items.AIR && !stack.is(wanted)) {
                continue;
            }

            ItemStack copy = stack.copy();
            if (limit > 0) {
                copy.setCount(Math.min(copy.getCount(), limit - lootedCount));
            }

            int leftover;
            if (inventory == null) {
                leftover = copy.getCount();
                steve.spawnAtLocation(copy);
            } else {
                leftover = inventory.addItem(copy);
            }

            int moved = copy.getCount() - leftover;
            if (moved <= 0) {
                continue;   // inventory full
            }

            container.removeItem(slot, moved);
            lootedCount += moved;
            takenThisVisit += moved;
            recordTaken(copy.getItem(), moved);
        }

        if (takenThisVisit > 0) {
            ticksSinceProgress = 0;
            SteveMod.LOGGER.info("Steve '{}' looted {} item(s) from {} (total {})",
                steve.getSteveName(), takenThisVisit, pos, lootedCount);
        }

        // Either the chest is empty for our purposes, or we have enough
        boolean done = takenThisVisit == 0
            || (limit > 0 && lootedCount >= limit)
            || !containerHasMore(container, wanted);

        if (done) {
            finish(lootedCount > 0 ? null
                : "The container had nothing I needed"
                    + (itemFilter != null ? " (" + itemFilter + ")" : ""));
        }
    }

    private boolean containerHasMore(Container container, Item wanted) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (wanted == null || wanted == net.minecraft.world.item.Items.AIR || stack.is(wanted)) {
                return true;
            }
        }
        return false;
    }

    private void recordTaken(Item item, int count) {
        if (taken.size() >= MAX_REPORTED_STACKS) {
            return;
        }
        taken.add(count + "x " + ActionUtils.itemName(item));
    }

    /**
     * Finds the closest container within range.
     *
     * @return true when one was found
     */
    private boolean findNearestContainer() {
        BlockPos center = steve.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        int r = (int) SEARCH_RADIUS;
        int minY = Math.max(steve.level().getMinBuildHeight(), center.getY() - 10);
        int maxY = Math.min(steve.level().getMaxBuildHeight() - 1, center.getY() + 10);

        for (int y = minY; y <= maxY; y++) {
            int dy = y - center.getY();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d2 = (double) dx * dx + (double) dz * dz + (double) dy * dy;
                    if (d2 > (double) r * r || d2 >= bestDist) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    if (isLootable(pos)) {
                        best = pos;
                        bestDist = d2;
                    }
                }
            }
        }

        targetContainer = best;
        return best != null;
    }

    /**
     * Cheap check for "is this a container worth opening".
     *
     * <p>Filters by block class first so the expensive {@code getBlockEntity} call only runs
     * for plausible candidates.</p>
     */
    private boolean isLootable(BlockPos pos) {
        BlockState state = steve.level().getBlockState(pos);
        Block block = state.getBlock();

        boolean candidate = block instanceof ChestBlock
            || block instanceof BarrelBlock
            || block instanceof ShulkerBoxBlock
            || block instanceof HopperBlock
            || block instanceof DispenserBlock
            || block instanceof AbstractFurnaceBlock;

        if (!candidate) {
            return false;
        }
        return steve.level().getBlockEntity(pos) instanceof Container;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Loot container (" + lootedCount + " items)";
    }

    private void finish(String problem) {
        steve.getNavigation().stop();

        if (problem == null) {
            String summary = taken.isEmpty() ? "" : " (" + String.join(", ", taken) + ")";
            result = ActionResult.success("Looted " + lootedCount + " item(s)" + summary);
        } else if (lootedCount > 0) {
            result = ActionResult.success("Looted " + lootedCount + " item(s), then stopped");
        } else {
            result = ActionResult.failure(problem);
        }
    }
}
