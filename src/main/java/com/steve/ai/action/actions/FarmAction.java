package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveInventory;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Harvests mature crops and replants them - the renewable food loop.
 *
 * <p>A real player with a wheat field does not starve. This lets the AI keep itself fed once
 * it finds farmland: reap the grown crops, bank the produce, and put the seeds back so the
 * field keeps producing.</p>
 *
 * <p><b>Parameters:</b> {@code quantity} - how many crop blocks to harvest (default 8).</p>
 */
public class FarmAction extends BaseAction {

    private static final double SEARCH_RADIUS = 24.0;
    private static final double REACH = 4.5;
    private static final int MAX_TICKS = 6000;              // 5 minutes
    private static final int NO_PROGRESS_LIMIT = 600;

    private int wanted;
    private int harvested;
    private int ticksRunning;
    private int ticksSinceProgress;

    private final List<BlockPos> pendingCrops = new ArrayList<>();

    public FarmAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        wanted = Math.max(1, task.getIntParameter("quantity", 8));
        harvested = 0;
        ticksRunning = 0;
        ticksSinceProgress = 0;
        pendingCrops.clear();

        steve.setFlying(false);

        if (!findMatureCrops()) {
            result = ActionResult.failure(
                "No mature crops within " + (int) SEARCH_RADIUS
                    + " blocks. I need to find a farm - maybe explore first.");
            return;
        }

        SteveMod.LOGGER.info("Steve '{}' harvesting {} crop block(s)",
            steve.getSteveName(), pendingCrops.size());
    }

    @Override
    protected void onTick() {
        if (result != null) {
            return;
        }

        ticksRunning++;
        ticksSinceProgress++;

        if (ticksRunning > MAX_TICKS) {
            finish("Farming took too long");
            return;
        }
        if (ticksSinceProgress > NO_PROGRESS_LIMIT) {
            finish(harvested > 0 ? null : "Could not reach any crops");
            return;
        }

        if (harvested >= wanted) {
            finish(null);
            return;
        }

        pendingCrops.removeIf(pos -> !isMatureCrop(pos));

        if (pendingCrops.isEmpty()) {
            if (!findMatureCrops()) {
                finish(harvested > 0 ? null : "No more mature crops nearby");
                return;
            }
        }

        BlockPos target = pendingCrops.get(0);
        double distance = Math.sqrt(steve.blockPosition().distSqr(target));

        if (distance > REACH) {
            steve.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.1);
            return;
        }

        harvestAndReplant(target);
    }

    /** Reaps a crop and, when possible, puts a seed back in the same spot. */
    private void harvestAndReplant(BlockPos pos) {
        steve.getNavigation().stop();
        steve.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        steve.swing(InteractionHand.MAIN_HAND, true);

        BlockState state = steve.level().getBlockState(pos);
        Block cropBlock = state.getBlock();

        List<ItemStack> drops = new ArrayList<>();
        if (steve.level() instanceof ServerLevel serverLevel) {
            drops.addAll(Block.getDrops(state, serverLevel, pos, null));
        }

        if (!steve.level().destroyBlock(pos, false)) {
            pendingCrops.remove(pos);
            return;
        }

        depositDrops(drops);
        harvested++;
        ticksSinceProgress = 0;
        pendingCrops.remove(pos);

        SteveMod.LOGGER.info("Steve '{}' harvested {} at {} ({}/{})",
            steve.getSteveName(), ActionUtils.blockName(cropBlock), pos, harvested, wanted);

        replant(pos, cropBlock);
    }

    /** Puts the matching seed back so the field keeps growing. */
    private void replant(BlockPos pos, Block cropBlock) {
        Item seed = seedFor(cropBlock);
        if (seed == null) {
            return;
        }

        SteveInventory inventory = steve.getInventory();
        if (inventory == null || inventory.removeItem(seed, 1) <= 0) {
            return;   // no seed to replant with - the field just shrinks
        }

        // Only replant on farmland with air above
        BlockPos below = pos.below();
        if (steve.level().getBlockState(below).getBlock() != Blocks.FARMLAND
            || !steve.level().getBlockState(pos).isAir()) {
            // Give the seed back rather than losing it
            inventory.addItem(new ItemStack(seed));
            return;
        }

        if (cropBlock instanceof CropBlock crop) {
            steve.level().setBlock(pos, crop.getStateForAge(0), 3);
        }
    }

    /** Maps a crop block to the item used to replant it. */
    private Item seedFor(Block cropBlock) {
        if (cropBlock == Blocks.WHEAT) {
            return Items.WHEAT_SEEDS;
        }
        if (cropBlock == Blocks.CARROTS) {
            return Items.CARROT;
        }
        if (cropBlock == Blocks.POTATOES) {
            return Items.POTATO;
        }
        if (cropBlock == Blocks.BEETROOTS) {
            return Items.BEETROOT_SEEDS;
        }
        return null;
    }

    private boolean isMatureCrop(BlockPos pos) {
        BlockState state = steve.level().getBlockState(pos);
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    /** Collects mature crops within range, nearest first. */
    private boolean findMatureCrops() {
        BlockPos center = steve.blockPosition();
        List<BlockPos> found = new ArrayList<>();

        int r = (int) SEARCH_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    double d2 = (double) dx * dx + (double) dz * dz;
                    if (d2 > (double) r * r) {
                        continue;
                    }
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (isMatureCrop(pos)) {
                        found.add(pos);
                    }
                }
            }
        }

        if (found.isEmpty()) {
            return false;
        }

        found.sort((a, b) -> Double.compare(a.distSqr(center), b.distSqr(center)));
        pendingCrops.clear();
        pendingCrops.addAll(found);
        return true;
    }

    private void depositDrops(List<ItemStack> drops) {
        SteveInventory inventory = steve.getInventory();
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            if (inventory == null) {
                steve.spawnAtLocation(drop);
                continue;
            }
            int leftover = inventory.addItem(drop);
            if (leftover > 0) {
                steve.spawnAtLocation(drop.copyWithCount(leftover));
            }
        }
    }

    private void finish(String problem) {
        steve.getNavigation().stop();
        pendingCrops.clear();

        if (problem == null) {
            result = ActionResult.success("Harvested " + harvested + " crop block(s)");
        } else if (harvested > 0) {
            result = ActionResult.success("Harvested " + harvested + " crop block(s), then stopped");
        } else {
            result = ActionResult.failure(problem);
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        pendingCrops.clear();
    }

    @Override
    public String getDescription() {
        return "Farm (" + harvested + "/" + wanted + ")";
    }
}
