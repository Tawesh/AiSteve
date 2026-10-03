package com.steve.ai.memory;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Long-term memory of places the AI has discovered, persisted with the entity.
 *
 * <p>Without this the AI rediscovers the same village every session and cannot act on
 * knowledge it already has ("there is a village 200 blocks north" is exactly the kind of
 * thing a player remembers). Entries survive world reloads because they are written to NBT
 * alongside the rest of the entity state.</p>
 *
 * <p>Deliberately small and cheap: a handful of named landmark categories, each holding the
 * most recent positions, plus a de-duplication radius so standing next to a village for a
 * while does not create hundreds of identical notes.</p>
 */
public class WorldMemory {

    /** Categories we track. Free-form strings are allowed too. */
    public static final String VILLAGE = "village";
    public static final String WATER = "water";
    public static final String FARM = "farm";
    public static final String FOREST = "forest";
    public static final String CAVE = "cave";
    public static final String ORE = "ore";

    /** Positions closer than this to an existing note of the same kind are not re-recorded. */
    private static final double DEDUPE_RADIUS = 24.0;
    /** Cap per category, oldest entries are dropped first. */
    private static final int MAX_PER_CATEGORY = 12;

    private static final String NBT_KEY = "WorldMemory";

    /**
     * Landmarks per category, in discovery order.
     *
     * <p>Not thread-safe by design - it is only touched from the server thread.</p>
     */
    private final Map<String, List<Landmark>> landmarks = new LinkedHashMap<>();

    /** A remembered place. */
    public static class Landmark {
        public final String kind;
        public final BlockPos pos;
        /** Game time (in ticks) when it was discovered. */
        public final long discoveredAt;

        public Landmark(String kind, BlockPos pos, long discoveredAt) {
            this.kind = kind;
            // Copy into a fresh BlockPos so the stored value is immutable in practice.
            this.pos = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
            this.discoveredAt = discoveredAt;
        }
    }

    /**
     * Records a landmark, ignoring near-duplicates.
     *
     * @return true when a new note was actually added
     */
    public boolean record(String kind, BlockPos pos, long gameTime) {
        if (kind == null || pos == null) {
            return false;
        }
        String key = kind.toLowerCase();

        List<Landmark> list = landmarks.computeIfAbsent(key, k -> new ArrayList<>());

        for (Landmark existing : list) {
            if (existing.pos.distSqr(pos) <= DEDUPE_RADIUS * DEDUPE_RADIUS) {
                return false;   // already known
            }
        }

        list.add(new Landmark(key, pos, gameTime));
        while (list.size() > MAX_PER_CATEGORY) {
            list.remove(0);
        }
        return true;
    }

    /** @return the closest remembered landmark of this kind, if any */
    public Optional<BlockPos> findNearest(String kind, BlockPos from) {
        List<Landmark> list = landmarks.get(kind == null ? "" : kind.toLowerCase());
        if (list == null || list.isEmpty() || from == null) {
            return Optional.empty();
        }
        return list.stream()
            .min(Comparator.comparingDouble(l -> l.pos.distSqr(from)))
            .map(l -> l.pos);
    }

    public boolean knows(String kind) {
        List<Landmark> list = landmarks.get(kind == null ? "" : kind.toLowerCase());
        return list != null && !list.isEmpty();
    }

    public List<Landmark> get(String kind) {
        List<Landmark> list = landmarks.get(kind == null ? "" : kind.toLowerCase());
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * Describes known places relative to a position, for the LLM prompt.
     *
     * @param from the AI's current position (to compute distances)
     * @return e.g. {@code "village at [120,64,-40] (95m away), water at [..] (12m away)"}
     *         or {@code "none"} when nothing is remembered
     */
    public String describe(BlockPos from) {
        if (landmarks.isEmpty()) {
            return "none";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Landmark>> entry : landmarks.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }

            // Closest one per category keeps the prompt short and useful
            Landmark nearest = entry.getValue().stream()
                .min(Comparator.comparingDouble(l ->
                    from == null ? 0 : l.pos.distSqr(from)))
                .orElse(null);
            if (nearest == null) {
                continue;
            }

            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(entry.getKey()).append(" at ").append(nearest.pos.toShortString());
            if (from != null) {
                int distance = (int) Math.sqrt(nearest.pos.distSqr(from));
                sb.append(" (").append(distance).append("m away)");
            }
        }

        return sb.length() == 0 ? "none" : sb.toString();
    }

    public void clear() {
        landmarks.clear();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public void saveToNBT(CompoundTag parentTag) {
        ListTag list = new ListTag();
        for (List<Landmark> perKind : landmarks.values()) {
            for (Landmark landmark : perKind) {
                CompoundTag tag = new CompoundTag();
                tag.putString("Kind", landmark.kind);
                tag.putInt("X", landmark.pos.getX());
                tag.putInt("Y", landmark.pos.getY());
                tag.putInt("Z", landmark.pos.getZ());
                tag.putLong("At", landmark.discoveredAt);
                list.add(tag);
            }
        }
        parentTag.put(NBT_KEY, list);
    }

    public void loadFromNBT(CompoundTag parentTag) {
        landmarks.clear();
        if (!parentTag.contains(NBT_KEY)) {
            return;
        }

        ListTag list = parentTag.getList(NBT_KEY, 10); // 10 = TAG_Compound
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            String kind = tag.getString("Kind");
            BlockPos pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
            long at = tag.getLong("At");
            if (!kind.isEmpty()) {
                landmarks.computeIfAbsent(kind, k -> new ArrayList<>())
                    .add(new Landmark(kind, pos, at));
            }
        }
    }
}
