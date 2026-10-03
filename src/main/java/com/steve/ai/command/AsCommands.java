package com.steve.ai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.util.ActionUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The single entry point for talking to the AI companion.
 *
 * <p>Commands:</p>
 * <pre>
 * /as create &lt;name&gt;            创建 AI 玩家（同时只能存在一个）
 * /as remove                   移除 AI 玩家
 * /as cleanup                  强制清理世界上所有 AI 实体（含遗留）
 * /as info                     查看状态（位置、背包、当前目标）
 * /as stop                     立即停止当前任务
 * /as come                     把 AI 叫到身边
 * /as say &lt;指令&gt;               用自然语言给 AI 下达任务（主要用法）
 * /as give [&lt;物品&gt;] [&lt;数量&gt;]    把手上的物品（或指定物品）交给 AI
 * /as take                     让 AI 把身上的东西都交给你
 * /as &lt;指令&gt;                   等价于 /as say &lt;指令&gt;（便捷写法）
 * </pre>
 */
public class AsCommands {

    /** Root command name. */
    public static final String ROOT = "as";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
            .then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(AsCommands::createAi)))
            .then(Commands.literal("remove")
                .executes(AsCommands::removeAi))
            .then(Commands.literal("cleanup")
                .executes(AsCommands::cleanupAi))
            .then(Commands.literal("agent")
                .executes(AsCommands::showAgent))
            .then(Commands.literal("goals")
                .executes(AsCommands::showGoals))
            .then(Commands.literal("memory")
                .executes(AsCommands::showMemory))
            .then(Commands.literal("info")
                .executes(AsCommands::showInfo))
            .then(Commands.literal("stop")
                .executes(AsCommands::stopAi))
            .then(Commands.literal("come")
                .executes(AsCommands::callAi))
            .then(Commands.literal("config")
                .executes(AsCommands::openConfig))
            .then(Commands.literal("say")
                .then(Commands.argument("instruction", StringArgumentType.greedyString())
                    .executes(AsCommands::sayToAi)))
            .then(Commands.literal("give")
                .executes(AsCommands::giveHeldItem)
                .then(Commands.argument("item", StringArgumentType.word())
                    .executes(ctx -> giveItem(ctx, 1))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 2304))
                        .executes(ctx -> giveItem(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
            .then(Commands.literal("take")
                .executes(AsCommands::takeItems))
            // Convenience: /as <自然语言指令>
            .then(Commands.argument("instruction", StringArgumentType.greedyString())
                .executes(AsCommands::sayToAi))
        );
    }

    // ------------------------------------------------------------------
    // create / remove / cleanup / info / stop
    // ------------------------------------------------------------------

    private static int createAi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");

        ServerLevel level;
        try {
            level = source.getLevel();
        } catch (Exception e) {
            source.sendFailure(Component.literal("必须在服务器端执行该命令"));
            return 0;
        }

        SteveManager manager = SteveMod.getSteveManager();

        // Reconcile before deciding: an AI may be sitting in chunks that were not loaded
        // when the player logged in. Without this the registry looks empty and we would
        // happily create a second AI, leaving the original as an unresponsive duplicate.
        MinecraftServer server = source.getServer();
        if (server != null) {
            manager.syncFromWorld(server.getAllLevels());
        }

        if (manager.hasSteve()) {
            SteveEntity existing = manager.getSingleSteve();
            source.sendFailure(Component.literal(
                "§c已经存在一个 AI 玩家了：§e" + existing.getSteveName()
                    + "§c。请先用 §b/" + ROOT + " remove§c 移除它。"));
            return 0;
        }

        Vec3 pos = spawnPosition(source);
        SteveEntity steve = manager.spawnSteve(level, pos, name);
        if (steve == null) {
            source.sendFailure(Component.literal("§c创建 AI 玩家失败，请查看日志。"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
            "§a已创建 AI 玩家 §e" + name + "§a。\n"
                + "§7· 下达任务：§b/" + ROOT + " say <你想要的>\n"
                + "§7· 给它物品：§b/" + ROOT + " give§7（拿在手上）\n"
                + "§7· 收回物品：§b/" + ROOT + " take"), false);
        return 1;
    }

    /** Spawns the AI in front of the player without overlapping them. */
    private static Vec3 spawnPosition(CommandSourceStack source) {
        if (source.getEntity() == null) {
            return source.getPosition().add(2, 0, 0);
        }
        Vec3 pos = source.getPosition();
        Vec3 look = source.getEntity().getLookAngle();
        return new Vec3(pos.x + look.x * 2, pos.y, pos.z + look.z * 2);
    }

    private static int removeAi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveManager manager = SteveMod.getSteveManager();

        MinecraftServer server = source.getServer();
        if (server != null) {
            manager.syncFromWorld(server.getAllLevels());
        }

        SteveEntity steve = manager.getSingleSteve();
        if (steve == null) {
            // Nothing registered - but leftovers from older builds may still be out there.
            if (server != null && manager.purgeAll(server.getAllLevels()) > 0) {
                source.sendSuccess(() -> Component.literal(
                    "§a已清理世界上残留的 AI 实体。"), true);
                return 1;
            }
            source.sendFailure(Component.literal("§c当前没有 AI 玩家。"));
            return 0;
        }

        String name = steve.getSteveName();
        steve.getActionExecutor().stopCurrentAction();

        // purgeAll rather than discarding just the registered instance: that also sweeps up
        // unresponsive duplicates created by older versions of this mod.
        int removed = server != null ? manager.purgeAll(server.getAllLevels()) : 0;
        if (removed == 0) {
            manager.removeSingleSteve();
            removed = 1;
        }

        final int total = removed;
        source.sendSuccess(() -> Component.literal(
            "§a已移除 AI 玩家 §e" + name + "§a"
                + (total > 1 ? "（同时清理了 " + (total - 1) + " 个残留实体）" : "")), true);
        return 1;
    }

    /**
     * Removes every AI entity in the loaded world, registered or not.
     *
     * <p>Needed after upgrading from a build with a different mod id: the old entity still
     * exists in the save but no longer matches the current registry.</p>
     */
    private static int cleanupAi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.literal("§c该命令只能在服务器端执行。"));
            return 0;
        }

        int removed = SteveMod.getSteveManager().purgeAll(server.getAllLevels());
        if (removed > 0) {
            source.sendSuccess(() -> Component.literal(
                "§a已清理 " + removed + " 个 AI 实体（含旧版本残留）。现在可以重新 §b/"
                    + ROOT + " create§a。"), true);
        } else {
            source.sendSuccess(() -> Component.literal("§7没有找到需要清理的 AI 实体。"), false);
        }
        return 1;
    }

    private static int showInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();

        if (steve == null) {
            source.sendSuccess(() -> Component.literal(
                "§7当前没有 AI 玩家。用 §b/" + ROOT + " create <名字>§7 创建一个。"), false);
            return 1;
        }

        var pos = steve.blockPosition();
        String goal = steve.getMemory().getCurrentGoal();
        source.sendSuccess(() -> Component.literal(
            "§aAI 玩家：§e" + steve.getSteveName() + "\n"
                + "§7位置：§f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "\n"
                + "§7生命：§f" + (int) steve.getHealth() + "/" + (int) steve.getMaxHealth() + "\n"
                + "§7背包：§f" + steve.getInventory().describe() + "\n"
                + "§7当前目标：§f" + (goal == null || goal.isEmpty() ? "空闲" : goal)), false);
        return 1;
    }

    /**
     * Shows the layered agent's internal state: persona, needs, memory counters, loop phase.
     *
     * <p>This is the debug window into the architecture - it is what makes "why did it do that?"
     * answerable without reading the log.</p>
     */
    private static int showAgent(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.literal("§c当前没有 AI 玩家。"));
            return 0;
        }
        var runtime = steve.getAgentRuntime();
        if (runtime == null) {
            source.sendFailure(Component.literal("§c该 AI 没有启用分层 Agent 运行时。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§a【Agent 状态】\n§f" + runtime.describe()), false);
        return 1;
    }

    /** Lists the goal stack, highest priority first. */
    private static int showGoals(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.literal("§c当前没有 AI 玩家。"));
            return 0;
        }
        var runtime = steve.getAgentRuntime();
        if (runtime == null) {
            source.sendFailure(Component.literal("§c该 AI 没有启用分层 Agent 运行时。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "§a【目标栈】\n§f" + runtime.goals().describe()), false);
        return 1;
    }

    /** Dumps what the AI remembers about players and the past. */
    private static int showMemory(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.literal("§c当前没有 AI 玩家。"));
            return 0;
        }
        var runtime = steve.getAgentRuntime();
        if (runtime == null) {
            source.sendFailure(Component.literal("§c该 AI 没有启用分层 Agent 运行时。"));
            return 0;
        }

        long tick = steve.level().getGameTime();
        String text = runtime.memory().social().toPromptText()
            + runtime.memory().episodic().toPromptText(tick, 5);
        if (text.isBlank()) {
            text = "（还什么都没记住）";
        }
        final String body = text;
        source.sendSuccess(() -> Component.literal(
            "§a【记忆】§7" + runtime.memory().describe() + "\n§f" + body), false);
        return 1;
    }

    private static int stopAi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.literal("§c当前没有 AI 玩家。"));
            return 0;
        }
        steve.getActionExecutor().stopCurrentAction();
        steve.getMemory().clearTaskQueue();
        source.sendSuccess(() -> Component.literal(
            "§a已让 §e" + steve.getSteveName() + "§a 停下。"), true);
        return 1;
    }

    /**
     * Teleports the AI right next to the player.
     *
     * <p>Escape hatch for the "it wandered off / is stuck somewhere underground" case:
     * the AI normally walks back on its own, but this guarantees it in one command.</p>
     */
    private static int callAi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.literal("§c当前没有 AI 玩家。"));
            return 0;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c该用法只能由玩家执行。"));
            return 0;
        }

        steve.getNavigation().stop();
        steve.teleportTo(player.getX(), player.getY(), player.getZ());
        source.sendSuccess(() -> Component.literal(
            "§a已把 §e" + steve.getSteveName() + "§a 叫到身边。"), false);
        return 1;
    }

    /**
     * Opens the configuration GUI on the client side.
     * Server-side command sends a packet to open client GUI.
     */
    private static int openConfig(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal(
            "§a请按 §eK§a 键打开配置界面，或在客户端使用 §b/as config§a。"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // say  (the main interaction channel)
    // ------------------------------------------------------------------

    private static int sayToAi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String instruction = StringArgumentType.getString(context, "instruction");

        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.literal(
                "§c还没有 AI 玩家。先用 §b/" + ROOT + " create <名字>§c 创建一个。"));
            return 0;
        }

        if (instruction == null || instruction.isBlank()) {
            source.sendFailure(Component.literal(
                "§c要说什么？例如：§b/" + ROOT + " 帮我弄一个羊排"));
            return 0;
        }

        // The planner is non-blocking, but it is still started off-thread so the
        // server tick is never held up by prompt construction.
        final String text = instruction;
        final String speaker = source.getEntity() != null
            ? source.getEntity().getName().getString()
            : "玩家";
        // hearPlayer 只会把指令放进线程安全的队列，由 AgentLoop 在服务器线程上处理，
        // 因此这里不需要再包一层线程。
        steve.hearPlayer(speaker, text);

        return 1;
    }

    // ------------------------------------------------------------------
    // give / take
    // ------------------------------------------------------------------

    /** Moves whatever the player is holding into the AI's inventory. */
    private static int giveHeldItem(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.literal(
                "§c还没有 AI 玩家。先用 §b/" + ROOT + " create <名字>§c 创建一个。"));
            return 0;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c该用法只能由玩家执行。"));
            return 0;
        }

        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("§c你手上没有物品。"));
            return 0;
        }

        int moved = transfer(player, steve, held.copy());
        if (moved <= 0) {
            source.sendFailure(Component.literal("§cAI 的背包已满。"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
            "§a已交给 §e" + steve.getSteveName() + "§a：" + moved + "x " + held.getItem()), false);
        return 1;
    }

    /** Gives a specific item by id (handy for testing or for creative mode). */
    private static int giveItem(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.literal(
                "§c还没有 AI 玩家。先用 §b/" + ROOT + " create <名字>§c 创建一个。"));
            return 0;
        }

        String rawName = StringArgumentType.getString(context, "item");
        Item item = ActionUtils.parseItem(rawName);
        if (item == Items.AIR) {
            source.sendFailure(Component.literal("§c未知物品：" + rawName));
            return 0;
        }

        ItemStack stack = new ItemStack(item, count);
        int leftover = steve.getInventory().addItem(stack);
        if (leftover >= count) {
            source.sendFailure(Component.literal("§cAI 的背包已满，无法给予。"));
            return 0;
        }

        int given = count - leftover;
        source.sendSuccess(() -> Component.literal(
            "§a已交给 §e" + steve.getSteveName() + "§a：" + given + "x " + item), false);
        return 1;
    }

    /** Asks the AI to hand everything over to the player. */
    private static int takeItems(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.literal("§c还没有 AI 玩家。"));
            return 0;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c该用法只能由玩家执行。"));
            return 0;
        }

        if (steve.getInventory().isEmpty()) {
            source.sendFailure(Component.literal("§e" + steve.getSteveName() + "§c 身上没有东西。"));
            return 0;
        }

        int moved = 0;
        for (ItemStack stack : new java.util.ArrayList<>(steve.getInventory().getStacks())) {
            int n = stack.getCount();
            if (!player.getInventory().add(stack.copy())) {
                player.drop(stack.copy(), false);
            }
            steve.getInventory().removeItem(stack.getItem(), n);
            moved += n;
        }

        final int total = moved;
        source.sendSuccess(() -> Component.literal(
            "§a已从 §e" + steve.getSteveName() + "§a 取回 " + total + " 个物品。"), false);
        return 1;
    }

    /** Removes the given stack from the player and stores it in the AI's inventory. */
    private static int transfer(ServerPlayer player, SteveEntity steve, ItemStack stack) {
        int wanted = stack.getCount();
        int leftover = steve.getInventory().addItem(stack);
        int moved = wanted - leftover;
        if (moved > 0) {
            player.getMainHandItem().shrink(moved);
        }
        return moved;
    }
}
