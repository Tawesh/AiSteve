package com.steve.ai.tool;

import com.steve.ai.protocol.Permission;
import com.steve.ai.protocol.RiskLevel;
import com.steve.ai.protocol.ToolCall;
import com.steve.ai.protocol.ToolResult;
import com.steve.ai.protocol.ToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Social tools - category 6 of 6.
 *
 * <p>Chat is a first-class capability, not a debug channel. A player who never hears anything
 * back is playing with a tool, not a companion - so saying "等一下，前面有苦力怕" while the
 * combat skill engages is modelled as a real action, composed by the same planner as mining.</p>
 */
public final class SocialTools {

    private SocialTools() {
    }

    public static void register(ToolRegistry registry) {
        registry.register(sendChat());
        registry.register(askPlayer());
        registry.register(rememberPlayer());
    }

    // ------------------------------------------------------------------

    private static Tool sendChat() {
        ToolSpec spec = ToolSpec.builder("send_chat", "在聊天框说话")
            .category("social")
            .required("message", "string", "要说的话")
            .permission(Permission.SOCIAL)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "message");
                if (bad != null) {
                    return bad;
                }
                String message = call.string("message");
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("message", message);
                ctx.enqueue("say", args);
                return ToolResult.scheduled("说：" + message);
            }
        };
    }

    private static Tool askPlayer() {
        ToolSpec spec = ToolSpec.builder("ask_player", "向玩家索要物品或请求帮助")
            .category("social")
            .required("message", "string", "要问的话")
            .permission(Permission.SOCIAL)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "message");
                if (bad != null) {
                    return bad;
                }
                String message = call.string("message");
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("message", message);
                ctx.enqueue("say", args);
                return ToolResult.scheduled("向玩家求助：" + message);
            }
        };
    }

    private static Tool rememberPlayer() {
        ToolSpec spec = ToolSpec.builder("remember_player", "记下关于某个玩家的事（长期记住）")
            .category("social")
            .required("player", "string", "玩家名")
            .required("fact", "string", "要记住的事，如“喜欢建房子”")
            .permission(Permission.SOCIAL)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "player", "fact");
                if (bad != null) {
                    return bad;
                }
                String player = call.string("player");
                String fact = call.string("fact");

                if (ctx.memory() != null) {
                    ctx.memory().social().remember(player, fact);
                }
                return ToolResult.ok("记住了：" + player + " " + fact);
            }
        };
    }
}
