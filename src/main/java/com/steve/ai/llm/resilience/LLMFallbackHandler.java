package com.steve.ai.llm.resilience;

import com.steve.ai.llm.async.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fallback handler that generates pattern-based responses when LLM calls fail.
 *
 * <p>Provides graceful degradation when all LLM providers are unavailable.
 * Uses simple pattern matching to recognize common Minecraft commands and
 * generate appropriate action responses.</p>
 *
 * <p><b>When is this used?</b></p>
 * <ul>
 *   <li>Circuit breaker is OPEN (provider experiencing failures)</li>
 *   <li>All retry attempts exhausted</li>
 *   <li>Rate limiter rejects request</li>
 *   <li>Network is completely unavailable</li>
 *   <li>No API key configured (the most common cause)</li>
 * </ul>
 *
 * <p><b>Response format contract (IMPORTANT):</b></p>
 * <p>Responses must match exactly what {@link com.steve.ai.llm.ResponseParser} expects,
 * otherwise the tasks are silently dropped:</p>
 * <ul>
 *   <li>Action name lives in {@code "action"}</li>
 *   <li>All action arguments MUST be nested inside a {@code "parameters"} object</li>
 *   <li>Only actions registered in {@code ActionExecutor} / {@code TaskPlanner} are valid:
 *       {@code mine, place, craft, attack, follow, gather, build, pathfind}</li>
 * </ul>
 * <p>An empty {@code "tasks"} array is valid and means "do nothing" (Steve keeps idling).</p>
 *
 * @since 1.1.0
 */
