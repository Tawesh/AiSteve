package com.steve.ai.skill;

import com.steve.ai.protocol.Observation;

/**
 * A complex capability built from several {@code Tool}s.
 *
 * <p>The architecture document's distinction, restated as code:</p>
 * <blockquote>
 *   Tool: "我能做什么？" — 原子。<br>
 *   Skill: 复杂能力 —— 内部会组合多个 Tool。
 * </blockquote>
 *
 * <p>Skills exist for a very practical reason: the most common goals should not cost a
 * language-model round trip. "挖 8 个铁" has exactly one sensible plan, so
 * {@link MiningSkill} produces it instantly and for free. The LLM is reserved for the
 * genuinely open-ended.</p>
 */
public interface Skill {

    /** Stable identifier, used in logs ({@code skill:mining}). */
    String name();

    /**
     * Whether this skill is a sensible way to satisfy the request.
     *
     * <p>Implementations should be conservative: returning {@code true} for something they only
     * half-understand produces worse behaviour than letting the LLM handle it.</p>
     */
    boolean canHandle(SkillRequest request, Observation observation);

    /** Builds the plan. Only called when {@link #canHandle} returned true. */
    SkillPlan plan(SkillRequest request, SkillContext context);
}
