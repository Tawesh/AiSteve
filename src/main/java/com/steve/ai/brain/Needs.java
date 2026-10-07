package com.steve.ai.brain;

import com.steve.ai.protocol.Observation;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The "欲望系统" - internal pressure that makes the AI do things nobody asked it to do.
 *
 * <p>Without this, an idle AI stands still until a human types something. With it, the same
 * chain the architecture document describes happens on its own:</p>
 * <pre>
 * 饥饿 20 → 想吃东西 → 寻找食物 → 找动物 / 找箱子 → 获取食物 → 吃
 * </pre>
 *
 * <p>Each need is a 0-100 <b>pressure</b> (higher = more urgent), re-derived from the latest
 * observation plus elapsed time. Pressures decay on their own so a satisfied need stops
 * shouting.</p>
 */
public final class Needs {

    /** The drives. */
    public enum Kind {
        HUNGER("饥饿"),
        SAFETY("安全"),
        SOCIAL("社交"),
        EXPLORATION("探索"),
        ACHIEVEMENT("成就"),
        RESOURCES("资源"),
        CURIOSITY("好奇");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Pressure above which a need is strong enough to generate a goal on its own. */
    private static final double DESIRE_THRESHOLD = 55.0;

    private final Map<Kind, Double> pressure = new EnumMap<>(Kind.class);

    /** Last tick each need generated a goal, so a desire cannot spam the stack. */
    private final Map<Kind, Long> lastFired = new EnumMap<>(Kind.class);

    public Needs() {
        // Baseline pressure per need. Curiosity/exploration start slightly warm so a brand-new
        // companion eventually goes for a walk on its own rather than standing still for five
        // minutes; survival needs start cold because nothing is wrong yet.
        for (Kind kind : Kind.values()) {
            pressure.put(kind, 20.0);
        }
        pressure.put(Kind.SAFETY, 0.0);
        pressure.put(Kind.EXPLORATION, 38.0);
        pressure.put(Kind.CURIOSITY, 36.0);
        pressure.put(Kind.ACHIEVEMENT, 10.0);
        pressure.put(Kind.HUNGER, 15.0);
    }

    /**
     * Recomputes pressures from the world.
     *
     * <p>Pure function of the observation plus the tick, so it is trivially testable and can
     * never drift into a state that contradicts reality.</p>
     */
    public void updateFrom(Observation observation, long tick) {
        if (observation == null || observation.self() == null) {
            return;
        }
        Observation.SelfState self = observation.self();

        // Hunger comes straight from the AI's real food bar now (see SteveEntity#getFoodData), so
        // this is an observation, not a guess. `food < 0` means hunger is switched off in the
        // config, in which case the need simply stays satisfied.
        if (self.food() >= 0) {
            set(Kind.HUNGER, clamp(100.0 - self.food() * 5.0));
        } else {
            set(Kind.HUNGER, 0.0);
        }

        // Safety: low health dominates; nearby hostiles add pressure.
        double healthDeficit = (1.0 - self.healthFraction()) * 100.0;
        double threat = 0.0;
        Observation.EntityView hostile = observation.nearestHostile();
        if (hostile != null) {
            // Hostile at 0 blocks = 60 pressure, at 16+ blocks = ~0.
            threat = clamp(60.0 * (1.0 - Math.min(1.0, hostile.distance() / 16.0)));
        }
        set(Kind.SAFETY, clamp(Math.max(healthDeficit, threat)));

        // Social: grows with loneliness, resets when someone is close.
        // (Rates are per tick; 20 ticks = 1 s. A need should start nagging after minutes,
        // not seconds, or the AI would be permanently demanding attention.)
        Observation.PlayerView player = observation.nearestPlayer();
        if (player == null) {
            grow(Kind.SOCIAL, 0.020);
        } else if (player.distance() > 24) {
            grow(Kind.SOCIAL, 0.010);
        } else {
            decay(Kind.SOCIAL, 25.0);
        }

        // Curiosity / exploration: drift upward over time.
        grow(Kind.CURIOSITY, 0.008);
        grow(Kind.EXPLORATION, 0.006);

        // Achievement: only interesting once the basics are handled.
        if (pressure.get(Kind.SAFETY) < 40 && pressure.get(Kind.HUNGER) < 40) {
            grow(Kind.ACHIEVEMENT, 0.004);
        }

        // Resources: pressure when the backpack is nearly empty, relief when it is stocked.
        if (observation.inventory().size() <= 2) {
            grow(Kind.RESOURCES, 0.010);
        } else if (observation.inventory().size() >= 12) {
            decay(Kind.RESOURCES, 30.0);
        }
    }

