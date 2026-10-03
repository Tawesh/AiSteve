package com.steve.ai.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the LLM is allowed to answer with.
 *
 * <p>Every "slow thought" round trip produces exactly one of these. It is deliberately richer
 * than the old {@code {reasoning, plan, tasks[]}} shape, because the architecture asks the
 * model for three different things at once:</p>
 *
 * <pre>
 * {
 *   "intent": "task",                       // chat | task
 *   "thought": "铁矿在洞里，先跟陶哥进去",       // 推理（可审计）
 *   "goal_update": {                        // 可选：调整/新增目标
 *       "description": "跟随陶哥寻找钻石",
 *       "type": "resource",
 *       "priority": 80
 *   },
 *   "actions": [                            // 要执行的原子能力
 *       { "tool": "follow_player", "arguments": {"player": "陶哥"}, "reason": "..." }
 *   ],
 *   "reply": ""                             // intent=chat 时的正式回复
 * }
 * </pre>
 */
public final class AgentDecision {

    /** What the model believes the player wanted. */
    public enum Intent {
        /** Just talking - no work, only {@link #reply()}. */
        CHAT,
        /** A job to carry out - {@link #actions()} is authoritative. */
        TASK
    }

    /** A requested change to the goal stack. */
    public static final class GoalUpdate {
        private final String description;
        private final String type;
        private final Double priority;
        private final String parentId;

        public GoalUpdate(String description, String type, Double priority, String parentId) {
            this.description = description;
            this.type = type;
            this.priority = priority;
            this.parentId = parentId;
        }

        public String description() {
            return description;
        }

        public String type() {
            return type;
        }

        public Double priority() {
            return priority;
        }

        public String parentId() {
            return parentId;
        }

        public boolean isEmpty() {
            return description == null || description.isBlank();
        }
    }

    private final Intent intent;
    private final String thought;
    private final GoalUpdate goalUpdate;
    private final List<ToolCall> actions;
    private final String reply;
    private final String raw;

    public AgentDecision(Intent intent, String thought, GoalUpdate goalUpdate,
                         List<ToolCall> actions, String reply, String raw) {
        this.intent = intent == null ? Intent.TASK : intent;
        this.thought = thought == null ? "" : thought;
        this.goalUpdate = goalUpdate;
        this.actions = actions == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(actions));
        this.reply = reply == null ? "" : reply;
        this.raw = raw == null ? "" : raw;
    }

    /** A conversational answer with no work attached. */
    public static AgentDecision chat(String reply, String thought) {
        return new AgentDecision(Intent.CHAT, thought, null, List.of(), reply, "");
    }

    /** A plan with work attached. */
    public static AgentDecision task(String thought, GoalUpdate goalUpdate, List<ToolCall> actions) {
        return new AgentDecision(Intent.TASK, thought, goalUpdate, actions, "", "");
    }

    public Intent intent() {
        return intent;
    }

    public boolean isChat() {
        return intent == Intent.CHAT;
    }

    public String thought() {
        return thought;
    }

    public GoalUpdate goalUpdate() {
        return goalUpdate;
    }

    public List<ToolCall> actions() {
        return actions;
    }

    public String reply() {
        return reply;
    }

    /** The untouched model output, kept for logging when parsing looks suspicious. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "AgentDecision{" + intent + ", thought='" + thought + "', actions=" + actions.size()
            + (reply.isEmpty() ? "" : ", reply='" + reply + "'") + "}";
    }
}
