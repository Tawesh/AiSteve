package com.steve.ai.skill;

import com.steve.ai.i18n.AgentLang;
import com.steve.ai.protocol.Observation;
import com.steve.ai.protocol.ToolCall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Survival - eat, heal, and get out of trouble.
 *
 * <p>This is the skill that gives the AI something to do when nobody is telling it anything.
 * {@code Needs} raises the pressure, {@code GoalManager} turns it into a goal, and this skill
 * turns the goal into actions:</p>
 * <pre>
 * 饥饿 20 → SURVIVE "找点吃的" → SurvivalSkill
 *    ├ 背包里有食物   → use_item(food, self)        ← 立刻解决
 *    └ 背包里没有食物 → loot_container(item=food)   ← 先翻箱子
 *                      （失败则 Reflection 给出 explore 目标）
 * </pre>
 */
public final class SurvivalSkill implements Skill {

    @Override
    public String name() {
        return "survival";
    }

    @Override
    public boolean canHandle(SkillRequest request, Observation observation) {
        String lower = request.lower();
        boolean survivalType = "SURVIVE".equals(request.type());

        boolean hungry = SkillSupport.containsAny(lower, "吃", "饿", "食物", "food", "hungry", "eat");
        boolean heal = SkillSupport.containsAny(lower, "回血", "治疗", "heal", "睡觉", "sleep");
        boolean retreat = SkillSupport.containsAny(lower, "撤退", "逃", "安全", "危险", "retreat",
            "flee", "escape", "danger");

        if (hungry || heal || retreat) {
            return true;
        }

        // A SURVIVE goal with no keywords: decide from the numbers instead of the wording.
        return survivalType && observation != null && observation.self() != null
            && (observation.self().food() <= 8 || observation.self().healthFraction() < 0.4f);
    }

    @Override
    public SkillPlan plan(SkillRequest request, SkillContext context) {
        String lower = request.lower();
        Observation observation = context.observation();
        Observation.SelfState self = observation == null ? null : observation.self();

        boolean retreat = SkillSupport.containsAny(lower, "撤退", "逃", "安全", "危险", "retreat",
            "flee", "escape", "danger");
        double healthFraction = self == null ? 1.0 : self.healthFraction();
        // A Mob has no hunger bar; -1 means "not applicable", so fall back to "fed" and let
        // the keyword path (吃/饿) or the HUNGER need drive the behaviour instead.
        int food = self == null || self.food() < 0 ? 20 : self.food();

        // 1) Being chased beats being hungry.
        boolean inDanger = retreat
            || (observation != null && observation.hasHostileWithin(10.0) && healthFraction < 0.5f);
        if (inDanger) {
            return SkillPlan.of(AgentLang.t("agent.skill.survival.flee"),
                new ToolCall("flee", Map.of(), "low health"),
                new ToolCall("send_chat",
                    Map.of("message", AgentLang.t("agent.skill.survival.flee_chat")), "notify"));
        }

        // 2) Eat what we are carrying - the cheapest possible fix.
        if (food <= 14 || SkillSupport.containsAny(lower, "吃", "饿", "eat", "hungry")) {
            String foodItem = firstFoodInInventory(observation);
            if (foodItem != null) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("item", foodItem);
                args.put("self", true);
                return SkillPlan.of(AgentLang.t("agent.skill.survival.eat", foodItem),
                    new ToolCall("use_item", args, "eat"));
            }

            // 3) Nothing edible carried: go and find some, and be honest about it.
            Map<String, Object> lootArgs = new LinkedHashMap<>();
            lootArgs.put("item", "food");
            return SkillPlan.of(AgentLang.t("agent.skill.survival.find_food"),
                new ToolCall("open_container", lootArgs, "loot for food"),
                new ToolCall("send_chat",
                    Map.of("message", AgentLang.t("agent.skill.survival.no_food_chat")), "notify"));
        }

        return SkillPlan.nothing("no survival action needed");
    }

    /** First carried food item, or {@code null}. */
    private static String firstFoodInInventory(Observation observation) {
        if (observation == null) {
            return null;
        }
        for (String food : SkillSupport.FOOD_ITEMS) {
            if (observation.countItem(food) > 0) {
                return food;
            }
        }
        return null;
    }
}
