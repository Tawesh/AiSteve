package com.steve.ai.tool;

import com.steve.ai.i18n.AgentLang;
import com.steve.ai.protocol.Permission;
import com.steve.ai.protocol.RiskLevel;
import com.steve.ai.protocol.ToolCall;
import com.steve.ai.protocol.ToolResult;
import com.steve.ai.protocol.ToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Interaction tools - category 2 of 6: changing the world.
 *
 * <p>All of these are thin adapters onto the existing, already-hardened actions. That is the
 * point of the refactor: the layered architecture above describes intent, while the mining and
 * placing logic that took months to get right keeps running underneath untouched.</p>
 *
 * <p>Note that {@code break_block} never spawns a tool. If the AI lacks a pickaxe it mines by
 * hand, exactly like a player would - see the item-honesty rules in the changelog.</p>
 */
public final class InteractionTools {

    private InteractionTools() {
    }

    public static void register(ToolRegistry registry) {
        registry.register(breakBlock());
        registry.register(placeBlock());
        registry.register(openContainer());
        registry.register(pickupItem());
        registry.register(useItem());
        registry.register(build());
        registry.register(explore());
        registry.register(fish());
        registry.register(farm());
    }

    // ------------------------------------------------------------------

    private static Tool breakBlock() {
        ToolSpec spec = ToolSpec.builder("break_block", "采集方块：走到最近的这类方块并挖掉收进背包")
            .category("interaction")
            .required("block", "string", "方块名，如 oak_log / iron_ore")
            .param("quantity", "number", "数量，默认 1")
            .permission(Permission.WORLD_WRITE)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "block");
                if (bad != null) {
                    return bad;
                }
                String block = call.string("block");
                int quantity = Math.max(1, call.integer("quantity", 1));

                Map<String, Object> args = new LinkedHashMap<>();
                args.put("block", block);
                args.put("quantity", quantity);
                ctx.enqueue("mine", args);

