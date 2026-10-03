package com.steve.ai.memory;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Snapshot of what the AI can perceive right now.
 *
 * <p>Besides blocks and entities this reports <b>points of interest</b> - containers to open
 * and structure hints such as the presence of a village. That is what lets the planner reason
 * like a player: "I need food, there are chests and a bell nearby, so instead of insisting on
 * hunting I can go and loot the village."</p>
 */
public class WorldKnowledge {

    private final SteveEntity steve;
    private final int scanRadius = 16;
    /** Container scan is finer-grained (chests are one block) but over a smaller area. */
    private final int containerRadius = 14;

    private Map<Block, Integer> nearbyBlocks;
    private List<Entity> nearbyEntities;
    private String biomeName;

    /** Interesting surrounding blocks -> count. */
    private final Map<Block, Integer> pointsOfInterest = new LinkedHashMap<>();
    private int containerCount;
    private double nearestContainerDistance = -1;
    private BlockPos nearestContainerPos;
    /** True when mature or growing crops are visible (implies a farm). */
    private boolean hasCrops;

    public WorldKnowledge(SteveEntity steve) {
        this.steve = steve;
        scan();
        rememberDiscoveries();
    }

    /**
     * Persists noteworthy surroundings into the AI's long-term world memory.
     *
     * <p>This is what lets it act on knowledge across sessions - "there is a village to the
     * north" should not have to be rediscovered every time the game is started.</p>
     */
    private void rememberDiscoveries() {
        WorldMemory memory = steve.getWorldMemory();
        if (memory == null) {
            return;
        }

        long now = steve.level().getGameTime();
        BlockPos here = steve.blockPosition();

        if (looksLikeVillage()) {
            memory.record(WorldMemory.VILLAGE, here, now);
        }
        if (containerCount > 0 && nearestContainerPos != null) {
            memory.record(WorldMemory.VILLAGE, nearestContainerPos, now);
        }
        if (nearbyBlocks.containsKey(Blocks.WATER)) {
            memory.record(WorldMemory.WATER, here, now);
        }
        if (hasCrops) {
            memory.record(WorldMemory.FARM, here, now);
        }
        if (nearbyBlocks.containsKey(Blocks.OAK_LOG)
            || nearbyBlocks.containsKey(Blocks.BIRCH_LOG)
            || nearbyBlocks.containsKey(Blocks.SPRUCE_LOG)) {
            memory.record(WorldMemory.FOREST, here, now);
        }
    }

    private void scan() {
        scanBiome();
        scanBlocks();
        scanPointsOfInterest();
        scanEntities();
    }

    private void scanBiome() {
        Level level = steve.level();
        BlockPos pos = steve.blockPosition();

        Biome biome = level.getBiome(pos).value();
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var biomeKey = biomeRegistry.getKey(biome);

        biomeName = biomeKey != null ? biomeKey.getPath() : "unknown";
    }

    private void scanBlocks() {
        nearbyBlocks = new HashMap<>();
        Level level = steve.level();
        BlockPos stevePos = steve.blockPosition();

        for (int x = -scanRadius; x <= scanRadius; x += 2) {
            for (int y = -scanRadius; y <= scanRadius; y += 2) {
                for (int z = -scanRadius; z <= scanRadius; z += 2) {
                    BlockPos checkPos = stevePos.offset(x, y, z);
                    BlockState state = level.getBlockState(checkPos);
                    Block block = state.getBlock();

                    if (block != Blocks.AIR && block != Blocks.CAVE_AIR && block != Blocks.VOID_AIR) {
                        nearbyBlocks.put(block, nearbyBlocks.getOrDefault(block, 0) + 1);
                    }
                }
            }
        }
    }