public class LLMFallbackHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LLMFallbackHandler.class);

    // Pattern-based fallback responses in JSON format matching ResponseParser expectations.
    // NOTE: parameters MUST be nested under "parameters", and only registered actions are used.
    private static final Map<Pattern, String> PATTERN_RESPONSES = new LinkedHashMap<>();

    static {
        // Greeting patterns - should respond with a friendly chat message
        register("(?i)^(hi|hello|hey|你好|嗨|嘿)\\b.*",
            "{\"reasoning\":\"[Fallback] Greeting detected\",\"plan\":\"Respond to greeting\",\"tasks\":[{\"action\":\"say\",\"parameters\":{\"message\":\"Hello! How can I help you?\"}}]}");
        register("(?i).*(你好|您好|早上好|晚上好|下午好).*",
            "{\"reasoning\":\"[Fallback] Greeting detected (zh)\",\"plan\":\"Respond to greeting\",\"tasks\":[{\"action\":\"say\",\"parameters\":{\"message\":\"你好！有什么可以帮你的吗？\"}}]}");

        // Mining patterns (English + Chinese) - more specific to avoid false matches
        register("(?i)\\b(mine|dig|get me|find|collect).*(diamond|iron|coal|gold|copper|redstone|emerald|ore)\\b.*",
            "{\"reasoning\":\"[Fallback] Mining detected\",\"plan\":\"Mine ore\",\"tasks\":[{\"action\":\"mine\",\"parameters\":{\"block\":\"iron\",\"quantity\":16}}]}");
        register("(?i).*(挖矿|采矿|挖煤|挖铁|挖金|找钻石|找铁矿|采集矿物).*",
            "{\"reasoning\":\"[Fallback] Mining detected (zh)\",\"plan\":\"Mine ore\",\"tasks\":[{\"action\":\"mine\",\"parameters\":{\"block\":\"iron\",\"quantity\":16}}]}");

        // Building patterns (English + Chinese)
        register("(?i)\\b(build|construct|create|make).*(house|home|shelter|structure|base|castle|tower|barn)\\b.*",
            "{\"reasoning\":\"[Fallback] Building detected\",\"plan\":\"Build structure\",\"tasks\":[{\"action\":\"build\",\"parameters\":{\"structure\":\"house\",\"blocks\":[\"oak_planks\",\"cobblestone\",\"glass_pane\"],\"dimensions\":[9,6,9]}}]}");
        register("(?i).*(建造|盖房|造房子|搭建|建个).*(房子|屋子|城堡|塔|仓库|基地|家).*",
            "{\"reasoning\":\"[Fallback] Building detected (zh)\",\"plan\":\"Build structure\",\"tasks\":[{\"action\":\"build\",\"parameters\":{\"structure\":\"house\",\"blocks\":[\"oak_planks\",\"cobblestone\",\"glass_pane\"],\"dimensions\":[9,6,9]}}]}");

        // Combat patterns (English + Chinese)
        register("(?i)\\b(attack|fight|kill|destroy).*(mob|hostile|monster|zombie|skeleton|creeper|spider)\\b.*",
            "{\"reasoning\":\"[Fallback] Combat detected\",\"plan\":\"Attack hostiles\",\"tasks\":[{\"action\":\"attack\",\"parameters\":{\"target\":\"hostile\"}}]}");
        register("(?i).*(攻击|打怪|击杀|杀死|干掉|清理).*(怪物|僵尸|骷髅|苦力怕|蜘蛛).*",
            "{\"reasoning\":\"[Fallback] Combat detected (zh)\",\"plan\":\"Attack hostiles\",\"tasks\":[{\"action\":\"attack\",\"parameters\":{\"target\":\"hostile\"}}]}");

        // Follow patterns (English + Chinese)
        register("(?i)\\b(follow|come here|come with|stay with)\\b.*",
            "{\"reasoning\":\"[Fallback] Follow detected\",\"plan\":\"Follow player\",\"tasks\":[{\"action\":\"follow\",\"parameters\":{\"player\":\"USE_NEARBY_PLAYER_NAME\"}}]}");
        register("(?i).*(跟随|跟着我|过来|跟我走|别走开).*",
            "{\"reasoning\":\"[Fallback] Follow detected (zh)\",\"plan\":\"Follow player\",\"tasks\":[{\"action\":\"follow\",\"parameters\":{\"player\":\"USE_NEARBY_PLAYER_NAME\"}}]}");

        // Gathering patterns
        register("(?i)\\b(gather|chop|get|collect).*(wood|log|tree|oak|birch|spruce)\\b.*",
            "{\"reasoning\":\"[Fallback] Gathering detected\",\"plan\":\"Gather wood\",\"tasks\":[{\"action\":\"gather\",\"parameters\":{\"resource\":\"wood\",\"quantity\":32}}]}");
        register("(?i).*(砍树|砍木头|收集木头|采集树木|伐木).*",
            "{\"reasoning\":\"[Fallback] Gathering detected (zh)\",\"plan\":\"Gather wood\",\"tasks\":[{\"action\":\"gather\",\"parameters\":{\"resource\":\"wood\",\"quantity\":32}}]}");
    }

    private static void register(String regex, String response) {
        PATTERN_RESPONSES.put(Pattern.compile(regex), response);
    }

    /**
     * Default response when no pattern matches.
     *
     * <p>Returns an EMPTY task list on purpose. The legacy fallback used an
     * undocumented "wait" action, which is not registered in the action system,
     * so it produced a confusing "Unknown action type: wait" error and did nothing.
     * An empty task list is a clean no-op: the Steve simply stays idle.</p>
     */
    private static final String DEFAULT_RESPONSE =
        "{\"reasoning\":\"[Fallback] No pattern matched; no-op\",\"plan\":\"No action\",\"tasks\":[]}";

    /**
     * Generates a fallback response based on pattern matching.
     *
     * @param prompt Original prompt that failed
     * @param error  The error that triggered the fallback (for logging)
     * @return LLMResponse containing pattern-matched action or a safe no-op
     */
    public LLMResponse generateFallback(String prompt, Throwable error) {
        LOGGER.warn("Generating fallback response for prompt: '{}' (error: {})",
            truncatePrompt(prompt, 50),
            error != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : "unknown");

        String responseContent = matchPattern(prompt);
        String matchedPattern = responseContent.equals(DEFAULT_RESPONSE) ? "default" : "pattern-match";

        LOGGER.warn("Fallback response generated (matched: {}). " +
            "This is rule-based, NOT a real LLM reply. Check your API key / network if this happens often.",
            matchedPattern);

        return LLMResponse.builder()
            .content(responseContent)
            .model("fallback-pattern-matcher")
            .providerId("fallback")
            .latencyMs(0)
            .tokensUsed(0)
            .fromCache(false)
            .build();
    }

    private String matchPattern(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return DEFAULT_RESPONSE;
        }

        String lowerPrompt = prompt.toLowerCase();

        for (Map.Entry<Pattern, String> entry : PATTERN_RESPONSES.entrySet()) {
            if (entry.getKey().matcher(lowerPrompt).find()) {
                LOGGER.debug("Matched pattern: {}", entry.getKey().pattern());
                return entry.getValue();
            }
        }

        LOGGER.debug("No pattern matched, using default no-op response");
        return DEFAULT_RESPONSE;
    }

    private String truncatePrompt(String prompt, int maxLength) {
        if (prompt == null) {
            return "[null]";
        }
        if (prompt.length() <= maxLength) {
            return prompt;
        }
        return prompt.substring(0, maxLength) + "...";
    }

    /**
     * Checks if a prompt would match any known pattern.
     *
     * @param prompt The prompt to check
     * @return true if a pattern matches, false if would use default
     */
    public boolean wouldMatchPattern(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return false;
        }

        String lowerPrompt = prompt.toLowerCase();
        return PATTERN_RESPONSES.keySet().stream()
            .anyMatch(pattern -> pattern.matcher(lowerPrompt).find());
    }

    /**
     * Returns the number of registered patterns.
     *
     * @return Pattern count
     */
    public int getPatternCount() {
        return PATTERN_RESPONSES.size();
    }
}
