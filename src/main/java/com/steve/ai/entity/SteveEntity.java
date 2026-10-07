package com.steve.ai.entity;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionExecutor;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.agent.AgentRuntime;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.config.RuntimeSettings;
import com.steve.ai.event.AgentEvent;
import com.steve.ai.event.AgentEventType;
import com.steve.ai.i18n.AgentLang;
import com.steve.ai.memory.SteveMemory;
import com.steve.ai.memory.WorldMemory;
import com.steve.ai.menu.SteveInventoryMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
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
    /** 临时无敌（建造/飞行期间），与 {@code [agent].invulnerable} 是两个不同的东西。 */
    private boolean isInvulnerable = false;

    /**
     * Real hunger, using vanilla's own {@link FoodData}.
     *
     * <p>In 1.20.1 {@code FoodData} is only <em>attached</em> to {@code Player}, but the class
     * itself is standalone and instantiable - so a Mob can own one. That matters because it is
     * what gives us real food values: {@code eat(item, stack)} looks up the item's
     * {@code FoodProperties}, so every vanilla (and modded) food restores the right amount
     * instead of a made-up constant.</p>
     *
     * <p>The one thing we cannot reuse is {@code FoodData#tick(Player, boolean)} - it needs a
     * {@code Player} for the creative-mode check - so {@link #tickHunger()} implements the same
     * rules on top of it: exhaust from movement, slow regeneration when well fed, starvation
     * damage at zero.</p>
     */
    private final FoodData foodData = new FoodData();

    /** Accumulated exhaustion while sprinting, the same unit vanilla uses. */
    private float exhaustionBuffer;

    /** True while {@code invulnerable = true}, or during a temporary build/flight shield. */
    private boolean damageShielded() {
        return RuntimeSettings.invulnerable() || this.isInvulnerable;
    }

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

        // Real health and hunger by default; [agent].invulnerable restores the old behaviour.
        this.isInvulnerable = false;
        this.setInvulnerable(RuntimeSettings.invulnerable());

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
            tickHunger();

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
     *
     * <p>Two behaviours, split by what the player is holding:</p>
     * <ul>
     *   <li><b>Something in hand</b> - hand one of it over to the AI (unchanged).</li>
     *   <li><b>Empty hand</b> - open a read-only view of the AI's backpack, so "what does it
     *       actually have?" stops being a guessing game played through {@code /as info}.</li>
     * </ul>
     *
     * <p>The viewer is deliberately read-only. The AI plans its crafting against that bag one
     * step ahead of consuming from it, so letting a player pull items out mid-step could break
     * the plan; handing items over and taking them back keep their own explicit paths
     * (this right-click, {@code /as give}, {@code /as take}).</p>
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

                    this.sendChatMessage(AgentLang.t("aisteve.event.got_item",
                        toGive.getHoverName().getString()));
                    SteveMod.LOGGER.info("Player {} gave {} x1 to Steve '{}'",
                        player.getName().getString(),
                        toGive.getItem(),
                        this.getSteveName());

                    return InteractionResult.SUCCESS;
                } else {
                    this.sendChatMessage(AgentLang.t("aisteve.event.bag_full_chat"));
                    return InteractionResult.PASS;
                }
            }
        }

        // Empty hand: show what the AI is carrying.
        if (player instanceof ServerPlayer serverPlayer) {
            openInventoryView(serverPlayer);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    /**
     * Opens the read-only backpack viewer for this AI.
     *
     * <p>Server-side only, and the entity id is appended to the open-container payload so the
     * client binds the window to the right companion rather than inferring it.</p>
     */
    public void openInventoryView(ServerPlayer player) {
        NetworkHooks.openScreen(player, new SimpleMenuProvider(
            (windowId, playerInventory, viewer) ->
                new SteveInventoryMenu(windowId, playerInventory, this),
            Component.translatable("aisteve.gui.inventory.title", this.getSteveName())),
            buffer -> buffer.writeVarInt(this.getId()));
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

    // ------------------------------------------------------------------
    // Delivery bookkeeping (used by GiveItemAction)
    // ------------------------------------------------------------------

    /**
     * 最近真的交到玩家手里的物品 → 发生时的游戏刻。
     *
     * <p>为什么需要记这笔账：模型常把"合成 → 交给玩家"写成多步计划，甚至重试同一步。
     * 第二次去交同一个东西时背包已经空了，`GiveItemAction` 只能说"我身上没有 X"，
     * 于是玩家看到的是**东西明明拿到了，AI 却报"做不了"** —— 自相矛盾。
     * 记下交付事实之后，这种情况就能如实回答"刚才那把已经给你了"，
     * 而不是把自己已经做完的事报成失败。</p>
     *
     * <p>会话级数据，不进 NBT：它只用于消除"同一秒内重复交付"的歧义，
     * 重启游戏后留着反而可能误报"已经给过"。</p>
     */
    private final java.util.Map<net.minecraft.world.item.Item, Long> recentlyGivenToPlayer =
        new java.util.LinkedHashMap<>();

    /** 上限：只留最近一批条目，防止长局游戏里无限增长。 */
    private static final int DELIVERY_LOG_LIMIT = 32;

    /** 记一笔"这个物品刚刚交给玩家了"。 */
    public void recordGivingToPlayer(net.minecraft.world.item.Item item) {
        if (item == null || level() == null) {
            return;
        }
        recentlyGivenToPlayer.remove(item);
        recentlyGivenToPlayer.put(item, level().getGameTime());
        while (recentlyGivenToPlayer.size() > DELIVERY_LOG_LIMIT) {
            java.util.Iterator<net.minecraft.world.item.Item> it =
                recentlyGivenToPlayer.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    /**
     * 该物品是否在最近 {@code windowTicks} 刻内交给过玩家。
     *
     * @param windowTicks 时间窗（20 刻 = 1 秒）；小于等于 0 表示只看"有没有记录"
     */
    public boolean wasGivenToPlayerRecently(net.minecraft.world.item.Item item, long windowTicks) {
        if (item == null || level() == null) {
            return false;
        }
        Long tick = recentlyGivenToPlayer.get(item);
        if (tick == null) {
            return false;
        }
        return windowTicks <= 0 || level().getGameTime() - tick <= windowTicks;
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

        // 让 AI 的说话语言跟随玩家：你用英文问它，它就用英文回你。
        // 这是服务端唯一能推断出"该说哪种语言"的途径 —— Minecraft 的客户端语言设置
        // 不会上传到服务端（模组界面由客户端自己翻译，不需要这条路径）。
        AgentLang.observePlayerSpeech(text);

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

        // Hunger survives a restart, like a player's would.
        CompoundTag foodTag = new CompoundTag();
        this.foodData.addAdditionalSaveData(foodTag);
        tag.put("Food", foodTag);

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
        if (tag.contains("Food")) {
            this.foodData.readAdditionalSaveData(tag.getCompound("Food"));
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
        // Never lift the shield when the player asked for a fully invulnerable AI.
        this.setInvulnerable(invulnerable || RuntimeSettings.invulnerable());
    }

    /**
     * Whether the AI currently takes damage.
     *
     * <p>Since the AI can now be hurt for real, this is the honest way for other systems to ask
     * - {@code CombatAction} only shields itself while it is flying or building.</p>
     */
    public boolean isDamageImmune() {
        return damageShielded();
    }

    /**
     * Real damage.
     *
     * <p>This used to be {@code return false} plus an {@code isInvulnerableTo} that always said
     * true, which made the AI untouchable - and therefore made a whole half of the agent
     * decorative: {@code Needs.SAFETY}, the {@code flee} tool and every "low health, pull back"
     * branch could never be triggered by an actual hit.</p>
     *
     * <p>When the AI does get hurt, two things happen beyond losing health:</p>
     * <ul>
     *   <li>a {@code LOW_HEALTH} event is published once it drops below a third, which is what
     *       wakes the loop up and lets {@code SurvivalSkill} retreat or heal;</li>
     *   <li>the attacker is remembered as a threat for the agent's own self-defence.</li>
     * </ul>
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (damageShielded() || this.isInvulnerableTo(source)) {
            return false;
        }

        // A hit that would kill: with respawnAfterDeath it is taken as being knocked down
        // instead (see below). Without it, the AI dies for real and its bag spills.
        if (RuntimeSettings.respawnAfterDeath() && amount >= getHealth() && getHealth() > 0.0f) {
            knockedDown(source);
            return false;
        }

        boolean hurt = super.hurt(source, amount);
        if (!hurt || this.level().isClientSide) {
            return hurt;
        }

        // Freshly hurt: wake the loop so it can react to the new health value immediately
        // instead of waiting for the next scheduled perception cycle.
        if (agentRuntime != null && RuntimeSettings.agentEnabled()) {
            agentRuntime.post(AgentEvent.of(AgentEventType.AGENT_HURT, getSteveName(),
                getSteveName() + " 被打了一下（血量 " + Math.round(getHealth()) + "）",
                level().getGameTime()));
        }
        if (getHealth() < getMaxHealth() / 3.0f) {
            postAgentEvent(AgentEventType.LOW_HEALTH,
                getSteveName() + " 血量偏低：" + Math.round(getHealth()) + "/"
                    + Math.round(getMaxHealth()));
        }
        return true;
    }

    /**
     * Takes a fatal hit without dying: knocked to one heart, then left to recover.
     *
     * <p>This is the default reading of {@code [agent].respawnAfterDeath} - the AI genuinely
     * takes damage, genuinely gets knocked down, and genuinely has to be rescued by its own
     * survival behaviour, but it is never permanently lost and never spills a bag that may hold
     * things the player gave it. Set {@code respawnAfterDeath = false} for real death with drops.
     * </p>
     *
     * <p>Deliberately does <b>not</b> teleport: it goes down where it stood, and the ordinary
     * companion behaviour walks it back afterwards. The whole point of the damage rework is that
     * hits have consequences.</p>
     */
    private void knockedDown(DamageSource source) {
        setHealth(1.0f);
        clearFire();
        removeAllEffects();

        // No teleport, no healing: just alive and in trouble. SurvivalSkill will see a health
        // fraction this low and pull back; Needs will raise SAFETY to the top of the stack.
        if (!this.level().isClientSide) {
            postAgentEvent(AgentEventType.LOW_HEALTH,
                getSteveName() + " 被打倒了，只剩一口气");
            sendChatMessage(AgentLang.t("aisteve.event.knocked_down", sourceName(source)));
        }
        SteveMod.LOGGER.info("Steve '{}' knocked down by {} (kept alive at 1 HP)",
            getSteveName(), sourceName(source));
    }

    /** Readable name for whatever dealt the damage, for chat and logs. */
    private String sourceName(DamageSource source) {
        try {
            if (source.getEntity() != null) {
                return source.getEntity().getName().getString();
            }
            return source.getMsgId();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Real death. The AI's own bag is dropped, exactly like a player's inventory.
     *
     * <p>{@code Mob#die} knows nothing about {@link SteveInventory}, so without this the items the
     * player handed over would silently vanish with the entity.</p>
     */
    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide && this.inventory != null) {
            this.inventory.dropAll();
        }
        if (!this.level().isClientSide && agentRuntime != null) {
            agentRuntime.post(AgentEvent.of(AgentEventType.AGENT_DIED, getSteveName(),
                getSteveName() + " 死了（" + sourceName(source) + "）", level().getGameTime()));
        }
        SteveMod.LOGGER.info("Steve '{}' died: {}", getSteveName(), sourceName(source));
        super.die(source);
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        // Keep the build/flight shield and the config switch; everything else is real.
        return damageShielded() || super.isInvulnerableTo(source);
    }

    // ------------------------------------------------------------------
    // Hunger
    // ------------------------------------------------------------------

    /** The AI's own hunger bar - real vanilla food values, no made-up constants. */
    public FoodData getFoodData() {
        return this.foodData;
    }

    /**
     * Feeds the AI, using the item's real nutrition.
     *
     * <p>{@code FoodData#eat} takes the <em>stack</em> only to read its nutrition - it does not
     * consume it - so the shrink is done here, exactly where vanilla's {@code Player#eat} does it.
     * Using the two-argument overload on purpose: it cannot consume anything by surprise.</p>
     *
     * @return true when the item was edible and one was eaten
     */
    public boolean eat(ItemStack stack) {
        if (stack.isEmpty() || !stack.isEdible()) {
            return false;
        }
        foodData.eat(stack.getItem(), stack);
        stack.shrink(1);

        // Keeps the agent's own satiety model in step with the real hunger bar, so the AI does
        // not immediately go looking for more food after a full meal.
        if (agentRuntime != null) {
            agentRuntime.needs().onAte();
        }
        return true;
    }

    /**
     * Vanilla's hunger cycle, reimplemented on top of {@link FoodData}.
     *
     * <p>Mirrors {@code FoodData#tick}: exhaustion from moving, slow regeneration while well fed,
     * and starvation damage at zero. The vanilla method needs a {@code Player} (for the creative
     * check) which a Mob cannot supply, so the rules are restated here rather than skipped -
     * "needs to eat" is only real if running out of food actually costs something.</p>
     */
    private void tickHunger() {
        if (!RuntimeSettings.hunger()) {
            return;
        }

        if (this.isFlying) {
            // Creative-style flight while building: no walking, nothing to burn.
            exhaustionBuffer = Math.max(0.0f, exhaustionBuffer - 0.4f);
        } else if (this.getNavigation().isInProgress()) {
            // Vanilla charges 0.1 per metre walked; walking under our own power costs half.
            exhaustionBuffer += 0.05f;
        }
        if (exhaustionBuffer >= 4.0f) {
            exhaustionBuffer -= 4.0f;
            foodData.addExhaustion(4.0f);
        }

        int food = foodData.getFoodLevel();
        float saturation = foodData.getSaturationLevel();

        // Regeneration, exactly the vanilla thresholds (18+ = fast, 6+ = slow).
        if (saturation > 0.0f) {
            foodData.addExhaustion(1.0f);
        }
        boolean hurt = getHealth() < getMaxHealth();
        if (hurt && food >= 18 && this.tickCount % 80 == 0) {
            heal(1.0f);
        } else if (hurt && food >= 6 && this.tickCount % 320 == 0 && saturation > 0.0f) {
            heal(1.0f);
        }

        // Starvation: vanilla deals 1 damage every 4 seconds at zero food.
        if (food <= 0 && this.tickCount % 80 == 0) {
            hurt(this.damageSources().starve(), 1.0f);
        }
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
