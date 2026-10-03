package com.steve.ai.llm.async;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous DeepSeek API client using Java HttpClient's sendAsync().
 *
 * <p>DeepSeek exposes an OpenAI-compatible chat completions API, so the request
 * and response formats match OpenAI's, but the base URL and models differ:</p>
 *
 * <ul>
 *   <li><b>Base URL:</b> https://api.deepseek.com (context path /chat/completions)</li>
 *   <li><b>Models:</b> deepseek-flash (V4.1-Flash, recommended), deepseek-v4-pro (V4-Pro);
 *       older deepseek-chat / deepseek-reasoner may still be available on some accounts</li>
 * </ul>
 *
 * <p>The base URL is configurable so that proxies or self-hosted gateways can be used.</p>
 *
 * <p><b>Thread Safety:</b> Thread-safe. HttpClient is thread-safe and immutable.</p>
 *
 * @since 1.2.0
 */
public class AsyncDeepSeekClient implements AsyncLLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncDeepSeekClient.class);
    private static final String PROVIDER_ID = "deepseek";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final String endpoint;

    /**
     * Constructs an AsyncDeepSeekClient.
     *
     * @param apiKey      DeepSeek API key (may be empty; validated lazily on request)
     * @param model       Model to use (e.g., "deepseek-flash")
     * @param maxTokens   Maximum tokens in response (e.g., 8000)
     * @param temperature Response randomness (0.0 - 2.0)
     * @param baseUrl     API base URL (e.g., "https://api.deepseek.com")
     */
    public AsyncDeepSeekClient(String apiKey, String model, int maxTokens, double temperature, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.endpoint = normalizeBaseUrl(baseUrl) + CHAT_COMPLETIONS_PATH;

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        LOGGER.info("AsyncDeepSeekClient initialized (model: {}, maxTokens: {}, temperature: {}, endpoint: {})",
            model, maxTokens, temperature, endpoint);
    }

    /**
     * Normalizes the base URL by trimming whitespace and trailing slashes.
     *
     * @param baseUrl Raw base URL (may be null/empty)
     * @return A clean base URL without a trailing slash
     */
    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.deepseek.com";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        if (apiKey == null || apiKey.isEmpty()) {
            return CompletableFuture.failedFuture(new LLMException(
                "DeepSeek API key not configured (set [deepseek].apiKey or [openai].apiKey)",
                LLMException.ErrorType.AUTH_ERROR,
                PROVIDER_ID,
                false
            ));
        }

        String requestBody = buildRequestBody(prompt, params);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(60))
            .build();

        LOGGER.debug("[deepseek] Sending async request (prompt length: {} chars)", prompt.length());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                long latencyMs = System.currentTimeMillis() - startTime;

                if (response.statusCode() != 200) {
                    LLMException.ErrorType errorType = determineErrorType(response.statusCode());
                    boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;

                    LOGGER.error("[deepseek] API error: status={}, body={}", response.statusCode(),
                        truncate(response.body(), 200));

                    throw new LLMException(
                        "DeepSeek API error: HTTP " + response.statusCode(),
                        errorType,
                        PROVIDER_ID,
                        retryable
                    );
                }

                return parseResponse(response.body(), latencyMs);
            });
    }

    /**
     * Builds the JSON request body (OpenAI-compatible format).
     *
     * @param prompt User prompt
     * @param params Additional parameters
     * @return JSON string
     */
    private String buildRequestBody(String prompt, Map<String, Object> params) {
        JsonObject body = new JsonObject();

        String modelToUse = (String) params.getOrDefault("model", this.model);
        int maxTokensToUse = (int) params.getOrDefault("maxTokens", this.maxTokens);
        double tempToUse = (double) params.getOrDefault("temperature", this.temperature);

        body.addProperty("model", modelToUse);
        body.addProperty("max_tokens", maxTokensToUse);
        body.addProperty("temperature", tempToUse);

        JsonArray messages = new JsonArray();

        // System message (if provided)
        String systemPrompt = (String) params.get("systemPrompt");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", systemPrompt);
            messages.add(systemMessage);
        }

        // User message
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);

        body.add("messages", messages);

        return body.toString();
    }

    /**
     * Parses the DeepSeek API response (OpenAI-compatible format).
     *
     * @param responseBody Raw JSON response
     * @param latencyMs    Request latency
     * @return Parsed LLMResponse
     */
    private LLMResponse parseResponse(String responseBody, long latencyMs) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if (!json.has("choices") || json.getAsJsonArray("choices").isEmpty()) {
                throw new LLMException(
                    "DeepSeek response missing 'choices' array",
                    LLMException.ErrorType.INVALID_RESPONSE,
                    PROVIDER_ID,
                    false
                );
            }

            JsonObject firstChoice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            String content = message.get("content").getAsString();

            int tokensUsed = 0;
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                if (usage.has("total_tokens")) {
                    tokensUsed = usage.get("total_tokens").getAsInt();
                }
            }

            LOGGER.debug("[deepseek] Response received (latency: {}ms, tokens: {})", latencyMs, tokensUsed);

            return LLMResponse.builder()
                .content(content)
                .model(model)
                .providerId(PROVIDER_ID)
                .latencyMs(latencyMs)
                .tokensUsed(tokensUsed)
                .fromCache(false)
                .build();

        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("[deepseek] Failed to parse response: {}", truncate(responseBody, 200), e);
            throw new LLMException(
                "Failed to parse DeepSeek response: " + e.getMessage(),
                LLMException.ErrorType.INVALID_RESPONSE,
                PROVIDER_ID,
                false,
                e
            );
        }
    }

    private LLMException.ErrorType determineErrorType(int statusCode) {
        return switch (statusCode) {
            case 429 -> LLMException.ErrorType.RATE_LIMIT;
            case 401, 403 -> LLMException.ErrorType.AUTH_ERROR;
            case 400 -> LLMException.ErrorType.CLIENT_ERROR;
            case 408, 504 -> LLMException.ErrorType.TIMEOUT;
            default -> {
                if (statusCode >= 500) {
                    yield LLMException.ErrorType.SERVER_ERROR;
                }
                yield LLMException.ErrorType.CLIENT_ERROR;
            }
        };
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "[null]";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isHealthy() {
        // Base client is always healthy; resilience layer checks circuit breaker
        return true;
    }
}
