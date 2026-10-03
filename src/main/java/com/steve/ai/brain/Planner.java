package com.steve.ai.brain;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.MemoryManager;
import com.steve.ai.protocol.Observation;
import com.steve.ai.skill.Skill;
import com.steve.ai.skill.SkillContext;
import com.steve.ai.skill.SkillPlan;
import com.steve.ai.skill.SkillRegistry;
import com.steve.ai.skill.SkillRequest;

import java.util.Optional;

/**
 * Turns a {@link Goal} into a {@link Plan}.
 *
 * <p>The layering matters: <b>skill first, LLM second</b>.</p>
 * <ol>
 *   <li>Ask the {@link SkillRegistry} whether a known skill covers this goal. If so the plan is
 *       deterministic, instant and free.</li>
 *   <li>Otherwise return {@link Plan#needsLlm} and let {@code AgentLoop} consult the model.</li>
 * </ol>
 *
 * <p>This single decision is what keeps the token bill sane: the twenty most common requests
 * ("挖铁"、"砍树"、"跟着我"、"建房子") never reach a paid API at all, while the open-ended ones
 * ("用我给的这些材料搭个能看的东西") still get real intelligence.</p>
 */
public final class Planner {

    private final SkillRegistry skills;

    public Planner(SkillRegistry skills) {
        this.skills = skills == null ? SkillRegistry.createDefault() : skills;
    }

    public SkillRegistry skills() {
        return skills;
    }

    /**
     * Builds the plan for a goal.
     *
     * @param goal        what to achieve
     * @param steve       the entity (skills may need to resolve names)
     * @param observation latest world view; may be {@code null} before the first perception cycle
     * @param memory      agent memory
     */
    public Plan plan(Goal goal, SteveEntity steve, Observation observation, MemoryManager memory) {
        SkillRequest request = SkillRequest.of(goal.description(), goal.type().name(), goal.params());

        Optional<Skill> skill = skills.find(request, observation);
        if (skill.isPresent()) {
            SkillPlan skillPlan = skill.get().plan(request, new SkillContext(steve, observation, memory));
            String narrative = "skill:" + skill.get().name() + " - " + skillPlan.narrative();

            if (skillPlan.steps().isEmpty()) {
                // The skill looked and decided there is nothing to do.
                return Plan.nothing(narrative);
            }
            return Plan.fromSkill(narrative, skillPlan.steps());
        }

        // No skill claims this - the LLM is genuinely needed.
        return Plan.needsLlm(goal.description());
    }
}
