package com.steve.ai.perception;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.protocol.Observation;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Observes the surrounding blocks: resources worth gathering and containers worth looting.
 *
 * <p>This is the most expensive observer, so it runs on the slow cycle with caching (see
 * {@link PerceptionService}). The scan is strided (every 2 blocks) and vertically bounded,
 * which keeps it to a few thousand lookups per refresh rather than a full volume.</p>
 *
 * <p>Only <em>interesting</em> blocks are reported - ores, logs, water, crops, containers and
 * crafting tables. Sending "there are 3400 stone blocks" would burn tokens and teach the model
 * nothing.</p>
 */
public final class WorldObserver {

    /** Blocks that mark a village / exploitable structure. */
    private static final java.util.Set<Block> LANDMARKS = java.util.Set.of(
        Blocks.BELL, Blocks.COMPOSTER, Blocks.HAY_BLOCK, Blocks.CARTOGRAPHY_TABLE,
        Blocks.FLETCHING_TABLE, Blocks.SMITHING_TABLE, Blocks.LOOM, Blocks.STONECUTTER,
        Blocks.GRINDSTONE, Blocks.BEE_NEST, Blocks.BEEHIVE);

    /** Max distinct resource entries reported. */
    private static final int MAX_RESOURCES = 10;
    /** Max distinct container entries reported. */
    private static final int MAX_CONTAINERS = 6;

    private WorldObserver() {
    }

    /** Result bundle for the slow perception cycle. */
    public record Result(List<Observation.BlockView> resources,
                         List<Observation.BlockView> containers) {

        public static Result empty() {
            return new Result(List.of(), List.of());
        }
    }

    /**
     * Scans around the AI.
     *
     * @param radius horizontal scan radius
     * @param down   how far below the AI to look
     * @param up     how far above the AI to look
     */
    public static Result scan(SteveEntity steve, int radius, int down, int up) {
        Level level = steve.level();
        BlockPos center = steve.blockPosition();

        Map<String, Accumulator> resources = new HashMap<>();
        Map<String, Accumulator> containers = new HashMap<>();

        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                for (int dy = -down; dy <= up; dy += 2) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Block block = level.getBlockState(pos).getBlock();
                    if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
                        continue;
                    }

                    double distance = Math.sqrt(center.distSqr(pos));
                    String name = ActionUtils.blockName(block);

                    if (isContainer(block)) {
                        containers.computeIfAbsent(name, k -> new Accumulator())
                            .add(pos, distance);
                        continue;
                    }

                    if (isResource(block, name)) {
                        resources.computeIfAbsent(name, k -> new Accumulator())
                            .add(pos, distance);
                    }
                }
            }
        }

        return new Result(toViews(resources, MAX_RESOURCES), toViews(containers, MAX_CONTAINERS));
    }

    // ------------------------------------------------------------------

    private static boolean isContainer(Block block) {
        return block instanceof ChestBlock
            || block instanceof BarrelBlock
            || block instanceof ShulkerBoxBlock
            || block instanceof AbstractFurnaceBlock
            || block instanceof HopperBlock;
    }

    private static boolean isResource(Block block, String name) {
        if (name.endsWith("_ore")) {
            return true;
        }
        if (ActionUtils.isLog(block)) {
            return true;
        }
        if (block == Blocks.WATER) {
            return true;
        }
        if (block == Blocks.CRAFTING_TABLE) {
            return true;
        }
        if (block == Blocks.WHEAT || block == Blocks.CARROTS
            || block == Blocks.POTATOES || block == Blocks.BEETROOTS) {
            return true;
        }
        if (LANDMARKS.contains(block)) {
            return true;
        }
        // A few directly edible / useful blocks the planner likes to know about.
        return block == Blocks.PUMPKIN || block == Blocks.MELON || block == Blocks.SUGAR_CANE;
    }

    private static List<Observation.BlockView> toViews(Map<String, Accumulator> map, int max) {
        List<Observation.BlockView> views = new ArrayList<>();
        for (Map.Entry<String, Accumulator> entry : map.entrySet()) {
            Accumulator acc = entry.getValue();
            views.add(new Observation.BlockView(
                entry.getKey(), acc.count, acc.nearestDistance,
                acc.nearest.getX(), acc.nearest.getY(), acc.nearest.getZ()));
        }
        views.sort(Comparator.comparingDouble(Observation.BlockView::distance));
        return views.size() <= max ? views : new ArrayList<>(views.subList(0, max));
    }

    /** Tracks count + nearest position for one block type. */
    private static final class Accumulator {
        private int count;
        private double nearestDistance = Double.MAX_VALUE;
        private BlockPos nearest = BlockPos.ZERO;

        void add(BlockPos pos, double distance) {
            count++;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = pos;
            }
        }
    }
}
