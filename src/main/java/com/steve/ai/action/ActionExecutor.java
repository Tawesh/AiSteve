package com.steve.ai.action;

import com.steve.ai.SteveMod;
import com.steve.ai.action.actions.*;
import com.steve.ai.di.ServiceContainer;
import com.steve.ai.di.SimpleServiceContainer;
import com.steve.ai.event.EventBus;
import com.steve.ai.event.SimpleEventBus;
import com.steve.ai.execution.*;
import com.steve.ai.llm.ResponseParser;
import com.steve.ai.llm.TaskPlanner;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.plugin.ActionRegistry;
import com.steve.ai.plugin.PluginManager;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * Executes actions for a Steve entity using the plugin-based action system.
 *
 * <p><b>Architecture:</b></p>
 * <ul>
 *   <li>Uses ActionRegistry for dynamic action creation (Factory + Registry patterns)</li>
 *   <li>Uses InterceptorChain for cross-cutting concerns (logging, metrics, events)</li>
 *   <li>Uses AgentStateMachine for explicit state management</li>
 *   <li>Falls back to legacy switch statement if registry lookup fails</li>
 * </ul>
 *
 * @since 1.1.0
 */
public class ActionExecutor {
    private final SteveEntity steve;
    private TaskPlanner taskPlanner;  // Lazy-initialized to avoid loading dependencies on entity creation
    private final Queue<Task> taskQueue;

    private BaseAction currentAction;
    private String currentGoal;
    private int ticksSinceLastAction;
    private BaseAction idleFollowAction;  // Follow player when idle
    // NEW: Async planning support (non-blocking LLM calls)
    private CompletableFuture<ResponseParser.ParsedResponse> planningFuture;
    private boolean isPlanning = false;
    private String pendingCommand;  // Store command while planning

    // ------------------------------------------------------------------
    // Layered-agent integration (see ARCHITECTURE.md)
    // ------------------------------------------------------------------

    /** The Task currently being executed, so a finished action can be correlated with it. */
    private Task currentTask;

    /** Notified whenever an action starts or finishes; used by {@code AgentLoop}. */
    private ExecutionListener executionListener;

    /**
     * When true the layered {@code AgentRuntime} owns planning and replanning, and this executor
     * only runs the queue it is handed.
     *
     * <p>Without this lock a failed step would be replanned twice - once by the legacy
     * {@link #maybeReplan} and once by the agent's reflection layer - and the two would fight
     * over the same task queue.</p>
     */
    private boolean externalPlanningEnabled = false;

    /** Observer for action lifecycle events. */
    public interface ExecutionListener {
        void onActionStarted(String actionName, Task task);

        void onActionFinished(String actionName, Task task, ActionResult result);
    }

    /** Queues a task directly (used by the Tool layer via the Agent Runtime). */
    public void enqueueTask(Task task) {
        if (task != null) {
            taskQueue.add(task);
        }
    }

    /** Number of steps still waiting. */
    public int getQueueSize() {
        return taskQueue.size();
    }

    /**
     * True while the AI is doing anything at all: planning, executing, or holding a queue.
     *
     * <p>The agent loop uses this to stay out of the way while an action owns the body.</p>
     */
    public boolean isBusy() {
        return isPlanning || currentAction != null || !taskQueue.isEmpty();
    }

    /** Registers the agent-loop observer. */
    public void setExecutionListener(ExecutionListener listener) {
        this.executionListener = listener;
    }

    /**
     * Hands planning authority to the layered Agent Runtime.
     *
     * @param enabled true to disable this executor's own replanning
     */
    public void setExternalPlanningEnabled(boolean enabled) {
        this.externalPlanningEnabled = enabled;
    }

    // Self-correction: remember the original instruction so a failed action can be
    // reported back to the LLM for replanning instead of silently giving up.
    private static final int MAX_REPLAN_ATTEMPTS = 3;
    private String lastCommand;
    private int replanAttempts;

    // NEW: Plugin architecture components
    private final ActionContext actionContext;
    private final InterceptorChain interceptorChain;
    private final AgentStateMachine stateMachine;
    private final EventBus eventBus;

