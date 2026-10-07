package com.steve.ai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.steve.ai.SteveMod;
import com.steve.ai.config.RuntimeSettings;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.i18n.AgentLang;
import com.steve.ai.i18n.ConversationLanguage;
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
 * /as lang [zh_cn|en_us]       查看/设置 AI 说话的语言
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
            .then(Commands.literal("lang")
                .executes(AsCommands::showLang)
                .then(Commands.argument("language", StringArgumentType.word())
                    .executes(AsCommands::setLang)))
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
            source.sendFailure(Component.translatable("aisteve.cmd.server_only"));
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
            source.sendFailure(Component.translatable("aisteve.cmd.exists", existing.getSteveName()));
            return 0;
        }

        Vec3 pos = spawnPosition(source);
        SteveEntity steve = manager.spawnSteve(level, pos, name);
        if (steve == null) {
            source.sendFailure(Component.translatable("aisteve.cmd.create_failed"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("aisteve.cmd.created", name), false);
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
                source.sendSuccess(() -> Component.translatable("aisteve.cmd.cleaned_leftovers"), true);
                return 1;
            }
            source.sendFailure(Component.translatable("aisteve.cmd.no_ai"));
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
        source.sendSuccess(() -> total > 1
            ? Component.translatable("aisteve.cmd.removed_extra", name, total - 1)
            : Component.translatable("aisteve.cmd.removed", name), true);
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
            source.sendFailure(Component.translatable("aisteve.cmd.server_only"));
            return 0;
        }

        int removed = SteveMod.getSteveManager().purgeAll(server.getAllLevels());
        if (removed > 0) {
            source.sendSuccess(() -> Component.translatable("aisteve.cmd.cleanup_done", removed), true);
        } else {
            source.sendSuccess(() -> Component.translatable("aisteve.cmd.cleanup_none"), false);
        }
        return 1;
    }

    private static int showInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();

        if (steve == null) {
            source.sendSuccess(() -> Component.translatable("aisteve.cmd.info_none"), false);
            return 1;
        }

        var pos = steve.blockPosition();
        String goal = steve.getMemory().getCurrentGoal();
        final String goalText = goal == null || goal.isEmpty()
            ? Component.translatable("aisteve.cmd.idle").getString()
            : goal;

        // Hunger is only meaningful when it is switched on; report it the way SelfObserver does.
        final int food = RuntimeSettings.hunger() ? steve.getFoodData().getFoodLevel() : -1;
        final String foodText = food < 0
            ? Component.translatable("aisteve.cmd.food_off").getString()
            : food + " / 20";

        source.sendSuccess(() -> Component.translatable("aisteve.cmd.info",
            steve.getSteveName(),
            pos.getX(), pos.getY(), pos.getZ(),
            (int) steve.getHealth(), (int) steve.getMaxHealth(),
            foodText,
            steve.getInventory().describe(),
            goalText), false);
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
            source.sendFailure(Component.translatable("aisteve.cmd.no_ai"));
            return 0;
        }
        var runtime = steve.getAgentRuntime();
        if (runtime == null) {
            source.sendFailure(Component.translatable("aisteve.cmd.no_agent_runtime"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("aisteve.cmd.agent_state", runtime.describe()), false);
        return 1;
    }

    /** Lists the goal stack, highest priority first. */
    private static int showGoals(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.translatable("aisteve.cmd.no_ai"));
            return 0;
        }
        var runtime = steve.getAgentRuntime();
        if (runtime == null) {
            source.sendFailure(Component.translatable("aisteve.cmd.no_agent_runtime"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("aisteve.cmd.goals", runtime.goals().describe()), false);
        return 1;
    }

    /** Dumps what the AI remembers about players and the past. */
    private static int showMemory(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.translatable("aisteve.cmd.no_ai"));
            return 0;
        }
        var runtime = steve.getAgentRuntime();
        if (runtime == null) {
            source.sendFailure(Component.translatable("aisteve.cmd.no_agent_runtime"));
            return 0;
        }

        long tick = steve.level().getGameTime();
        String text = runtime.memory().social().toPromptText()
            + runtime.memory().episodic().toPromptText(tick, 5);
        if (text.isBlank()) {
            text = Component.translatable("aisteve.cmd.memory_empty").getString();
        }
        final String body = text;
        source.sendSuccess(() -> Component.translatable("aisteve.cmd.memory",
            runtime.memory().describe(), body), false);
        return 1;
    }

    private static int stopAi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.translatable("aisteve.cmd.no_ai"));
            return 0;
        }
        steve.getActionExecutor().stopCurrentAction();
        steve.getMemory().clearTaskQueue();
        source.sendSuccess(() -> Component.translatable("aisteve.cmd.stopped", steve.getSteveName()), true);
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
            source.sendFailure(Component.translatable("aisteve.cmd.no_ai"));
            return 0;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("aisteve.cmd.player_only"));
            return 0;
        }

        steve.getNavigation().stop();
        steve.teleportTo(player.getX(), player.getY(), player.getZ());
        source.sendSuccess(() -> Component.translatable("aisteve.cmd.called", steve.getSteveName()), false);
        return 1;
    }

    /**
     * Opens the configuration GUI on the client side.
     * Server-side command sends a packet to open client GUI.
     */
    private static int openConfig(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.translatable("aisteve.cmd.open_config"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // lang  (which language the AI speaks)
    // ------------------------------------------------------------------

    /** Reports the current AI language. */
    private static int showLang(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.translatable("aisteve.cmd.lang_set",
            AgentLang.current().displayName()), false);
        return 1;
    }

    /**
     * Sets the language the AI speaks.
     *
     * <p>Only affects the AI's own speech. Note that a player talking to it in the other language
     * will still switch it back - that mirroring is deliberate, and this command just chooses the
     * starting point.</p>
     */
    private static int setLang(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String raw = StringArgumentType.getString(context, "language");

        ConversationLanguage parsed = ConversationLanguage.parse(raw, null);
        if (parsed == null) {
            source.sendFailure(Component.translatable("aisteve.cmd.lang_usage"));
            return 0;
        }

        AgentLang.setCurrent(parsed);
        source.sendSuccess(() -> Component.translatable("aisteve.cmd.lang_set",
            parsed.displayName()), false);
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
            source.sendFailure(Component.translatable("aisteve.cmd.no_ai_create"));
            return 0;
        }

        if (instruction == null || instruction.isBlank()) {
            source.sendFailure(Component.translatable("aisteve.cmd.ask_what"));
            return 0;
        }

        // hearPlayer 只做入队，不会阻塞服务器线程，因此这里不需要再包一层线程。
        final String text = instruction;
        final String speaker = source.getEntity() != null
            ? source.getEntity().getName().getString()
            : "Player";
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
            source.sendFailure(Component.translatable("aisteve.cmd.player_only"));
            return 0;
        }

        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.translatable("aisteve.cmd.held_empty"));
            return 0;
        }

        int moved = transfer(player, steve, held.copy());
        if (moved <= 0) {
            source.sendFailure(Component.translatable("aisteve.cmd.bag_full"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("aisteve.cmd.gave_held",
            steve.getSteveName(), moved, held.getItem().toString()), false);
        return 1;
    }

    /** Gives a specific item by id (handy for testing or for creative mode). */
    private static int giveItem(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.translatable("aisteve.cmd.no_ai_create"));
            return 0;
        }

        String rawName = StringArgumentType.getString(context, "item");
        Item item = ActionUtils.parseItem(rawName);
        if (item == Items.AIR) {
            source.sendFailure(Component.translatable("aisteve.cmd.unknown_item", rawName));
            return 0;
        }

        ItemStack stack = new ItemStack(item, count);
        int leftover = steve.getInventory().addItem(stack);
        if (leftover >= count) {
            source.sendFailure(Component.translatable("aisteve.cmd.bag_full_cannot_give"));
            return 0;
        }

        int given = count - leftover;
        source.sendSuccess(() -> Component.translatable("aisteve.cmd.gave_item",
            steve.getSteveName(), given, item.toString()), false);
        return 1;
    }

    /** Asks the AI to hand everything over to the player. */
    private static int takeItems(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            source.sendFailure(Component.translatable("aisteve.cmd.no_ai_create"));
            return 0;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("aisteve.cmd.player_only"));
            return 0;
        }

        if (steve.getInventory().isEmpty()) {
            source.sendFailure(Component.translatable("aisteve.cmd.nothing_to_take",
                steve.getSteveName()));
            return 0;
        }

        int moved = 0;
        for (ItemStack stack : new java.util.ArrayList<>(steve.getInventory().getStacks())) {
            int n = stack.getCount();

            // `Inventory#add` consumes the stack it is handed and leaves only the part that did
            // not fit, so the leftover has to be dropped from *that* stack. Dropping a fresh
            // copy() here handed the player everything twice: (n - leftover) in the inventory
            // plus a full n on the ground, while only n ever left the AI.
            ItemStack toTake = stack.copy();
            if (!player.getInventory().add(toTake)) {
                player.drop(toTake, false);
            }

            steve.getInventory().removeItem(stack.getItem(), n);
            moved += n;
        }

        final int total = moved;
        source.sendSuccess(() -> Component.translatable("aisteve.cmd.took",
            steve.getSteveName(), total), false);
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
