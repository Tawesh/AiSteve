package com.steve.ai.brain;

import com.steve.ai.memory.MemoryManager;

import java.util.Locale;

/**
 * Turns player chat into relationship state and, when appropriate, a goal.
 *
 * <p>This is the module that answers "真人玩家不再是 AI 的控制器，而是 AI 世界里的另一个玩家".
 * A message is not a command to be parsed - it is an interaction with someone the AI knows:</p>
 * <pre>
 * PLAYER_CHAT("陶哥", "帮我挖点铁")
 *    │
 *    ├─ SocialMemory: 陶哥 互动+1, 信任+0.05, 记下"想让AI挖铁"
 *    └─ 建议目标: RESOURCE "帮陶哥挖铁"
 * </pre>
 *
 * <p>Deliberately rule-based rather than LLM-based: chat arrives on the game thread and needs
 * an immediate answer path. The LLM is still consulted for anything ambiguous - this layer
 * only decides whether it is worth waking it, and what the AI should remember.</p>
 */
public final class SocialSystem {

    /** What one player message means to the agent. */
    public record SocialIntent(String speaker, String message, boolean task,
                               GoalType suggestedType, String suggestedGoal,
                               String note, double trustDelta) {

        static SocialIntent chat(String speaker, String message, String note) {
            return new SocialIntent(speaker, message, false, null, null, note, 0.02);
        }

        static SocialIntent task(String speaker, String message, GoalType type, String goal,
                                 String note) {
            return new SocialIntent(speaker, message, true, type, goal, note, 0.05);
        }
    }

    /**
     * Analyses one chat line and records it in social memory.
     *
     * @param speaker     player name
     * @param message     raw chat text
     * @param tick        game time
     * @param memory      social/episodic memory to update
     */
    public SocialIntent analyze(String speaker, String message, long tick, MemoryManager memory) {
        String text = message == null ? "" : message.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        SocialIntent intent = classify(speaker, text, lower);

        if (memory != null) {
            memory.social().recordInteraction(speaker, intent.note(), intent.trustDelta(), tick);

            // Durable preferences ("我喜欢建房子") are worth keeping as relationship facts.
            if (text.contains("喜欢") || text.contains("不喜欢") || text.contains("讨厌")) {
                memory.social().remember(speaker, text);
            }

            // Anything a player actually asked for is worth remembering as an event.
            if (intent.task()) {
                memory.episodic().record(tick,
                    speaker + " 让我：" + text,
                    speaker, "进行中", 0.5);
            }
        }

        return intent;
    }

    private SocialIntent classify(String speaker, String text, String lower) {
        if (text.isBlank()) {
            return SocialIntent.chat(speaker, text, "打招呼");
        }

        // --- Social / following -------------------------------------------------
        if (containsAny(lower, "跟着我", "跟我", "跟随", "follow me", "follow")) {
            return SocialIntent.task(speaker, text, GoalType.SOCIAL,
                "跟随 " + speaker, "想让我跟着他");
        }
        if (containsAny(lower, "过来", "来我这", "come here", "come")) {
            return SocialIntent.task(speaker, text, GoalType.SOCIAL,
                "到 " + speaker + " 身边", "让我过去");
        }

        // --- Combat -------------------------------------------------------------
        if (containsAny(lower, "打", "杀", "清怪", "赶走", "保护", "attack", "kill", "defend")) {
            return SocialIntent.task(speaker, text, GoalType.PROTECT,
                "帮 " + speaker + " 战斗", "需要战斗协助");
        }

        // --- Building -----------------------------------------------------------
        if (containsAny(lower, "建", "盖", "造", "build")) {
            return SocialIntent.task(speaker, text, GoalType.BUILD,
                "帮 " + speaker + " 建造：" + text, "想建东西");
        }

        // --- Resource gathering -------------------------------------------------
        if (containsAny(lower, "挖", "砍", "采", "找点", "找些", "收集", "mine", "chop", "gather",
            "collect")) {
            return SocialIntent.task(speaker, text, GoalType.RESOURCE,
                "帮 " + speaker + " 采集：" + text, "需要资源");
        }

        // --- Hand something over ------------------------------------------------
        if (containsAny(lower, "给我", "送我", "交给我", "give me", "hand over")) {
            return SocialIntent.task(speaker, text, GoalType.TASK,
                "把东西交给 " + speaker, "想从我这里拿东西");
        }

        // --- Find / explore -----------------------------------------------------
        if (containsAny(lower, "找", "去那边", "探索", "find", "explore", "search")) {
            return SocialIntent.task(speaker, text, GoalType.EXPLORE,
                text.replace("你", "").trim(), "想让我去找东西");
        }

        // --- Everything else is conversation ------------------------------------
        return SocialIntent.chat(speaker, text, text.length() > 20 ? "聊了几句" : text);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** True when the message looks like it wants work done (used to decide whether to wake the LLM). */
    public boolean looksLikeTask(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "挖", "砍", "采", "建", "盖", "造", "打", "杀", "跟", "给",
            "找", "来", "做", "拿", "带", "帮我", "mine", "craft", "build", "follow", "attack",
            "give", "find", "gather", "bring");
    }
}
