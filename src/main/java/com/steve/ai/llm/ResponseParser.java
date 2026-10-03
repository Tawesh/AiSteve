package com.steve.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the LLM's JSON reply into either a conversational answer or an executable plan.
 *
 * <p>Two supported shapes:</p>
 * <pre>
 * {"type":"chat","reply":"你好呀！"}                                    // conversation only
 * {"type":"task","reasoning":"...","plan":"...","tasks":[...]}          // work to do
 * </pre>
 * <p>Task-only JSON without a {@code type} field is still accepted for compatibility.</p>
 */
public class ResponseParser {

    /** What the player actually wanted. */
    public enum Intent {
        /** A job to carry out. */
        TASK,
        /** Just talking. */
        CHAT
    }

    public static ParsedResponse parseAIResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        try {
            String jsonString = extractJSON(response);
            JsonObject json = JsonParser.parseString(jsonString).getAsJsonObject();

            Intent intent = detectIntent(json);

            String reasoning = getString(json, "reasoning");
            String plan = getString(json, "plan");
            String reply = getString(json, "reply");

            List<Task> tasks = new ArrayList<>();
            if (json.has("tasks") && json.get("tasks").isJsonArray()) {
                JsonArray tasksArray = json.getAsJsonArray("tasks");
                for (JsonElement taskElement : tasksArray) {
                    if (taskElement.isJsonObject()) {
                        Task task = parseTask(taskElement.getAsJsonObject());
                        if (task != null) {
                            tasks.add(task);
                        }
                    }
                }
            }

            // Conversation never queues work
            if (intent == Intent.CHAT) {
                tasks.clear();
            }

            return new ParsedResponse(intent, reasoning, plan, reply, tasks);

        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to parse AI response: {}", response, e);
            return null;
        }
    }

    /**
     * Works out whether the player asked for work or was just chatting.
     *
     * <p>Prefers an explicit {@code "type"} field; falls back to heuristics so that
     * older prompt formats and slightly malformed replies still behave sensibly.</p>
     */
    private static Intent detectIntent(JsonObject json) {
        if (json.has("type")) {
            String type = json.get("type").getAsString().toLowerCase();
            if (type.contains("chat") || type.contains("talk") || type.contains("conversation")) {
                return Intent.CHAT;
            }
            if (type.contains("task") || type.contains("action") || type.contains("work")) {
                return Intent.TASK;
            }
        }

        boolean hasReply = json.has("reply") && !getString(json, "reply").isBlank();
        boolean hasTasks = json.has("tasks") && json.get("tasks").isJsonArray()
            && !json.getAsJsonArray("tasks").isEmpty();

        if (hasReply && !hasTasks) {
            return Intent.CHAT;
        }
        return Intent.TASK;
    }

    private static String getString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    /** Strips markdown fences and repairs the JSON mistakes models commonly make. */
    private static String extractJSON(String response) {
        String cleaned = response.trim();

        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }

        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        cleaned = cleaned.trim();

        // Collapse newlines that would otherwise break the JSON
        cleaned = cleaned.replaceAll("\\n\\s*", " ");

        // Fix missing commas between array/object elements (common AI mistake)
        cleaned = cleaned.replaceAll("}\\s+\\{", "},{");
        cleaned = cleaned.replaceAll("}\\s+\\[", "},[");
        cleaned = cleaned.replaceAll("]\\s+\\{", "],{");
        cleaned = cleaned.replaceAll("]\\s+\\[", "],[");

        return cleaned;
    }

    private static Task parseTask(JsonObject taskObj) {
        if (!taskObj.has("action")) {
            return null;
        }

        String action = taskObj.get("action").getAsString();
        Map<String, Object> parameters = new HashMap<>();

        if (taskObj.has("parameters") && taskObj.get("parameters").isJsonObject()) {
            JsonObject paramsObj = taskObj.getAsJsonObject("parameters");

            for (String key : paramsObj.keySet()) {
                JsonElement value = paramsObj.get(key);

                if (value.isJsonPrimitive()) {
                    if (value.getAsJsonPrimitive().isNumber()) {
                        parameters.put(key, value.getAsNumber());
                    } else if (value.getAsJsonPrimitive().isBoolean()) {
                        parameters.put(key, value.getAsBoolean());
                    } else {
                        parameters.put(key, value.getAsString());
                    }
                } else if (value.isJsonArray()) {
                    List<Object> list = new ArrayList<>();
                    for (JsonElement element : value.getAsJsonArray()) {
                        if (element.isJsonPrimitive()) {
                            if (element.getAsJsonPrimitive().isNumber()) {
                                list.add(element.getAsNumber());
                            } else {
                                list.add(element.getAsString());
                            }
                        }
                    }
                    parameters.put(key, list);
                }
            }
        }

        return new Task(action, parameters);
    }

    /** Result of parsing: either a chat answer or a task list. */
    public static class ParsedResponse {
        private final Intent intent;
        private final String reasoning;
        private final String plan;
        private final String reply;
        private final List<Task> tasks;

        public ParsedResponse(Intent intent, String reasoning, String plan, String reply, List<Task> tasks) {
            this.intent = intent;
            this.reasoning = reasoning;
            this.plan = plan;
            this.reply = reply;
            this.tasks = tasks;
        }

        public Intent getIntent() {
            return intent;
        }

        /** True when the player was just chatting and nothing should be executed. */
        public boolean isChat() {
            return intent == Intent.CHAT;
        }

        public String getReasoning() {
            return reasoning;
        }

        public String getPlan() {
            return plan;
        }

        /** The conversational answer for CHAT intents. */
        public String getReply() {
            return reply;
        }

        public List<Task> getTasks() {
            return tasks;
        }
    }
}
