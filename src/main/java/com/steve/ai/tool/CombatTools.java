package com.steve.ai.tool;

import com.steve.ai.protocol.Observation;
import com.steve.ai.protocol.Permission;
import com.steve.ai.protocol.RiskLevel;
import com.steve.ai.protocol.ToolCall;
import com.steve.ai.protocol.ToolResult;
import com.steve.ai.protocol.ToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Combat tools - category 4 of 6.
 *
 * <p>{@code flee} is worth a note: it computes a retreat vector from the nearest threat and
 * queues ordinary navigation. No teleporting to safety, no invulnerability frames - if the
 * escape fails, the AI dies like anyone else. That is the whole point of the project.</p>
 */
public final class CombatTools {

    /** How far to run when fleeing. */
    private static final double FLEE_DISTANCE = 16.0;

    private CombatTools() {
    }

    public static void register(ToolRegistry registry) {
        registry.register(attackEntity());
        registry.register(useWeapon());
        registry.register(flee());
    }

    // ------------------------------------------------------------------

    private static Tool attackEntity() {
        ToolSpec spec = ToolSpec.builder("attack_entity", "攻击/猎杀目标：可以是具体生物或 hostile（所有敌对生物）")
            .category("combat")
            .required("target", "string", "生物名，或 hostile")
            .param("quantity", "number", "杀几个，默认 1")
            .permission(Permission.COMBAT)
            .risk(RiskLevel.MEDIUM)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "target");
                if (bad != null) {
                    return bad;
                }
                String target = call.string("target");

                // Guard rail: never let the model target a player, unless that specific player
                // is on the temporary defence whitelist (they attacked the player we protect).
                if (isPlayerName(ctx, target) && !ctx.isAllowedPlayerTarget(target)) {
                    return ToolResult.denied("我不会攻击玩家（包括你）");
                }

                Map<String, Object> args = new LinkedHashMap<>();
                args.put("target", target);
                args.put("quantity", Math.max(1, call.integer("quantity", 1)));
                ctx.enqueue("attack", args);

                return ToolResult.scheduled("开始攻击 " + target);
            }
        };
    }

    private static Tool useWeapon() {
        ToolSpec spec = ToolSpec.builder("use_weapon", "用当前武器攻击某个目标（等价于 attack_entity）")
            .category("combat")
            .required("target", "string", "生物名，或 hostile")
            .permission(Permission.COMBAT)
            .risk(RiskLevel.MEDIUM)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "target");
                if (bad != null) {
                    return bad;
                }
                String target = call.string("target");
                if (isPlayerName(ctx, target) && !ctx.isAllowedPlayerTarget(target)) {
                    return ToolResult.denied("我不会攻击玩家");
                }
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("target", target);
                args.put("quantity", 1);
                ctx.enqueue("attack", args);
                return ToolResult.scheduled("用武器攻击 " + target);
            }
        };
    }

    private static Tool flee() {
        ToolSpec spec = ToolSpec.builder("flee", "从最近的威胁处撤离一段距离")
            .category("combat")
            .param("distance", "number", "撤离距离，默认 16")
            .permission(Permission.COMBAT)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                Observation observation = ctx.observation();
                Observation.EntityView threat = observation == null ? null : observation.nearestHostile();
                if (threat == null) {
                    return ToolResult.ok("附近没有威胁，不需要逃");
                }

                double distance = call.decimal("distance", FLEE_DISTANCE);

                // Retreat along the vector pointing away from the threat.
                double dx = ctx.steve().getX() - threat.x();
                double dz = ctx.steve().getZ() - threat.z();
                double length = Math.hypot(dx, dz);
                if (length < 0.001) {
                    // Standing on top of it: pick an arbitrary direction rather than divide by zero.
                    dx = 1;
                    dz = 0;
                    length = 1;
                }
                double targetX = ctx.steve().getX() + (dx / length) * distance;
                double targetZ = ctx.steve().getZ() + (dz / length) * distance;

                Map<String, Object> args = new LinkedHashMap<>();
                args.put("x", (int) Math.floor(targetX));
                args.put("y", (int) Math.floor(ctx.steve().getY()));
                args.put("z", (int) Math.floor(targetZ));
                ctx.enqueue("pathfind", args);

                return ToolResult.scheduled("从 " + threat.type() + " 处撤离");
            }
        };
    }

    /** True when the name matches a player currently in the world (or the generic "player"). */
    private static boolean isPlayerName(ToolContext ctx, String target) {
        if (target == null) {
            return false;
        }
        String lower = target.toLowerCase();
        if (lower.equals("player") || lower.equals("玩家") || lower.equals("me")
            || lower.equals("you") || lower.equals("我自己")) {
            return true;
        }
        Observation observation = ctx.observation();
        if (observation == null) {
            return false;
        }
        for (Observation.PlayerView player : observation.players()) {
            if (player.name().equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }
}
