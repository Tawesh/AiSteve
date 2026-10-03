package com.steve.ai.context;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.List;
import java.util.Map;

/**
 * Snapshot of entities near the AI.
 */
public class NearbyEntities {

    private final Map<EntityType<?>, List<Entity>> entitiesByType;

    public NearbyEntities(Map<EntityType<?>, List<Entity>> entitiesByType) {
        this.entitiesByType = Map.copyOf(entitiesByType);
    }

    public List<Entity> getByType(EntityType<?> type) {
        return entitiesByType.getOrDefault(type, List.of());
    }

    public Map<EntityType<?>, List<Entity>> getAll() {
        return entitiesByType;
    }
}
