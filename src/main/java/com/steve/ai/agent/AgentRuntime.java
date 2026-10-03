package com.steve.ai.agent;

import com.steve.ai.SteveMod;
import com.steve.ai.brain.GoalManager;
import com.steve.ai.brain.Needs;
import com.steve.ai.brain.Persona;
import com.steve.ai.brain.Planner;
import com.steve.ai.brain.Reflection;
import com.steve.ai.brain.SocialSystem;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.event.AgentEvent;
import com.steve.ai.event.AgentEventType;
import com.steve.ai.llm.TaskPlanner;
import com.steve.ai.memory.MemoryManager;
import com.steve.ai.perception.PerceptionService;
import com.steve.ai.skill.SkillRegistry;
import com.steve.ai.tool.ToolDispatcher;
import com.steve.ai.tool.ToolRegistry;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The assembled agent: one per AI player, owning every layer.
 *
 * <p>This is the object the architecture document calls the "Agent Runtime". Its constructor is
 * the wiring diagram made real:</p>
 * <pre>
 *   Perception ──┐
 *   Memory       ├─→ Brain (Persona / Needs / Goals / Planner / Reflection / Social)
 *   Event Bus ───┘        │
 *                         ├─→ LLM (only when a skill cannot decide)
 *                         ├─→ Skills
 *                         └─→ Tools ─→ Permission Gate ─→ ActionExecutor ─→ Minecraft
 * </pre>
 *
 * <p>Deliberately <b>not</b> a microservice split. One object graph, one thread, no serialisation
 * - the architecture document's advice ("不要一上来就做成十几个微服务") taken literally. The
 * {@code Agent Protocol} still exists as the boundary, but it is the boundary with the
 * <em>model</em>, not with a network.</p>
 */
public final class AgentRuntime {

    private final SteveEntity steve;

    // --- Layer instances -------------------------------------------------------
    private final PerceptionService perception = new PerceptionService();
    private final MemoryManager memory = new MemoryManager();
    private final Persona persona;
    private final Needs needs = new Needs();
    private final GoalManager goals = new GoalManager();
    private final SkillRegistry skills = SkillRegistry.createDefault();
    private final Planner planner;
    private final Reflection reflection = new Reflection();
    private final SocialSystem social = new SocialSystem();
    private final ToolRegistry tools = ToolRegistry.createDefault();
    private final ToolDispatcher dispatcher;

    // --- Event bus (in-memory, tick-drained) -----------------------------------
    private final Queue<AgentEvent> events = new ConcurrentLinkedQueue<>();

    private final AgentLoop loop;

    /** Created on first use so a Steve standing in an unloaded chunk costs nothing. */
    private TaskPlanner llm;

    public AgentRuntime(SteveEntity steve) {
        this.steve = steve;
        this.persona = new Persona(steve.getSteveName(),
            "自然、简短、偶尔开玩笑，像一个普通玩家");
        this.planner = new Planner(skills);
        this.dispatcher = new ToolDispatcher(tools);
        this.loop = new AgentLoop(this);

        // Config access is defensive: the runtime may be constructed before Forge has
        // finished loading config values in edge cases (world load order, tests).
        try {
            this.goals.setAutonomyEnabled(
                com.steve.ai.config.SteveConfig.ENABLE_AUTONOMY.get());
        } catch (Exception e) {
            SteveMod.LOGGER.warn("[Agent] 读取 autonomy 配置失败，使用默认（开启）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Called every server tick by {@link SteveEntity}. */
    public void tick() {
        loop.tick();
    }

    /** A player told the AI something (via {@code /as say} or chat). */
    public void submitInstruction(String text) {
        loop.requestInstruction(text);
    }

    /** Publishes an event onto the agent bus. */
    public void post(AgentEvent event) {
        if (event != null) {
            events.add(event);
        }
    }

    /** Convenience overload. */
    public void post(AgentEventType type, String source, String summary, long gameTime) {
        post(AgentEvent.of(type, source, summary, gameTime));
    }

    /** Drains one pending event, or {@code null} when the queue is empty. */
    public AgentEvent pollEvent() {
        return events.poll();
    }

    /** Lazily-built LLM gateway (shares the existing resilient, cached client stack). */
    public TaskPlanner llm() {
        if (llm == null) {
            SteveMod.LOGGER.info("[Agent] 为 '{}' 初始化 LLM 网关", steve.getSteveName());
            llm = new TaskPlanner();
        }
        return llm;
    }

    /** Keeps the persona label in step with {@code /as create <name>}. */
    public void onRename(String name) {
        persona.setName(name);
    }

    // ------------------------------------------------------------------
    // Accessors (the loop and the debug commands both need these)
    // ------------------------------------------------------------------

    public SteveEntity steve() { return steve; }
    public PerceptionService perception() { return perception; }
    public MemoryManager memory() { return memory; }
    public Persona persona() { return persona; }
    public Needs needs() { return needs; }
    public GoalManager goals() { return goals; }
    public SkillRegistry skills() { return skills; }
    public Planner planner() { return planner; }
    public Reflection reflection() { return reflection; }
    public SocialSystem social() { return social; }
    public ToolRegistry tools() { return tools; }
    public ToolDispatcher dispatcher() { return dispatcher; }
    public AgentLoop loop() { return loop; }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    /** Multi-line snapshot for {@code /as agent}. */
    public String describe() {
        return "人格：" + persona.archetype()
            + "\n需求：" + needs
            + "\n" + memory.describe()
            + "\n技能：" + skills.describe()
            + "\n工具：" + tools.size() + " 个"
            + "\n循环：" + loop.describeState();
    }

    /** Payload for a {@code NEED_THRESHOLD} style event. */
    public Map<String, Object> needSnapshot() {
        return Map.of(
            "hunger", Math.round(needs.get(Needs.Kind.HUNGER)),
            "safety", Math.round(needs.get(Needs.Kind.SAFETY)),
            "social", Math.round(needs.get(Needs.Kind.SOCIAL)),
            "exploration", Math.round(needs.get(Needs.Kind.EXPLORATION)));
    }
}
