package com.steve.ai.skill;

import com.steve.ai.protocol.Observation;
import com.steve.ai.protocol.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combat - both "clear the monsters" and "kill me three sheep".
 *
 * <p>The cases produce different plans, because they are different jobs:</p>
 * <ul>
 *   <li><b>Hostiles</b> → fight and stop. No point collecting rotten flesh.</li>
 *   <li><b>Animals</b> → hunt <em>and then pick up the drops</em>, because raw meat on the
 *       ground is worthless to the player who asked for food.</li>
 *   <li><b>Defending</b> → attack the specific thing that hurt our player, and say so.</li>
 * </ul>
 */
public final class CombatSkill implements Skill {

    @Override
    public String name() {
        return "combat";
    }

    @Override
    public boolean canHandle(SkillRequest request, Observation observation) {
        String lower = request.lower();
        boolean combatVerb = SkillSupport.containsAny(lower,
            "打", "杀", "清", "战斗", "攻击", "attack", "kill", "fight", "保护", "defend", "报仇");
        boolean threatType = "PROTECT".equals(request.type()) || "SURVIVE".equals(request.type());

        if (threatType) {
            // 防御类目标总是接下来：玩家被打时不该花钱等模型。
            return true;
        }
        if (!combatVerb) {
            return false;
        }
        // 明确了打谁才接，否则交给 LLM 判断（避免误伤）
        return extractNamedTarget(request.description()) != null
            || SkillSupport.findEntityToken(request.description()) != null;
    }

    @Override
    public SkillPlan plan(SkillRequest request, SkillContext context) {
        String description = request.description();

        // 优先使用描述里点名的目标（"消灭 zombie" / "打 Steve"）
        String target = extractNamedTarget(description);
        if (target == null) {
            target = SkillSupport.findEntityToken(description);
        }

        boolean defending = "PROTECT".equals(request.type())
            || SkillSupport.containsAny(request.lower(), "保护", "defend", "报仇");

        // 没点名就用当前真正的威胁
        if (target == null) {
            Observation.EntityView hostile = context.observation() == null
                ? null : context.observation().nearestHostile();
            target = hostile != null ? hostile.type() : "hostile";
        }

        int count = SkillSupport.readCount(description,
            target.equals("hostile") ? 4 : 1);
        boolean animal = !defending && isAnimal(target);

        List<ToolCall> steps = new ArrayList<>();

        Map<String, Object> attackArgs = new LinkedHashMap<>();
        attackArgs.put("target", target);
        attackArgs.put("quantity", count);

        if (defending) {
            // 先说一声再动手 —— 真人队友会喊一句，而不是默默冲上去。
            steps.add(new ToolCall("send_chat",
                Map.of("message", "别怕，我来对付" + target + "！"), "提醒玩家"));
        }

        steps.add(new ToolCall("attack_entity", attackArgs, "攻击 " + target));

        if (animal) {
            // Killing something edible is only useful if the drop is collected.
            steps.add(new ToolCall("pickup_item", Map.of(), "捡起掉落物"));
        }

        String narrative;
        if (defending) {
            narrative = "保护玩家，消灭 " + target;
        } else if (animal) {
            narrative = "猎杀 " + count + " 只 " + target + " 并捡走掉落物";
        } else {
            narrative = "消灭 " + count + " 个 " + target;
        }
        return SkillPlan.of(narrative, steps);
    }

    /**
     * 从描述里取出被点名的目标。
     *
     * <p>形如 "保护玩家：消灭 zombie" / "打 Steve" / "kill the creeper"。
     * 只认短词条，避免把整句话误当成生物名。</p>
     */
    private static String extractNamedTarget(String description) {
        if (description == null) {
            return null;
        }
        for (String marker : new String[]{"消灭", "攻击", "杀掉", "打死", "打", "kill", "attack", "fight"}) {
            int index = description.indexOf(marker);
            if (index < 0) {
                continue;
            }
            String rest = description.substring(index + marker.length()).trim();
            rest = rest.replace("！", "").replace("!", "").replace("。", "").trim();

            // 去掉可能的中文量词尾巴，例如 "zombie 两只"
            int space = rest.indexOf(' ');
            if (space > 0) {
                rest = rest.substring(0, space);
            }
            if (!rest.isEmpty() && rest.length() <= 24) {
                return rest;
            }
        }
        return null;
    }

    private static boolean isAnimal(String type) {
        return switch (type) {
            case "sheep", "cow", "pig", "chicken", "rabbit" -> true;
            default -> false;
        };
    }
}
