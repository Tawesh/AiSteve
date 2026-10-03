package com.steve.ai.agent;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionExecutor;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.brain.Goal;
import com.steve.ai.brain.GoalType;
import com.steve.ai.brain.Plan;
import com.steve.ai.brain.Reflection;
import com.steve.ai.config.RuntimeSettings;
import com.steve.ai.event.AgentEvent;
import com.steve.ai.event.AgentEventType;
import com.steve.ai.i18n.AgentLang;
import com.steve.ai.llm.AgentPromptBuilder;
import com.steve.ai.protocol.AgentDecision;
import com.steve.ai.protocol.Observation;
import com.steve.ai.protocol.ToolCall;
import com.steve.ai.protocol.ToolResult;
import com.steve.ai.tool.ToolContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The slow-thinking loop: {@code Observe → Think → Act → Reflect}.
 *
 * <p>Runs once per server tick, but almost always does nothing expensive. That is the whole
 * design: the loop is cheap to call, and decides for itself whether anything is worth doing.</p>
 *
 * <pre>
 * tick()
 *   ├─ drain event bus        → working memory (+ wake up on danger)
 *   ├─ perception (2 Hz)      → Observation
 *   ├─ Needs + GoalManager    → dynamic priorities, autonomous goals, roam limit
 *   ├─ an LLM call in flight? → collect the answer and act on it
 *   ├─ executor busy?         → let the action own the body, do nothing
 *   └─ advance()              → plan (skill first) / dispatch next step / close the goal
 * </pre>
 *
 * <p>Frequency discipline, straight from the architecture document: Minecraft runs at 20 TPS,
 * perception at 2 Hz, and the LLM at 0.2-2 Hz <em>at most</em> - and only when a goal genuinely
 * cannot be planned without it.</p>
 *
 * <p><b>Talking.</b> A companion that never speaks is indistinguishable from a tool. So this loop
 * narrates the three moments a teammate would speak up at - starting a job, finishing it, and
 * failing at it - plus occasional unprompted remarks when it is idle. Those lines are sent
 * directly rather than queued, so a long mining job cannot delay them.</p>
 */
public final class AgentLoop {

    /** Perception refresh interval (10 ticks = 2 Hz). */
    private static final int PERCEPTION_INTERVAL_TICKS = 10;
    /** Minimum gap between two LLM calls (20 ticks = 1 s). */
    private static final int MIN_THINK_INTERVAL_TICKS = 20;
    /** How many times one goal may bounce back through reflection before it is abandoned. */
    private static final int MAX_REPLAN = 3;
    /** Minimum gap between two narration lines, so a fast plan cannot spam chat. */
    private static final int NARRATION_INTERVAL_TICKS = 30;
    /** How often the AI may start a conversation of its own (90 s). */
    private static final int IDLE_CHAT_INTERVAL_TICKS = 20 * 90;

    private final AgentRuntime runtime;

    // --- perception ----------------------------------------------------------
    private Observation observation = Observation.empty();
    private long lastPerceptionTick = Long.MIN_VALUE;

    // --- planning ------------------------------------------------------------
    private Plan currentPlan;
    private final Deque<ToolCall> stepQueue = new ArrayDeque<>();
    private String activeGoalId;
    private Goal referencedGoal;
    private int replanCount;

    // --- thinking ------------------------------------------------------------
    private CompletableFuture<AgentDecision> thinking;
    private String pendingInstruction;
    private String reflectionNote;
    private long lastThinkTick = Long.MIN_VALUE;

    // --- talking -------------------------------------------------------------
    private long lastNarrationTick = Long.MIN_VALUE;
    private long lastIdleChatTick = Long.MIN_VALUE;

    /**
     * Player instructions arriving from other threads.
     *
     * <p>Commands and chat events do not run on the server tick thread, so they must not touch
     * the agent's state directly. They enqueue here; {@link #processInbox} drains it on tick.</p>
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> inbox =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    // --- action correlation --------------------------------------------------
    /** Set when a queued action is dispatched, so a later failure can be attributed to a step. */
    private ToolCall dispatchedStep;
    private Goal dispatchedGoal;

