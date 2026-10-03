package com.steve.ai.skill;

import com.steve.ai.protocol.Observation;
import com.steve.ai.protocol.ToolCall;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mining and woodcutting - the most requested thing a player ever asks a bot to do.
 *
 * <p>Deterministic on purpose. "挖 16 个铁" has exactly one sensible plan, so paying for an LLM
 * round trip to discover it would be waste. The underlying {@code mine} action already handles
 * the hard parts (walking there, staircase-digging to the right Y level, branch mining,
 * felling a whole tree), so this skill's job is only to turn fuzzy language into exact
 * parameters.</p>
 */
public final class MiningSkill implements Skill {

    @Override
    public String name() {
        return "mining";
    }

    @Override
    public boolean canHandle(SkillRequest request, Observation observation) {
        if (!"RESOURCE".equals(request.type()) && !"TASK".equals(request.type())) {
            return false;
        }
        String lower = request.lower();
        boolean miningVerb = SkillSupport.containsAny(lower,
            "挖", "砍", "采", "收集", "矿", "mine", "chop", "gather", "collect", "dig");
        // A block must be identifiable, otherwise the LLM should handle the ambiguity.
        return miningVerb && SkillSupport.findBlockToken(request.description()) != null;
    }

    @Override
    public SkillPlan plan(SkillRequest request, SkillContext context) {
        String block = SkillSupport.findBlockToken(request.description());
        if (block == null) {
            block = "oak_log";
        }
        boolean wood = block.endsWith("_log");
        int defaultCount = wood ? 8 : 8;
        int count = SkillSupport.readCount(request.description(), defaultCount);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("block", block);
        args.put("quantity", count);

        String narrative = (wood ? "去砍 " : "去挖 ") + count + " 个 " + block;
        return SkillPlan.of(narrative, new ToolCall("break_block", args, narrative));
    }
}
