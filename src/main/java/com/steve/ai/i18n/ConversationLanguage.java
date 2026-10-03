package com.steve.ai.i18n;

import java.util.Locale;

/**
 * 对话语言 / Conversation languages the AI can speak.
 *
 * <p><b>为什么需要它：</b>Minecraft 的语言设置是**纯客户端**的 —— 服务端拿不到玩家把客户端
 * 设成了什么语言。所以"模组界面跟着客户端语言走"可以靠 {@code Component.translatable} 自动实现，
 * 但 **AI 说出来的话是服务端生成的字符串**，没法自动适配。</p>
 *
 * <p>因此 AI 的对话语言单独跟踪：玩家用什么语言跟它说话，它就用什么语言回话
 * （见 {@link AgentLang#observePlayerSpeech}）。没人说话时用 {@link #ZH_CN}（默认）。</p>
 */
public enum ConversationLanguage {

    /** 简体中文 */
    ZH_CN("zh_cn", "简体中文"),

    /** English */
    EN_US("en_us", "English");

    /** 语言代码，与 Minecraft 的 locale 命名一致（{@code zh_cn} / {@code en_us}）。 */
    private final String code;

    /** 语言自己的名字，用于日志和指令输出。 */
    private final String displayName;

    ConversationLanguage(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    /** 给 LLM 看的语言名（附带英文名，模型认得更准）。 */
    public String promptName() {
        return this == ZH_CN ? "简体中文 (Simplified Chinese)" : "English";
    }

    /**
     * 按语言代码解析，未知代码回退到 {@link #ZH_CN}。
     *
     * <p>支持 {@code zh_cn}、{@code zh-cn}、{@code zh}、{@code en_us}、{@code en} 等形式，
     * 因为玩家在配置里手写时格式并不统一。</p>
     */
    public static ConversationLanguage parse(String raw, ConversationLanguage fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (ConversationLanguage language : values()) {
            if (language.code.equals(key)) {
                return language;
            }
        }
        // 容忍只写了语言主体的情况
        if (key.startsWith("zh")) {
            return ZH_CN;
        }
        if (key.startsWith("en")) {
            return EN_US;
        }
        return fallback;
    }

    /** 除自己以外的另一种语言（本项目只有两种，够用）。 */
    public ConversationLanguage opposite() {
        return this == ZH_CN ? EN_US : ZH_CN;
    }
}
