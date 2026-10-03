package com.steve.ai.perception;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.protocol.Observation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Observes nearby living entities and sorts them into the three buckets the brain actually
 * cares about: players (social), hostiles (threat) and animals (food).
 *
 * <p>Entity scans are cheap relative to block scans, so this runs on the fast perception
 * cycle. Everything is distance-sorted and capped so "who is closest" is always answer #1.</p>
 */
public final class EntityObserver {

    /** Cap per bucket - the model never needs the 40th cow. */
    private static final int MAX_PER_BUCKET = 10;

    private EntityObserver() {
    }

    /** Result bundle: the three entity categories. */
    public record Result(List<Observation.PlayerView> players,
                         List<Observation.EntityView> hostiles,
                         List<Observation.EntityView> animals) {
    }

    public static Result observe(SteveEntity steve, double radius) {
        AABB box = steve.getBoundingBox().inflate(radius);
        List<Entity> found = steve.level().getEntities(steve, box);

        List<Observation.PlayerView> players = new ArrayList<>();
        List<Observation.EntityView> hostiles = new ArrayList<>();
        List<Observation.EntityView> animals = new ArrayList<>();

        for (Entity entity : found) {
            if (!(entity instanceof LivingEntity) || entity.isRemoved()) {
                continue;
            }

            double distance = steve.distanceTo(entity);

            if (entity instanceof Player player) {
                if (player.isSpectator()) {
                    continue;
                }
                players.add(new Observation.PlayerView(
                    player.getName().getString(), distance,
                    entity.getX(), entity.getY(), entity.getZ()));
                continue;
            }

            String type = entityName(entity);

            if (entity instanceof Enemy) {
                hostiles.add(new Observation.EntityView(
                    type, distance, true, entity.getX(), entity.getY(), entity.getZ()));
            } else if (entity instanceof Animal) {
                animals.add(new Observation.EntityView(
                    type, distance, false, entity.getX(), entity.getY(), entity.getZ()));
            }
        }

        players.sort(Comparator.comparingDouble(Observation.PlayerView::distance));
        hostiles.sort(Comparator.comparingDouble(Observation.EntityView::distance));
        animals.sort(Comparator.comparingDouble(Observation.EntityView::distance));

        return new Result(
            cap(players, MAX_PER_BUCKET),
            cap(hostiles, MAX_PER_BUCKET),
            cap(animals, MAX_PER_BUCKET));
    }

    private static <T> List<T> cap(List<T> in, int max) {
        return in.size() <= max ? in : new ArrayList<>(in.subList(0, max));
    }

    /** Human/LLM friendly entity id such as {@code sheep} instead of {@code entity.minecraft.sheep}. */
    private static String entityName(Entity entity) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key != null ? key.getPath() : entity.getType().toString();
    }
}