    public AgentLoop(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    // ==================================================================
    // Public API
    // ==================================================================

    /** A player asked for something. Wakes the loop; never blocks the game thread. */
    public void requestInstruction(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        // 只做入队：调用方可能是命令线程或网络线程（/as say、聊天事件）。
        // 真正的处理放到 tick() 里，保证所有游戏状态操作都在服务器线程上完成。
        inbox.add(text);
    }

    /**
     * 处理排队中的玩家指令。
     *
     * <p>放在主线程做，是因为解析社交关系、写记忆、提交目标都会碰实体/世界状态。
     * 之前这些直接跑在命令线程上，属于"大多数时候能跑、偶尔出怪事"的隐患。</p>
     */
    private void processInbox(long tick) {
        String text;
        int handled = 0;
        while ((text = inbox.poll()) != null && handled++ < 8) {
            handleInstruction(text, tick);
        }
    }

    private void handleInstruction(String text, long tick) {
        String speaker = nearestPlayerName();

        // Social first: relationships and durable facts are updated even if the model is down.
        var intent = runtime.social().analyze(speaker, text, tick, runtime.memory());

        runtime.memory().working().remember(tick, speaker + " 对我说：" + text);

        if (intent.task() && intent.suggestedGoal() != null) {
            runtime.goals().submit(intent.suggestedType(), intent.suggestedGoal(), speaker, tick);
            runtime.memory().episodic().record(tick,
                speaker + " 让我" + intent.suggestedGoal(), speaker, "进行中", 0.4);
        }

        // 新指令应当打断旧计划：玩家刚说了话，旧任务已经过时了。
        interruptCurrentWork();

        // Every instruction deserves one thought cycle - for a task it produces the plan,
        // for small talk it produces the reply.
        pendingInstruction = text;
    }

    /** Called by the executor listener when a queued action finishes. */
    public void onActionFinished(String actionName, Task task, ActionResult result) {
        if (dispatchedStep == null) {
            return;
        }
        ToolCall step = dispatchedStep;
        Goal goal = dispatchedGoal;
        dispatchedStep = null;
        dispatchedGoal = null;

        if (result == null || result.isSuccess()) {
            return;
        }
        // The tool reported "scheduled"; the real verdict only arrives now.
        if (goal != null) {
            reflect(goal, step, ToolResult.failed(safeMessage(result)), nowTick());
        }
    }

    // ==================================================================
    // Tick
    // ==================================================================

    public void tick() {
        long tick = nowTick();

        processInbox(tick);
        drainEvents(tick);

        if (tick - lastPerceptionTick >= PERCEPTION_INTERVAL_TICKS
            || lastPerceptionTick == Long.MIN_VALUE) {
            perceive(tick);
        }

        runtime.needs().updateFrom(observation, tick);
        runtime.goals().reevaluate(observation, runtime.needs(), tick);

        // An LLM answer is pending: collect it and stop; do not start a second thought.
        if (thinking != null) {
            if (thinking.isDone()) {
                collectDecision(tick);
            }
            return;
        }

        ActionExecutor executor = executor();
        if (executor != null && executor.isBusy()) {
            // 执行中也可以偶尔搭句话 —— 沉默地挖三分钟矿，和"不在场"没什么区别。
            maybeIdleChat(tick);
            return;
        }

        advance(tick);
        maybeIdleChat(tick);
    }

    // ==================================================================

    private void drainEvents(long tick) {
        AgentEvent event;
        int drained = 0;
        while ((event = runtime.pollEvent()) != null && drained++ < 32) {
            runtime.memory().working().remember(tick, event.summary());

            // 玩家被打：这是"保护意识"的触发器，独立于 LLM 处理。
            if (event.type() == AgentEventType.PLAYER_HURT) {
                handlePlayerHurt(event, tick);
            }

            // Only genuinely urgent things interrupt an otherwise idle agent, otherwise a
            // chatty tick could burn tokens for nothing.
            if (pendingInstruction == null
                && (event.type() == AgentEventType.LOW_HEALTH
                    || event.type() == AgentEventType.AGENT_DIED)) {
                pendingInstruction = "（紧急）" + event.summary();
            }
        }
    }

    /**
     * 玩家被袭击 → 立刻生成"保护"目标。
     *
     * <p>刻意不经过 LLM：同伴看到你被打是**条件反射**，不是需要思考三十秒的事情。
     * 目标会走正常的 目标→行动 流程（{@code CombatSkill} + {@code attack_entity}）。</p>
     */
    private void handlePlayerHurt(AgentEvent event, long tick) {
        String attacker = event.payloadString("attacker");
        String victim = event.payloadString("victim");
        boolean attackerIsPlayer = Boolean.parseBoolean(event.payloadString("attackerIsPlayer"));

        if (attacker == null || attacker.isBlank()) {
            return;
        }

        // PvP 反击是可选能力；关掉时完全不考虑玩家目标。
        if (attackerIsPlayer && !RuntimeSettings.defendAgainstPlayers()) {
            return;
        }
        // 被保护的人自己不在场时（例如已离开），不追出去。
        if (victim != null && !victim.isBlank() && observation != null
            && observation.players().stream().noneMatch(p -> p.name().equals(victim))) {
            return;
        }

        defendVictim = victim;
        if (attackerIsPlayer) {
            defendPlayerAttacker = attacker;
        }

        Goal goal = runtime.goals().submit(GoalType.PROTECT,
            "保护玩家：消灭 " + attacker, "defend", tick);
        if (goal != null) {
            // 高于常规任务，仅次于自身生存危机（100 是生存/战斗紧急线）。
            goal.setPriority(100);
        }

        narrate(tick, AgentLang.t("agent.narrate.defend", attacker));
    }

    private void perceive(long tick) {
        observation = runtime.perception().observe(runtime.steve(),
            runtime.memory().working().recent(6));
        lastPerceptionTick = tick;
    }

    /**
     * The decision function: does anything need to happen right now?
     */
    private void advance(long tick) {
        // (A) A pending instruction always gets one thought.
        if (pendingInstruction != null) {
            if (canThink(tick)) {
                startThinking(tick, pendingInstruction);
            }
            return;
        }

        // (B) Work on the current goal.
        Optional<Goal> current = runtime.goals().current();
        if (current.isEmpty()) {
            clearFocus();
            maybeIdleChat(tick);
            return;
        }
        Goal goal = current.get();

        if (!goal.id().equals(activeGoalId)) {
            // A new (or newly-promoted) goal: plan it from scratch.
            beginGoal(goal, tick);
            if (currentPlan == null || currentPlan.needsLlm()) {
                return;
            }
            if (stepQueue.isEmpty()) {
                // The skill looked and decided there is nothing to do.
                runtime.goals().complete(goal.id(), currentPlan.narrative());
                clearFocus();
                return;
            }
        }

        // (C) Dispatch the next step of the plan.
        if (!stepQueue.isEmpty()) {
            dispatchNextStep(goal, tick);
            return;
        }

        // (D) Plan exhausted: the goal is finished.
        finishGoal(goal, tick);
    }

    private void beginGoal(Goal goal, long tick) {
        activeGoalId = goal.id();
        referencedGoal = goal;
        replanCount = 0;
        stepQueue.clear();

        runtime.memory().working().setContext(goal.description());

        Plan plan = runtime.planner().plan(goal, runtime.steve(), observation, runtime.memory());
        if (plan.needsLlm()) {
            currentPlan = null;
            if (canThink(tick)) {
                startThinking(tick, "（自主行动）" + goal.description());
            }
            return;
        }
        currentPlan = plan;
        stepQueue.addAll(plan.steps());
        runtime.memory().working().remember(tick, "计划：" + plan.narrative());

        SteveMod.LOGGER.info("[Agent] '{}' 目标 '{}' 使用确定性计划 {}（{} 步）",
            runtime.steve().getSteveName(), goal.description(), plan.source(), plan.steps().size());

        // 先说计划再动手 —— 玩家需要知道它打算干什么。
        narrate(tick, describePlan(plan, goal));
    }

    /**
     * 把计划讲成人话。
     *
     * <p>例如 "挖 8 个铁矿" 这一步会播报成
     * "我先去挖 8 个 iron_ore，搞完告诉你。"</p>
     */
    private String describePlan(Plan plan, Goal goal) {
        String narrative = plan.narrative() == null ? "" : plan.narrative();
        // skill:<name> - 描述  →  只保留描述部分
        int dash = narrative.indexOf(" - ");
        if (dash >= 0) {
            narrative = narrative.substring(dash + 3);
        }
        if (narrative.isBlank()) {
            narrative = goal.description();
        }
        int steps = plan.steps().size();
        if (steps <= 1) {
            return AgentLang.t("agent.narrate.plan_one", narrative);
        }
        return AgentLang.t("agent.narrate.plan_many", narrative, steps);
    }

    private void dispatchNextStep(Goal goal, long tick) {
        ToolCall call = stepQueue.pollFirst();
        ToolResult result = runtime.dispatcher().dispatch(toolContext(), call);

        runtime.memory().working().remember(tick, "执行 " + call.tool() + "：" + result.message());

        if (result.isHardFailure()) {
            reflect(goal, call, result, tick);
            return;
        }

        // Correlate a queued action with its (much later) real outcome.
        if (ToolResult.SCHEDULED.equals(result.status())) {
            dispatchedStep = call;
            dispatchedGoal = goal;
        }
    }

    private void finishGoal(Goal goal, long tick) {
        String summary = currentPlan == null ? goal.description() : humanSummary(currentPlan, goal);

        runtime.goals().complete(goal.id(), summary);
        runtime.memory().working().remember(tick, "完成了：" + goal.description());
        runtime.memory().episodic().record(tick, "完成目标：" + goal.description(),
            "", "成功", 0.6);
        runtime.goals().prune();

        narrate(tick, AgentLang.t("agent.narrate.done", summary));

        clearFocus();
        // 保护任务结束后撤销 PvP 白名单：反击只在当场有效。
        defendPlayerAttacker = null;
        defendVictim = null;
    }

    /** 完成时说出来的短句（去掉 skill: 前缀等技术细节）。 */
    private String humanSummary(Plan plan, Goal goal) {
        String narrative = plan.narrative() == null ? "" : plan.narrative();
        int dash = narrative.indexOf(" - ");
        if (dash >= 0) {
            narrative = narrative.substring(dash + 3);
        }
        return narrative.isBlank() ? goal.description() : narrative;
    }

    // ------------------------------------------------------------------
    // Talking
    // ------------------------------------------------------------------

    /**
     * 播报一句进度话。
     *
     * <p>直接发到聊天框而不是排进动作队列：正在挖矿时队列会堵住，而"我要开始挖矿了"
     * 这种话晚 20 秒才说就没有意义了。</p>
     *
     * <p>节流到 {@link #NARRATION_INTERVAL_TICKS}，避免一个多步计划把聊天刷屏。</p>
     */
    private void narrate(long tick, String message) {
        if (!RuntimeSettings.progressNarration() || !RuntimeSettings.chatResponses()) {
            return;
        }
        if (message == null || message.isBlank()) {
            return;
        }
        if (tick - lastNarrationTick < NARRATION_INTERVAL_TICKS) {
            // 太频繁就攒着，等下一次机会再说，避免刷屏
            pendingNarration = message;
            return;
        }
        lastNarrationTick = tick;
        pendingNarration = null;
        sendChat(message);
    }

    /** 被节流拦下的一句话，等间隔过去后补发。 */
    private String pendingNarration;

    // --- 保护玩家 ------------------------------------------------------------
    /** 正在保护的玩家（被攻击的那个）。 */
    private String defendVictim;
    /** 如果攻击者也是玩家且允许反击，记在这里作为临时白名单。 */
    private String defendPlayerAttacker;

    /** 空闲时主动找话说 —— 这是"它好像真的在玩"和"它是个工具"的区别。 */
    private void maybeIdleChat(long tick) {
        if (!RuntimeSettings.idleChat() || !RuntimeSettings.chatResponses()) {
            return;
        }

        // 先把被节流压住的那句补上
        if (pendingNarration != null && tick - lastNarrationTick >= NARRATION_INTERVAL_TICKS) {
            String queued = pendingNarration;
            pendingNarration = null;
            lastNarrationTick = tick;
            sendChat(queued);
            return;
        }

        if (tick - lastIdleChatTick < IDLE_CHAT_INTERVAL_TICKS) {
            return;
        }

        String line = pickSmallTalk();
        if (line == null) {
            return;
        }
        lastIdleChatTick = tick;
        sendChat(line);
    }

    /**
     * 根据当前处境挑一句自然的搭话。
     *
     * <p>纯规则、不调模型：闲聊不该花钱，也不该有延迟。</p>
     */
    private String pickSmallTalk() {
        Observation obs = observation;
        if (obs == null || obs.self() == null) {
            return null;
        }

        Observation.EntityView hostile = obs.nearestHostile();
        if (hostile != null && hostile.distance() <= 16) {
            return AgentLang.t("agent.smalltalk.hostile", hostile.type());
        }

        if (!obs.self().isDay()) {
            return AgentLang.t("agent.smalltalk.night");
        }

        Observation.PlayerView player = obs.nearestPlayer();
        if (player != null && player.distance() <= 16) {
            if (obs.inventory().isEmpty()) {
                return AgentLang.t("agent.smalltalk.empty_bag");
            }
            if (!obs.animals().isEmpty()) {
                return AgentLang.t("agent.smalltalk.animals", obs.animals().get(0).type());
            }
            return AgentLang.t("agent.smalltalk.nearby");
        }

        return null;
    }

    /** 直接发一条聊天消息（不走动作队列）。 */
    private void sendChat(String message) {
        try {
            runtime.steve().sendChatMessage(message);
            runtime.memory().working().remember(nowTick(), "（我说）" + message);
        } catch (Exception e) {
            SteveMod.LOGGER.warn("[Agent] 发送聊天失败: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Thinking (LLM)
    // ------------------------------------------------------------------

    private boolean canThink(long tick) {
        if (thinking != null) {
            return false;
        }
        return lastThinkTick == Long.MIN_VALUE
            || tick - lastThinkTick >= MIN_THINK_INTERVAL_TICKS;
    }

    private void startThinking(long tick, String instruction) {
        String system = AgentPromptBuilder.buildSystemPrompt(runtime.persona(), runtime.tools(),
            AgentLang.current());
        String user = AgentPromptBuilder.buildUserPrompt(
            observation, runtime.goals(), runtime.needs(), runtime.memory(),
            instruction, reflectionNote, tick);

        lastThinkTick = tick;
        SteveMod.LOGGER.info("[Agent] '{}' 开始思考：{}", runtime.steve().getSteveName(),
            abbreviate(instruction));

        thinking = runtime.llm().decideAsync(system, user);
    }

    private void collectDecision(long tick) {
        CompletableFuture<AgentDecision> future = thinking;
        thinking = null;

        AgentDecision decision;
        try {
            decision = future.get();
        } catch (Exception e) {
            SteveMod.LOGGER.error("[Agent] 获取决策失败", e);
            decision = null;
        }

        String instruction = pendingInstruction;
        pendingInstruction = null;
        reflectionNote = null;

        if (decision == null) {
            // 不要装死：模型出错时也要有交代，否则玩家会以为是模组坏了。
            runtime.memory().working().remember(tick, "我这次没想出来（接口或解析问题）");
            if (instruction != null && looksLikeDirectAddress(instruction)) {
                narrate(tick, AgentLang.t("agent.narrate.thinking_failed"));
            }
            return;
        }
        runtime.memory().working().setContext("");

        if (!decision.thought().isBlank()) {
            runtime.memory().working().remember(tick, "（想法）" + decision.thought());
        }

        // 1) Apply any goal change the model asked for.
        AgentDecision.GoalUpdate update = decision.goalUpdate();
        if (update != null && !update.isEmpty()) {
            GoalType type = GoalType.parse(update.type());
            String description = update.description() == null || update.description().isBlank()
                ? type.label() : update.description();
            Goal created = runtime.goals().submit(type, description, "llm", tick);
            if (created != null && update.priority() != null) {
                created.setPriority(update.priority());
            }
        }

        // 2) Pure conversation.
        if (decision.isChat()) {
            String reply = decision.reply().isBlank() ? "嗯嗯" : decision.reply();
            runtime.memory().working().remember(tick, "（回应）" + reply);
            narrate(tick, reply);
            // 闲聊不产生工作，也不要让当前目标被误判成"已完成"。
            activeGoalId = null;
            currentPlan = null;
            return;
        }

        // 3) Work to do.
        if (decision.actions().isEmpty()) {
            SteveMod.LOGGER.info("[Agent] '{}' 决策未包含任何动作", runtime.steve().getSteveName());
            // 模型说了要做事却没给动作：如实说明，而不是静默卡住。
            if (instruction != null) {
                narrate(tick, AgentLang.t("agent.narrate.confused"));
            }
            // 关键：不要把这个目标留成"待办"，否则下一轮 advance() 会发现 stepQueue 是空的，
            // 把它当成"计划已经跑完"并播报"搞定了" —— 玩家就会同时看到"我没想好"和"搞定了"。
            runtime.goals().current().ifPresent(goal ->
                runtime.goals().fail(goal.id(), "模型没有给出可执行步骤"));
            clearFocus();
            return;
        }

        // Make sure there is a goal to hang these steps on, otherwise the next advance()
        // would find nothing and silently drop the work.
        Optional<Goal> current = runtime.goals().current();
        if (current.isEmpty()) {
            runtime.goals().submit(GoalType.TASK,
                instruction == null || instruction.isBlank() ? "自己决定要做的事" : instruction,
                "llm", tick);
            current = runtime.goals().current();
        }

        stepQueue.clear();
        stepQueue.addAll(decision.actions());
        currentPlan = Plan.fromSkill("llm", decision.actions());
        current.ifPresent(goal -> {
            activeGoalId = goal.id();
            referencedGoal = goal;
        });

        if (referencedGoal != null) {
            runtime.memory().episodic().record(tick,
                "和 " + (observation.nearestPlayer() == null ? "自己"
                    : observation.nearestPlayer().name()) + " 一起：" + referencedGoal.description(),
                "", "进行中", 0.4);
        }

        // 说了要做什么，就说出来
        narrate(tick, AgentLang.t("agent.narrate.starting", summarizeActions(decision.actions())));
    }

    /** 把动作列表压成一句可读的话。 */
    private String summarizeActions(java.util.List<ToolCall> actions) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ToolCall call : actions) {
            if (shown >= 3) {
                sb.append(AgentLang.t("agent.act.and_so_on"));
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append(describeTool(call));
            shown++;
        }
        return sb.length() == 0 ? AgentLang.t("agent.act.generic") : sb.toString();
    }

    /** 把一次工具调用说成人话，用于播报"我打算做什么"。 */
    private String describeTool(ToolCall call) {
        return switch (call.tool()) {
            case "break_block" -> AgentLang.t("agent.act.mine", call.string("block", "block"));
            case "place_block" -> AgentLang.t("agent.act.place");
            case "craft_item" -> AgentLang.t("agent.act.craft", call.string("item", "item"));
            case "give_item" -> AgentLang.t("agent.act.give", call.string("item", "item"));
            case "attack_entity" -> AgentLang.t("agent.act.attack", call.string("target", "target"));
            case "follow_player" -> AgentLang.t("agent.act.follow");
            case "move_to" -> AgentLang.t("agent.act.move");
            case "open_container" -> AgentLang.t("agent.act.container");
            case "pickup_item" -> AgentLang.t("agent.act.pickup");
            case "build" -> AgentLang.t("agent.act.build", call.string("structure", "structure"));
            case "explore" -> AgentLang.t("agent.act.explore");
            case "fish" -> AgentLang.t("agent.act.fish");
            case "farm" -> AgentLang.t("agent.act.farm");
            case "use_item" -> AgentLang.t("agent.act.use", call.string("item", "item"));
            // 未知工具直接显示名字，便于排查
            default -> call.tool();
        };
    }

    /**
     * 玩家是不是直接跟 AI 说话（而不是一条与它无关的聊天）。
     *
     * <p>中英双语判断：中文看"我/你"，英文看常见的人称与祈使句式。</p>
     */
    private boolean looksLikeDirectAddress(String instruction) {
        if (instruction == null) {
            return false;
        }
        String lower = instruction.toLowerCase(java.util.Locale.ROOT);
        return instruction.contains("我") || instruction.contains("你")
            || lower.contains("you") || lower.contains(" me") || lower.startsWith("me")
            || lower.contains("can you") || lower.contains("could you")
            || instruction.length() <= 12;
    }

    // ------------------------------------------------------------------
    // Reflection
    // ------------------------------------------------------------------

    private void reflect(Goal goal, ToolCall call, ToolResult result, long tick) {
        Reflection.Outcome outcome = runtime.reflection().reflect(goal, call, result, observation);

        runtime.memory().working().remember(tick, "（失败）" + outcome.cause());
        runtime.memory().episodic().record(tick,
            "尝试「" + goal.description() + "」失败：" + outcome.cause(),
            "", outcome.nextStep(), outcome.importance());

        stepQueue.clear();
        currentPlan = null;
        activeGoalId = null;
        replanCount++;

        SteveMod.LOGGER.info("[Agent] '{}' 反思：{} → {}",
            runtime.steve().getSteveName(), outcome.cause(), outcome.nextStep());

        if (replanCount > MAX_REPLAN) {
            runtime.goals().fail(goal.id(), outcome.cause());
            // 只有在确认做不成时才这么说；措辞也点明是哪一步卡住了。
            narrate(tick, AgentLang.t("agent.narrate.cannot_do",
                goal.description(), outcome.cause()));
            reflectionNote = null;
            return;
        }

        if (outcome.suggestedType() != null && outcome.suggestedGoal() != null) {
            runtime.goals().submit(outcome.suggestedType(), outcome.suggestedGoal(), "reflection", tick);
            reflectionNote = "上一步失败了：" + outcome.cause()
                + "\n请换一种可行的方法继续完成「" + goal.description() + "」。";
            // 先补充说明要去准备什么，再继续
            narrate(tick, AgentLang.t("agent.narrate.recovering",
                outcome.cause(), outcome.nextStep()));
            return;
        }

        if (outcome.needsLlm()) {
            reflectionNote = "上一步失败了：" + outcome.cause()
                + "\n请根据我现在的状态，给出下一步可行做法。";
            if (pendingInstruction == null) {
                pendingInstruction = "（反思后继续）" + goal.description();
            }
            return;
        }

        runtime.goals().fail(goal.id(), outcome.cause());
        narrate(tick, AgentLang.t("agent.narrate.failed", goal.description(), outcome.cause()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ToolContext toolContext() {
        // 只有在"保护玩家"且攻击者确实是玩家时，才临时放行那一个玩家目标。
        java.util.Set<String> allowedPlayers = defendPlayerAttacker == null
            ? java.util.Set.of()
            : java.util.Set.of(defendPlayerAttacker);

        return new ToolContext(runtime.steve(), executor(), observation,
            runtime.memory(), ToolContext.defaultGrant(), allowedPlayers);
    }

    /** 打断当前工作（新指令到来时调用）。 */
    private void interruptCurrentWork() {
        activeGoalId = null;
        currentPlan = null;
        stepQueue.clear();
        dispatchedStep = null;
        dispatchedGoal = null;
    }

    private void clearFocus() {
        activeGoalId = null;
        referencedGoal = null;
        currentPlan = null;
        stepQueue.clear();
        runtime.memory().working().setContext("");
    }

    private ActionExecutor executor() {
        return runtime.steve().getActionExecutor();
    }

    private long nowTick() {
        try {
            return runtime.steve().level().getGameTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    private String nearestPlayerName() {
        Observation.PlayerView player = observation == null ? null : observation.nearestPlayer();
        return player == null ? "玩家" : player.name();
    }

    private static String safeMessage(ActionResult result) {
        String message = result.getMessage();
        return message == null || message.isBlank() ? "动作失败" : message;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    /** Short status line for {@code /as agent}. */
    public String describeState() {
        StringBuilder sb = new StringBuilder();
        sb.append(thinking != null ? "思考中" : (executor() != null && executor().isBusy() ? "执行中" : "空闲"));
        sb.append("，目标=").append(activeGoalId == null ? "无" : activeGoalId);
        sb.append("，待执行步骤=").append(stepQueue.size());
        if (currentPlan != null) {
            sb.append("，计划来源=").append(currentPlan.source());
        }
        return sb.toString();
    }
}
