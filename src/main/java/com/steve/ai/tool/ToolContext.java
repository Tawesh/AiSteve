package com.steve.ai.tool;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.execution.MovementController;
import com.steve.ai.i18n.AgentLang;
import com.steve.ai.memory.MemoryManager;
import com.steve.ai.protocol.Observation;
import com.steve.ai.protocol.Permission;
import com.steve.ai.protocol.ToolCall;
import com.steve.ai.protocol.ToolResult;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Everything a {@link Tool} is allowed to touch when it runs.
 *
 * <p>Two jobs. First, dependency plumbing: a tool needs the entity, the observation, memory and
 * a way to queue long-running work. Second, and more importantly, <b>authority</b>: the
 * permission set lives here and {@link ToolDispatcher} enforces it, so a tool cannot escalate
 * its own privileges.</p>
 */
public final class ToolContext {

    private final SteveEntity steve;
    private final ActionExecutor executor;
    private final MovementController movement;
    private final Observation observation;
    private final MemoryManager memory;
    private final Set<Permission> granted;
    /**
     * 允许被攻击的玩家名（小写）。
     *
     * <p>正常情况下 AI 绝不攻击玩家。唯一的例外是"保护模式"：某个玩家打了它要保护的队友，
     * 它去反击。白名单由事件驱动地临时设置，避免出现"模型学会打人"的通用路径。</p>
     */
    private final Set<String> allowedPlayerTargets;

    public ToolContext(SteveEntity steve, ActionExecutor executor, Observation observation,
                       MemoryManager memory, Set<Permission> granted) {
        this(steve, executor, observation, memory, granted, Set.of());
    }

    public ToolContext(SteveEntity steve, ActionExecutor executor, Observation observation,
                       MemoryManager memory, Set<Permission> granted,
                       Set<String> allowedPlayerTargets) {
        this.steve = steve;
        this.executor = executor;
        this.movement = new MovementController(steve);
        this.observation = observation;
        this.memory = memory;
        this.granted = granted == null ? EnumSet.noneOf(Permission.class) : granted;
        this.allowedPlayerTargets = allowedPlayerTargets == null
            ? Set.of()
            : allowedPlayerTargets.stream()
                .filter(java.util.Objects::nonNull)
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** The default grant: every real capability except {@link Permission#ADMIN}. */
    public static Set<Permission> defaultGrant() {
        Set<Permission> permissions = EnumSet.allOf(Permission.class);
        permissions.remove(Permission.ADMIN);
        return permissions;
    }

    public SteveEntity steve() {
        return steve;
    }

    public ActionExecutor executor() {
        return executor;
    }

    public MovementController movement() {
        return movement;
    }

    public Observation observation() {
        return observation;
    }

    public MemoryManager memory() {
        return memory;
    }

    /**
     * Queues a long-running action.
     *
     * <p>This is the bridge from the new layered architecture down to the existing, battle-tested
     * {@code Action} implementations. Tools describe <em>what</em>; the action queue still owns
     * the per-tick execution.</p>
     */
    public void enqueue(String action, Map<String, Object> parameters) {
        executor.enqueueTask(new Task(action, parameters));
    }

    /** {@inheritDoc} */
    public boolean allows(Permission permission) {
        return granted.contains(permission);
    }

    /** 该玩家是否处于"可以反击"的保护白名单中（默认永远为否）。 */
    public boolean isAllowedPlayerTarget(String playerName) {
        if (playerName == null || allowedPlayerTargets.isEmpty()) {
            return false;
        }
        return allowedPlayerTargets.contains(playerName.toLowerCase(java.util.Locale.ROOT));
    }

    /** Convenience: fail fast with a well-formed result if the tool cannot be honest. */
    public ToolResult requireArgs(ToolCall call, String... keys) {
        for (String key : keys) {
            String value = call.string(key);
            if (value == null || value.isBlank()) {
                return ToolResult.badArguments(AgentLang.t("agent.tool.missing_arg", key));
            }
        }
        return null;
    }
}
