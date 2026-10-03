package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Walks the AI into unexplored territory, optionally looking for something specific on the way.
 *
 * <p><b>Why this matters:</b> a real player asked to "find some food" does not stand still and
 * declare that nothing is nearby - they walk until they find something. Without this, the AI
 * could only ever use what happened to be inside its immediate scan radius, so a village two
 * hundred blocks away might as well not exist.</p>
 *
 * <p>Combined with the other primitives this produces far more capable behaviour. The planner
 * can emit {@code explore} followed by {@code open_container} / {@code attack} / {@code mine}:
 * once the AI has moved, those actions search the <i>new</i> surroundings and succeed.</p>
 *
 * <p><b>Parameters:</b></p>
 * <ul>
 *   <li>{@code distance} (optional) - how far to travel, default 40, clamped to 16..200</li>
 *   <li>{@code direction} (optional) - {@code north|south|east|west}; random when omitted</li>
 *   <li>{@code target} (optional) - an entity name ({@code sheep}) or block name
 *       ({@code chest}, {@code oak_log}). Stops early and reports when spotted.</li>
 * </ul>
 */
public class ExploreAction extends BaseAction {

    private static final int DEFAULT_DISTANCE = 40;
    private static final int MIN_DISTANCE = 16;
    private static final int MAX_DISTANCE = 200;

    /** Length of each pathfinding hop. Short hops cope far better with rough terrain. */
    private static final int STEP = 12;
    private static final double ARRIVED = 3.0;

    private static final int MAX_TICKS = 6000;              // 5 minutes
    /** Give up when no forward progress is made for this long (20 s). */
    private static final int STALL_LIMIT = 400;
    /** How often to look for the target entity/block. */
    private static final int SCAN_INTERVAL = 20;

    private int maxDistance;
    private String targetName;
    private int dirX;
    private int dirZ;

    private int ticksRunning;
    private int ticksStalled;
    private BlockPos startPos;
    private double lastTravelled;

    public ExploreAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        maxDistance = clamp(task.getIntParameter("distance", DEFAULT_DISTANCE));

        // 以玩家为中心：探索距离不能超过跟随半径，否则就是"跑丢"而不是"陪你看世界"。
        int roam = com.steve.ai.config.RuntimeSettings.roamRadius();
        maxDistance = Math.min(maxDistance, Math.max(MIN_DISTANCE, roam));
        targetName = task.getStringParameter("target", null);
        ticksRunning = 0;
        ticksStalled = 0;
        lastTravelled = 0;

        steve.setFlying(false);
        startPos = steve.blockPosition();

        pickDirection(task.getStringParameter("direction", null));
        navigateToNextWaypoint();

