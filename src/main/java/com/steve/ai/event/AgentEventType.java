package com.steve.ai.event;

/**
 * The event vocabulary of the Agent Event Bus.
 *
 * <p>These are the only things allowed to wake the slow-thinking loop. Using a closed set,
 * rather than letting any code call the LLM, is what keeps the architecture honest:</p>
 * <pre>
 * 陶哥: "AI，跟我去找钻石"
 *        ↓
 *    PLAYER_CHAT
 *        ↓
 *    SocialSystem → GoalManager → Goal: 跟随陶哥寻找钻石
 * </pre>
 * <p>...instead of throwing the sentence at one omnipotent prompt.</p>
 */
public enum AgentEventType {

    /** A player said something in chat. */
    PLAYER_CHAT("玩家说话", true),
    /** A player came within interaction range. */
    PLAYER_NEARBY("玩家靠近", false),
    /** A player (or the AI) took damage. */
    PLAYER_HURT("受到伤害", true),
    /** A threat entered perception range. */
    ENTITY_DETECTED("发现实体", false),
    /** A resource worth gathering was noticed. */
    BLOCK_FOUND("发现方块", false),
    /** A block was broken. */
    BLOCK_BROKEN("破坏方块", false),
    /** An item was picked up. */
    ITEM_PICKED("拾取物品", false),
    /** Health dropped into the danger zone. */
    LOW_HEALTH("血量过低", true),
    /** A goal was achieved. */
    GOAL_COMPLETED("目标完成", false),
    /** A player died. */
    PLAYER_DIED("玩家死亡", true),
    /** The world changed around the AI (new biome, dimension...). */
    WORLD_CHANGED("世界变化", false),
    /** An action failed and the loop should consider reflecting. */
    ACTION_FAILED("动作失败", true),
    /** An internal need crossed its threshold. */
    NEED_THRESHOLD("需求阈值", true),
    /** The AI died. */
    AGENT_DIED("AI死亡", true);

    private final String label;
    private final boolean wakesBrain;

    AgentEventType(String label, boolean wakesBrain) {
        this.label = label;
        this.wakesBrain = wakesBrain;
    }

    public String label() {
        return label;
    }

    /** True when this event alone justifies spending an LLM call. */
    public boolean wakesBrain() {
        return wakesBrain;
    }
}
