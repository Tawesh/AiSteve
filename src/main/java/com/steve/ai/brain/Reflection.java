package com.steve.ai.brain;

import com.steve.ai.protocol.Observation;
import com.steve.ai.protocol.ToolCall;
import com.steve.ai.protocol.ToolResult;

/**
 * The "反思层" - turns a raw tool failure into a reason and a corrected intention.
 *
 * <p>The architecture document's example, made concrete:</p>
 * <pre>
 * 目标：获得钻石
 * 失败原因：当前没有铁镐       ← 不是一句 "Tool failed"
 * 下一步：寻找铁 → 制作铁镐
 * 更新 Goal：制作铁镐
 * </pre>
 *
 * <p>Handled with a status-code table first (free, instant, reliable) and only escalated to
 * the LLM for genuinely ambiguous cases. That split is what keeps
 * {@code Planning → Execution → Evaluation → Reflection → Replanning} affordable.</p>
 */
public final class Reflection {

    /**
     * The lesson learned from one failed step.
     *
     * @param cause         why it failed, in plain language
     * @param nextStep      what should happen instead
     * @param needsLlm      true when the situation is too ambiguous for a rule
     * @param suggestedType goal type that should be submitted, or {@code null}
     * @param suggestedGoal description for that goal, or {@code null}
     * @param importance    how memorable this is (feeds {@code EpisodicMemory})
     */
    public record Outcome(String cause, String nextStep, boolean needsLlm,
                          GoalType suggestedType, String suggestedGoal, double importance) {

        static Outcome of(String cause, String nextStep, boolean needsLlm,
                          GoalType type, String goal) {
            return new Outcome(cause, nextStep, needsLlm, type, goal, 0.6);
        }
    }

    /**
     * Analyses a failed tool call.
     *
     * @param goal   the goal the step belonged to
     * @param call   the tool call that failed
     * @param result its failure result
     * @param observation current world view (for context when escalating to the LLM)
     */
    public Outcome reflect(Goal goal, ToolCall call, ToolResult result, Observation observation) {
        String status = result.status();
        String message = result.message();
        String target = targetOf(call);

        return switch (status) {
            case ToolResult.MISSING_TOOL -> Outcome.of(
                "缺少必要物品/工具：" + message,
                "先设法获得缺失的东西，再回来继续「" + goal.description() + "」",
                false, GoalType.RESOURCE, "准备材料：" + extractMissing(message, target));

            case ToolResult.NOT_FOUND -> Outcome.of(
                "附近找不到目标：" + message,
                "换个地方找，或者用工具搜索更大范围",
                false, GoalType.EXPLORE, "去别处寻找 " + target);

            case ToolResult.DENIED -> new Outcome(
                "这个能力被权限限制，不能执行：" + message,
                "换一种符合游戏规则的正常做法",
                true, null, null, 0.7);

            case ToolResult.BAD_ARGUMENTS -> new Outcome(
                "参数不对：" + message,
                "重新规划，给出合法参数",
                true, null, null, 0.4);

            case ToolResult.UNSUPPORTED -> new Outcome(
                "这个能力目前实现不了：" + message,
                "改用别的手段，或者告诉玩家做不到",
                true, null, null, 0.5);

            case ToolResult.UNKNOWN_TOOL -> new Outcome(
                "我调用了一个不存在的工具：" + call.tool(),
                "只能使用工具清单里列出的能力",
                true, null, null, 0.3);

            case ToolResult.ERROR -> new Outcome(
                "执行出错：" + message,
                "换一种可行的方法继续"+ (observation != null ? "" : ""),
                true, null, null, 0.6);

            default -> new Outcome(
                "步骤没有成功：" + message,
                "根据当前状态换一种可行的方法",
                true, null, null, 0.5);
        };
    }

    /** Best-effort extraction of "what were we trying to affect" for the follow-up goal. */
    private static String targetOf(ToolCall call) {
        for (String key : new String[]{"block", "item", "target", "resource", "player", "structure"}) {
            String value = call.string(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return call.tool();
    }

    /**
     * Pulls the item name out of "Missing X in inventory" style messages.
     *
     * <p>Falls back to the raw message so the follow-up goal is never empty.</p>
     */
    private static String extractMissing(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        int colon = message.indexOf(':');
        if (colon >= 0 && colon + 1 < message.length()) {
            String tail = message.substring(colon + 1).trim();
            if (!tail.isEmpty()) {
                return tail;
            }
        }
        return fallback;
    }
}
