package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveInventory;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.Random;

/**
 * Fishes for food at a nearby water body.
 *
 * <p>Requires a fishing rod in the inventory; without one the action fails with an actionable
 * message instead of pretending. The Steve walks to the water's edge, casts, waits a
 * realistic amount of time and pulls something in - the loot follows the vanilla fishing
 * table, including the occasional junk or treasure drop.</p>
 *
 * <p>This gives the AI a renewable food source that does not depend on animals happening to
 * be nearby, which matters when it has been told to survive rather than to hunt.</p>
 *
 * <p><b>Parameters:</b> {@code quantity} - how many catches to attempt (default 3).</p>
 */
public class FishingAction extends BaseAction {

    private static final double WATER_SEARCH_RADIUS = 24.0;
    private static final double CAST_REACH = 5.0;
    private static final int MAX_TICKS = 12000;             // 10 minutes
    /** Ticks a cast is left in the water before reeling in (1-3 s). */
    private static final int MIN_WAIT = 20;
    private static final int MAX_WAIT = 60;

    private int wantedCatches;
    private int catches;
    private int ticksRunning;
    private int castTicks;
    /** Ticks to wait for the current cast to "bite". */
    private int biteIn;
    private boolean isCasting;

    private BlockPos waterPos;
    private final Random random = new Random();

    // Loot table approximation (weights follow the vanilla fishing loot roughly)
    private static final Object[][] JUNK = {
        {Items.LILY_PAD, 1}, {Items.BOWL, 1}, {Items.STICK, 1}, {Items.STRING, 1},
        {Items.LEATHER, 1}, {Items.BONE, 1}, {Items.ROTTEN_FLESH, 1}, {Items.INK_SAC, 1},
    };
    private static final Object[][] TREASURE = {
        {Items.BOW, 1}, {Items.ENCHANTED_BOOK, 1}, {Items.NAME_TAG, 1}, {Items.SADDLE, 1},
    };

    public FishingAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        wantedCatches = Math.max(1, task.getIntParameter("quantity", 3));
        catches = 0;
        ticksRunning = 0;
        castTicks = 0;
        isCasting = false;

        steve.setFlying(false);

        if (!steve.getInventory().has(Items.FISHING_ROD)) {
            result = ActionResult.failure(
                "I need a fishing rod to fish - please give me one (/as give fishing_rod)");
            return;
        }

        if (!findWater()) {
            result = ActionResult.failure(
                "No water within " + (int) WATER_SEARCH_RADIUS
                    + " blocks. I need to explore to find a river or lake.");
            return;
        }

        steve.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
        SteveMod.LOGGER.info("Steve '{}' going fishing at {}",
            steve.getSteveName(), waterPos);
        steve.sendChatMessage("我去钓鱼了~");
    }

    @Override
    protected void onTick() {
        if (result != null) {
            return;
        }

        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            finish("Fishing took too long");
            return;
        }

        if (catches >= wantedCatches) {
            finish(null);
            return;
        }

        if (waterPos == null || !isWater(waterPos)) {
            if (!findWater()) {
                finish(catches > 0 ? null : "The water I was fishing in disappeared");
                return;
            }
        }

        // Walk to a spot where the water is within casting reach
        double distance = Math.sqrt(steve.blockPosition().distSqr(waterPos));
        if (distance > CAST_REACH) {
            steve.getNavigation().moveTo(
                waterPos.getX() + 0.5, waterPos.getY() + 1, waterPos.getZ() + 0.5, 1.1);
            return;
        }

        steve.getNavigation().stop();
        steve.getLookControl().setLookAt(
            waterPos.getX() + 0.5, waterPos.getY() + 0.5, waterPos.getZ() + 0.5);

        if (!isCasting) {
            // Cast
            isCasting = true;
            castTicks = 0;
            biteIn = MIN_WAIT + random.nextInt(MAX_WAIT - MIN_WAIT + 1);
            steve.swing(InteractionHand.MAIN_HAND, true);
            SteveMod.LOGGER.debug("Steve '{}' cast the line", steve.getSteveName());
            return;
        }

        castTicks++;
        if (castTicks < biteIn) {
            return;   // waiting for a bite
        }

        // Reel in
        steve.swing(InteractionHand.MAIN_HAND, true);
        isCasting = false;

        ItemStack catchStack = rollCatch();
        SteveInventory inventory = steve.getInventory();
        if (inventory != null) {
            int leftover = inventory.addItem(catchStack);
            if (leftover > 0) {
                steve.spawnAtLocation(catchStack.copyWithCount(leftover));
            }
        }

        catches++;
        SteveMod.LOGGER.info("Steve '{}' caught {} ({}/{})",
            steve.getSteveName(), ActionUtils.itemName(catchStack.getItem()),
            catches, wantedCatches);
    }

    /** Rolls a fishing drop: mostly fish, sometimes junk, rarely treasure. */
    private ItemStack rollCatch() {
        int roll = random.nextInt(100);

        if (roll < 6) {
            // Treasure (rare)
            Object[] entry = TREASURE[random.nextInt(TREASURE.length)];
            return new ItemStack((net.minecraft.world.item.Item) entry[0], (Integer) entry[1]);
        }
        if (roll < 16) {
            // Junk
            Object[] entry = JUNK[random.nextInt(JUNK.length)];
            return new ItemStack((net.minecraft.world.item.Item) entry[0], (Integer) entry[1]);
        }

        // Fish - the main prize
        int fishRoll = random.nextInt(100);
        if (fishRoll < 60) {
            return new ItemStack(Items.COD);
        } else if (fishRoll < 85) {
            return new ItemStack(Items.SALMON);
        } else if (fishRoll < 95) {
            return new ItemStack(Items.TROPICAL_FISH);
        }
        return new ItemStack(Items.PUFFERFISH);
    }

    /** Locates a water surface block within range. */
    private boolean findWater() {
        BlockPos center = steve.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        int r = (int) WATER_SEARCH_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -6; dy <= 4; dy++) {
                    double d2 = (double) dx * dx + (double) dz * dz;
                    if (d2 > (double) r * r || d2 >= bestDist) {
                        continue;
                    }
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (isWater(pos)) {
                        best = pos;
                        bestDist = d2;
                    }
                }
            }
        }

        waterPos = best;
        return best != null;
    }

    private boolean isWater(BlockPos pos) {
        BlockState state = steve.level().getBlockState(pos);
        return state.getFluidState().is(Fluids.WATER)
            && state.getBlock() == Blocks.WATER;
    }

    private void finish(String problem) {
        steve.getNavigation().stop();
        if (steve.getInventory() != null
            && !steve.getInventory().has(Items.FISHING_ROD)) {
            steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }

        if (problem == null) {
            result = ActionResult.success("Caught " + catches + " item(s) fishing");
        } else if (catches > 0) {
            result = ActionResult.success("Caught " + catches + " item(s), then stopped");
        } else {
            result = ActionResult.failure(problem);
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        isCasting = false;
    }

    @Override
    public String getDescription() {
        return "Fishing (" + catches + "/" + wantedCatches + ")";
    }
}
