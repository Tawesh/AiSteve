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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects blocks of a given type - the general "go get me some X" primitive.
 *
 * <p>Two modes, chosen automatically:</p>
 * <ul>
 *   <li><b>Surface</b> - the target is visible nearby, so walk over and mine it (trees, stone,
 *       exposed ore).</li>
 *   <li><b>Underground</b> - the target is an ore that realistically only exists at depth, or
 *       the caller explicitly asked for it. The Steve digs a staircase down to the ore's
 *       natural Y level, then branch-mines until it has enough, exactly like a player
 *       would when hunting diamonds.</li>
 * </ul>
 *
 * <p>This replaces the old tunnel borer, which picked a fixed direction, teleported the Steve
 * in front of the player and destroyed whatever block happened to be ahead - which is why
 * "chop a tree" used to mean "eat dirt forever".</p>
 *
 * <p><b>Parameters:</b></p>
 * <ul>
 *   <li>{@code block} - block or resource name ({@code diamond}, {@code oak_log}, ...)</li>
 *   <li>{@code quantity} - how many to collect (default 8)</li>
 *   <li>{@code deep} (optional) - force underground mode / {@code false} to forbid it</li>
 * </ul>
 */
public class MineBlockAction extends BaseAction {

    // --- Timeouts -----------------------------------------------------------------
    /** Absolute cap; with progress detection this is only a safety net. */
    private static final int MAX_TICKS = 12000;             // 10 minutes
    /** Give up if nothing new is collected for this long (30 s). */
    private static final int NO_PROGRESS_LIMIT = 600;

    private static final double SEARCH_RADIUS = 24.0;
    private static final double REACH = 4.5;
    private static final int VERTICAL_REACH = 5;
    private static final int MAX_TREE_BLOCKS = 80;

    // --- Underground mining -------------------------------------------------------
    /** How far a branch tunnel extends sideways from the main shaft. */
    private static final int BRANCH_LENGTH = 14;
    /** Spacing between branch tunnels (blocks). */
    private static final int BRANCH_SPACING = 4;
    /** Torch every N blocks mined underground. */
    private static final int TORCH_INTERVAL = 12;
    /** Never dig below this (bedrock layer). */
    private static final int MIN_SAFE_Y = -60;

    /** Block -> the Y level where it is realistically found. */
    private static final Map<String, Integer> ORE_DEPTHS = new HashMap<>();

    static {
        ORE_DEPTHS.put("iron_ore", 16);
        ORE_DEPTHS.put("deepslate_iron_ore", 16);
        ORE_DEPTHS.put("coal_ore", 96);
        ORE_DEPTHS.put("copper_ore", 48);
        ORE_DEPTHS.put("gold_ore", -16);
        ORE_DEPTHS.put("deepslate_gold_ore", -16);
        ORE_DEPTHS.put("diamond_ore", -59);
        ORE_DEPTHS.put("deepslate_diamond_ore", -59);
        ORE_DEPTHS.put("redstone_ore", -59);
        ORE_DEPTHS.put("deepslate_redstone_ore", -59);
        ORE_DEPTHS.put("lapis_ore", 0);
        ORE_DEPTHS.put("deepslate_lapis_ore", 0);
        ORE_DEPTHS.put("emerald_ore", 132);
    }

    private enum Phase { SURFACE, DESCEND, BRANCH }

    private Block targetBlock;
    private int targetQuantity;
    private Phase phase = Phase.SURFACE;

    private int collectedCount;
    private int ticksRunning;
    private int ticksSinceProgress;

    /** Surface mode: blocks queued for removal (a tree contributes several). */
    private final Deque<BlockPos> pendingBlocks = new ArrayDeque<>();

    /** Underground mode state. */
    private int digDirectionX;
    private int digDirectionZ;
    private int targetY;
    private int branchCounter;
    private int torchCounter;
    private int branchStep;      // progress along the current branch
    private int branchSign = 1;  // which way the next branch goes

    public MineBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("block");
        targetQuantity = Math.max(1, task.getIntParameter("quantity", 8));
        collectedCount = 0;
        ticksRunning = 0;
        ticksSinceProgress = 0;
        pendingBlocks.clear();
        phase = Phase.SURFACE;

        targetBlock = ActionUtils.parseBlock(blockName);
        if (targetBlock == null || targetBlock == Blocks.AIR) {
            result = ActionResult.failure("I don't know how to collect '" + blockName + "'");
            return;
        }

        steve.setFlying(false);
        equipBestTool();

        // Surface first: if the target is right there, no reason to dig.
        if (findSomethingToCollect()) {
            SteveMod.LOGGER.info("Steve '{}' collecting {} from the surface",
                steve.getSteveName(), ActionUtils.blockName(targetBlock));
            return;
        }