                return ToolResult.scheduled(AgentLang.t("agent.tool.break.start", quantity, block));
            }
        };
    }

    private static Tool placeBlock() {
        ToolSpec spec = ToolSpec.builder("place_block", "在指定坐标放置一个方块（必须背包里真有这个方块）")
            .category("interaction")
            .required("block", "string", "方块名")
            .required("x", "number", "X")
            .required("y", "number", "Y")
            .required("z", "number", "Z")
            .permission(Permission.WORLD_WRITE)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "block", "x", "y", "z");
                if (bad != null) {
                    return bad;
                }
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("block", call.string("block"));
                args.put("x", call.integer("x", 0));
                args.put("y", call.integer("y", 0));
                args.put("z", call.integer("z", 0));
                ctx.enqueue("place", args);

                // The action itself will refuse (and say so) when the block is not carried.
                return ToolResult.scheduled(AgentLang.t("agent.tool.place.start",
                    call.string("block"),
                    call.integer("x", 0), call.integer("y", 0), call.integer("z", 0)));
            }
        };
    }

    private static Tool openContainer() {
        ToolSpec spec = ToolSpec.builder("open_container", "走过去打开附近的箱子/木桶/熔炉并取走东西")
            .category("interaction")
            .param("item", "string", "只取这一种，或 food/all")
            .param("limit", "number", "最多取几件")
            .permission(Permission.WORLD_WRITE)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                Map<String, Object> args = new LinkedHashMap<>();
                if (call.has("item")) {
                    args.put("item", call.string("item"));
                }
                if (call.has("limit")) {
                    args.put("limit", call.integer("limit", 8));
                }
                ctx.enqueue("loot_container", args);
                return ToolResult.scheduled(AgentLang.t("agent.tool.container.start"));
            }
        };
    }

    private static Tool pickupItem() {
        ToolSpec spec = ToolSpec.builder("pickup_item", "捡起附近地上的掉落物")
            .category("interaction")
            .param("item", "string", "只捡这一种；不填则全捡")
            .permission(Permission.WORLD_WRITE)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                Map<String, Object> args = new LinkedHashMap<>();
                if (call.has("item")) {
                    args.put("item", call.string("item"));
                }
                ctx.enqueue("pickup", args);
                return ToolResult.scheduled(AgentLang.t("agent.tool.pickup.start"));
            }
        };
    }

    private static Tool useItem() {
        ToolSpec spec = ToolSpec.builder("use_item", "使用物品：对实体、对方块、或对自己（吃东西）")
            .category("interaction")
            .required("item", "string", "物品名，如 flint_and_steel")
            .param("target", "string", "目标实体名（对实体使用时）")
            .param("block", "array", "目标方块坐标 [x,y,z]")
            .param("self", "boolean", "对自己使用，例如吃东西")
            .permission(Permission.WORLD_WRITE)
            .risk(RiskLevel.MEDIUM)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "item");
                if (bad != null) {
                    return bad;
                }
                String item = call.string("item");

                Map<String, Object> args = new LinkedHashMap<>();
                args.put("item", item);
                if (call.has("target")) {
                    args.put("target", call.string("target"));
                }
                if (call.has("block")) {
                    args.put("block", call.arguments().get("block"));
                }
                if (call.flag("self", false)) {
                    args.put("self", true);
                }
                ctx.enqueue("use_item", args);

                return ToolResult.scheduled(AgentLang.t("agent.tool.use.start", item));
            }
        };
    }

    private static Tool build() {
        ToolSpec spec = ToolSpec.builder("build", "建造一个建筑（程序化生成；会自行平整地面）")
            .category("interaction")
            .required("structure", "string", "建筑类型：house/castle/tower/barn/modern")
            .param("blocks", "array", "可用材料，如 [\"oak_planks\",\"cobblestone\"]")
            .param("x", "number", "建造中心 X（可选，默认玩家前方）")
            .param("y", "number", "建造中心 Y（可选）")
            .param("z", "number", "建造中心 Z（可选）")
            .permission(Permission.WORLD_WRITE)
            .risk(RiskLevel.MEDIUM)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "structure");
                if (bad != null) {
                    return bad;
                }
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("structure", call.string("structure"));
                if (call.has("blocks")) {
                    args.put("blocks", call.arguments().get("blocks"));
                }
                for (String key : new String[]{"x", "y", "z"}) {
                    if (call.has(key)) {
                        args.put(key, call.integer(key, 0));
                    }
                }
                ctx.enqueue("build", args);
                return ToolResult.scheduled(AgentLang.t("agent.tool.build.start", call.string("structure")));
            }
        };
    }

    private static Tool explore() {
        ToolSpec spec = ToolSpec.builder("explore", "朝一个方向走一段路去找东西")
            .category("interaction")
            .param("direction", "string", "north/south/east/west，不填随机")
            .param("distance", "number", "走多远，默认 40")
            .param("target", "string", "途中发现这个目标就停下并报告")
            .permission(Permission.MOVEMENT)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                Map<String, Object> args = new LinkedHashMap<>();
                if (call.has("direction")) {
                    args.put("direction", call.string("direction"));
                }
                args.put("distance", Math.max(16, call.integer("distance", 40)));
                if (call.has("target")) {
                    args.put("target", call.string("target"));
                }
                ctx.enqueue("explore", args);
                return ToolResult.scheduled(AgentLang.t("agent.tool.explore.start"));
            }
        };
    }

    private static Tool fish() {
        ToolSpec spec = ToolSpec.builder("fish", "在水边钓鱼（需要背包里有鱼竿）")
            .category("interaction")
            .param("quantity", "number", "钓几条，默认 1")
            .permission(Permission.WORLD_WRITE)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                if (!hasRod(ctx)) {
                    // Honest: the action would fail anyway, and the brain needs a real reason.
                    return ToolResult.missingTool(AgentLang.t("agent.tool.fish.no_rod"));
                }
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("quantity", Math.max(1, call.integer("quantity", 1)));
                ctx.enqueue("fish", args);
                return ToolResult.scheduled(AgentLang.t("agent.tool.fish.start"));
            }

            private boolean hasRod(ToolContext ctx) {
                return ctx.observation() != null && ctx.observation().countItem("fishing_rod") > 0;
            }
        };
    }

    private static Tool farm() {
        ToolSpec spec = ToolSpec.builder("farm", "收割附近的成熟作物并补种")
            .category("interaction")
            .param("quantity", "number", "收几株，默认 8")
            .permission(Permission.WORLD_WRITE)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("quantity", Math.max(1, call.integer("quantity", 8)));
                ctx.enqueue("farm", args);
                return ToolResult.scheduled(AgentLang.t("agent.tool.farm.start"));
            }
        };
    }
}
