package com.steve.ai.skill;

import com.steve.ai.config.RuntimeSettings;
import com.steve.ai.i18n.AgentLang;
import com.steve.ai.protocol.Observation;
import com.steve.ai.protocol.ToolCall;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exploration - "去别处找找".
 *
 * <p>Exists because the AI can only ever use what is inside its scan radius, which makes a
 * village two hundred blocks away effectively non-existent. A real player says "I'll go and
 * have a look"; this skill is that sentence.</p>
 *
 * <p>Critically it emits <b>move-then-act</b>, not a bare walk: exploring only pays off if the
 * follow-up step re-evaluates the new surroundings, which {@code explore}'s {@code target}
 * parameter provides.</p>
 *
 * <p><b>Range.</b> The distance is clamped to the configured companion radius. Exploring is
 * "wander a bit while you are nearby", not "abandon your teammate and go sightseeing" - an AI
 * that vanishes over the horizon feels broken, not autonomous.</p>
 */
public final class ExplorationSkill implements Skill {

    private static final int DEFAULT_DISTANCE = 48;
    private static final int MIN_DISTANCE = 16;

    @Override
    public String name() {
        return "exploration";
    }

    @Override
    public boolean canHandle(SkillRequest request, Observation observation) {
        if (!"EXPLORE".equals(request.type())) {
            return false;
        }
        return SkillSupport.containsAny(request.lower(),
            "探索", "找", "去", "看看", "转转", "explore", "find", "search", "look");
    }

    @Override
    public SkillPlan plan(SkillRequest request, SkillContext context) {
        // Prefer an explicit target (entity or block) so exploration has a purpose.
        String target = SkillSupport.findEntityToken(request.description());
        if (target == null) {
            target = SkillSupport.findBlockToken(request.description());
        }

        // 以玩家为中心：探索半径不得超过跟随半径，否则"探索世界"就变成"把队友丢下跑路"。
        int requested = SkillSupport.readCount(request.description(), DEFAULT_DISTANCE);
        int roam = RuntimeSettings.roamRadius();
        int distance = Math.max(MIN_DISTANCE, Math.min(requested, roam));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("distance", distance);
        if (target != null) {
            args.put("target", target);
        }

        String narrative = target == null
            ? AgentLang.t("agent.skill.explore.around")
            : AgentLang.t("agent.skill.explore.find", target);
        return SkillPlan.of(narrative, new ToolCall("explore", args, narrative));
    }
}
