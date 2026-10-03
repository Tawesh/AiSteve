package com.steve.ai.brain;

import com.steve.ai.protocol.Observation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The goal stack - "the core of the whole agent" in the architecture document's words.
 *
 * <p>Its whole reason to exist is to stop the LLM being asked "下一步干什么?" on a timer.
 * Goals persist across many ticks and many tool calls; the model is only consulted when a goal
 * genuinely needs decomposing, or when something went wrong.</p>
 *
 * <p>Priorities are dynamic. {@link #reevaluate} re-derives them from {@link Needs} and the
 * latest observation every cycle, which is how "血量 20%" ends up meaning
 * "生存 100 / 探索 0" without anyone hard-coding that transition.</p>
 */
public final class GoalManager {

    /** Autonomy switch, set from config at construction time. */
    private boolean autonomyEnabled = true;

    /** Enables/disables generating goals from internal needs. */
    public void setAutonomyEnabled(boolean enabled) {
        this.autonomyEnabled = enabled;
    }

    /** When health drops below this fraction, everything except survival is de-prioritised. */
    private static final double CRITICAL_HEALTH = 0.35;
    /** Food level considered dangerously low. */
    private static final int CRITICAL_FOOD = 6;

    private final Map<String, Goal> goals = new LinkedHashMap<>();
    private int sequence = 0;

    // ------------------------------------------------------------------
    // Stack operations
    // ------------------------------------------------------------------

    /** Creates and submits a goal, returning the stored instance. */
    public Goal submit(GoalType type, String description, String source, long tick) {
        if (type == null) {
            type = GoalType.TASK;
        }
        if (description == null || description.isBlank()) {
            description = type.label();
        }
        if (alreadyOpen(type, description)) {
            return null;
        }
        Goal goal = new Goal("g" + (++sequence), type, description, type.defaultPriority(),
            source, null, tick, null);
        goals.put(goal.id(), goal);
        return goal;
    }

    /** Submits a pre-built goal (used when a parent/child relationship matters). */
    public Goal submit(Goal draft, long tick) {
        if (draft == null || alreadyOpen(draft.type(), draft.description())) {
            return null;
        }
        Goal stored = new Goal("g" + (++sequence), draft.type(), draft.description(),
            draft.priority(), draft.source(), draft.parentId(), tick, draft.params());
        goals.put(stored.id(), stored);
        return stored;
    }

    /** All still-wanted goals, highest priority first. */
    public List<Goal> open() {
        List<Goal> result = new ArrayList<>();
        for (Goal goal : goals.values()) {
            if (goal.isOpen()) {
                result.add(goal);
            }
        }
        result.sort(Comparator.comparingDouble(Goal::priority).reversed());
        return result;
    }

    /**
     * The goal to work on right now: the highest-priority open one.
     *
     * <p>Side effect: marks it ACTIVE and everything else PENDING, so {@code /as goals} always
     * shows exactly one focus.</p>
     */
    public Optional<Goal> current() {
        List<Goal> open = open();
        if (open.isEmpty()) {
            return Optional.empty();
        }
        Goal top = open.get(0);
        for (Goal goal : open) {
            goal.setStatus(goal == top ? Goal.Status.ACTIVE : Goal.Status.PENDING);
        }
        return Optional.of(top);
    }

    /** The highest-priority goal of a given type, if open. */
    public Optional<Goal> openOfType(GoalType type) {
        return open().stream().filter(g -> g.type() == type).findFirst();
    }

    public void complete(String id, String note) {
        Goal goal = goals.get(id);
        if (goal != null) {
            goal.setStatus(Goal.Status.DONE);
            if (note != null) {
                goal.withParam("result", note);
            }
        }
    }

    public void fail(String id, String reason) {
        Goal goal = goals.get(id);
        if (goal != null) {
            goal.setStatus(Goal.Status.FAILED);
            if (reason != null) {
                goal.withParam("failure", reason);
            }
        }
    }

    /** Drops finished/failed goals (keeps the map from growing forever). */
    public void prune() {
        goals.values().removeIf(goal -> !goal.isOpen());
    }

    public void clear() {
        goals.clear();
    }

    public int openCount() {
        return open().size();
    }

    // ------------------------------------------------------------------
    // Dynamic re-evaluation
    // ------------------------------------------------------------------

    /**
     * Adjusts the stack to match the world.
     *
     * <p>Called every agent cycle. Three jobs:</p>
     * <ol>
     *   <li><b>Emergency override.</b> Low health / hunger / nearby hostiles inject a
     *       SURVIVE goal at priority 100 and squash long-term goals.</li>
     *   <li><b>Autonomy.</b> If nothing is pending and a need is nagging, generate a goal
     *       from it - this is what produces behaviour with no player instruction.</li>
     *   <li><b>Bookkeeping.</b> Drop stale IDLE goals and finished entries.</li>
     * </ol>
     */
    public void reevaluate(Observation observation, Needs needs, long tick) {
        prune();

        if (observation == null || observation.self() == null) {
            return;
        }
        Observation.SelfState self = observation.self();

        boolean criticalHealth = self.healthFraction() <= CRITICAL_HEALTH;
        boolean criticalFood = self.food() >= 0 && self.food() <= CRITICAL_FOOD;
        Observation.EntityView hostile = observation.nearestHostile();
        boolean immediateThreat = hostile != null && hostile.distance() <= 8.0;

        // 1) Emergency override
        if (criticalHealth || criticalFood || immediateThreat) {
            String description = immediateThreat
                ? "应对附近的" + hostile.type()
                : (criticalHealth ? "脱离危险、恢复生命" : "找点吃的");
            GoalType type = immediateThreat ? GoalType.PROTECT : GoalType.SURVIVE;

            Goal emergency = openOfType(type).orElse(null);
            if (emergency == null) {
                emergency = submit(type, description, "needs", tick);
            }
            if (emergency != null) {
                emergency.setPriority(100);
            }

            // Squash everything that is not about staying alive.
            for (Goal goal : open()) {
                if (goal.type() == GoalType.EXPLORE || goal.type() == GoalType.BUILD
                    || goal.type() == GoalType.RESOURCE || goal.type() == GoalType.IDLE) {
                    goal.setPriority(Math.min(goal.priority(), 5));
                }
            }
        } else {
            // Healthy again: let survival goals retire so they stop re-triggering.
            for (Goal goal : open()) {
                if (goal.type() == GoalType.SURVIVE && goal.priority() >= 100) {
                    goal.setPriority(20);
                }
            }

            // Not in danger -> respect the leash. Roaming away from the player is what makes an
            // AI companion feel like it "left to go do its own thing"; a real teammate stays close.
            // Deliberately skipped while something urgent is happening: getting back to the
            // player matters far less than not dying.
            applyRoamLimit(observation, tick);
        }

        // 2) Autonomy: act on the strongest unmet need
        boolean hasRealWork = open().stream()
            .anyMatch(g -> g.type() != GoalType.IDLE);
        if (!hasRealWork && autonomyEnabled) {
            needs.topDesire().ifPresent(kind -> {
                if (!needs.canFire(kind, tick)) {
                    return;
                }
                GoalType type = Needs.goalTypeFor(kind);
                Goal created = submit(type, Needs.goalDescriptionFor(kind), "needs", tick);
                if (created != null) {
                    needs.markFired(kind, tick);
                }
            });
        } else {
            // 3) Bookkeeping: an idle goal is pointless once anything real exists.
            for (Goal goal : open()) {
                if (goal.type() == GoalType.IDLE) {
                    complete(goal.id(), "有更重要的事了");
                }
            }
        }
    }

    /**
     * 让 AI 待在玩家附近。
     *
     * <p>超出 {@code roamRadius} 时插入一个高优先级的"回家"目标，并把其它目标压下去。
     * 走的是正常的目标→计划→工具链路（最终由 {@code follow_player} 完成），
     * 所以它仍然是"走回去"，不是瞬移。</p>
     */
    private void applyRoamLimit(Observation observation, long tick) {
        Observation.PlayerView nearest = observation.nearestPlayer();
        if (nearest == null) {
            return;
        }

        int radius = com.steve.ai.config.RuntimeSettings.roamRadius();
        if (nearest.distance() <= radius) {
            return;
        }

        Goal home = null;
        for (Goal goal : open()) {
            if (goal.type() == GoalType.SOCIAL && goal.description().startsWith(RETURN_HOME_PREFIX)) {
                home = goal;
                break;
            }
        }
        if (home == null) {
            home = submit(GoalType.SOCIAL,
                RETURN_HOME_PREFIX + nearest.name() + " 身边", "roam", tick);
        }
        if (home == null) {
            return;
        }

        // 高于一切常规任务（采集 50 / 建造 45 / 探索 30），低于紧急生存（100）。
        home.setPriority(90);
        for (Goal goal : open()) {
            if (goal != home && goal.priority() < 100) {
                goal.setPriority(Math.min(goal.priority(), 10));
            }
        }
    }

    /** "回家"目标统一用这个前缀，方便识别与去重。 */
    public static final String RETURN_HOME_PREFIX = "回到";

    private boolean alreadyOpen(GoalType type, String description) {        for (Goal goal : goals.values()) {
            if (!goal.isOpen()) {
                continue;
            }
            if (goal.type() != type) {
                continue;
            }
            String existing = goal.description();
            if (existing.equalsIgnoreCase(description)
                || existing.contains(description)
                || description.contains(existing)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Prompt / diagnostics
    // ------------------------------------------------------------------

    public String toPromptText() {
        Optional<Goal> current = current();
        if (current.isEmpty()) {
            return "【当前目标】没有明确目标，可以自行决定做点什么。\n";
        }
        Goal goal = current.get();
        StringBuilder sb = new StringBuilder("【当前目标】");
        sb.append(goal.description()).append("（类型 ").append(goal.type().label())
          .append("，优先级 ").append(Math.round(goal.priority())).append("）\n");

        List<Goal> others = open();
        if (others.size() > 1) {
            sb.append("【其他目标】");
            for (int i = 0; i < others.size() && i < 4; i++) {
                Goal other = others.get(i);
                if (other == goal) {
                    continue;
                }
                sb.append(other.type().label()).append(':').append(other.description())
                  .append("(").append(Math.round(other.priority())).append(") ");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Multi-line status for {@code /as goals}. */
    public String describe() {
        List<Goal> open = open();
        if (open.isEmpty()) {
            return "（没有目标）";
        }
        StringBuilder sb = new StringBuilder();
        for (Goal goal : open) {
            sb.append(goal.describe()).append('\n');
        }
        return sb.toString().trim();
    }
}
