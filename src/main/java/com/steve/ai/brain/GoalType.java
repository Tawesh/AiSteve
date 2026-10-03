package com.steve.ai.brain;

/**
 * The kinds of goal the AI can hold, each with a default priority.
 *
 * <p>Priorities are <b>starting points, not constants</b>. {@link GoalManager} re-derives them
 * every cycle from {@link Needs} and the current {@code Observation}, because a real player
 * does not keep "explore" at 30 when they are on two hearts:</p>
 * <pre>
 * 平时：        探索 60  资源 50  社交 40
 * 血量 20%：    生存 100  逃跑 95   探索 0
 * </pre>
 */
public enum GoalType {

    /** Stay alive: eat, heal, retreat, hide. Always allowed to outrank everything. */
    SURVIVE("活下来", 100),

    /** Protect a player (or the AI) from a specific threat. */
    PROTECT("保护", 90),

    /** An explicit instruction from a human. */
    TASK("完成任务", 70),

    /** Gather raw materials (mine, chop, hunt, fish). */
    RESOURCE("采集资源", 50),

    /** Construct something. */
    BUILD("建造", 45),

    /** Keep a relationship healthy: follow, help, hand things over. */
    SOCIAL("社交", 40),

    /** Go and see what is out there. */
    EXPLORE("探索", 30),

    /** Nothing pressing - wander, look around, idle chatter. */
    IDLE("闲着", 5);

    private final String label;
    private final double defaultPriority;

    GoalType(String label, double defaultPriority) {
        this.label = label;
        this.defaultPriority = defaultPriority;
    }

    /** Chinese label used in prompts and {@code /as goals}. */
    public String label() {
        return label;
    }

    public double defaultPriority() {
        return defaultPriority;
    }

    /** Parses a model- or config-supplied type name, falling back to {@link #TASK}. */
    public static GoalType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return TASK;
        }
        String key = raw.trim().toLowerCase();
        for (GoalType type : values()) {
            if (type.name().toLowerCase().equals(key)) {
                return type;
            }
        }
        // Tolerate the labels / synonyms a model might emit.
        return switch (key) {
            case "生存", "survive", "survival", "heal" -> SURVIVE;
            case "保护", "protect", "defend", "combat" -> PROTECT;
            case "任务", "task", "command" -> TASK;
            case "资源", "resource", "mine", "gather", "collect" -> RESOURCE;
            case "建造", "build", "building" -> BUILD;
            case "社交", "social", "follow", "help" -> SOCIAL;
            case "探索", "explore", "exploration" -> EXPLORE;
            case "闲", "idle", "none" -> IDLE;
            default -> TASK;
        };
    }
}