        // Not visible. Ores live underground, so switch to real mining.
        boolean forceDeep = Boolean.parseBoolean(task.getStringParameter("deep", "false"));
        boolean oreTarget = ORE_DEPTHS.containsKey(ActionUtils.blockName(targetBlock));

        if (!forceDeep && !oreTarget) {
            result = ActionResult.failure(
                "No " + ActionUtils.blockName(targetBlock) + " within " + (int) SEARCH_RADIUS
                    + " blocks. I need to be closer, or the player should lead me to it.");
            return;
        }

        startUnderground();
    }

    // ------------------------------------------------------------------
    // Tick dispatch
    // ------------------------------------------------------------------

    @Override
    protected void onTick() {
        if (result != null) {
            return;
        }

        ticksRunning++;
        ticksSinceProgress++;

        if (ticksRunning > MAX_TICKS) {
            finish("Mining took too long");
            return;
        }
        if (ticksSinceProgress > NO_PROGRESS_LIMIT) {
            finish("Stopped making progress while mining " + ActionUtils.blockName(targetBlock));
            return;
        }

        if (collectedCount >= targetQuantity) {
            finish(null);
            return;
        }

        switch (phase) {
            case SURFACE -> tickSurface();
            case DESCEND -> tickDescend();
            case BRANCH -> tickBranchMine();
        }
    }

    // ------------------------------------------------------------------
    // Surface mode
    // ------------------------------------------------------------------

    private void tickSurface() {
        if (pendingBlocks.isEmpty()) {
            if (!findSomethingToCollect()) {
                // Lost the target - fall back to underground mining for ores
                if (ORE_DEPTHS.containsKey(ActionUtils.blockName(targetBlock))) {
                    startUnderground();
                    return;
                }
                finish(collectedCount > 0 ? null
                    : "No " + ActionUtils.blockName(targetBlock) + " left nearby");
                return;
            }
        }

        BlockPos next = pendingBlocks.peek();
        if (next == null) {
            return;
        }

        if (steve.level().getBlockState(next).getBlock() != targetBlock) {
            pendingBlocks.poll();
            return;
        }

        BlockPos stevePos = steve.blockPosition();
        int dx = stevePos.getX() - next.getX();
        int dz = stevePos.getZ() - next.getZ();
        int dy = Math.abs(stevePos.getY() - next.getY());
        double horizontal = Math.sqrt((double) dx * dx + (double) dz * dz);

        if (horizontal > REACH || dy > VERTICAL_REACH) {
            steve.getNavigation().moveTo(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, 1.1);
            return;
        }

        harvest(next);
    }

    // ------------------------------------------------------------------
    // Underground mode
    // ------------------------------------------------------------------

    /** Decides the target depth and starts the staircase. */
    private void startUnderground() {
        String name = ActionUtils.blockName(targetBlock);
        Integer depth = ORE_DEPTHS.get(name);
        targetY = depth != null ? depth : 32;

        // Keep it above bedrock
        targetY = Math.max(targetY, MIN_SAFE_Y);

        digDirectionX = steve.getRandom().nextBoolean() ? 1 : -1;
        digDirectionZ = steve.getRandom().nextBoolean() ? 1 : -1;

        phase = Phase.DESCEND;
        equipBestTool();

        SteveMod.LOGGER.info("Steve '{}' going underground for {} (target Y={})",
            steve.getSteveName(), name, targetY);

        steve.sendChatMessage("这个得挖下去找，我往地下挖了（目标 Y=" + targetY + "）");
    }

    /** Staircases downward, one step per invocation. */
    private void tickDescend() {
        int y = steve.blockPosition().getY();

        if (y <= targetY) {
            // Arrived at the ore level - start branch mining
            phase = Phase.BRANCH;
            branchCounter = BRANCH_SPACING;      // branch immediately
            branchStep = 0;
            torchCounter = 0;
            SteveMod.LOGGER.info("Steve '{}' reached Y={} - starting branch mining",
                steve.getSteveName(), y);
            return;
        }

        BlockPos current = steve.blockPosition();

        // Alternate the horizontal direction so we dig a descending staircase, not a pit.
        BlockPos step = current.offset(digDirectionX, -1, digDirectionZ);

        // Carve the step plus headroom, and the block in front at foot level so we can
        // actually walk into it.
        breakIfDiggable(step);
        breakIfDiggable(step.above());
        breakIfDiggable(current.offset(digDirectionX, 0, digDirectionZ));

        placeTorchIfDark(step);

        // Move into the freshly carved step.
        steve.teleportTo(step.getX() + 0.5, step.getY(), step.getZ() + 0.5);
        ticksSinceProgress = 0;

        // Occasionally swap direction so long staircases do not wander off forever.
        if (steve.getRandom().nextInt(12) == 0) {
            if (steve.getRandom().nextBoolean()) {
                digDirectionX = -digDirectionX;
            } else {
                digDirectionZ = -digDirectionZ;
            }
        }
    }

    /** Digs the main tunnel and side branches, collecting anything of the target type. */
    private void tickBranchMine() {
        // Grab any target blocks we are standing next to as we go
        if (harvestAdjacentTargets()) {
            return;
        }

        if (collectedCount >= targetQuantity) {
            finish(null);
            return;
        }

        // Start a new branch when due
        if (branchCounter >= BRANCH_SPACING) {
            branchCounter = 0;
            branchStep = 0;
            branchSign = -branchSign;
            digBranch(branchSign);
            return;
        }

        // Otherwise advance the main tunnel
        digForward();
        branchCounter++;
    }

    /** Digs one block along the main tunnel. */
    private void digForward() {
        BlockPos current = steve.blockPosition();
        BlockPos ahead = current.offset(digDirectionX, 0, digDirectionZ);

        breakIfDiggable(ahead);
        breakIfDiggable(ahead.above());
        // Keep the floor clean so we do not fall into our own tunnel
        breakIfDiggable(ahead.below());

        placeTorchIfDark(ahead);
        steve.teleportTo(ahead.getX() + 0.5, current.getY(), ahead.getZ() + 0.5);
        ticksSinceProgress = 0;
    }

    /** Digs a short side tunnel perpendicular to the main one. */
    private void digBranch(int sign) {
        // Perpendicular to (digDirectionX, digDirectionZ)
        int px = -digDirectionZ * sign;
        int pz = digDirectionX * sign;

        BlockPos current = steve.blockPosition();
        for (int i = 1; i <= BRANCH_LENGTH; i++) {
            BlockPos pos = current.offset(px * i, 0, pz * i);
            breakIfDiggable(pos);
            breakIfDiggable(pos.above());
            collectIfTarget(pos);

            if (collectedCount >= targetQuantity) {
                // Walk back to where the branch started and stop
                steve.teleportTo(current.getX() + 0.5, current.getY(), current.getZ() + 0.5);
                finish(null);
                return;
            }
        }

        // Walk back out of the branch so we do not start the next one deep inside
        steve.teleportTo(current.getX() + 0.5, current.getY(), current.getZ() + 0.5);
        ticksSinceProgress = 0;
    }

    /** Mines any adjacent target blocks (the ore we just exposed). */
    private boolean harvestAdjacentTargets() {
        BlockPos center = steve.blockPosition();
        boolean found = false;

        for (int dx = -2; dx <= 2 && !found; dx++) {
            for (int dy = -2; dy <= 2 && !found; dy++) {
                for (int dz = -2; dz <= 2 && !found; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (steve.level().getBlockState(pos).getBlock() == targetBlock) {
                        collectIfTarget(pos);
                        found = true;
                    }
                }
            }
        }
        return found;
    }

    /** Breaks a block if it is not air and not unbreakable, banking the drops. */
    private void breakIfDiggable(BlockPos pos) {
        BlockState state = steve.level().getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        Block block = state.getBlock();
        if (block == Blocks.BEDROCK || block == Blocks.BARRIER
            || block == Blocks.END_PORTAL || block == Blocks.NETHER_PORTAL) {
            return;
        }

        List<ItemStack> drops = new ArrayList<>();
        if (steve.level() instanceof ServerLevel serverLevel) {
            drops.addAll(Block.getDrops(state, serverLevel, pos, null));
        }
        if (!steve.level().destroyBlock(pos, false)) {
            return;
        }
        depositDrops(drops);

        // Track real progress: collecting the target, or at least moving through rock
        if (block == targetBlock) {
            collectedCount++;
            ticksSinceProgress = 0;
            SteveMod.LOGGER.info("Steve '{}' found {} at {} ({}/{})",
                steve.getSteveName(), ActionUtils.blockName(targetBlock), pos,
                collectedCount, targetQuantity);
        }

        torchCounter++;
        if (torchCounter >= TORCH_INTERVAL) {
            torchCounter = 0;
            placeTorchAt(pos);
        }
    }

    /** Breaks a block and counts it only when it is the target. */
    private void collectIfTarget(BlockPos pos) {
        if (steve.level().getBlockState(pos).getBlock() != targetBlock) {
            return;
        }
        breakIfDiggable(pos);
    }

    /** Keeps the tunnel lit so the Steve does not work in the dark. */
    private void placeTorchIfDark(BlockPos pos) {
        BlockPos floor = pos.below();
        if (steve.level().getBlockState(floor).isSolid()
            && steve.level().getBlockState(pos).isAir()) {
            placeTorchAt(pos);
        }
    }

    private void placeTorchAt(BlockPos pos) {
        BlockPos floor = pos.below();
        if (steve.level().getBlockState(pos).isAir()
            && steve.level().getBlockState(floor).isSolid()) {
            // Only if we actually own torches - no conjuring.
            SteveInventory inventory = steve.getInventory();
            if (inventory != null && inventory.removeItem(Items.TORCH, 1) > 0) {
                steve.level().setBlock(pos, Blocks.TORCH.defaultBlockState(), 3);
            }
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /**
     * Breaks the block, banks the drops and queues connected logs so a whole tree comes down.
     */
    private void harvest(BlockPos pos) {
        steve.getNavigation().stop();
        steve.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        steve.swing(InteractionHand.MAIN_HAND, true);

        BlockState state = steve.level().getBlockState(pos);
        if (state.isAir()) {
            pendingBlocks.poll();
            return;
        }

        List<ItemStack> drops = new ArrayList<>();
        if (steve.level() instanceof ServerLevel serverLevel) {
            drops.addAll(Block.getDrops(state, serverLevel, pos, null));
        }
        if (!steve.level().destroyBlock(pos, false)) {
            pendingBlocks.poll();
            return;
        }

        depositDrops(drops);
        collectedCount++;
        ticksSinceProgress = 0;
        pendingBlocks.poll();

        SteveMod.LOGGER.info("Steve '{}' collected {} at {} ({}/{})",
            steve.getSteveName(), ActionUtils.blockName(targetBlock), pos,
            collectedCount, targetQuantity);

        if (ActionUtils.isLog(state.getBlock())) {
            queueConnectedLogs(pos);
        }
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

    private void queueConnectedLogs(BlockPos origin) {
        int queued = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos neighbour = origin.offset(dx, dy, dz);
                    if (steve.level().getBlockState(neighbour).getBlock() != targetBlock) {
                        continue;
                    }
                    if (pendingBlocks.contains(neighbour)) {
                        continue;
                    }
                    if (pendingBlocks.size() >= MAX_TREE_BLOCKS) {
                        return;
                    }
                    pendingBlocks.offer(neighbour);
                    queued++;
                }
            }
        }
        if (queued > 0) {
            SteveMod.LOGGER.debug("Steve '{}' queued {} more connected logs",
                steve.getSteveName(), queued);
        }
    }

    /**
     * Locates the nearest block of the target type within {@link #SEARCH_RADIUS}.
     *
     * @return true when at least one block was queued
     */
    private boolean findSomethingToCollect() {
        BlockPos center = steve.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        int r = (int) SEARCH_RADIUS;
        int minY = Math.max(steve.level().getMinBuildHeight(), center.getY() - 12);
        int maxY = Math.min(steve.level().getMaxBuildHeight() - 1, center.getY() + 20);

        // Scan every block in range - don't skip any
        for (int y = minY; y <= maxY; y++) {
            int dy = y - center.getY();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d2 = (double) dx * dx + (double) dz * dz + (double) dy * dy;
                    if (d2 > (double) r * r || d2 >= bestDist) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    if (steve.level().getBlockState(pos).getBlock() == targetBlock) {
                        best = pos;
                        bestDist = d2;
                    }
                }
            }
        }

        if (best == null) {
            return false;
        }

        pendingBlocks.clear();
        pendingBlocks.offer(best);
        SteveMod.LOGGER.info("Steve '{}' heading for {} at {} ({}m away)",
            steve.getSteveName(), ActionUtils.blockName(targetBlock), best,
            (int) Math.sqrt(bestDist));
        return true;
    }

    private void equipBestTool() {
        ItemStack tool = ActionUtils.findBestTool(steve.getInventory(), targetBlock);
        steve.setItemInHand(InteractionHand.MAIN_HAND, tool);
    }

    @Override
    protected void onCancel() {
        steve.setFlying(false);
        steve.getNavigation().stop();
        pendingBlocks.clear();
    }

    @Override
    public String getDescription() {
        return "Collect " + ActionUtils.blockName(targetBlock)
            + " (" + collectedCount + "/" + targetQuantity
            + (phase == Phase.SURFACE ? "" : ", underground") + ")";
    }

    /** Ends the action; {@code problem == null} means success. */
    private void finish(String problem) {
        steve.setFlying(false);
        steve.getNavigation().stop();
        pendingBlocks.clear();

        if (problem == null) {
            result = ActionResult.success(
                "Collected " + collectedCount + " " + ActionUtils.blockName(targetBlock));
        } else if (collectedCount > 0) {
            // Partial haul: the work we could do is done. Reporting this as a failure used to
            // trigger replanning and eventually a bogus "I failed" message even though the
            // run was finished, so it is a completed step with an honest note instead.
            result = ActionResult.partial(
                problem + " (got " + collectedCount + "/" + targetQuantity + ")");
        } else {
            result = ActionResult.failure(problem);
        }
    }
}
