package com.steve.ai.tool;

import com.steve.ai.SteveMod;
import com.steve.ai.i18n.AgentLang;
import com.steve.ai.protocol.RiskLevel;
import com.steve.ai.protocol.ToolCall;
import com.steve.ai.protocol.ToolResult;
import com.steve.ai.protocol.ToolSpec;

/**
 * The permission gate: the single place where "may the AI do this?" is decided.
 *
 * <p>Three checks, in order, before anything runs:</p>
 * <ol>
 *   <li><b>Exists?</b> - unknown names are rejected rather than silently ignored.</li>
 *   <li><b>Forbidden?</b> - {@link RiskLevel#FORBIDDEN} tools (teleport, spawn items, gamemode,
 *       kill players) are refused even if the model somehow learns their name.</li>
 *   <li><b>Permitted?</b> - the tool's {@code Permission} must be in the context's grant.</li>
 * </ol>
 *
 * <p>This is what makes the architecture rule true in practice: <em>AI 的能力边界由 Agent
 * Runtime 决定，而不是由 Prompt 决定。</em> A prompt injection that convinces the model to
 * "call teleport_to_player" gets a {@code denied} result, not a teleport.</p>
 */
public final class ToolDispatcher {

    private final ToolRegistry registry;

    public ToolDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    public ToolRegistry registry() {
        return registry;
    }

    public ToolResult dispatch(ToolContext context, ToolCall call) {
        if (call == null || call.tool() == null || call.tool().isBlank()) {
            return ToolResult.badArguments(AgentLang.t("agent.tool.no_tool_name"));
        }

        Tool tool = registry.get(call.tool());
        if (tool == null) {
            SteveMod.LOGGER.warn("[Agent/Tool] 未知工具: {}", call.tool());
            return ToolResult.unknownTool(call.tool());
        }

        ToolSpec spec = tool.spec();

        if (spec.risk() == RiskLevel.FORBIDDEN) {
            SteveMod.LOGGER.warn("[Agent/Tool] 拒绝执行被禁止的工具: {}", spec.name());
            return ToolResult.denied(AgentLang.t("agent.tool.forbidden", spec.name()));
        }

        if (!context.allows(spec.permission())) {
            SteveMod.LOGGER.warn("[Agent/Tool] 权限不足: {} 需要 {}",
                spec.name(), spec.permission());
            return ToolResult.denied(AgentLang.t("agent.tool.no_permission",
                spec.name(), spec.permission()));
        }

        try {
            ToolResult result = tool.invoke(context, call);
            if (result == null) {
                // A tool returning null would corrupt the reflection layer - treat as an error.
                return ToolResult.error(AgentLang.t("agent.tool.no_result", spec.name()));
            }
            SteveMod.LOGGER.debug("[Agent/Tool] {} -> {}", spec.name(), result);
            return result;
        } catch (Exception e) {
            SteveMod.LOGGER.error("[Agent/Tool] 工具 '{}' 执行异常", spec.name(), e);
            return ToolResult.error(AgentLang.t("agent.tool.crashed",
                spec.name(), e.getMessage()));
        }
    }
}
