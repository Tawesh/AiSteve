package com.steve.ai.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The AI's whole view of the world, reduced to something a language model can reason about.
 *
 * <p>This is the concrete answer to the architecture rule
 * <em>"千万不要把所有世界信息都塞给 LLM"</em>: instead of the 100000×100000 block world,
 * the model receives self state, nearby players/entities, nearby resources, inventory and the
 * last few events - already ranked by distance and capped in size.</p>
 *
 * <p>Immutable snapshot; built once per perception cycle by
 * {@code com.steve.ai.perception.PerceptionService}.</p>
 */
public final class Observation {

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    /** The AI's own state. */
    public static final class SelfState {
        private final float health;
        private final float maxHealth;
        private final int food;
        private final double x;
        private final double y;
        private final double z;
        private final String dimension;
        private final String biome;
        private final long gameTime;
        private final boolean day;
        private final boolean raining;
        private final boolean onGround;
        private final String mainHand;

        public SelfState(float health, float maxHealth, int food, double x, double y, double z,
                         String dimension, String biome, long gameTime, boolean day,
                         boolean raining, boolean onGround, String mainHand) {
            this.health = health;
            this.maxHealth = maxHealth;
            this.food = food;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.biome = biome;
            this.gameTime = gameTime;
            this.day = day;
            this.raining = raining;
            this.onGround = onGround;
            this.mainHand = mainHand;
        }

        public float health() { return health; }
        public float maxHealth() { return maxHealth; }
        public int food() { return food; }
        public double x() { return x; }
        public double y() { return y; }
        public double z() { return z; }
        public String dimension() { return dimension; }
        public String biome() { return biome; }
        public long gameTime() { return gameTime; }
        public boolean isDay() { return day; }
        public boolean isRaining() { return raining; }
        public boolean onGround() { return onGround; }
        public String mainHand() { return mainHand; }

        /** Fraction of max health remaining (0..1). */
        public float healthFraction() {
            return maxHealth <= 0 ? 1f : health / maxHealth;
        }

        /** Cheap human-readable position, e.g. {@code 123,64,-230}. */
        public String posString() {
            return (int) x + "," + (int) y + "," + (int) z;
        }
    }

    /** A nearby player. */
    public record PlayerView(String name, double distance, double x, double y, double z) {}

    /** A nearby living entity. */
    public record EntityView(String type, double distance, boolean hostile, double x, double y, double z) {}

    /** One inventory stack. */
    public record ItemView(String name, int count) {}

    /** A nearby interesting block. */
    public record BlockView(String type, int count, double distance, int x, int y, int z) {}

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    private final SelfState self;
    private final List<PlayerView> players;
    private final List<EntityView> hostiles;
    private final List<EntityView> animals;
    private final List<ItemView> inventory;
    private final List<BlockView> resources;
    private final List<BlockView> containers;
    private final List<String> recentEvents;
    private final long capturedAtTick;

    private Observation(Builder b) {
        this.self = b.self;
        this.players = List.copyOf(b.players);
        this.hostiles = List.copyOf(b.hostiles);
        this.animals = List.copyOf(b.animals);
        this.inventory = List.copyOf(b.inventory);
        this.resources = List.copyOf(b.resources);
        this.containers = List.copyOf(b.containers);
        this.recentEvents = List.copyOf(b.recentEvents);
        this.capturedAtTick = b.capturedAtTick;
    }

    public SelfState self() { return self; }
    public List<PlayerView> players() { return players; }
    public List<EntityView> hostiles() { return hostiles; }
    public List<EntityView> animals() { return animals; }
    public List<ItemView> inventory() { return inventory; }
    public List<BlockView> resources() { return resources; }
    public List<BlockView> containers() { return containers; }
    public List<String> recentEvents() { return recentEvents; }
    public long capturedAtTick() { return capturedAtTick; }

    /** Closest player by distance, or {@code null} when nobody is around. */
    public PlayerView nearestPlayer() {
        return players.isEmpty() ? null : players.get(0);
    }

    /** True when any hostile is within {@code radius} blocks. */
    public boolean hasHostileWithin(double radius) {
        for (EntityView e : hostiles) {
            if (e.distance() <= radius) {
                return true;
            }
        }
        return false;
    }

    /** Closest hostile, or {@code null}. */
    public EntityView nearestHostile() {
        return hostiles.isEmpty() ? null : hostiles.get(0);
    }

