package com.steve.ai.event;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.memory.StructureRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Server lifecycle glue: keeps the AI registry in step with the actual world.
 *
 * <p><b>Why this is not a login-only scan any more:</b> entities only exist while their chunk
 * is loaded. A single scan at login therefore misses an AI standing in unloaded chunks - the
 * registry looks empty, the player creates another one, and the original later shows up as an
 * unresponsive duplicate. Synchronising continuously fixes that regardless of when chunks
 * happen to load.</p>
 */
@Mod.EventBusSubscriber(modid = SteveMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerEventHandler {

    /** How often the world is reconciled (ticks). 100 = every 5 seconds. */
    private static final int SYNC_INTERVAL_TICKS = 100;

    /** Chat beyond this distance is out of earshot and does not wake the model. */
    private static final double CHAT_HEARING_RANGE = 48.0;

    /** How far away a hurt player can be and still expect the AI to come help. */
    private static final double DEFEND_RANGE = 32.0;

    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        StructureRegistry.clear();
        sync(event.getServer(), false);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter < SYNC_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            sync(server, true);
        }
    }

    // --- Chat: the AI can hear players, not just /as commands ---
    /**
     * Lets the AI actually hear players talking.
     *
     * <p>Before the layered refactor the only way to reach the AI was {@code /as say}. Now
     * ordinary chat within earshot becomes a {@code PLAYER_CHAT} event - which is what makes
     * "真人玩家不再是 AI 的控制器，而是 AI 世界里的另一个玩家" true rather than aspirational.</p>
     *
     * <p>Gated by distance so a conversation on the other side of the world does not wake the
     * model, and dispatched off-thread so prompt construction never blocks the server tick.</p>
     */
    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null) {
            return;
        }
        String text = event.getRawText();
        if (text == null || text.isBlank()) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (player.distanceTo(steve) > CHAT_HEARING_RANGE) {
            return;
        }
        final String speaker = player.getName().getString();
        final String message = text;
        // requestInstruction 只做入队，不会阻塞服务器线程，所以这里可以直接调用。
        steve.hearPlayer(speaker, message);
    }

    /**
     * 玩家被打 → 告诉 AI。
     *
     * <p>这是"保护意识"的输入侧：真人队友会注意到你被打了，并且不用你开口就会处理。</p>
     *
     * <p>只在同一个感知范围内通知，避免隔着几百格被一只远处的僵尸反复唤醒。</p>
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker)) {
            return;
        }
        // 自己打自己、或者 AI 自己造成的伤害，都不是"玩家被袭击"
        if (attacker == victim) {
            return;
        }
        if (attacker instanceof SteveEntity) {
            return;
        }

        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve == null || !steve.isAgentEnabled()) {
            return;
        }
        if (victim.distanceTo(steve) > DEFEND_RANGE) {
            return;
        }

        boolean attackerIsPlayer = attacker instanceof ServerPlayer;
        String attackerId;
        if (attackerIsPlayer) {
            attackerId = attacker.getName().getString();
        } else {
            // 用注册表 id（zombie）而不是显示名（Zombie），这样动作层能精确匹配
            var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(attacker.getType());
            attackerId = key != null ? key.getPath() : attacker.getName().getString();
        }

        final String victimName = victim.getName().getString();
        final String attackerName = attackerId;
        final boolean isPlayer = attackerIsPlayer;
        // 事件本身就在服务器线程上，直接处理即可（onPlayerHurt 只往线程安全的队列里投递）。
        steve.onPlayerHurt(victimName, attackerName, isPlayer);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        StructureRegistry.clear();

        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        sync(server, false);

        SteveEntity steve = SteveMod.getSteveManager().getSingleSteve();
        if (steve != null) {
            player.sendSystemMessage(Component.translatable("aisteve.event.ready",
                steve.getSteveName()));
        } else {
            player.sendSystemMessage(Component.translatable("aisteve.event.none"));
        }
    }

    /**
     * Reconciles the registry with the loaded world.
     *
     * @param quiet when true, only unexpected changes (duplicates found) are announced
     */
    private static void sync(MinecraftServer server, boolean quiet) {
        SteveManager manager = SteveMod.getSteveManager();
        if (manager == null) {
            return;
        }

        SteveManager.SyncResult result = manager.syncFromWorld(server.getAllLevels());
        if (!result.changed()) {
            return;
        }

        // Adoptions are routine (chunk loading), duplicates are worth telling the player about.
        if (result.discarded() > 0) {
            broadcast(server, Component.translatable("aisteve.event.duplicates",
                result.discarded()));
        } else if (!quiet && result.adopted() > 0) {
            SteveEntity steve = manager.getSingleSteve();
            if (steve != null) {
                broadcast(server, Component.translatable("aisteve.event.adopted",
                    steve.getSteveName()));
            }
        }
    }

    private static void broadcast(MinecraftServer server, Component message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(message);
        }
    }
}
