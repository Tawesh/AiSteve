package com.steve.ai.context;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.Map;

/**
 * Immutable snapshot of the AI's internal state.
 */
public class SteveState {

    private final BlockPos position;
    private final float health;
    private final float maxHealth;
    private final Map<Item, Integer> inventory;

    public SteveState(BlockPos position, float health, float maxHealth, Map<Item, Integer> inventory) {
        this.position = position;
        this.health = health;
        this.maxHealth = maxHealth;
        this.inventory = Map.copyOf(inventory);
    }

    public BlockPos getPosition() {
        return position;
    }

    public float getHealth() {
        return health;
    }

    public float getMaxHealth() {
        return maxHealth;
    }

    public Map<Item, Integer> getInventory() {
        return inventory;
    }
}
