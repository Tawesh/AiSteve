package com.steve.ai.entity;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionExecutor;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.agent.AgentRuntime;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.event.AgentEvent;
import com.steve.ai.event.AgentEventType;
import com.steve.ai.memory.SteveMemory;
import com.steve.ai.memory.WorldMemory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The AI player entity.
 *
 * <p>Behaves like a player-controlled character: it owns an inventory, can be handed items,
 * understands natural language tasks (via {@link ActionExecutor}) and talks back in chat.</p>
 */
public class SteveEntity extends PathfinderMob {

    private static final EntityDataAccessor<String> STEVE_NAME =
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);

    private String steveName;
    private SteveMemory memory;
    private WorldMemory worldMemory;
    private SteveInventory inventory;
    private ActionExecutor actionExecutor;

    /** The layered agent runtime: perception, memory, goals, skills, tools. */
    private AgentRuntime agentRuntime;

    /** True when the layered runtime is driving instead of the legacy one-shot planner. */
    private boolean agentEnabled = true;
    private boolean isFlying = false;
    private boolean isInvulnerable = false;

    public SteveEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.steveName = "Steve";
        this.memory = new SteveMemory(this);
        this.worldMemory = new WorldMemory();
        this.inventory = new SteveInventory(this);
        this.actionExecutor = new ActionExecutor(this);

        // ---------------------------------------------------------------
        // Layered Agent Runtime (see ARCHITECTURE.md).
        //
        // The entity stays a thin Minecraft Adapter: it owns the body and forwards
        // events. All thinking lives in the runtime, which is deliberately free of any
        // Forge/Fabric type beyond the entity itself.
        // ---------------------------------------------------------------
        try {
            this.agentEnabled = SteveConfig.ENABLE_AGENT.get();
        } catch (Exception e) {
            this.agentEnabled = true;
        }

        this.agentRuntime = new AgentRuntime(this);

        // When the layered runtime drives, it owns replanning - otherwise the legacy
        // replanner and the reflection layer would both rewrite the same queue.
        this.actionExecutor.setExternalPlanningEnabled(agentEnabled);
        final AgentRuntime runtimeRef = this.agentRuntime;
        this.actionExecutor.setExecutionListener(new ActionExecutor.ExecutionListener() {
            @Override
            public void onActionStarted(String actionName, Task task) {
                // Nothing to do: the loop already knows which step it dispatched.
            }

            @Override
            public void onActionFinished(String actionName, Task task, ActionResult result) {
                // Correlates the *real* outcome of a queued action with the tool call that
                // scheduled it, which is the only way reflection can see late failures.
                runtimeRef.loop().onActionFinished(actionName, task, result);
            }
        });
        this.setCustomNameVisible(true);

        this.isInvulnerable = true;
        this.setInvulnerable(true);

        // CRITICAL: a CREATURE mob is subject to vanilla despawning. After standing still
        // for ~30s while further than 32 blocks from a player, Mob#checkDespawn() silently
        // discard()s it - which is exactly what made the AI "vanish after finishing a task".
        // Persistence opts out of despawning completely.
        this.setPersistenceRequired();
    }

    /**
     * Belt-and-braces against despawning: never treat the player's AI companion as
     * "far away and removable", regardless of distance or idle time.
     */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.25D)
            .add(Attributes.ATTACK_DAMAGE, 8.0D)
            .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(STEVE_NAME, "Steve");
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            actionExecutor.tick();

            // The slow-thinking loop. Cheap to call: it does nothing at all unless
            // perception is due, an event woke it, or a goal needs attention.
            //
            // Enabled state is read live so toggling it in the settings GUI takes effect
            // without a restart.
            if (agentRuntime != null && com.steve.ai.config.RuntimeSettings.agentEnabled()) {
                agentRuntime.tick();
            }
        }
    }

    /**
     * Handles right-click interaction from players.
     * Allows players to give items to the AI by right-clicking with items in hand.
     */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        ItemStack heldItem = player.getItemInHand(hand);

        // If player is holding an item, transfer one to Steve's inventory
        if (!heldItem.isEmpty()) {
            ItemStack toGive = heldItem.copy();
            toGive.setCount(1);

            // Add to Steve's inventory
            if (this.inventory != null) {
                int leftover = this.inventory.addItem(toGive);
                if (leftover == 0) {
                    // Successfully added, remove from player's hand
                    heldItem.shrink(1);

                    this.sendChatMessage("收到了 " + toGive.getHoverName().getString() + "，谢谢！");
                    SteveMod.LOGGER.info("Player {} gave {} x1 to Steve '{}'",
                        player.getName().getString(),
                        toGive.getItem(),
                        this.getSteveName());

                    return InteractionResult.SUCCESS;
                } else {
                    this.sendChatMessage("我的背包满了...");
                    return InteractionResult.PASS;
                }
            }
        }

        return InteractionResult.PASS;
    }

    public void setSteveName(String name) {
        this.steveName = name;
        this.entityData.set(STEVE_NAME, name);
        this.setCustomName(Component.literal(name));

        if (agentRuntime != null) {
            agentRuntime.onRename(name);
        }
    }

    public String getSteveName() {
        return this.steveName;
    }

    public SteveMemory getMemory() {
        return this.memory;
    }

    public WorldMemory getWorldMemory() {
        return this.worldMemory;
    }


    public SteveInventory getInventory() {
        return this.inventory;
    }

    public ActionExecutor getActionExecutor() {
        return this.actionExecutor;
    }

    /** The layered agent runtime (perception / memory / goals / skills / tools). */
    public AgentRuntime getAgentRuntime() {
        return this.agentRuntime;
    }

    /** True when the layered Agent Runtime is driving this AI. */
    public boolean isAgentEnabled() {
        return com.steve.ai.config.RuntimeSettings.agentEnabled();
    }

    // ------------------------------------------------------------------
    // Perception input: how the world reaches the agent
    // ------------------------------------------------------------------

    /**
     * A player said something the AI should hear.
     *
     * <p>Used both by {@code /as say} and by ordinary chat (via {@code ServerEventHandler}).
     * The message is published on the agent event bus <em>and</em> handed to the loop, so
     * observers can react while the social system records the relationship.</p>
     */
    public void hearPlayer(String speaker, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        // 动态读取，这样在设置里关掉分层 Agent 后立刻回到旧路径，无需重启。
        if (agentRuntime != null && com.steve.ai.config.RuntimeSettings.agentEnabled()) {
            agentRuntime.post(AgentEvent.of(AgentEventType.PLAYER_CHAT, speaker,
                speaker + " 说：" + text, level().getGameTime()));
            agentRuntime.submitInstruction(text);
            return;
        }

        // Legacy path: the old one-shot planner still handles it.
        actionExecutor.processNaturalLanguageCommand(text);
    }

    /** Publishes an event to the agent bus without waking the loop. */
    public void postAgentEvent(AgentEventType type, String summary) {
        if (agentRuntime != null && com.steve.ai.config.RuntimeSettings.agentEnabled()) {
            agentRuntime.post(AgentEvent.of(type, getSteveName(), summary, level().getGameTime()));
        }
    }

    /**
     * A player the AI is travelling with just got hit.
     *
     * <p>This is the input side of the "保护意识" the companion needs: a real teammate notices
     * when you are being attacked and does something about it, without being told.</p>
     *
     * @param victim        player who was hurt (display name)
     * @param attacker      what hit them - a registry id for mobs, the name for players
     * @param attackerIsPlayer true when the attacker was another player (handled more carefully)
     */
    public void onPlayerHurt(String victim, String attacker, boolean attackerIsPlayer) {
        if (agentRuntime == null || attacker == null || attacker.isBlank()) {
            return;
        }
        if (!com.steve.ai.config.RuntimeSettings.agentEnabled()) {
            return;
        }
        agentRuntime.post(new AgentEvent(
            AgentEventType.PLAYER_HURT,
            attacker,
            "玩家 " + victim + " 被 " + attacker + " 攻击了",
            Map.of(
                "victim", victim == null ? "" : victim,
                "attacker", attacker,
                "attackerIsPlayer", attackerIsPlayer),
            level().getGameTime()));
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("SteveName", this.steveName);

        CompoundTag memoryTag = new CompoundTag();
        this.memory.saveToNBT(memoryTag);
        tag.put("Memory", memoryTag);
        CompoundTag inventoryTag = new CompoundTag();
        this.inventory.saveToNBT(inventoryTag);
        tag.put("Inventory", inventoryTag);

        CompoundTag worldMemoryTag = new CompoundTag();
        this.worldMemory.saveToNBT(worldMemoryTag);
        tag.put("WorldMemory", worldMemoryTag);

        // Layered agent memory (episodic / semantic / social). Working memory is
        // deliberately session-scoped and not persisted.
        if (this.agentRuntime != null) {
            CompoundTag agentMemoryTag = new CompoundTag();
            this.agentRuntime.memory().saveToNBT(agentMemoryTag);
            tag.put("AgentMemory", agentMemoryTag);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("SteveName")) {
            this.setSteveName(tag.getString("SteveName"));
        }
        if (tag.contains("Memory")) {
            this.memory.loadFromNBT(tag.getCompound("Memory"));
        }
        if (tag.contains("Inventory")) {
            this.inventory.loadFromNBT(tag.getCompound("Inventory"));
        }
        if (tag.contains("WorldMemory")) {
            this.worldMemory.loadFromNBT(tag.getCompound("WorldMemory"));
        }
        if (tag.contains("AgentMemory") && this.agentRuntime != null) {
            this.agentRuntime.memory().loadFromNBT(tag.getCompound("AgentMemory"));
        }
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                       MobSpawnType spawnType, @Nullable SpawnGroupData spawnData,
                                       @Nullable CompoundTag tag) {
        return super.finalizeSpawn(level, difficulty, spawnType, spawnData, tag);
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    /** Broadcasts a message to every player, formatted like a normal player chat line. */
    public void sendChatMessage(String message) {
        if (this.level().isClientSide) {
            return;
        }
        Component chatComponent = Component.literal("<" + this.steveName + "> " + message);
        this.level().players().forEach(player -> player.sendSystemMessage(chatComponent));
    }

    // ------------------------------------------------------------------
    // Building helpers
    // ------------------------------------------------------------------

    @Override
    protected void dropCustomDeathLoot(DamageSource source, int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);
    }

    /** Enables creative-style flight (used while constructing structures). */
    public void setFlying(boolean flying) {
        this.isFlying = flying;
        this.setNoGravity(flying);
        this.setInvulnerableBuilding(flying);
    }

    public boolean isFlying() {
        return this.isFlying;
    }

    /** Makes the AI immune to all damage while it is busy building. */
    public void setInvulnerableBuilding(boolean invulnerable) {
        this.isInvulnerable = invulnerable;
        this.setInvulnerable(invulnerable);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (this.isFlying && !this.level().isClientSide) {
            double motionY = this.getDeltaMovement().y;
            super.travel(travelVector);
            if (this.getNavigation().isInProgress() && Math.abs(motionY) < 0.1) {
                this.setDeltaMovement(this.getDeltaMovement().add(0, 0.05, 0));
            }
        } else {
            super.travel(travelVector);
        }
    }

    @Override
    public boolean causeFallDamage(float distance, float damageMultiplier, DamageSource source) {
        if (this.isFlying) {
            return false;
        }
        return super.causeFallDamage(distance, damageMultiplier, source);
    }
}
