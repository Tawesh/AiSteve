package com.steve.ai.llm;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.context.EnvironmentScanner;
import com.steve.ai.context.WorldContext;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.llm.async.*;
import com.steve.ai.llm.resilience.LLMFallbackHandler;
import com.steve.ai.llm.resilience.ResilientLLMClient;
import com.steve.ai.memory.WorldKnowledge;
import com.steve.ai.protocol.AgentDecision;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class TaskPlanner {
    // Legacy synchronous clients (for backward compatibility)
    private final OpenAIClient openAIClient;
    private final GeminiClient geminiClient;
    private final GroqClient groqClient;
    private final DeepSeekClient deepSeekClient;

    // NEW: Async resilient clients
    private final AsyncLLMClient asyncOpenAIClient;
    private final AsyncLLMClient asyncGroqClient;
    private final AsyncLLMClient asyncGeminiClient;
    private final AsyncLLMClient asyncDeepSeekClient;
    private final LLMCache llmCache;
    private final LLMFallbackHandler fallbackHandler;

    public TaskPlanner() {
        // Legacy clients
        this.openAIClient = new OpenAIClient();
        this.geminiClient = new GeminiClient();
        this.groqClient = new GroqClient();
        this.deepSeekClient = new DeepSeekClient();

        // Initialize async infrastructure
        this.llmCache = new LLMCache();
        this.fallbackHandler = new LLMFallbackHandler();

        // Initialize async clients with resilience wrappers
        String apiKey = SteveConfig.OPENAI_API_KEY.get();
        String model = SteveConfig.OPENAI_MODEL.get();
        int maxTokens = SteveConfig.MAX_TOKENS.get();
        double temperature = SteveConfig.TEMPERATURE.get();

        // Create base async clients
        AsyncLLMClient baseOpenAI = new AsyncOpenAIClient(apiKey, model, maxTokens, temperature);
        AsyncLLMClient baseGroq = new AsyncGroqClient(apiKey, "llama-3.1-8b-instant", 500, temperature);
        AsyncLLMClient baseGemini = new AsyncGeminiClient(apiKey, "gemini-1.5-flash", maxTokens, temperature);
        String deepSeekKey = SteveConfig.DEEPSEEK_API_KEY.get();
        if (deepSeekKey == null || deepSeekKey.isEmpty()) {
            deepSeekKey = apiKey;
        }
        AsyncLLMClient baseDeepSeek = new AsyncDeepSeekClient(
            deepSeekKey, SteveConfig.DEEPSEEK_MODEL.get(), maxTokens, temperature,
            SteveConfig.DEEPSEEK_BASE_URL.get());

        // Wrap with resilience patterns
        this.asyncOpenAIClient = new ResilientLLMClient(baseOpenAI, llmCache, fallbackHandler);
        this.asyncGroqClient = new ResilientLLMClient(baseGroq, llmCache, fallbackHandler);
        this.asyncGeminiClient = new ResilientLLMClient(baseGemini, llmCache, fallbackHandler);
        this.asyncDeepSeekClient = new ResilientLLMClient(baseDeepSeek, llmCache, fallbackHandler);

        SteveMod.LOGGER.info("TaskPlanner initialized with async resilient clients");
    }

    public ResponseParser.ParsedResponse planTasks(SteveEntity steve, String command) {
        try {
            String systemPrompt = PromptBuilder.buildSystemPrompt();

            // NEW: Use environment scanner for structured context
            WorldContext worldContext = EnvironmentScanner.scan(steve);
            String userPrompt = PromptBuilder.buildUserPrompt(steve, command, worldContext);

            String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
            SteveMod.LOGGER.info("Requesting AI plan for Steve '{}' using {}: {}", steve.getSteveName(), provider, command);

            String response = getAIResponse(provider, systemPrompt, userPrompt);

            if (response == null) {
                SteveMod.LOGGER.error("Failed to get AI response for command: {}", command);
                return null;
            }

            ResponseParser.ParsedResponse parsedResponse = ResponseParser.parseAIResponse(response);

            if (parsedResponse == null) {
                SteveMod.LOGGER.error("Failed to parse AI response");
                return null;
            }

            SteveMod.LOGGER.info("Plan: {} ({} tasks)", parsedResponse.getPlan(), parsedResponse.getTasks().size());

            return parsedResponse;

        } catch (Exception e) {
            SteveMod.LOGGER.error("Error planning tasks", e);
            return null;
        }
    }

    /**
     * Asks the model for an {@link AgentDecision} using the layered agent prompt.
     *
     * <p>This is the new entry point used by {@code AgentLoop}. It reuses the exact same
     * provider selection, caching, circuit breaker and fallback stack as the legacy
     * {@link #planTasksAsync} path - only the prompt contract and the response shape differ.</p>
     *
     * @param systemPrompt output of {@code AgentPromptBuilder.buildSystemPrompt}
     * @param userPrompt   output of {@code AgentPromptBuilder.buildUserPrompt}
     * @return a future that completes with the parsed decision, or {@code null} on any failure
     */
    public CompletableFuture<AgentDecision> decideAsync(String systemPrompt, String userPrompt) {
        try {
            String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
            warnIfApiKeyMissing(provider);

            String model = provider.equals("deepseek")
                ? SteveConfig.DEEPSEEK_MODEL.get()
                : SteveConfig.OPENAI_MODEL.get();

            Map<String, Object> params = Map.of(
                "systemPrompt", systemPrompt,
                "model", model,
                "maxTokens", SteveConfig.MAX_TOKENS.get(),
                "temperature", SteveConfig.TEMPERATURE.get()
            );

            AsyncLLMClient client = getAsyncClient(provider);

            return client.sendAsync(userPrompt, params)
                .thenApply(response -> {
                    if (response == null || response.getContent() == null
                        || response.getContent().isEmpty()) {
                        SteveMod.LOGGER.error("[Agent] LLM 返回为空");
                        return null;
                    }
                    AgentDecision decision = AgentDecisionParser.parse(response.getContent());
                    if (decision == null) {
                        SteveMod.LOGGER.error("[Agent] 决策解析失败");
                        return null;
                    }
                    SteveMod.LOGGER.info("[Agent] 决策: {} ({}ms, {} tokens, cache: {})",
                        decision, response.getLatencyMs(), response.getTokensUsed(),
                        response.isFromCache());
                    return decision;
                })
                .exceptionally(throwable -> {
                    SteveMod.LOGGER.error("[Agent] 决策请求失败: {}", throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            SteveMod.LOGGER.error("[Agent] 决策初始化失败", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private String getAIResponse(String provider, String systemPrompt, String userPrompt) {
        String response = switch (provider) {
            case "groq" -> groqClient.sendRequest(systemPrompt, userPrompt);
            case "gemini" -> geminiClient.sendRequest(systemPrompt, userPrompt);
            case "openai" -> openAIClient.sendRequest(systemPrompt, userPrompt);
            case "deepseek" -> deepSeekClient.sendRequest(systemPrompt, userPrompt);
            default -> {
                SteveMod.LOGGER.warn("Unknown AI provider '{}', using Groq", provider);
                yield groqClient.sendRequest(systemPrompt, userPrompt);
            }
        };

        if (response == null && !provider.equals("groq")) {
            SteveMod.LOGGER.warn("{} failed, trying Groq as fallback", provider);
            response = groqClient.sendRequest(systemPrompt, userPrompt);
        }

        return response;
    }

    /**
     * Asynchronously plans tasks for Steve using the configured LLM provider.
     *
     * <p>This method returns immediately with a CompletableFuture, allowing the game thread
     * to continue without blocking. The actual LLM call is executed on a separate thread pool
     * with full resilience patterns (circuit breaker, retry, rate limiting, caching).</p>
     *
     * <p><b>Non-blocking:</b> Game thread is never blocked</p>
     * <p><b>Resilient:</b> Automatic retry, circuit breaker, fallback on failure</p>
     * <p><b>Cached:</b> Repeated prompts may hit cache (40-60% hit rate)</p>
     *
     * @param steve   The Steve entity making the request
     * @param command The user command to plan
     * @return CompletableFuture that completes with the parsed response, or null on failure
     */
    public CompletableFuture<ResponseParser.ParsedResponse> planTasksAsync(SteveEntity steve, String command) {
        try {
            String systemPrompt = PromptBuilder.buildSystemPrompt();

            // NEW: Use environment scanner for structured context
            WorldContext worldContext = EnvironmentScanner.scan(steve);
            String userPrompt = PromptBuilder.buildUserPrompt(steve, command, worldContext);

            String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
            warnIfApiKeyMissing(provider);
            SteveMod.LOGGER.info("[Async] Requesting AI plan for Steve '{}' using {}: {}",
                steve.getSteveName(), provider, command);

            // Build params map
            Map<String, Object> params = Map.of(
                "systemPrompt", systemPrompt,
                "model", provider.equals("deepseek") ? SteveConfig.DEEPSEEK_MODEL.get() : SteveConfig.OPENAI_MODEL.get(),
                "maxTokens", SteveConfig.MAX_TOKENS.get(),
                "temperature", SteveConfig.TEMPERATURE.get()
            );

            // Select async client based on provider
            AsyncLLMClient client = getAsyncClient(provider);

            // Execute async request
            return client.sendAsync(userPrompt, params)
                .thenApply(response -> {
                    String content = response.getContent();
                    if (content == null || content.isEmpty()) {
                        SteveMod.LOGGER.error("[Async] Empty response from LLM");
                        return null;
                    }

                    ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(content);
                    if (parsed == null) {
                        SteveMod.LOGGER.error("[Async] Failed to parse AI response");
                        return null;
                    }

                    SteveMod.LOGGER.info("[Async] Plan received: {} ({} tasks, {}ms, {} tokens, cache: {})",
                        parsed.getPlan(),
                        parsed.getTasks().size(),
                        response.getLatencyMs(),
                        response.getTokensUsed(),
                        response.isFromCache());

                    return parsed;
                })
                .exceptionally(throwable -> {
                    SteveMod.LOGGER.error("[Async] Error planning tasks: {}", throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            SteveMod.LOGGER.error("[Async] Error setting up task planning", e);
            return CompletableFuture.completedFuture(null);
        }
    }
    /**
     * Logs an explicit, actionable error when the selected provider has no API key.
     *
     * <p>Without this, a missing key only surfaces as an opaque HTTP 403/401 followed by a
     * rule-based fallback, which looks like "the Steve just ignores my commands".</p>
     *
     * @param provider Selected provider id (lowercase)
     */
    private void warnIfApiKeyMissing(String provider) {
        if (hasApiKeyFor(provider)) {
            return;
        }
        SteveMod.LOGGER.error(
            "No API key configured for provider '{}'. Set [deepseek].apiKey (or [openai].apiKey) " +
            "in config/aisteve-common.toml and RESTART the game. " +
            "Until then, commands will fail and fall back to rule-based no-op responses.",
            provider);
    }

    /**
     * Returns whether an API key is available for the given provider.
     *
     * <p>DeepSeek falls back to {@code [openai].apiKey} when {@code [deepseek].apiKey} is empty,
     * matching the behaviour of the client constructors.</p>
     *
     * @param provider Provider id (lowercase)
     * @return true if a non-empty key is present
     */
    private boolean hasApiKeyFor(String provider) {
        if ("deepseek".equals(provider)) {
            String deepSeekKey = SteveConfig.DEEPSEEK_API_KEY.get();
            if (deepSeekKey != null && !deepSeekKey.isEmpty()) {
                return true;
            }
        }
        String sharedKey = SteveConfig.OPENAI_API_KEY.get();
        return sharedKey != null && !sharedKey.isEmpty();
    }

    /**
     * Returns the appropriate async client based on provider config.
     *
     * @param provider Provider name ("openai", "groq", "gemini", "deepseek")
     * @return Resilient async client
     */
    private AsyncLLMClient getAsyncClient(String provider) {
        return switch (provider) {
            case "openai" -> asyncOpenAIClient;
            case "gemini" -> asyncGeminiClient;
            case "groq" -> asyncGroqClient;
            case "deepseek" -> asyncDeepSeekClient;
            default -> {
                SteveMod.LOGGER.warn("[Async] Unknown provider '{}', using Groq", provider);
                yield asyncGroqClient;
            }
        };
    }

    /**
     * Returns the LLM cache for monitoring.
     *
     * @return LLM cache instance
     */
    public LLMCache getLLMCache() {
        return llmCache;
    }

    /**
     * Checks if the specified provider's async client is healthy.
     *
     * @param provider Provider name
     * @return true if healthy (circuit breaker not OPEN)
     */
    public boolean isProviderHealthy(String provider) {
        return getAsyncClient(provider).isHealthy();
    }

    public boolean validateTask(Task task) {
        String action = task.getAction();
        
        return switch (action) {
            case "pathfind" -> task.hasParameters("x", "y", "z");
            case "mine" -> task.hasParameters("block", "quantity");
            case "place" -> task.hasParameters("block", "x", "y", "z");
            case "craft" -> task.hasParameters("item", "quantity");
            case "attack" -> task.hasParameters("target");
            case "build" -> task.hasParameters("structure", "blocks", "dimensions");
            case "pickup" -> true;                        // item filter is optional
            case "give" -> task.hasParameters("item");
            case "use_item" -> task.hasParameters("item");
            case "say" -> task.hasParameters("message");
            case "loot_container" -> true;                 // all parameters optional
            case "explore" -> true;                        // all parameters optional
            case "fish" -> true;                           // quantity optional
            case "farm" -> true;                           // quantity optional
            default -> {
                SteveMod.LOGGER.warn("Unknown action type: {}", action);
                yield false;
            }
        };
    }

    public List<Task> validateAndFilterTasks(List<Task> tasks) {
        return tasks.stream()
            .filter(this::validateTask)
            .toList();
    }
}


