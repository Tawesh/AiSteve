package com.steve.ai.i18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.SteveMod;
import com.steve.ai.config.RuntimeSettings;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-side language support for the things the <b>AI itself says</b>.
 *
 * <p><b>Why this cannot use {@code Component.translatable}.</b> Minecraft's translation system
 * resolves text on the <em>client</em>, which is perfect for the mod's own UI (see
 * {@code assets/aisteve/lang/*.json}). But the AI's chat lines and status reports are built on the
 * <em>server</em> as plain strings before being broadcast - the server has no idea what language
 * any given client is set to, because that setting never leaves the client.</p>
 *
 * <p>So AI speech gets its own track: the conversation language is <b>inferred from what the
 * player types</b> ({@link #observePlayerSpeech}) and used to pick the right bundle. A player
 * speaking English gets an English-speaking companion; a player speaking Chinese gets a
 * Chinese-speaking one. That is also what a real teammate would do - you do not get to configure
 * the language a person replies in.</p>
 *
 * <p>Bundles live in {@code assets/aisteve/agent/strings_<locale>.json} - deliberately outside
 * {@code lang/} so the client's resource loader never mistakes them for client translations.</p>
 */
public final class AgentLang {

    /** Resource directory holding the server-side bundles. */
    private static final String RESOURCE_DIR = "/assets/aisteve/agent/strings_";

    /** Fallback used when a key is missing from every bundle. */
    private static final ConversationLanguage FALLBACK = ConversationLanguage.ZH_CN;

    private static final Map<ConversationLanguage, Map<String, String>> BUNDLES =
        new EnumMap<>(ConversationLanguage.class);

    /**
     * The language the AI is currently speaking.
     *
     * <p>Updated whenever a player says something, and reset from config on load.</p>
     */
    private static final AtomicReference<ConversationLanguage> CURRENT =
        new AtomicReference<>(FALLBACK);

    private static volatile boolean loaded = false;

    private AgentLang() {
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Loads every bundle. Safe to call more than once. */
    public static synchronized void load() {
        BUNDLES.clear();
        for (ConversationLanguage language : ConversationLanguage.values()) {
            BUNDLES.put(language, loadBundle(language));
        }

        // 让配置里的默认语言生效（玩家还没说过话时用它）
        CURRENT.set(RuntimeSettings.conversationLanguage());
        loaded = true;

        SteveMod.LOGGER.info("[AiSteve] 语言包已加载: {} 条(zh) / {} 条(en)，默认对话语言: {}",
            bundleSize(ConversationLanguage.ZH_CN),
            bundleSize(ConversationLanguage.EN_US),
            CURRENT.get().code());
    }

    private static int bundleSize(ConversationLanguage language) {
        Map<String, String> bundle = BUNDLES.get(language);
        return bundle == null ? 0 : bundle.size();
    }

    private static Map<String, String> loadBundle(ConversationLanguage language) {
        Map<String, String> map = new HashMap<>();
        String path = RESOURCE_DIR + language.code() + ".json";

        try (InputStream in = AgentLang.class.getResourceAsStream(path)) {
            if (in == null) {
                SteveMod.LOGGER.error("[AiSteve] 找不到语言包资源: {}", path);
                return map;
            }
            JsonObject root = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    map.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Exception e) {
            // 语言包加载失败不应该让模组起不来：退化成"显示 key"，功能照常。
            SteveMod.LOGGER.error("[AiSteve] 语言包解析失败: {}", path, e);
        }
        return map;
    }

    // ------------------------------------------------------------------
    // Language tracking
    // ------------------------------------------------------------------

    /** 当前 AI 说话用的语言。 */
    public static ConversationLanguage current() {
        if (!loaded) {
            load();
        }
        return CURRENT.get();
    }

    /** 手动指定 AI 的说话语言（{@code /as lang <zh_cn|en_us>}）。 */
    public static void setCurrent(ConversationLanguage language) {
        if (language != null) {
            CURRENT.set(language);
        }
    }

    /**
     * 根据玩家说的话更新对话语言。
     *
     * <p>只有"明显是某种语言"的句子才会改变当前语言，避免一句 {@code "ok"} 或一串数字
     * 就把语言切走。</p>
     *
     * @return true 表示本次确实改变了语言（调用方可能要记日志）
     */
    public static boolean observePlayerSpeech(String text) {
        ConversationLanguage detected = detect(text);
        if (detected == null) {
            return false;
        }
        return CURRENT.getAndSet(detected) != detected;
    }

    /**
     * 判断一段文本最可能是哪种语言。
     *
     * <p>用的是**字符集比例**而不是语言检测库：中日韩字符和拉丁字母在实际输入里几乎不会
     * 混用到难以判断的程度，所以统计一下占比就足够可靠，而且零依赖、零成本。</p>
     *
     * @return 判定出的语言，或 {@code null} 表示无法判断（调用方应保持原语言）
     */
    public static ConversationLanguage detect(String text) {
        if (text == null) {
            return null;
        }

        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else if (Character.isLetter(c) && c < 0x2E80) {
                latin++;
            }
        }

        int letters = cjk + latin;
        // 太短的输入（"ok"、"?"）信息量不足，不据此切换语言。
        if (letters < 2) {
            return null;
        }

        // 只要出现一定比例的中日韩字符就按中文处理：中英混写（"帮我 mine 20 iron"）很常见，
        // 这种情况下玩家期望的仍然是中文回复。
        if (cjk > 0 && (double) cjk / letters >= 0.15) {
            return ConversationLanguage.ZH_CN;
        }
        if (latin > 0) {
            return ConversationLanguage.EN_US;
        }
        return null;
    }

    /** 中日韩统一表意文字 + 假名 + 谚文。 */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
            || (c >= 0x3400 && c <= 0x4DBF)      // 扩展 A
            || (c >= 0x3040 && c <= 0x30FF)      // 平假名 / 片假名
            || (c >= 0xAC00 && c <= 0xD7AF)      // 谚文
            || (c >= 0xF900 && c <= 0xFAFF);     // 兼容汉字
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    /**
     * 取一条 AI 用语，按当前对话语言渲染。
     *
     * <p>用 {@link String#format} 做参数替换，所以语言文件里写 {@code %s} / {@code %d}。</p>
     *
     * @param key  语言文件里的键
     * @param args 格式化参数
     * @return 渲染后的字符串；键缺失时回退到英文包，再缺失则原样返回键名（便于发现遗漏）
     */
    public static String t(String key, Object... args) {
        if (!loaded) {
            load();
        }

        String template = lookup(CURRENT.get(), key);
        if (template == null) {
            template = lookup(FALLBACK, key);
        }
        if (template == null) {
            template = lookup(ConversationLanguage.EN_US, key);
        }
        if (template == null) {
            SteveMod.LOGGER.warn("[AiSteve] 缺少语言条目: {}", key);
            return key;
        }

        if (args == null || args.length == 0) {
            return template;
        }
        try {
            return String.format(template, args);
        } catch (Exception e) {
            // 占位符和参数对不上时不要把整句话吞掉，原样返回更容易排查。
            SteveMod.LOGGER.warn("[AiSteve] 语言条目 '{}' 格式化失败: {}", key, e.getMessage());
            return template;
        }
    }

    /** 指定语言渲染，用于必须固定语言的场景（例如给日志用的英文）。 */
    public static String t(ConversationLanguage language, String key, Object... args) {
        if (!loaded) {
            load();
        }
        String template = lookup(language, key);
        if (template == null) {
            template = lookup(FALLBACK, key);
        }
        if (template == null) {
            return key;
        }
        return args == null || args.length == 0 ? template : String.format(template, args);
    }

    private static String lookup(ConversationLanguage language, String key) {
        Map<String, String> bundle = BUNDLES.get(language);
        return bundle == null ? null : bundle.get(key);
    }

    /** 某个键在当前语言下是否存在（用于测试和诊断）。 */
    public static boolean has(String key) {
        return lookup(CURRENT.get(), key) != null;
    }
}