    public ActionExecutor(SteveEntity steve) {
        this.steve = steve;
        this.taskPlanner = null;  // Will be initialized when first needed
        this.taskQueue = new LinkedList<>();
        this.ticksSinceLastAction = 0;
        this.idleFollowAction = null;
        this.planningFuture = null;
        this.pendingCommand = null;

        // Initialize plugin architecture components
        this.eventBus = new SimpleEventBus();
        this.stateMachine = new AgentStateMachine(eventBus, steve.getSteveName());
        this.interceptorChain = new InterceptorChain();

        // Setup interceptors
        interceptorChain.addInterceptor(new LoggingInterceptor());
        interceptorChain.addInterceptor(new MetricsInterceptor());
        interceptorChain.addInterceptor(new EventPublishingInterceptor(eventBus, steve.getSteveName()));

        // Build action context
        ServiceContainer container = new SimpleServiceContainer();
        this.actionContext = ActionContext.builder()
            .serviceContainer(container)
            .eventBus(eventBus)
            .stateMachine(stateMachine)
            .interceptorChain(interceptorChain)
            .build();

        SteveMod.LOGGER.debug("ActionExecutor initialized with plugin architecture for Steve '{}'",
            steve.getSteveName());
    }
    
    private TaskPlanner getTaskPlanner() {
        if (taskPlanner == null) {
            SteveMod.LOGGER.info("Initializing TaskPlanner for Steve '{}'", steve.getSteveName());
            taskPlanner = new TaskPlanner();
        }
        return taskPlanner;
    }

    /**
     * Processes a natural language command using ASYNC non-blocking LLM calls.
     *
     * <p>This method returns immediately and does NOT block the game thread.
     * The LLM response is processed in tick() when the CompletableFuture completes.</p>
     *
     * <p><b>Non-blocking flow:</b></p>
     * <ol>
     *   <li>User sends command</li>
     *   <li>This method starts async LLM call, returns immediately</li>
     *   <li>Game continues running normally (no freeze!)</li>
     *   <li>tick() checks if planning is done</li>
     *   <li>When done, tasks are queued and execution begins</li>
     * </ol>
     *
     * @param command The natural language command from the user
     */
    public void processNaturalLanguageCommand(String command) {
        SteveMod.LOGGER.info("Steve '{}' processing command (async): {}", steve.getSteveName(), command);

        // Immediate user feedback
        if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
            sendToGUI(steve.getSteveName(), "收到指令，正在思考...");
        }

        // If already planning, ignore new commands
        if (isPlanning) {
            SteveMod.LOGGER.warn("Steve '{}' is already planning, ignoring command: {}", steve.getSteveName(), command);
            sendToGUI(steve.getSteveName(), "Hold on, I'm still thinking about the previous command...");
            return;
        }

