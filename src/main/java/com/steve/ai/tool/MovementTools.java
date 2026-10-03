package com.steve.ai.tool;

import com.steve.ai.protocol.Permission;
import com.steve.ai.protocol.RiskLevel;
import com.steve.ai.protocol.ToolResult;
import com.steve.ai.protocol.ToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Movement tools - category 1 of 6.
 *
 * <p>Every entry here funnels through {@code MovementController}, which means every entry obeys
 * two invariants: <b>no teleporting</b>, and <b>long walks are queued rather than performed</b>.
 * The AI says "go to 200,64,-300"; the action queue spends the next N ticks actually doing it
 * under normal physics.</p>
 */
public final class MovementTools {

    private MovementTools() {
    }

    public static void register(ToolRegistry registry) {
        registry.register(moveTo());
        registry.register(followPlayer());
        registry.register(lookAt());
        registry.register(jump());
        registry.register(stop());
    }

    // ------------------------------------------------------------------

    private static Tool moveTo() {
        ToolSpec spec = ToolSpec.builder("move_to", "走到指定坐标（会寻路走过去，不会瞬移）")
            .category("movement")
            .required("x", "number", "目标 X")
            .required("y", "number", "目标 Y")
            .required("z", "number", "目标 Z")
            .permission(Permission.MOVEMENT)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, com.steve.ai.protocol.ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "x", "y", "z");
                if (bad != null) {
                    return bad;
                }
                int x = call.integer("x", 0);
                int y = call.integer("y", 0);
                int z = call.integer("z", 0);

                Map<String, Object> args = new LinkedHashMap<>();
                args.put("x", x);
                args.put("y", y);
                args.put("z", z);
                ctx.enqueue("pathfind", args);

                return ToolResult.scheduled("开始前往 " + x + "," + y + "," + z);
            }
        };
    }

    private static Tool followPlayer() {
        ToolSpec spec = ToolSpec.builder("follow_player", "保持在某个玩家身边")
            .category("movement")
            .param("player", "string", "玩家名；填 nearest 表示最近的玩家")
            .permission(Permission.MOVEMENT)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, com.steve.ai.protocol.ToolCall call) {
                String player = call.string("player", "nearest");
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("player", player);
                ctx.enqueue("follow", args);
                return ToolResult.scheduled("开始跟随 " + player);
            }
        };
    }

    private static Tool lookAt() {
        ToolSpec spec = ToolSpec.builder("look_at", "转头看向某个坐标或玩家（只转头，不移动）")
            .category("movement")
            .param("x", "number", "X")
            .param("y", "number", "Y")
            .param("z", "number", "Z")
            .param("player", "string", "或者指定看向某个玩家")
            .permission(Permission.MOVEMENT)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, com.steve.ai.protocol.ToolCall call) {
                String player = call.string("player");
                if (player != null && !player.isBlank()) {
                    var nearest = ctx.observation() == null ? null : ctx.observation().nearestPlayer();
                    if (nearest == null) {
                        return ToolResult.notFound("视野里没有玩家可以看");
                    }
                    ctx.movement().lookAt(nearest.x(), nearest.y(), nearest.z());
                    return ToolResult.ok("看向玩家 " + nearest.name());
                }
                if (!call.hasAll("x", "y", "z")) {
                    return ToolResult.badArguments("需要 x/y/z 坐标，或者 player");
                }
                ctx.movement().lookAt(call.decimal("x", 0), call.decimal("y", 0), call.decimal("z", 0));
                return ToolResult.ok("已转头看向目标坐标");
            }
        };
    }

    private static Tool jump() {
        ToolSpec spec = ToolSpec.builder("jump", "跳一下")
            .category("movement")
            .permission(Permission.MOVEMENT)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, com.steve.ai.protocol.ToolCall call) {
                ctx.movement().jump();
                return ToolResult.ok("跳了一下");
            }
        };
    }

    private static Tool stop() {
        ToolSpec spec = ToolSpec.builder("stop", "立刻停下，取消当前寻路")
            .category("movement")
            .permission(Permission.MOVEMENT)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, com.steve.ai.protocol.ToolCall call) {
                ctx.movement().stop();
                return ToolResult.ok("已经停下");
            }
        };
    }
}