    /**
     * Fine-grained scan for things worth interacting with.
     *
     * <p>Uses step 1 so single-block containers cannot be missed, over a slightly smaller
     * radius to keep the cost reasonable (this runs once per planning call, not per tick).</p>
     */
    private void scanPointsOfInterest() {
        pointsOfInterest.clear();
        containerCount = 0;
        nearestContainerDistance = -1;
        nearestContainerPos = null;
        hasCrops = false;

        Level level = steve.level();
        BlockPos stevePos = steve.blockPosition();
        int r = containerRadius;

        for (int x = -r; x <= r; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = stevePos.offset(x, y, z);
                    Block block = level.getBlockState(pos).getBlock();

                    if (isContainer(block)) {
                        containerCount++;
                        double d = Math.sqrt(stevePos.distSqr(pos));
                        if (nearestContainerDistance < 0 || d < nearestContainerDistance) {
                            nearestContainerDistance = d;
                            nearestContainerPos = pos;
                        }
                        continue;   // containers are counted separately
                    }

                    if (isStructureHint(block)) {
                        pointsOfInterest.merge(block, 1, Integer::sum);
                    }

                    if (block == Blocks.WHEAT || block == Blocks.CARROTS
                        || block == Blocks.POTATOES || block == Blocks.BEETROOTS
                        || block == Blocks.FARMLAND) {
                        hasCrops = true;
                    }
                }
            }
        }
    }

    private static boolean isContainer(Block block) {
        return block instanceof ChestBlock
            || block instanceof BarrelBlock
            || block instanceof ShulkerBoxBlock
            || block instanceof AbstractFurnaceBlock;
    }

    /**
     * Blocks that mainly serve as evidence of a structure the AI can exploit.
     *
     * <p>A bell / composter / hay bale cluster means "there is a village here", which in turn
     * means villagers, crops and chests - a much better food source than wandering.</p>
     */
    private static boolean isStructureHint(Block block) {
        return block == Blocks.BELL
            || block == Blocks.COMPOSTER
            || block == Blocks.HAY_BLOCK
            || block == Blocks.CARTOGRAPHY_TABLE
            || block == Blocks.FLETCHING_TABLE
            || block == Blocks.SMITHING_TABLE
            || block == Blocks.LOOM
            || block == Blocks.STONECUTTER
            || block == Blocks.GRINDSTONE
            || block == Blocks.CRAFTING_TABLE
            || block == Blocks.BEE_NEST
            || block == Blocks.BEEHIVE;
    }

    private void scanEntities() {
        Level level = steve.level();
        AABB searchBox = steve.getBoundingBox().inflate(scanRadius);
        nearbyEntities = level.getEntities(steve, searchBox);
    }

    public String getBiomeName() {
        return biomeName;
    }

    public String getNearbyBlocksSummary() {
        if (nearbyBlocks.isEmpty()) {
            return "none";
        }

        StringBuilder sb = new StringBuilder();
        nearbyBlocks.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .limit(5)
            .forEach(entry -> {
                if (sb.length() > 0) sb.append(", ");
                sb.append(blockName(entry.getKey()));
            });

        return sb.toString();
    }

    public String getNearbyEntitiesSummary() {
        if (nearbyEntities.isEmpty()) {
            return "none";
        }

        Map<String, Integer> entityCounts = new LinkedHashMap<>();
        for (Entity entity : nearbyEntities) {
            String name = entityName(entity);
            entityCounts.put(name, entityCounts.getOrDefault(name, 0) + 1);
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Integer> entry : entityCounts.entrySet()) {
            if (count > 0) sb.append(", ");
            sb.append(entry.getValue()).append(" ").append(entry.getKey());
            count++;
            if (count >= 8) break;
        }

        return sb.toString();
    }

    /**
     * Describes containers and structure hints in a compact, LLM-friendly way.
     *
     * @return e.g. {@code "2 container(s) (nearest chest 8m) | village hints: bell, composter"}
     *         or {@code "none"} when there is nothing of interest
     */
    public String getNearbyPointsOfInterest() {
        StringBuilder sb = new StringBuilder();

        if (containerCount > 0) {
            sb.append(containerCount).append(" container(s) (nearest ")
              .append((int) nearestContainerDistance).append("m away)")
              .append(nearestContainerPos != null
                  ? " at " + nearestContainerPos.toShortString() : "");
        }

        if (!pointsOfInterest.isEmpty()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("notable blocks: ");
            int i = 0;
            for (Map.Entry<Block, Integer> entry : pointsOfInterest.entrySet()) {
                if (i++ > 0) sb.append(", ");
                sb.append(blockName(entry.getKey()));
                if (entry.getValue() > 1) sb.append(" x").append(entry.getValue());
                if (i >= 6) break;
            }
        }

        return sb.length() == 0 ? "none" : sb.toString();
    }

    public boolean hasNearbyContainer() {
        return containerCount > 0;
    }

    /** True when crops/farmland are visible - implies a farm to harvest. */
    public boolean hasNearbyFarm() {
        return hasCrops;
    }

    /** True when water is visible nearby - implies a place to fish. */
    public boolean hasNearbyWater() {
        return nearbyBlocks != null && nearbyBlocks.containsKey(Blocks.WATER);
    }

    /** True when the surroundings look like a village (bell / composter / workstations). */
    public boolean looksLikeVillage() {
        return pointsOfInterest.containsKey(Blocks.BELL)
            || pointsOfInterest.containsKey(Blocks.COMPOSTER)
            || (pointsOfInterest.containsKey(Blocks.HAY_BLOCK)
                && pointsOfInterest.containsKey(Blocks.CRAFTING_TABLE));
    }

    public Map<Block, Integer> getNearbyBlocks() {
        return nearbyBlocks;
    }

    public List<Entity> getNearbyEntities() {
        return nearbyEntities;
    }

    public String getNearbyPlayerNames() {
        List<String> playerNames = new ArrayList<>();
        for (Entity entity : nearbyEntities) {
            if (entity instanceof Player player) {
                playerNames.add(player.getName().getString());
            }
        }

        return playerNames.isEmpty() ? "none" : String.join(", ", playerNames);
    }

    // ------------------------------------------------------------------

    private static String blockName(Block block) {
        if (block == null) {
            return "unknown";
        }
        ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        return key != null ? key.getPath() : block.toString();
    }

    /**
     * Human / LLM friendly entity name such as {@code sheep} or {@code zombie}
     * (instead of the raw {@code entity.minecraft.sheep}).
     */
    private static String entityName(Entity entity) {
        var typeId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return typeId != null ? typeId.getPath() : entity.getType().toString();
    }
}
