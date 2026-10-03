package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveInventory;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places one block from the Steve's inventory at a given position.
 *
 * <p>No more conjuring blocks out of thin air: the previous version called
 * {@code level.setBlock(...)} directly without touching the inventory, so material
 * appeared from nothing. It now requires the item to actually be carried and consumes it,
 * matching how {@link UseItemAction} already behaved.</p>
 *
 * <p><b>Parameters:</b> {@code block}, {@code x}, {@code y}, {@code z}.</p>
 */
public class PlaceBlockAction extends BaseAction {

    private static final int MAX_TICKS = 400;   // 20 s is plenty to walk a few blocks
    private static final double REACH = 4.5;

    private Block blockToPlace;
    private Item requiredItem;
    private BlockPos targetPos;
    private int ticksRunning;

    public PlaceBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("block");
        targetPos = new BlockPos(
            task.getIntParameter("x", 0),
            task.getIntParameter("y", 0),
            task.getIntParameter("z", 0));
        ticksRunning = 0;

        blockToPlace = ActionUtils.parseBlock(blockName);
        if (blockToPlace == null || blockToPlace == Blocks.AIR) {
            result = ActionResult.failure("I don't know the block '" + blockName + "'");
            return;
        }

        requiredItem = blockToPlace.asItem();
        SteveInventory inventory = steve.getInventory();

        if (requiredItem == net.minecraft.world.item.Items.AIR) {
            result = ActionResult.failure(
                ActionUtils.blockName(blockToPlace) + " can't be carried as an item");
            return;
        }

        if (inventory == null || !inventory.has(requiredItem)) {
            // Actionable message: the planner can ask the player or go gather some.
            result = ActionResult.failure(
                "I have no " + ActionUtils.blockName(blockToPlace)
                    + " to place. I need to collect some or be given some first.");
            return;
        }

        steve.setFlying(false);
    }

    @Override
    protected void onTick() {
        if (result != null) {
            return;
        }

        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Could not reach the placement spot");
            return;
        }

        // Walk into range like a real player (no teleporting)
        if (steve.blockPosition().distSqr(targetPos) > REACH * REACH) {
            steve.getNavigation().moveTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, 1.1);
            return;
        }

        steve.getNavigation().stop();

        BlockState currentState = steve.level().getBlockState(targetPos);
        if (!currentState.isAir() && !currentState.liquid()) {
            result = ActionResult.failure("Something is already occupying " + targetPos.toShortString());
            return;
        }

        // Consume the material, then place
        SteveInventory inventory = steve.getInventory();
        if (inventory == null || inventory.removeItem(requiredItem, 1) <= 0) {
            result = ActionResult.failure("I ran out of " + ActionUtils.blockName(blockToPlace));
            return;
        }

        steve.getLookControl().setLookAt(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
        steve.swing(InteractionHand.MAIN_HAND, true);
        steve.level().setBlock(targetPos, blockToPlace.defaultBlockState(), 3);

        // Keep holding the same material if we have more, otherwise free the hand
        ItemStack remaining = inventory.has(requiredItem) ? new ItemStack(requiredItem) : ItemStack.EMPTY;
        steve.setItemInHand(InteractionHand.MAIN_HAND, remaining);

        SteveMod.LOGGER.info("Steve '{}' placed {} at {}",
            steve.getSteveName(), ActionUtils.blockName(blockToPlace), targetPos);

        result = ActionResult.success("Placed " + ActionUtils.blockName(blockToPlace));
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Place " + ActionUtils.blockName(blockToPlace) + " at " + targetPos;
    }
}
