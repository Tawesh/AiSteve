package com.steve.ai.skill;

import com.steve.ai.protocol.Observation;
import com.steve.ai.protocol.ToolCall;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Social behaviour - following, handing things over, and answering people.
 *
 * <p>Two-way by design. A player saying "你跟着我" produces both a {@code follow_player} call
 * <em>and</em> a spoken acknowledgement, because a real teammate does not silently start
 * trailing you. That acknowledgement is the difference between "a script that moved" and
 * "someone who agreed".</p>
 */
public final class SocialSkill implements Skill {

    @Override
    public String name() {
        return "social";
    }

    @Override
    public boolean canHandle(SkillRequest request, Observation observation) {
        String lower = request.lower();

        // "给我 X"：必须能认出 X 是什么，或者玩家明确说了"全部/所有东西"。
        // 否则交给 LLM 判断 —— 把"给我"一律当成"把身上所有东西全交出去"，
        // 正是玩家反馈的"它把一堆多余的东西也塞给我"的来源。
        if (SkillSupport.containsAny(lower, "给我", "交给", "送我", "give me", "hand over")) {
            return SkillSupport.findBlockToken(request.description()) != null
                || SkillSupport.containsAny(lower, "全部", "所有", "东西", "all", "everything");
        }

        if (SkillSupport.containsAny(lower,
            "跟随", "跟着", "跟我", "过来", "来我", "回到",
            "follow", "come here", "come back")) {
            return true;
        }
        return "SOCIAL".equals(request.type());
    }

    @Override
    public SkillPlan plan(SkillRequest request, SkillContext context) {
        String lower = request.lower();
        Observation observation = context.observation();
        String player = resolvePlayer(request, observation);

        // --- Hand something over -------------------------------------------------
        if (SkillSupport.containsAny(lower, "给我", "交给", "送我", "give me", "hand over")) {
            String item = SkillSupport.findBlockToken(request.description());
            if (item == null) {
                item = "all";
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("item", item);
            args.put("count", SkillSupport.readCount(request.description(), 1));
            return SkillPlan.of("把 " + item + " 交给 " + safe(player),
                new ToolCall("give_item", args, "交东西给玩家"));
        }

        // --- Follow / come here / come back --------------------------------------
        // "回到 X 身边" 也走这条分支：回位本来就是"走过去跟着你"，不需要另写一套。
        if (SkillSupport.containsAny(lower, "跟随", "跟着", "跟我", "过来", "来我", "回到",
            "follow", "come here", "come back")) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("player", player == null ? "nearest" : player);
            return SkillPlan.of("跟着 " + safe(player),
                new ToolCall("follow_player", args, "跟随玩家"),
                new ToolCall("send_chat", Map.of("message", "好，我过来。"), "回应玩家"));
        }

        // --- Generic social goal: just be present and answer ----------------------
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("player", player == null ? "nearest" : player);
        return SkillPlan.of("待在 " + safe(player) + " 身边",
            new ToolCall("follow_player", args, "保持陪伴"));
    }

    /**
     * 判断这句话是冲着哪个玩家说的。
     *
     * <p>原来的做法是从描述里做字符串切分，遇到 "回到 陶哥 身边" 这类句子会切出 "回" 这种
     * 垃圾名字，于是跟随必然失败。现在改成：先看描述里有没有"当前确实在场"的玩家名，
     * 没有就默认最近的玩家 —— 对一个陪伴型 AI 来说，"跟着你"本来就是最合理的解释。</p>
     */
    private static String resolvePlayer(SkillRequest request, Observation observation) {
        String description = request.description() == null ? "" : request.description();

        if (observation != null) {
            for (Observation.PlayerView view : observation.players()) {
                if (!view.name().isBlank() && description.contains(view.name())) {
                    return view.name();
                }
            }
            if (observation.nearestPlayer() != null) {
                return observation.nearestPlayer().name();
            }
        }

        // 玩家不在感知范围内时的保守回退
        return SkillSupport.findPlayerName(request);
    }

    private static String safe(String player) {
        return player == null ? "玩家" : player;
    }
}