    private void set(Kind kind, double value) {
        pressure.put(kind, clamp(value));
    }

    private void grow(Kind kind, double amount) {
        pressure.put(kind, clamp(pressure.getOrDefault(kind, 0.0) + amount));
    }

    private void decay(Kind kind, double amount) {
        pressure.put(kind, clamp(pressure.getOrDefault(kind, 0.0) - amount));
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(100.0, v));
    }

    public double get(Kind kind) {
        return pressure.getOrDefault(kind, 0.0);
    }

    /**
     * Records that the AI actually ate something.
     *
     * <p>Hunger is now derived from the real food bar, which {@code FoodData#eat} has already
     * topped up by the time this runs - so there is nothing to decay here. The hook is kept
     * because it is the one place that knows "a meal just happened", which is what stops the AI
     * from immediately setting off to look for more food on the same tick.</p>
     */
    public void onAte() {
        // Satisfied for now; the next perception cycle re-derives it from the real food bar.
        set(Kind.HUNGER, 0.0);
    }

    /**
     * The strongest need worth acting on, if any.
     *
     * <p>Safety always wins when it is genuinely high, regardless of the numbers - dying is
     * strictly worse than being hungry.</p>
     */
    public Optional<Kind> topDesire() {
        if (get(Kind.SAFETY) >= DESIRE_THRESHOLD) {
            return Optional.of(Kind.SAFETY);
        }
        Kind best = null;
        double bestValue = DESIRE_THRESHOLD;
        for (Kind kind : Kind.values()) {
            if (kind == Kind.SAFETY) {
                continue;
            }
            double value = get(kind);
            if (value > bestValue) {
                bestValue = value;
                best = kind;
            }
        }
        return Optional.ofNullable(best);
    }

    /** True when this need may generate a new goal right now (rate-limited to once per 30 s). */
    public boolean canFire(Kind kind, long tick) {
        long last = lastFired.getOrDefault(kind, Long.MIN_VALUE);
        return tick - last > 600;
    }

    /** Marks a need as having just produced a goal, and relieves its pressure. */
    public void markFired(Kind kind, long tick) {
        lastFired.put(kind, tick);
        decay(kind, 30.0);
    }

    /** Maps a need to the kind of goal it should produce. */
    public static GoalType goalTypeFor(Kind kind) {
        return switch (kind) {
            case HUNGER, SAFETY -> GoalType.SURVIVE;
            case SOCIAL -> GoalType.SOCIAL;
            case EXPLORATION, CURIOSITY -> GoalType.EXPLORE;
            case RESOURCES -> GoalType.RESOURCE;
            case ACHIEVEMENT -> GoalType.BUILD;
        };
    }

    /** A default goal description for a need. */
    public static String goalDescriptionFor(Kind kind) {
        return switch (kind) {
            case HUNGER -> "找点吃的，别饿死";
            case SAFETY -> "保证自身安全";
            case SOCIAL -> "去和玩家待在一起";
            case EXPLORATION -> "四处探索看看有什么";
            case CURIOSITY -> "找个新地方转转";
            case RESOURCES -> "补充一些资源";
            case ACHIEVEMENT -> "做点有成就感的事";
        };
    }

    public String toPromptText() {
        StringBuilder sb = new StringBuilder("【内在需求】");
        boolean first = true;
        for (Kind kind : Kind.values()) {
            double value = get(kind);
            if (value < 25) {
                continue;   // only report needs that are actually nagging
            }
            if (!first) {
                sb.append('，');
            }
            sb.append(kind.label()).append(' ').append(Math.round(value));
            first = false;
        }
        return first ? "" : sb.append('\n').toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Needs{");
        for (Kind kind : Kind.values()) {
            sb.append(kind).append('=').append(Math.round(get(kind))).append(' ');
        }
        return sb.append('}').toString();
    }
}
