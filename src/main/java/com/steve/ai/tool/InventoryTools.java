package com.steve.ai.tool;

import com.steve.ai.protocol.Permission;
import com.steve.ai.protocol.RiskLevel;
import com.steve.ai.protocol.ToolCall;
import com.steve.ai.protocol.ToolResult;
import com.steve.ai.protocol.ToolSpec;
import com.steve.ai.util.ActionUtils;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inventory tools - category 3 of 6.
 *
 * <p>The split between queued and immediate work is clearest here:</p>
 * <ul>
 *   <li>{@code craft_item} is queued (crafting walks to a table, may craft intermediate
 *       materials, takes time).</li>
 *   <li>{@code get_inventory} is immediate - the answer already exists in the observation.</li>
 *   <li>{@code equip_item} is an honest <b>refusal</b>: the action layer already picks the
 *       right tool automatically, so pretending to have equip semantics would be a lie.</li>
 * </ul>
 */
public final class InventoryTools {

    private InventoryTools() {
    }

    public static void register(ToolRegistry registry) {
        registry.register(getInventory());
        registry.register(craftItem());
        registry.register(dropItem());
        registry.register(equipItem());
        registry.register(giveItem());
    }

    // ------------------------------------------------------------------

    private static Tool getInventory() {
        ToolSpec spec = ToolSpec.builder("get_inventory", "查看自己背包里有什么")
            .category("inventory")
            .permission(Permission.INVENTORY)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                var observation = ctx.observation();
                if (observation == null || observation.inventory().isEmpty()) {
                    return ToolResult.ok("背包是空的", Map.of("items", java.util.List.of()));
                }
                StringBuilder sb = new StringBuilder();
                Map<String, Object> data = new LinkedHashMap<>();
                for (var view : observation.inventory()) {
                    if (sb.length() > 0) {
                        sb.append("，");
                    }
                    sb.append(view.name()).append('x').append(view.count());
                    data.put(view.name(), view.count());
                }
                return ToolResult.ok(sb.toString(), Map.of("items", data));
            }
        };
    }

    private static Tool craftItem() {
        ToolSpec spec = ToolSpec.builder("craft_item", "合成物品（会真的查配方、扣材料，需要工作台会自己造）")
            .category("inventory")
            .required("item", "string", "要合成的物品，如 wooden_pickaxe")
            .param("quantity", "number", "想要几个，默认 1")
            .permission(Permission.INVENTORY)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "item");
                if (bad != null) {
                    return bad;
                }
                String item = call.string("item");
                int quantity = Math.max(1, call.integer("quantity", 1));

                Map<String, Object> args = new LinkedHashMap<>();
                args.put("item", item);
                args.put("quantity", quantity);
                ctx.enqueue("craft", args);

                return ToolResult.scheduled("开始合成 " + quantity + " 个 " + item);
            }
        };
    }

    private static Tool dropItem() {
        ToolSpec spec = ToolSpec.builder("drop_item", "把背包里的东西丢到地上")
            .category("inventory")
            .required("item", "string", "物品名")
            .param("count", "number", "丢几个，默认 1")
            .permission(Permission.INVENTORY)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "item");
                if (bad != null) {
                    return bad;
                }
                String name = call.string("item");
                int count = Math.max(1, call.integer("count", 1));

                Item item = ActionUtils.parseItem(name);
                if (item == Items.AIR) {
                    return ToolResult.badArguments("不认识的物品：" + name);
                }
                if (ctx.steve().getInventory().count(item) <= 0) {
                    return ToolResult.missingTool("我身上没有 " + name);
                }

                int removed = ctx.steve().getInventory().removeItem(item, count);
                if (removed <= 0) {
                    return ToolResult.error("丢弃失败");
                }
                ItemStack stack = new ItemStack(item, removed);
                ItemEntity entity = new ItemEntity(ctx.steve().level(),
                    ctx.steve().getX(), ctx.steve().getY() + 0.5, ctx.steve().getZ(), stack);
                ctx.steve().level().addFreshEntity(entity);

                return ToolResult.ok("丢下了 " + removed + " 个 " + name);
            }
        };
    }

    private static Tool equipItem() {
        ToolSpec spec = ToolSpec.builder("equip_item", "装备物品（当前由动作层自动选择工具，无需手动装备）")
            .category("inventory")
            .required("item", "string", "物品名")
            .permission(Permission.INVENTORY)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                // Honest refusal: the mine/place actions already pick the best carried tool.
                // Faking success here would make the brain believe a step happened that did not.
                return ToolResult.unsupported(
                    "不需要手动装备：挖矿/放置时会自动选用背包里最合适的工具。");
            }
        };
    }

    private static Tool giveItem() {
        ToolSpec spec = ToolSpec.builder("give_item", "把背包里的东西交给玩家")
            .category("inventory")
            .required("item", "string", "物品名，或用 all 表示全部交出")
            .param("count", "number", "几个，默认 1")
            .permission(Permission.INVENTORY)
            .risk(RiskLevel.LOW)
            .build();

        return new SimpleTool(spec) {
            @Override
            public ToolResult invoke(ToolContext ctx, ToolCall call) {
                ToolResult bad = ctx.requireArgs(call, "item");
                if (bad != null) {
                    return bad;
                }
                String item = call.string("item");
                int count = Math.max(1, call.integer("count", 1));

                // 刻意不在这里用 observation 预检背包：
                // observation 是 2 Hz 的快照，而"合成→交付"是连续两步，
                // 合成刚完成时快照还没刷新，于是会误报"我身上没有 X"，把整个任务判成失败，
                // 紧接着又真的把东西交了出去 —— 玩家看到的就是"已完成却提示暂时无法完成"。
                // 真实判断交给 GiveItemAction 在执行那一刻用最新背包决定。
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("item", item);
                args.put("count", count);
                ctx.enqueue("give", args);

                return ToolResult.scheduled("把 " + item + " 交给玩家");
            }
        };
    }
}
