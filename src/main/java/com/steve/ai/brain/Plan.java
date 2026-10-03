package com.steve.ai.brain;

import com.steve.ai.protocol.ToolCall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A concrete answer to "how do I achieve this goal" - an ordered list of tool calls.
 *
 * <p>The important flag is {@link #needsLlm()}. A plan can come from two very different
 * places:</p>
 * <ul>
 *   <li><b>A skill</b> - deterministic, free, instant ("挖 8 个铁" is always
 *       {@code mine iron_ore quantity=8}).</li>
 *   <li><b>The LLM</b> - flexible but slow and costly. Used only when no skill matches.</li>
 * </ul>
 * <p>Modelling "I need to ask the model" as a <em>plan</em> rather than as an exception keeps
 * the agent loop linear and easy to reason about.</p>
 */
public final class Plan {

    private final List<ToolCall> steps;
    private final String narrative;
    private final boolean needsLlm;
    private final String source;

    private Plan(List<ToolCall> steps, String narrative, boolean needsLlm, String source) {
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.narrative = narrative == null ? "" : narrative;
        this.needsLlm = needsLlm;
        this.source = source;
    }

    /** A deterministic plan produced by a skill. */
    public static Plan fromSkill(String narrative, List<ToolCall> steps) {
        return new Plan(steps, narrative, false, "skill");
    }

    /** The agent must consult the LLM before it can act. */
    public static Plan needsLlm(String narrative) {
        return new Plan(List.of(), narrative, true, "llm");
    }

    /** A plan with nothing to do (the goal is already satisfied). */
    public static Plan nothing(String narrative) {
        return new Plan(List.of(), narrative, false, "noop");
    }

    public List<ToolCall> steps() {
        return steps;
    }

    public String narrative() {
        return narrative;
    }

    public boolean needsLlm() {
        return needsLlm;
    }

    /** {@code skill} / {@code llm} / {@code noop}. */
    public String source() {
        return source;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    @Override
    public String toString() {
        return "Plan{" + source + ", steps=" + steps.size() + ", needsLlm=" + needsLlm + "}";
    }
}
