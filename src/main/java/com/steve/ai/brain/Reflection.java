package com.steve.ai.brain;

import com.steve.ai.i18n.AgentLang;
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
                AgentLang.t("agent.reflect.missing_tool", message),
                AgentLang.t("agent.reflect.missing_next", goal.description()),
                false, GoalType.RESOURCE,
                AgentLang.t("agent.reflect.prepare", extractMissing(message, target)));

            case ToolResult.NOT_FOUND -> Outcome.of(
                AgentLang.t("agent.reflect.not_found", message),
                AgentLang.t("agent.reflect.search_elsewhere"),
                false, GoalType.EXPLORE, AgentLang.t("agent.reflect.go_find", target));

            case ToolResult.DENIED -> new Outcome(
                AgentLang.t("agent.reflect.denied", message),
                AgentLang.t("agent.reflect.use_legal"),
                true, null, null, 0.7);

            case ToolResult.BAD_ARGUMENTS -> new Outcome(
                AgentLang.t("agent.reflect.bad_args", message),
                AgentLang.t("agent.reflect.replan_args"),
                true, null, null, 0.4);

            case ToolResult.UNSUPPORTED -> new Outcome(
                AgentLang.t("agent.reflect.unsupported", message),
                AgentLang.t("agent.reflect.alternative"),
                true, null, null, 0.5);

            case ToolResult.UNKNOWN_TOOL -> new Outcome(
                AgentLang.t("agent.reflect.unknown_tool", call.tool()),
                AgentLang.t("agent.reflect.only_listed"),
                true, null, null, 0.3);

            case ToolResult.ERROR -> new Outcome(
                AgentLang.t("agent.reflect.error", message),
                AgentLang.t("agent.reflect.try_other"),
                true, null, null, 0.6);

            default -> new Outcome(
                AgentLang.t("agent.reflect.step_failed", message),
                AgentLang.t("agent.reflect.retry_other"),
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