        // Cancel any current actions
        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }

        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }

        try {
            // Store command and start async planning
            // Store command and start async planning
            this.pendingCommand = command;
            this.isPlanning = true;
            this.lastCommand = command;
            this.replanAttempts = 0;


            // No "Thinking..." chatter - the AI speaks only when it has something
            // meaningful to say (a chat reply, or a `say` action asking for help).


            // Start async LLM call - returns immediately!
            planningFuture = getTaskPlanner().planTasksAsync(steve, command);

            SteveMod.LOGGER.info("Steve '{}' started async planning for: {}", steve.getSteveName(), command);

        } catch (NoClassDefFoundError e) {
            SteveMod.LOGGER.error("Failed to initialize AI components", e);
            sendToGUI(steve.getSteveName(), "Sorry, I'm having trouble with my AI systems!");
            isPlanning = false;
            planningFuture = null;
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error starting async planning", e);
            sendToGUI(steve.getSteveName(), "Oops, something went wrong!");
            isPlanning = false;
            planningFuture = null;
        }
    }

    /**
     * Legacy synchronous command processing (blocking).
     *
     * <p><b>Warning:</b> This method blocks the game thread for 30-60 seconds during LLM calls.
     * Use {@link #processNaturalLanguageCommand(String)} instead for non-blocking execution.</p>
     *
     * @param command The natural language command
     * @deprecated Use {@link #processNaturalLanguageCommand(String)} instead
     */
    @Deprecated
    public void processNaturalLanguageCommandSync(String command) {
        SteveMod.LOGGER.info("Steve '{}' processing command (SYNC - blocking!): {}", steve.getSteveName(), command);

        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }

        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }

        try {
            ResponseParser.ParsedResponse response = getTaskPlanner().planTasks(steve, command);

            if (response == null) {
                sendToGUI(steve.getSteveName(), "I couldn't understand that command.");
                return;
            }

            currentGoal = response.getPlan();
            steve.getMemory().setCurrentGoal(currentGoal);

            // NEW: Task Decomposition Layer - validate preconditions and decompose if needed
            com.steve.ai.context.WorldContext worldContext =
                com.steve.ai.context.EnvironmentScanner.scan(steve);
            com.steve.ai.decomposition.TaskDecomposer.DecompositionResult decomposition =
                com.steve.ai.decomposition.TaskDecomposer.decompose(
                    response.getTasks(), worldContext, steve);

            taskQueue.clear();

            if (!decomposition.canProceed()) {
                SteveMod.LOGGER.warn("Steve '{}' cannot proceed - missing: {}",
                    steve.getSteveName(), decomposition.getMissingRequirements());
                sendToGUI(steve.getSteveName(),
                    "我需要：" + String.join(", ", decomposition.getMissingRequirements()));
                return;
            }

            taskQueue.addAll(decomposition.getTasks());

            if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                sendToGUI(steve.getSteveName(), "Okay! " + currentGoal);
            }
        } catch (NoClassDefFoundError e) {
            SteveMod.LOGGER.error("Failed to initialize AI components", e);
            sendToGUI(steve.getSteveName(), "Sorry, I'm having trouble with my AI systems!");
        }

        SteveMod.LOGGER.info("Steve '{}' queued {} tasks", steve.getSteveName(), taskQueue.size());
    }
    
    /**
     * Send a message to the GUI pane (client-side only, no chat spam)
    /**
     * Relays a short status line from the AI to the players' chat.
     *
     * <p>Replaces the removed K-panel GUI: the AI now talks in chat like a player would.</p>
     */
    private void sendToGUI(String steveName, String message) {
        if (!steve.level().isClientSide) {
            steve.sendChatMessage(message);
        }
    }

    /**
     * Feeds a failed action back to the LLM so it can try a different approach.
     *
     * <p>This is what turns a one-shot command translator into an actual agent: when a step
     * fails (item missing, path blocked, target gone, bad parameters) the model is told
     * exactly what went wrong together with the current inventory and surroundings, and gets
     * to produce a corrected plan - for example asking the player for a flint and steel
     * instead of stubbornly retrying.</p>
     *
     * <p>Bounded by {@link #MAX_REPLAN_ATTEMPTS} to guarantee termination.</p>
     *
     * @param failureMessage human readable reason reported by the failed action
     */
    private void maybeReplan(ActionResult result) {
        if (externalPlanningEnabled) {
            // The layered AgentRuntime owns replanning while it is driving. Its reflection
            // layer has already been notified through the execution listener, and letting
            // this method fire as well would clear the queue out from under it.
            return;
        }
        if (isPlanning) {
            return;                       // a replan is already in flight
        }
        if (lastCommand == null) {
            return;                       // nothing to retry against
        }

        String failureMessage = result.getMessage();

        // Remaining planned steps take priority: finish the plan before rethinking it.
        // Discarding valid work just because one step failed produced pointless LLM calls.
        if (!taskQueue.isEmpty()) {
            SteveMod.LOGGER.info(
                "Steve '{}' step failed ({}), but {} step(s) remain - continuing the plan",
                steve.getSteveName(), failureMessage, taskQueue.size());
            return;
        }

        // No steps left and still not done. One bounded attempt to recover...
        if (replanAttempts < MAX_REPLAN_ATTEMPTS) {
            replanAttempts++;
            SteveMod.LOGGER.info("Steve '{}' replanning (attempt {}/{}): {}",
                steve.getSteveName(), replanAttempts, MAX_REPLAN_ATTEMPTS, failureMessage);

            String followUp = lastCommand
                + "\n\n[系统反馈] 上一步执行失败：" + failureMessage
                + "\n请根据我当前的状态换一种可行的方法继续完成原任务。"
                + "如果缺少必要物品，请用 say 动作向我索要。如果确实无法完成，请用 say 说明原因。";

            // The corrected plan fully replaces whatever was left.
            taskQueue.clear();
            this.pendingCommand = followUp;
            this.isPlanning = true;
            planningFuture = getTaskPlanner().planTasksAsync(steve, followUp);
            return;
        }

        // ...then stop cleanly. Retrying forever is worse than an honest "can't do it".
        SteveMod.LOGGER.warn("Steve '{}' giving up after {} replan attempts: {}",
            steve.getSteveName(), replanAttempts, failureMessage);
        sendToGUI(steve.getSteveName(), "这个我暂时做不了：" + failureMessage);
        closeGoal("gave up");
    }

    /**
     * Marks the current goal as finished and returns the AI to an idle, following state.
     *
     * <p>Used both on success and when giving up, so the AI never stays stuck believing a job
     * is still outstanding after it has actually stopped.</p>
     *
     * @param reason short reason, for logging
     */
    private void closeGoal(String reason) {
        if (currentGoal != null && !currentGoal.isEmpty()) {
            SteveMod.LOGGER.info("Steve '{}' closed goal '{}' ({})",
                steve.getSteveName(), currentGoal, reason);
            steve.getMemory().addAction("结束: " + currentGoal);

            // Give user feedback on successful completion
            if ("completed".equals(reason) && SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                sendToGUI(steve.getSteveName(), "完成了！");
            }
        }
        currentGoal = null;
        steve.getMemory().setCurrentGoal(null);
        taskQueue.clear();
    }

    public void tick() {
        ticksSinceLastAction++;
        handlePlanningResult();

        // Companion behaviour (follow / loiter / catch-up)
        maintainCompanionBehaviour();

        if (currentAction != null) {
            if (currentAction.isComplete()) {
                ActionResult result = currentAction.getResult();
                SteveMod.LOGGER.info("Steve '{}' - Action completed: {} (Success: {})",
                    steve.getSteveName(), result.getMessage(), result.isSuccess());

                steve.getMemory().addAction(currentAction.getDescription());

                // Keep the layered agent loop informed about the *real* outcome, which only
                // becomes known here - the tool call itself returned "scheduled" long ago.
                if (executionListener != null) {
                    executionListener.onActionFinished(
                        currentTask != null ? currentTask.getAction() : "unknown",
                        currentTask, result);
                }

                if (!result.isSuccess()) {
                    if (result.requiresReplanning()) {
                        maybeReplan(result);
                    } else {
                        // The action already decided retrying is pointless - do not loop.
                        SteveMod.LOGGER.info("Steve '{}' stopping on non-retryable outcome: {}",
                            steve.getSteveName(), result.getMessage());
                        if (taskQueue.isEmpty()) {
                            closeGoal("unrecoverable step");
                        }
                    }
                }

                currentAction = null;
            } else {
                if (ticksSinceLastAction % 100 == 0) {
                    SteveMod.LOGGER.info("Steve '{}' - Ticking action: {}",
                        steve.getSteveName(), currentAction.getDescription());
                }
                currentAction.tick();
                return;
            }
        }

        if (ticksSinceLastAction >= SteveConfig.ACTION_TICK_DELAY.get() && !taskQueue.isEmpty()) {
            Task nextTask = taskQueue.poll();
            executeTask(nextTask);
            ticksSinceLastAction = 0;
            return;
        }

        // Nothing queued and nothing running -> the job is done
        if (taskQueue.isEmpty() && currentAction == null) {
            clearGoalIfFinished();
        }
    }




    /**
     * Consumes the finished async LLM call, if any, and either answers conversationally
     * (chat intent) or queues the produced tasks (task intent).
     */
    private void handlePlanningResult() {
        if (!isPlanning || planningFuture == null || !planningFuture.isDone()) {
            return;
        }
        try {
            ResponseParser.ParsedResponse response = planningFuture.get();

            if (response == null) {
                sendToGUI(steve.getSteveName(),
                    "我这边没处理成功（解析或接口问题），看下日志再试一次吧。");
                SteveMod.LOGGER.warn("Steve '{}' async planning returned null response", steve.getSteveName());
                return;
            }

            if (response.isChat()) {
                currentGoal = null;
                steve.getMemory().setCurrentGoal(null);
                taskQueue.clear();
                String reply = response.getReply();
                String actualReply = (reply == null || reply.isBlank()) ? "嗯嗯" : reply;
                sendToGUI(steve.getSteveName(), actualReply);

                // Record conversation turn for multi-turn dialogue
                steve.getMemory().addConversationTurn(pendingCommand, actualReply);

                SteveMod.LOGGER.info("Steve '{}' replied conversationally: {}", steve.getSteveName(), actualReply);
                return;
            }

            currentGoal = response.getPlan();
            steve.getMemory().setCurrentGoal(currentGoal);

            // Record conversation turn with plan description
            String planSummary = currentGoal != null ? currentGoal : "执行任务";
            steve.getMemory().addConversationTurn(pendingCommand, planSummary);

            // NEW: Task Decomposition Layer - validate preconditions and decompose if needed
            com.steve.ai.context.WorldContext worldContext =
                com.steve.ai.context.EnvironmentScanner.scan(steve);
            com.steve.ai.decomposition.TaskDecomposer.DecompositionResult decomposition =
                com.steve.ai.decomposition.TaskDecomposer.decompose(
                    response.getTasks(), worldContext, steve);

            taskQueue.clear();

            if (!decomposition.canProceed()) {
                // Missing requirements that cannot be auto-resolved
                SteveMod.LOGGER.warn("Steve '{}' cannot proceed - missing: {}",
                    steve.getSteveName(), decomposition.getMissingRequirements());
                sendToGUI(steve.getSteveName(),
                    "我需要：" + String.join(", ", decomposition.getMissingRequirements()));
                return;
            }

            taskQueue.addAll(decomposition.getTasks());

            SteveMod.LOGGER.info("Steve '{}' async planning complete: {} tasks queued (decomposed from {} original)",
                steve.getSteveName(), taskQueue.size(), response.getTasks().size());

        } catch (java.util.concurrent.CancellationException e) {
            SteveMod.LOGGER.info("Steve '{}' planning was cancelled", steve.getSteveName());
            sendToGUI(steve.getSteveName(), "Planning cancelled.");
        } catch (Exception e) {
            SteveMod.LOGGER.error("Steve '{}' failed to get planning result", steve.getSteveName(), e);
            sendToGUI(steve.getSteveName(), "Oops, something went wrong while planning!");
        } finally {
            isPlanning = false;
            planningFuture = null;
            pendingCommand = null;
        }
    }

    // ------------------------------------------------------------------
    // Companion behaviour (tamed-wolf-like trailing + loitering)
    // ------------------------------------------------------------------

    /** How far the AI may stray before it walks back to the player. */
    private static final double FOLLOW_START_DISTANCE = 6.0;
    /** Once this close, it stops walking and just watches. */
    private static final double FOLLOW_STOP_DISTANCE = 3.0;
    /** Beyond this the AI is considered lost and is moved back to the player. */
    private static final double CATCHUP_TELEPORT_DISTANCE = 48.0;
    /** If it cannot make progress toward the player for this long, snap back (ticks). */
    private static final int CATCHUP_STUCK_TICKS = 120;
    /** Minimum gap between idle strolls (ticks). */
    private static final int STROLL_INTERVAL_TICKS = 120;

    private int ticksSinceStroll = 0;
    private int ticksWithoutProgress = 0;
    private double lastFollowX, lastFollowZ;

    /**
     * True while the player's request is still outstanding.
     *
     * <p>"Outstanding" means the AI is planning, still has queued steps, is executing one,
     * or has a goal it has not finished yet.</p>
     */
    public boolean hasActiveTask() {
        return isPlanning
            || currentAction != null
            || !taskQueue.isEmpty()
            || (currentGoal != null && !currentGoal.isEmpty());
    }

    /**
     * Keeps the AI behaving like a companion: it hangs around the player, walks back when
     * it drifts away, and does small idle strolls so it does not stand frozen like a statue.
     *
     * <p>This runs whenever no action is executing - both between the steps of an unfinished
     * job and when completely idle. Because it returns early while an action is running, it
     * never fights a task for pathfinding control.</p>
     */
    private void maintainCompanionBehaviour() {
        if (isPlanning || currentAction != null || !taskQueue.isEmpty()) {
            return; // the task itself owns movement right now
        }

        net.minecraft.world.entity.player.Player player =
            com.steve.ai.util.ActionUtils.findNearestPlayer(steve);
        if (player == null) {
            return;
        }

        double distance = steve.distanceTo(player);

        // Last resort: never stay lost far away or trapped underground.
        if (distance > CATCHUP_TELEPORT_DISTANCE) {
            catchUpTo(player, "was " + (int) distance + "m away");
            return;
        }

        if (distance > FOLLOW_START_DISTANCE) {
            steve.getNavigation().moveTo(player, 1.1);
            steve.getLookControl().setLookAt(player);

            // Detect "trying to reach the player but making no headway" (e.g. stuck in a
            // mineshaft) and snap back rather than silently disappearing from view.
            double cx = steve.getX();
            double cz = steve.getZ();
            if (Math.abs(cx - lastFollowX) < 0.05 && Math.abs(cz - lastFollowZ) < 0.05) {
                ticksWithoutProgress++;
                if (ticksWithoutProgress > CATCHUP_STUCK_TICKS) {
                    catchUpTo(player, "could not path back (stuck)");
                }
            } else {
                ticksWithoutProgress = 0;
            }
            lastFollowX = cx;
            lastFollowZ = cz;
            return;
        }

        // Close enough: stop, then loiter around like a real player would.
        ticksWithoutProgress = 0;
        if (distance < FOLLOW_STOP_DISTANCE) {
            steve.getNavigation().stop();
        }
        maybeStroll(player);
    }

    /** Moves the AI right next to the player so it can never become permanently lost. */
    private void catchUpTo(net.minecraft.world.entity.player.Player player, String reason) {
        steve.getNavigation().stop();
        steve.teleportTo(player.getX(), player.getY(), player.getZ());
        ticksWithoutProgress = 0;
        SteveMod.LOGGER.info("Steve '{}' caught up to {} ({})",
            steve.getSteveName(), player.getName().getString(), reason);
    }

    /**
     * Occasionally walks a few blocks around the player.
     *
     * <p>Purely cosmetic: it makes the AI look alive (a real player never stands perfectly
     * still) while keeping it close to the player.</p>
     */
    private void maybeStroll(net.minecraft.world.entity.player.Player player) {
        ticksSinceStroll++;
        if (ticksSinceStroll < STROLL_INTERVAL_TICKS) {
            return;
        }
        ticksSinceStroll = 0;

        if (steve.getNavigation().isInProgress()) {
            return;
        }
        if (steve.getRandom().nextFloat() > 0.6f) {
            return; // not every time - keeps it relaxed
        }

        double angle = steve.getRandom().nextDouble() * Math.PI * 2.0;
        double radius = 2.0 + steve.getRandom().nextDouble() * 5.0;
        double tx = player.getX() + Math.cos(angle) * radius;
        double tz = player.getZ() + Math.sin(angle) * radius;

        steve.getNavigation().moveTo(tx, player.getY(), tz, 0.75);
    }

    /**
     * Marks the outstanding job as done once every step has run.
     *
     * <p>The AI keeps following the player after this (see
     * {@link #maintainCompanionBehaviour()}); only the goal bookkeeping is cleared.</p>
     */
    private void clearGoalIfFinished() {
        if (isPlanning || currentAction != null || !taskQueue.isEmpty()) {
            return;
        }
        // Single code path for closing goals - keeps success and give-up handling identical.
        closeGoal("completed");
    }



    private void executeTask(Task task) {
        SteveMod.LOGGER.info("Steve '{}' executing task: {} (action type: {})", 
            steve.getSteveName(), task, task.getAction());
        
        currentTask = task;
        currentAction = createAction(task);
        
        if (currentAction == null) {
            SteveMod.LOGGER.error("FAILED to create action for task: {}", task);
            return;
        }

        SteveMod.LOGGER.info("Created action: {} - starting now...", currentAction.getClass().getSimpleName());
        currentAction.start();
        SteveMod.LOGGER.info("Action started! Is complete: {}", currentAction.isComplete());

        if (executionListener != null) {
            executionListener.onActionStarted(task.getAction(), task);
        }
    }

    /**
     * Creates an action using the plugin registry with legacy fallback.
     *
     * <p>First attempts to create the action via ActionRegistry (plugin system).
     * If the registry doesn't have the action or creation fails, falls back
     * to the legacy switch statement for backward compatibility.</p>
     *
     * @param task Task containing action type and parameters
     * @return Created action, or null if unknown action type
     */
    private BaseAction createAction(Task task) {
        String actionType = task.getAction();

        // Check if action is allowed by capability settings
        if (!com.steve.ai.config.ActionCapabilities.isActionAllowed(actionType)) {
            SteveMod.LOGGER.warn("Action '{}' is disabled in capability settings", actionType);
            sendToGUI(steve.getSteveName(), "抱歉，" + actionType + " 功能已被禁用");
            return null;
        }

        // Try registry-based creation first (plugin architecture)
        ActionRegistry registry = ActionRegistry.getInstance();
        if (registry.hasAction(actionType)) {
            BaseAction action = registry.createAction(actionType, steve, task, actionContext);
            if (action != null) {
                SteveMod.LOGGER.debug("Created action '{}' via registry (plugin: {})",
                    actionType, registry.getPluginForAction(actionType));
                return action;
            }
        }

        // Fallback to legacy switch statement for backward compatibility
        SteveMod.LOGGER.debug("Using legacy fallback for action: {}", actionType);
        return createActionLegacy(task);
    }

    /**
     * Legacy action creation using switch statement.
     *
     * <p>Kept for backward compatibility during migration to plugin system.
     * Will be removed in a future version once all actions are registered
     * via plugins.</p>
     *
     * @param task Task containing action type and parameters
     * @return Created action, or null if unknown
     * @deprecated Use ActionRegistry instead
     */
    @Deprecated
    private BaseAction createActionLegacy(Task task) {
        return switch (task.getAction()) {
            case "pathfind" -> new PathfindAction(steve, task);
            case "mine" -> new MineBlockAction(steve, task);
            case "place" -> new PlaceBlockAction(steve, task);
            case "craft" -> new CraftItemAction(steve, task);
            case "attack" -> new CombatAction(steve, task);
            case "follow" -> new FollowPlayerAction(steve, task);
            case "gather" -> new GatherResourceAction(steve, task);
            case "build" -> new BuildStructureAction(steve, task);
            case "pickup" -> new PickupItemsAction(steve, task);
            case "give" -> new GiveItemAction(steve, task);
            case "ignite" -> new UseItemAction(steve, task);
            case "use_item" -> new UseItemAction(steve, task);
            case "say" -> new SayAction(steve, task);
            case "loot_container" -> new LootContainerAction(steve, task);
            case "explore" -> new ExploreAction(steve, task);
            case "fish" -> new FishingAction(steve, task);
            case "farm" -> new FarmAction(steve, task);
            default -> {
                SteveMod.LOGGER.warn("Unknown action type: {}", task.getAction());
                yield null;
            }
        };
    }

    public void stopCurrentAction() {
        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }
        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
        taskQueue.clear();
        currentGoal = null;

        // Reset state machine
        stateMachine.reset();
    }

    public boolean isExecuting() {
        return currentAction != null || !taskQueue.isEmpty();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    /**
     * Returns the event bus for subscribing to action events.
     *
     * @return EventBus instance
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * Returns the agent state machine.
     *
     * @return AgentStateMachine instance
     */
    public AgentStateMachine getStateMachine() {
        return stateMachine;
    }

    /**
     * Returns the interceptor chain for adding custom interceptors.
     *
     * @return InterceptorChain instance
     */
    public InterceptorChain getInterceptorChain() {
        return interceptorChain;
    }

    /**
     * Returns the action context.
     *
     * @return ActionContext instance
     */
    public ActionContext getActionContext() {
        return actionContext;
    }

    /**
     * Checks if the agent is currently planning (async LLM call in progress).
     *
     * @return true if planning
     */
    public boolean isPlanning() {
        return isPlanning;
    }
}

