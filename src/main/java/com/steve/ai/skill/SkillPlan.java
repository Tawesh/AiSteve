package com.steve.ai.skill;

import com.steve.ai.protocol.ToolCall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a skill produces: a short narrative plus the ordered tool calls that implement it.
 *
 * <p>This is the "Skill → 多个 Tool" relationship from the architecture document made
 * explicit. Example, for {@code MiningSkill} given "挖 8 个铁":</p>
 * <pre>
 * narrative: "走到最近的铁矿并挖 8 个"
 * steps:     [ mine {block: iron_ore, quantity: 8} ]
 * </pre>
 * <p>A more demanding skill can emit several steps (get tool → move → dig → collect), which is
 * exactly what the document means by a skill composing tools.</p>
 */
public final class SkillPlan {

    private final String narrative;
    private final List<ToolCall> steps;

    private SkillPlan(String narrative, List<ToolCall> steps) {
        this.narrative = narrative == null ? "" : narrative;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    public static SkillPlan of(String narrative, List<ToolCall> steps) {
        return new SkillPlan(narrative, steps);
    }

    public static SkillPlan of(String narrative, ToolCall... steps) {
        return new SkillPlan(narrative, List.of(steps));
    }

    /** A plan that concludes there is nothing to do (the goal is already satisfied). */
    public static SkillPlan nothing(String narrative) {
        return new SkillPlan(narrative, List.of());
    }

    public String narrative() {
        return narrative;
    }

    public List<ToolCall> steps() {
        return steps;
    }

    @Override
    public String toString() {
        return "SkillPlan{'" + narrative + "', steps=" + steps.size() + "}";
    }
}
