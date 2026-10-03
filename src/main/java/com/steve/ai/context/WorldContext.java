package com.steve.ai.context;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of the AI's environment at a point in time.
 *
 * <p>Provides structured access to:
 * <ul>
 *   <li>AI state (position, inventory, health)</li>
 *   <li>Nearby resources (blocks, entities, containers)</li>
 *   <li>Query interfaces for the upper layers</li>
 * </ul>
 *
 * <p>Created by {@link EnvironmentScanner}, consumed by planning and decomposition layers.
 */
public class WorldContext {

    private final SteveState steveState;
    private final Map<Block, List<BlockPos>> nearbyBlocks;
    private final NearbyEntities nearbyEntities;
    private final List<ContainerInfo> nearbyContainers;

    public WorldContext(
        SteveState steveState,
        Map<Block, List<BlockPos>> nearbyBlocks,
        NearbyEntities nearbyEntities,
        List<ContainerInfo> nearbyContainers
    ) {
        this.steveState = steveState;
        this.nearbyBlocks = nearbyBlocks;
        this.nearbyEntities = nearbyEntities;
        this.nearbyContainers = nearbyContainers;
    }

    // ===== State Access =====

    public SteveState getSteveState() {
        return steveState;
    }

    public BlockPos getPosition() {
        return steveState.getPosition();
    }

    public Map<Item, Integer> getInventory() {
        return steveState.getInventory();
    }

    // ===== Block Queries =====

    /**
     * Finds the nearest block of the given type.
     *
     * @return position, or null if not found within scan radius
     */
    @Nullable
    public BlockPos findNearby(Block block) {
        List<BlockPos> positions = nearbyBlocks.get(block);
        if (positions == null || positions.isEmpty()) {
            return null;
        }
        return positions.get(0);  // Scanner sorts by distance
    }

    /**
     * Finds all blocks of a given type within max distance.
     *
     * @param maxDistance maximum distance in blocks
     * @return list of positions, sorted by distance (may be empty)
     */
    public List<BlockPos> findAll(Block block, int maxDistance) {
        List<BlockPos> positions = nearbyBlocks.get(block);
        if (positions == null) {
            return List.of();
        }

        BlockPos center = steveState.getPosition();
        return positions.stream()
            .filter(pos -> pos.distSqr(center) <= maxDistance * maxDistance)
            .toList();
    }

    // ===== Inventory Queries =====

    /**
     * Checks if the AI has at least the specified count of an item.
     */
    public boolean hasInInventory(Item item, int minCount) {
        return countInInventory(item) >= minCount;
    }

    /**
     * Returns how many of the item the AI currently has.
     */
    public int countInInventory(Item item) {
        return steveState.getInventory().getOrDefault(item, 0);
    }

    // ===== Container Queries =====

    public List<ContainerInfo> getNearbyContainers() {
        return nearbyContainers;
    }

    /**
     * Finds the nearest container (chest, barrel, furnace, etc).
     */
    @Nullable
    public ContainerInfo findNearestContainer() {
        return nearbyContainers.isEmpty() ? null : nearbyContainers.get(0);
    }

    // ===== Entity Queries =====

    public NearbyEntities getEntities() {
        return nearbyEntities;
    }

    // ===== Prompt Generation =====

    /**
     * Generates a human-readable context string for the LLM prompt.
     *
     * <p>Format:
     * <pre>
     * 当前状态：
     * - 位置：(123, 64, -456)
     * - 生命值：20/20
     * - 背包：橡木木板x32, 木棍x5
     *
     * 附近资源（24格内）：
     * - 工作台 @ (130, 64, -450) 距离8m
     * - 橡木原木 @ (125, 65, -460) 距离10m
     * </pre>
     */
    public String toPromptContext() {
        StringBuilder sb = new StringBuilder();

        sb.append("当前状态：\n");
        sb.append("- 位置：").append(steveState.getPosition().toShortString()).append("\n");
        sb.append("- 生命值：").append(steveState.getHealth()).append("/").append(steveState.getMaxHealth()).append("\n");

        if (steveState.getInventory().isEmpty()) {
            sb.append("- 背包：空\n");
        } else {
            sb.append("- 背包：");
            steveState.getInventory().entrySet().stream()
                .limit(10)  // Show first 10 items
                .forEach(entry -> {
                    String name = entry.getKey().toString().replace("minecraft:", "");
                    sb.append(name).append("x").append(entry.getValue()).append(", ");
                });
            if (steveState.getInventory().size() > 10) {
                sb.append("... (").append(steveState.getInventory().size() - 10).append(" more)");
            } else {
                sb.setLength(sb.length() - 2);  // Remove trailing ", "
            }
            sb.append("\n");
        }

        if (!nearbyBlocks.isEmpty() || !nearbyContainers.isEmpty()) {
            sb.append("\n附近资源（24格内）：\n");

            BlockPos center = steveState.getPosition();

            nearbyBlocks.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .limit(5)
                .forEach(entry -> {
                    Block block = entry.getKey();
                    BlockPos pos = entry.getValue().get(0);
                    int distance = (int) Math.sqrt(pos.distSqr(center));
                    String name = block.toString().replace("Block{minecraft:", "").replace("}", "");
                    sb.append("- ").append(name)
                      .append(" @ ").append(pos.toShortString())
                      .append(" 距离").append(distance).append("m\n");
                });

            nearbyContainers.stream()
                .limit(3)
                .forEach(container -> {
                    int distance = (int) Math.sqrt(container.getPosition().distSqr(center));
                    sb.append("- ").append(container.getType())
                      .append(" @ ").append(container.getPosition().toShortString())
                      .append(" 距离").append(distance).append("m\n");
                });
        }

        return sb.toString();
    }
}
