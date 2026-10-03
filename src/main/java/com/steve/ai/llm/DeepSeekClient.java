package com.steve.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Synchronous client for the DeepSeek API (OpenAI-compatible).
 *
 * <p>Used by the legacy blocking code path ({@link TaskPlanner#planTasks}).
 * The primary runtime path is asynchronous via
 * {@link com.steve.ai.llm.async.AsyncDeepSeekClient}.</p>
 *
 * <p><b>Endpoint:</b> {baseUrl}/chat/completions (default https://api.deepseek.com)</p>
 * <p><b>Models:</b> deepseek-flash (V4.1-Flash), deepseek-v4-pro (V4-Pro)</p>
 */
public class DeepSeekClient {
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final int MAX_RETRIES = 3;
    private static final int INITIAL_RETRY_DELAY_MS = 1000; // 1 second

    private final HttpClient client;
    private final String apiKey;
    private final String model;
    private final String endpoint;

    public DeepSeekClient() {
        this.apiKey = resolveApiKey();
        this.model = SteveConfig.DEEPSEEK_MODEL.get();
        this.endpoint = normalizeBaseUrl(SteveConfig.DEEPSEEK_BASE_URL.get()) + "/chat/completions";
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * Resolves the DeepSeek API key, falling back to the shared OpenAI key field
     * when the dedicated DeepSeek key is empty.
     */
    private String resolveApiKey() {
        String key = SteveConfig.DEEPSEEK_API_KEY.get();
        if (key == null || key.isEmpty()) {
            key = SteveConfig.OPENAI_API_KEY.get();
        }
        return key;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public String sendRequest(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            SteveMod.LOGGER.error("DeepSeek API key not configured!");
            return null;
        }

        JsonObject requestBody = buildRequestBody(systemPrompt, userPrompt);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build();

        // Retry logic with exponential backoff
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    String responseBody = response.body();
                    if (responseBody == null || responseBody.isEmpty()) {
                        SteveMod.LOGGER.error("DeepSeek API returned empty response");
                        return null;
                    }
                    return parseResponse(responseBody);
                }

                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    if (attempt < MAX_RETRIES - 1) {
                        int delayMs = INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, attempt);
                        SteveMod.LOGGER.warn("DeepSeek API request failed with status {}, retrying in {}ms (attempt {}/{})",
                            response.statusCode(), delayMs, attempt + 1, MAX_RETRIES);
                        Thread.sleep(delayMs);
                        continue;
                    }
                }

                SteveMod.LOGGER.error("DeepSeek API request failed: {}", response.statusCode());
                SteveMod.LOGGER.error("Response body: {}", response.body());
                return null;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                SteveMod.LOGGER.error("Request interrupted", e);
                return null;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES - 1) {
                    int delayMs = INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, attempt);
                    SteveMod.LOGGER.warn("Error communicating with DeepSeek API, retrying in {}ms (attempt {}/{})",
                        delayMs, attempt + 1, MAX_RETRIES, e);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    SteveMod.LOGGER.error("Error communicating with DeepSeek API after {} attempts", MAX_RETRIES, e);
                    return null;
                }
            }
        }

        return null;
    }

    private JsonObject buildRequestBody(String systemPrompt, String userPrompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", SteveConfig.TEMPERATURE.get());
        body.addProperty("max_tokens", SteveConfig.MAX_TOKENS.get());

        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", systemPrompt);
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);

        body.add("messages", messages);

        return body;
    }

    private String parseResponse(String responseBody) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if (json.has("choices") && json.getAsJsonArray("choices").size() > 0) {
                JsonObject firstChoice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (firstChoice.has("message")) {
                    JsonObject message = firstChoice.getAsJsonObject("message");
                    if (message.has("content")) {
                        return message.get("content").getAsString();
                    }
                }
            }

            SteveMod.LOGGER.error("Unexpected DeepSeek response format: {}", responseBody);
            return null;

        } catch (Exception e) {
            SteveMod.LOGGER.error("Error parsing DeepSeek response", e);
            return null;
        }
    }
}