        SteveMod.LOGGER.info("Steve '{}' exploring {} blocks towards ({}, {}){}",
            steve.getSteveName(), maxDistance, dirX, dirZ,
            targetName != null ? " looking for " + targetName : "");
    }

    @Override
    protected void onTick() {
        if (result != null) {
            return;
        }

        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            finish("Explore timed out");
            return;
        }

        // Spot the target early so we do not walk right past it
        if (targetName != null && ticksRunning % SCAN_INTERVAL == 0) {
            String found = scanForTarget();
            if (found != null) {
                steve.getNavigation().stop();
                SteveMod.LOGGER.info("Steve '{}' spotted {} while exploring: {}",
                    steve.getSteveName(), targetName, found);
                result = ActionResult.success("Found " + found + " - it is nearby now");
                return;
            }
        }

        double travelled = travelledDistance();
        if (travelled >= maxDistance) {
            steve.getNavigation().stop();
            result = ActionResult.success(
                "Explored " + (int) travelled + " blocks. "
                    + (targetName != null ? "No " + targetName + " seen yet." : "")
                    + " I can see new surroundings from here.");
            return;
        }

        // 以玩家为中心：这里不会让它一路走远。
        // 玩家还在附近时，探索是"陪着你往那边走走看看"；一旦快要超出跟随半径，
        // 就主动收手，把身体交回给回位逻辑，而不是继续往外跑然后被判定走丢。
        if (outOfRange()) {
            steve.getNavigation().stop();
            result = ActionResult.success(
                "Explored " + (int) travelled + " blocks around you - "
                    + "head any further and I would be leaving you behind.");
            return;
        }

        // Detect being stuck (walls, cliffs, caves) and stop rather than spin
        if (travelled <= lastTravelled + 0.01) {
            ticksStalled++;
            if (ticksStalled > STALL_LIMIT) {
                steve.getNavigation().stop();
                result = ActionResult.failure(
                    "Exploration blocked after " + (int) travelled
                        + " blocks - I cannot get further that way");
                return;
            }
        } else {
            ticksStalled = 0;
            lastTravelled = travelled;
        }

        // Arrived at the current hop? Aim for the next one
        if (steve.getNavigation().isDone()) {
            navigateToNextWaypoint();
        }
    }

    /** 是否已经快要超出"以玩家为中心"的活动半径。 */
    private boolean outOfRange() {
        Player player = ActionUtils.findNearestPlayer(steve);
        if (player == null) {
            // 没有玩家在场时不设限：单机模式下它总得有地方可去。
            return false;
        }
        int radius = com.steve.ai.config.RuntimeSettings.roamRadius();
        return steve.distanceTo(player) > radius;
    }

    // ------------------------------------------------------------------

    private static int clamp(int distance) {
        if (distance < MIN_DISTANCE) {
            return MIN_DISTANCE;
        }
        return Math.min(distance, MAX_DISTANCE);
    }

    /** Chooses the travel direction from the named compass point, or randomly. */
    private void pickDirection(String named) {
        if (named != null) {
            switch (named.trim().toLowerCase()) {
                case "north", "北" -> { dirX = 0; dirZ = -1; return; }
                case "south", "南" -> { dirX = 0; dirZ = 1; return; }
                case "east", "东" -> { dirX = 1; dirZ = 0; return; }
                case "west", "西" -> { dirX = -1; dirZ = 0; return; }
                default -> { /* fall through to random */ }
            }
        }

        // Random cardinal direction - deterministic enough, easy to path along.
        int choice = steve.getRandom().nextInt(4);
        switch (choice) {
            case 0 -> { dirX = 0; dirZ = -1; }
            case 1 -> { dirX = 0; dirZ = 1; }
            case 2 -> { dirX = 1; dirZ = 0; }
            default -> { dirX = -1; dirZ = 0; }
        }
    }

    /** Aims the navigator one hop further along the chosen direction. */
    private void navigateToNextWaypoint() {
        BlockPos here = steve.blockPosition();
        BlockPos waypoint = here.offset(dirX * STEP, 0, dirZ * STEP);
        steve.getNavigation().moveTo(waypoint.getX() + 0.5, here.getY(), waypoint.getZ() + 0.5, 1.2);
    }

    /** Straight-line horizontal distance covered so far. */
    private double travelledDistance() {
        BlockPos here = steve.blockPosition();
        int dx = here.getX() - startPos.getX();
        int dz = here.getZ() - startPos.getZ();
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    /**
     * Looks for the requested entity or block in the current surroundings.
     *
     * @return a short description when found, otherwise null
     */
    private String scanForTarget() {
        if (targetName == null || targetName.isBlank()) {
            return null;
        }

        // Try blocks first (chest, oak_log, ...)
        Block asBlock = ActionUtils.parseBlock(targetName);
        if (asBlock != Blocks.AIR && asBlock != null) {
            BlockPos hit = findBlockNearby(asBlock);
            if (hit != null) {
                return ActionUtils.blockName(asBlock) + " at " + hit.toShortString();
            }
        }

        // Then entities (sheep, cow, ...)
        LivingEntity hit = findEntityNearby(targetName);
        if (hit != null) {
            return hit.getType().toString() + " nearby";
        }

        return null;
    }

    private BlockPos findBlockNearby(Block block) {
        BlockPos center = steve.blockPosition();
        int r = 24;
        // Search more carefully - don't skip blocks
        for (int y = -8; y <= 10; y++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) continue;  // circular scan
                    BlockPos pos = center.offset(dx, y, dz);
                    if (steve.level().getBlockState(pos).getBlock() == block) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private LivingEntity findEntityNearby(String name) {
        String wanted = name.toLowerCase();
        AABB box = steve.getBoundingBox().inflate(24.0);
        List<Entity> entities = steve.level().getEntities(steve, box);

        LivingEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }
            if (living instanceof Player || living instanceof SteveEntity) {
                continue;
            }
            if (!living.getType().toString().toLowerCase().contains(wanted)) {
                continue;
            }
            double d = steve.distanceTo(living);
            if (d < nearestDist) {
                nearest = living;
                nearestDist = d;
            }
        }
        return nearest;
    }

    private void finish(String problem) {
        steve.getNavigation().stop();
        result = ActionResult.failure(problem);
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Explore " + maxDistance + " blocks"
            + (targetName != null ? " for " + targetName : "");
    }
}
