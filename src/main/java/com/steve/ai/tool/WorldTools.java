package com.steve.ai.tool;

import com.steve.ai.i18n.AgentLang;
import com.steve.ai.protocol.Observation;
import com.steve.ai.protocol.Permission;
import com.steve.ai.protocol.RiskLevel;
import com.steve.ai.protocol.ToolCall;
import com.steve.ai.protocol.ToolResult;
import com.steve.ai.protocol.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * World query tools - category 5 of 6.
 *
 * <p>These answer questions from the <b>latest observation</b> rather than scanning the level
 * on demand. Two consequences, both intended:</p>
 * <ul>
 *   <li>They are effectively free, so the model can ask "what iron is near me?" as often as it
 *       likes without a performance cliff.</li>
 *   <li>They cannot see beyond the perception radius - which is exactly the "不能知道视野之外
 *       的信息" constraint the architecture demands of a believable player.</li>
 * </ul>
 */
public final class WorldTools {

    private WorldTools() {
    }

    public static void register(ToolRegistry registry) {
        registry.register(scanArea());
        registry.register(findBlock());
        registry.register(findEntity());
        registry.register(getTime());
        registry.register(getWeather());
    }

    // ------------------------------------------------------------------

    private static Tool scanArea() {
        ToolSpec spec = ToolSpec.builder("scan_area", "环顾四周，看看附近有什么玩家、生物、资源和容器")
            .category("world")
            .permission(Permission.WORLD_READ)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                Observation observation = ctx.observation();
                if (observation == null) {
                    return ToolResult.ok(AgentLang.t("agent.tool.scan.no_obs"));
                }
                return ToolResult.ok(AgentLang.t("agent.tool.scan.done"));
            }
        };
    }

    private static Tool findBlock() {
        ToolSpec spec = ToolSpec.builder("find_block", "在已知的附近资源里找某种方块")
            .category("world")
            .required("block", "string", "方块名，如 iron_ore")
            .permission(Permission.WORLD_READ)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "block");
                if (bad != null) {
                    return bad;
                }
                String wanted = call.string("block").toLowerCase();

                Observation observation = ctx.observation();
                if (observation == null) {
                    return ToolResult.notFound(AgentLang.t("agent.tool.scan.no_obs"));
                }
                for (Observation.BlockView view : observation.resources()) {
                    if (view.type().toLowerCase().contains(wanted)
                        || wanted.contains(view.type().toLowerCase())) {
                        return ToolResult.ok(
                            AgentLang.t("agent.tool.find_block.hit", view.type(), Math.round(view.distance())),
                            Map.of("type", view.type(), "x", view.x(), "y", view.y(), "z", view.z()));
                    }
                }
                return ToolResult.notFound(AgentLang.t("agent.tool.find_block.miss", wanted));
            }
        };
    }

    private static Tool findEntity() {
        ToolSpec spec = ToolSpec.builder("find_entity", "在已知的附近实体里找某种生物或玩家")
            .category("world")
            .required("target", "string", "生物名（sheep/zombie）或 player")
            .permission(Permission.WORLD_READ)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "target");
                if (bad != null) {
                    return bad;
                }
                String wanted = call.string("target").toLowerCase();
                Observation observation = ctx.observation();
                if (observation == null) {
                    return ToolResult.notFound(AgentLang.t("agent.tool.scan.no_obs"));
                }

                if (wanted.contains("player") || wanted.contains("玩家")) {
                    Observation.PlayerView player = observation.nearestPlayer();
                    if (player == null) {
                        return ToolResult.notFound(AgentLang.t("agent.tool.find_entity.no_player"));
                    }
                    return ToolResult.ok(AgentLang.t("agent.tool.find_entity.player", player.name(), Math.round(player.distance())),
                        Map.of("name", player.name(), "distance", player.distance()));
                }

                List<Observation.EntityView> pool =
                    wanted.contains("hostile") || wanted.contains("怪")
                        ? observation.hostiles()
                        : concat(observation.hostiles(), observation.animals());

                for (Observation.EntityView view : pool) {
                    if (view.type().toLowerCase().contains(wanted)) {
                        return ToolResult.ok(
                            AgentLang.t("agent.tool.find_entity.hit", view.type(), Math.round(view.distance())),
                            Map.of("type", view.type(), "x", view.x(), "y", view.y(), "z", view.z()));
                    }
                }
                return ToolResult.notFound(AgentLang.t("agent.tool.find_entity.miss", wanted));
            }
        };
    }

    private static Tool getTime() {
        ToolSpec spec = ToolSpec.builder("get_time", "现在是白天还是晚上")
            .category("world")
            .permission(Permission.WORLD_READ)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                Observation observation = ctx.observation();
                if (observation == null || observation.self() == null) {
                    return ToolResult.ok(AgentLang.t("agent.tool.time.unknown"));
                }
                return ToolResult.ok(observation.self().isDay() ? AgentLang.t("agent.tool.time.day") : AgentLang.t("agent.tool.time.night"));
            }
        };
    }

    private static Tool getWeather() {
        ToolSpec spec = ToolSpec.builder("get_weather", "现在天气怎么样")
            .category("world")
            .permission(Permission.WORLD_READ)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                Observation observation = ctx.observation();
                if (observation == null || observation.self() == null) {
                    return ToolResult.ok(AgentLang.t("agent.tool.weather.unknown"));
                }
                return ToolResult.ok(observation.self().isRaining() ? AgentLang.t("agent.tool.weather.rain") : AgentLang.t("agent.tool.weather.clear"));
            }
        };
    }

    private static List<Observation.EntityView> concat(List<Observation.EntityView> a,
                                                       List<Observation.EntityView> b) {
        List<Observation.EntityView> merged = new java.util.ArrayList<>(a);
        merged.addAll(b);
        return merged;
    }
}