    /** Total number of a given item across the inventory. */
    public int countItem(String itemName) {
        int total = 0;
        for (ItemView view : inventory) {
            if (view.name().equals(itemName)) {
                total += view.count();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Prompt rendering
    // ------------------------------------------------------------------

    /**
     * Renders the observation as the compact Chinese block used inside the agent prompt.
     *
     * <p>Everything is capped: 5 players, 6 hostiles, 8 animals, 12 inventory stacks,
     * 8 resources, 5 containers, 5 events. Token cost stays flat no matter how busy the
     * world is.</p>
     */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();

        if (self != null) {
            sb.append("【自身状态】\n");
            sb.append("- 生命 ").append((int) self.health()).append('/').append((int) self.maxHealth())
              .append("，饥饿 ").append(self.food() < 0 ? "不适用" : String.valueOf(self.food()))
              .append('\n');
            sb.append("- 位置 ").append(self.posString())
              .append("，维度 ").append(self.dimension())
              .append("，群系 ").append(self.biome()).append('\n');
            sb.append("- ").append(self.isDay() ? "白天" : "夜晚")
              .append(self.isRaining() ? "，正在下雨" : "")
              .append("，手上：").append(self.mainHand()).append('\n');
        }

        if (inventory.isEmpty()) {
            sb.append("【背包】空\n");
        } else {
            sb.append("【背包】");
            appendList(sb, inventory, 12, v -> v.name() + "x" + v.count());
            sb.append('\n');
        }

        if (!players.isEmpty()) {
            sb.append("【附近玩家】");
            appendList(sb, players, 5, v -> v.name() + " 距离" + r(v.distance()) + "m");
            sb.append('\n');
        }

        if (!hostiles.isEmpty()) {
            sb.append("【敌对生物】");
            appendList(sb, hostiles, 6, v -> v.type() + " 距离" + r(v.distance()) + "m");
            sb.append('\n');
        }

        if (!animals.isEmpty()) {
            sb.append("【附近动物】");
            appendList(sb, animals, 8, v -> v.type() + " 距离" + r(v.distance()) + "m");
            sb.append('\n');
        }

        if (!containers.isEmpty()) {
            sb.append("【附近容器】");
            appendList(sb, containers, 5,
                v -> v.type() + " 距离" + r(v.distance()) + "m @[" + v.x() + "," + v.y() + "," + v.z() + "]");
            sb.append('\n');
        }

        if (!resources.isEmpty()) {
            sb.append("【附近资源】");
            appendList(sb, resources, 8,
                v -> v.type() + "x" + v.count() + " 最近" + r(v.distance()) + "m");
            sb.append('\n');
        }

        if (!recentEvents.isEmpty()) {
            sb.append("【最近事件】\n");
            for (String event : recentEvents) {
                sb.append("- ").append(event).append('\n');
            }
        }

        return sb.toString();
    }

    private static <T> void appendList(StringBuilder sb, List<T> list, int max,
                                       java.util.function.Function<T, String> renderer) {
        int shown = 0;
        for (T item : list) {
            if (shown >= max) {
                sb.append(" …(共").append(list.size()).append(')');
                break;
            }
            if (shown > 0) {
                sb.append("，");
            }
            sb.append(renderer.apply(item));
            shown++;
        }
    }

    private static int r(double d) {
        return (int) Math.round(d);
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder - perception fills this in piece by piece. */
    public static final class Builder {
        private SelfState self;
        private final List<PlayerView> players = new ArrayList<>();
        private final List<EntityView> hostiles = new ArrayList<>();
        private final List<EntityView> animals = new ArrayList<>();
        private final List<ItemView> inventory = new ArrayList<>();
        private final List<BlockView> resources = new ArrayList<>();
        private final List<BlockView> containers = new ArrayList<>();
        private final List<String> recentEvents = new ArrayList<>();
        private long capturedAtTick;

        public Builder self(SelfState self) { this.self = self; return this; }
        public Builder players(List<PlayerView> v) { players.clear(); players.addAll(v); return this; }
        public Builder hostiles(List<EntityView> v) { hostiles.clear(); hostiles.addAll(v); return this; }
        public Builder animals(List<EntityView> v) { animals.clear(); animals.addAll(v); return this; }
        public Builder inventory(List<ItemView> v) { inventory.clear(); inventory.addAll(v); return this; }
        public Builder resources(List<BlockView> v) { resources.clear(); resources.addAll(v); return this; }
        public Builder containers(List<BlockView> v) { containers.clear(); containers.addAll(v); return this; }
        public Builder recentEvents(List<String> v) { recentEvents.clear(); recentEvents.addAll(v); return this; }
        public Builder capturedAtTick(long tick) { this.capturedAtTick = tick; return this; }

        public Observation build() {
            return new Observation(this);
        }
    }

    @Override
    public String toString() {
        return "Observation{players=" + players.size() + ", hostiles=" + hostiles.size()
            + ", resources=" + resources.size() + ", inventory=" + inventory.size() + "}";
    }

    /** Empty observation, used before the first perception cycle has run. */
    public static Observation empty() {
        return new Builder().build();
    }

    /** Unmodifiable helper for callers that need a defensive copy of the view lists. */
    public static <T> List<T> freeze(List<T> in) {
        return Collections.unmodifiableList(new ArrayList<>(in));
    }
}
