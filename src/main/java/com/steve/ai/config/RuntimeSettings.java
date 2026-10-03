package com.steve.ai.config;

import com.steve.ai.SteveMod;
import com.steve.ai.i18n.ConversationLanguage;

/**
 * 行为类设置的运行时快照。
 *
 * <p><b>为什么需要它：</b>Forge 的 {@code ConfigValue#get()} 每次都要走一遍 spec 查找，
 * 而这些值会被放在每 tick 的热路径上读（活动半径、是否播报、是否自主行动）。
 * 直接每 tick 调用 {@code get()} 既浪费又容易踩到配置尚未加载的时序问题。</p>
 *
 * <p>所以这里在启动时和每次保存配置后各刷新一次，其余时间读的是普通字段。
 * 好处是：玩家在设置界面点"保存"之后，**不需要重启游戏**就能看到新行为生效。</p>
 */
public final class RuntimeSettings {

    /** 默认值同时作为"配置尚未加载"时的兜底。 */
    private static final int DEFAULT_ROAM_RADIUS = 48;

    private static volatile int roamRadius = DEFAULT_ROAM_RADIUS;
    private static volatile boolean autonomy = true;
    private static volatile boolean idleChat = true;
    private static volatile boolean progressNarration = true;
    private static volatile boolean chatResponses = true;
    private static volatile boolean defendAgainstPlayers = true;
    private static volatile boolean agentEnabled = true;
    private static volatile ConversationLanguage conversationLanguage = ConversationLanguage.ZH_CN;

    private RuntimeSettings() {
    }

    /** 从 Forge 配置重新读取一次。配置未就绪时保持上一次的值（或默认值）。 */
    public static void refresh() {
        roamRadius = read(() -> SteveConfig.ROAM_RADIUS.get(), DEFAULT_ROAM_RADIUS);
        autonomy = read(() -> SteveConfig.ENABLE_AUTONOMY.get(), true);
        idleChat = read(() -> SteveConfig.ENABLE_IDLE_CHAT.get(), true);
        progressNarration = read(() -> SteveConfig.ENABLE_PROGRESS_NARRATION.get(), true);
        chatResponses = read(() -> SteveConfig.ENABLE_CHAT_RESPONSES.get(), true);
        defendAgainstPlayers = read(() -> SteveConfig.DEFEND_AGAINST_PLAYERS.get(), true);
        agentEnabled = read(() -> SteveConfig.ENABLE_AGENT.get(), true);
        conversationLanguage = ConversationLanguage.parse(
            read(() -> SteveConfig.AGENT_LANGUAGE.get(), "zh_cn"),
            ConversationLanguage.ZH_CN);

        SteveMod.LOGGER.debug(
            "[AiSteve] 运行时设置已刷新: 活动半径={}, 自主={}, 主动说话={}, 进度播报={}",
            roamRadius, autonomy, idleChat, progressNarration);
    }

    /** 与玩家保持的最大距离（方块）。 */
    public static int roamRadius() {
        return roamRadius;
    }

    /** 没人下指令时是否自己找事做。 */
    public static boolean autonomy() {
        return autonomy;
    }

    /** 是否允许主动开口（报备、提醒、闲聊）。 */
    public static boolean idleChat() {
        return idleChat;
    }

    /** 是否播报计划与结果（"先砍树，再做木镐" / "搞定了"）。 */
    public static boolean progressNarration() {
        return progressNarration;
    }

    /** 是否允许聊天回应。 */
    public static boolean chatResponses() {
        return chatResponses;
    }

    /**
     * 是否允许在 PvP 中反击攻击被保护玩家的那个人。
     *
     * <p>这是整个 Agent 里唯一能让 AI 把玩家当目标的开关，默认开启（"保护玩家"的本意），
     * 且只针对刚刚动手的那一个玩家、在有限时间内有效。</p>
     */
    public static boolean defendAgainstPlayers() {
        return defendAgainstPlayers;
    }

    /**
     * 是否使用分层 Agent 运行时。
     *
     * <p>每 tick 动态判断，因此在设置界面里切换后**不需要重启游戏**。</p>
     */
    public static boolean agentEnabled() {
        return agentEnabled;
    }

    /**
     * AI 的默认对话语言（没人说过话时用）。
     *
     * <p>注意这不影响模组界面：界面由 Minecraft 自己的翻译系统按**每个客户端**的语言渲染。</p>
     */
    public static ConversationLanguage conversationLanguage() {
        return conversationLanguage;
    }

    /**
     * 读取一个配置值，失败时返回兜底值。
     *
     * <p>配置系统在极早期（或单元测试里）可能还没初始化，此时 {@code get()} 会抛异常。
     * 设置读取绝不能成为让整个模组失效的单点。</p>
     */
    private static <T> T read(java.util.function.Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
