package com.steve.ai.context;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Scans the environment around the AI to build a WorldContext snapshot.
 */
public class EnvironmentScanner {

    private static final int BLOCK_SCAN_RADIUS = 24;
    private static final int ENTITY_SCAN_RADIUS = 32;
    private static final int VERTICAL_RANGE_DOWN = 8;
    private static final int VERTICAL_RANGE_UP = 10;

    /**
     * Scans the environment and returns an immutable snapshot.
     */
    public static WorldContext scan(SteveEntity steve) {
        SteveState steveState = captureSteveState(steve);
        Map<Block, List<BlockPos>> nearbyBlocks = scanBlocks(steve);
        NearbyEntities nearbyEntities = scanEntities(steve);
        List<ContainerInfo> nearbyContainers = scanContainers(steve);

        return new WorldContext(steveState, nearbyBlocks, nearbyEntities, nearbyContainers);
    }

    private static SteveState captureSteveState(SteveEntity steve) {
        BlockPos position = steve.blockPosition();
        float health = steve.getHealth();
        float maxHealth = steve.getMaxHealth();

        Map<Item, Integer> inventory = new HashMap<>();
        if (steve.getInventory() != null) {
            for (ItemStack stack : steve.getInventory().getStacks()) {
                if (!stack.isEmpty()) {
                    inventory.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }

        return new SteveState(position, health, maxHealth, inventory);
    }

    private static Map<Block, List<BlockPos>> scanBlocks(SteveEntity steve) {
        Map<Block, List<BlockPos>> blockMap = new HashMap<>();
        BlockPos center = steve.blockPosition();

        int minY = Math.max(steve.level().getMinBuildHeight(), center.getY() - VERTICAL_RANGE_DOWN);
        int maxY = Math.min(steve.level().getMaxBuildHeight() - 1, center.getY() + VERTICAL_RANGE_UP);

        for (int y = minY; y <= maxY; y++) {
            for (int dx = -BLOCK_SCAN_RADIUS; dx <= BLOCK_SCAN_RADIUS; dx++) {
                for (int dz = -BLOCK_SCAN_RADIUS; dz <= BLOCK_SCAN_RADIUS; dz++) {
                    if (dx * dx + dz * dz > BLOCK_SCAN_RADIUS * BLOCK_SCAN_RADIUS) {
                        continue;
                    }

                    BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    Block block = steve.level().getBlockState(pos).getBlock();

                    if (block == Blocks.AIR) {
                        continue;
                    }

                    if (isInterestingBlock(block)) {
                        blockMap.computeIfAbsent(block, k -> new ArrayList<>()).add(pos);
                    }
                }
            }
        }

        // Sort each list by distance
        blockMap.values().forEach(list -> list.sort(Comparator.comparingDouble(center::distSqr)));

        return blockMap;
    }

    private static boolean isInterestingBlock(Block block) {
        // Include crafting tables, furnaces, logs, ores, chests
        return block == Blocks.CRAFTING_TABLE
            || block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER
            || block == Blocks.CHEST || block == Blocks.BARREL
            || block.toString().contains("_log")
            || block.toString().contains("_ore")
            || block == Blocks.WATER
            || block == Blocks.LAVA;
    }

    private static NearbyEntities scanEntities(SteveEntity steve) {
        AABB box = steve.getBoundingBox().inflate(ENTITY_SCAN_RADIUS);
        List<Entity> entities = steve.level().getEntities(steve, box);

        Map<EntityType<?>, List<Entity>> entitiesByType = new HashMap<>();

        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }
            if (living instanceof Player || living instanceof SteveEntity) {
                continue;
            }

            entitiesByType.computeIfAbsent(entity.getType(), k -> new ArrayList<>()).add(entity);
        }

        return new NearbyEntities(entitiesByType);
    }

    private static List<ContainerInfo> scanContainers(SteveEntity steve) {
        List<ContainerInfo> containers = new ArrayList<>();
        BlockPos center = steve.blockPosition();

        int minY = Math.max(steve.level().getMinBuildHeight(), center.getY() - VERTICAL_RANGE_DOWN);
        int maxY = Math.min(steve.level().getMaxBuildHeight() - 1, center.getY() + VERTICAL_RANGE_UP);

        for (int y = minY; y <= maxY; y++) {
            for (int dx = -BLOCK_SCAN_RADIUS; dx <= BLOCK_SCAN_RADIUS; dx++) {
                for (int dz = -BLOCK_SCAN_RADIUS; dz <= BLOCK_SCAN_RADIUS; dz++) {
                    if (dx * dx + dz * dz > BLOCK_SCAN_RADIUS * BLOCK_SCAN_RADIUS) {
                        continue;
                    }

                    BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    BlockEntity blockEntity = steve.level().getBlockEntity(pos);

                    if (blockEntity instanceof ChestBlockEntity) {
                        containers.add(new ContainerInfo(pos, "chest"));
                    } else if (blockEntity instanceof BarrelBlockEntity) {
                        containers.add(new ContainerInfo(pos, "barrel"));
                    } else if (blockEntity instanceof FurnaceBlockEntity) {
                        containers.add(new ContainerInfo(pos, "furnace"));
                    }
                }
            }
        }

        // Sort by distance
        containers.sort(Comparator.comparingDouble(c -> c.getPosition().distSqr(center)));

        return containers;
    }
}
