package com.steve.ai.skill;

import com.steve.ai.i18n.AgentLang;
import com.steve.ai.protocol.Observation;
import com.steve.ai.protocol.ToolCall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Building - turns "在我前面盖个房子" into a concrete structure request.
 *
 * <p>Keeps the planning trivial and lets {@code BuildStructureAction} do the real work
 * (procedural generation, template fallback, terrain flattening). Sensible default materials
 * are supplied whenever the player did not specify any, so the AI never stalls asking
 * "made of what?" for a request that has an obvious answer.</p>
 */
public final class BuildingSkill implements Skill {

    private static final List<String> DEFAULT_BLOCKS =
        List.of("oak_planks", "cobblestone");

    @Override
    public String name() {
        return "building";
    }

    @Override
    public boolean canHandle(SkillRequest request, Observation observation) {
        if (!"BUILD".equals(request.type())) {
            return false;
        }
        return SkillSupport.containsAny(request.lower(),
            "建", "盖", "造", "房子", "屋", "城堡", "塔", "build", "house", "castle", "tower");
    }

    @Override
    public SkillPlan plan(SkillRequest request, SkillContext context) {
        String structure = SkillSupport.findStructureToken(request.description());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("structure", structure);
        args.put("blocks", DEFAULT_BLOCKS);

        String narrative = AgentLang.t("agent.skill.build", structure);
        return SkillPlan.of(narrative, new ToolCall("build", args, narrative));
    }
}
