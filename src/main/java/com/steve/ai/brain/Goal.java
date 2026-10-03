package com.steve.ai.brain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One goal on the AI's stack - the "欲望" layer made concrete.
 *
 * <p>A goal is intentionally not a plan: it describes <em>what</em> should be true
 * ("和陶哥找到钻石"), not <em>how</em>. Turning it into steps is {@link Planner}'s job, which is
 * what lets the same goal be approached by a deterministic {@code Skill} or by the LLM
 * depending on what is available.</p>
 */
public final class Goal {

    /** Lifecycle state. */
    public enum Status {
        /** Submitted, not yet being worked on. */
        PENDING,
        /** The current top goal. */
        ACTIVE,
        /** Achieved. */
        DONE,
        /** Abandoned, with a reason. */
        FAILED
    }

    private final String id;
    private final GoalType type;
    private final String description;
    private double priority;
    private Status status = Status.PENDING;
    /** Who/what created this goal: a player name, "needs", "reflection", "llm". */
    private final String source;
    private final String parentId;
    private final long createdAtTick;
    private final Map<String, Object> params;

    public Goal(String id, GoalType type, String description, double priority,
                String source, String parentId, long createdAtTick, Map<String, Object> params) {
        this.id = id;
        this.type = type;
        this.description = description;
        this.priority = priority;
        this.source = source == null ? "unknown" : source;
        this.parentId = parentId;
        this.createdAtTick = createdAtTick;
        this.params = params == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(params);
    }

    /** Creates a goal with its type's default priority. */
    public static Goal of(String id, GoalType type, String description, String source, long tick) {
        return new Goal(id, type, description, type.defaultPriority(), source, null, tick, null);
    }

    public String id() { return id; }
    public GoalType type() { return type; }
    public String description() { return description; }
    public double priority() { return priority; }
    public Status status() { return status; }
    public String source() { return source; }
    public String parentId() { return parentId; }
    public long createdAtTick() { return createdAtTick; }
    public Map<String, Object> params() { return Collections.unmodifiableMap(params); }

    public void setPriority(double priority) {
        this.priority = Math.max(0, Math.min(120, priority));
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    /** True while the goal still wants work (PENDING or ACTIVE). */
    public boolean isOpen() {
        return status == Status.PENDING || status == Status.ACTIVE;
    }

    public Object param(String key) {
        return params.get(key);
    }

    public String stringParam(String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public Goal withParam(String key, Object value) {
        params.put(key, value);
        return this;
    }

    /** One-line rendering for prompts and {@code /as goals}. */
    public String describe() {
        return String.format("#%s [%s] %s (优先级 %d, 来源 %s)",
            id, type.label(), description, Math.round(priority), source);
    }

    @Override
    public String toString() {
        return "Goal{" + id + " " + type + " '" + description + "' p=" + Math.round(priority)
            + " " + status + "}";
    }
}
