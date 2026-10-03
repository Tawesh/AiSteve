package com.steve.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.SteveMod;
import com.steve.ai.protocol.AgentDecision;
import com.steve.ai.protocol.ToolCall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses the model's reply into an {@link AgentDecision}.
 *
 * <p>Written defensively, because models are messy in predictable ways:</p>
 * <ul>
 *   <li>markdown fences around the JSON,</li>
 *   <li>raw newlines inside string values,</li>
 *   <li>missing commas between array elements,</li>
 *   <li>an array at the top level instead of an object,</li>
 *   <li>the old {@code {type, tasks:[{action, parameters}]}} shape from the pre-refactor prompt.</li>
 * </ul>
 *
 * <p>The legacy mapping matters: it means a model that has been fine-tuned on, or simply
 * remembers, the old vocabulary still produces working behaviour instead of a parse failure.</p>
 */
public final class AgentDecisionParser {

    /** Legacy action name -> new tool name. */
    private static final Map<String, String> LEGACY_TOOL_NAMES = new HashMap<>();

    static {
        LEGACY_TOOL_NAMES.put("pathfind", "move_to");
        LEGACY_TOOL_NAMES.put("mine", "break_block");
        LEGACY_TOOL_NAMES.put("gather", "break_block");
        LEGACY_TOOL_NAMES.put("place", "place_block");
        LEGACY_TOOL_NAMES.put("open_chest", "open_container");
        LEGACY_TOOL_NAMES.put("loot_container", "open_container");
        LEGACY_TOOL_NAMES.put("pickup", "pickup_item");
        LEGACY_TOOL_NAMES.put("craft", "craft_item");
        LEGACY_TOOL_NAMES.put("give", "give_item");
        LEGACY_TOOL_NAMES.put("attack", "attack_entity");
        LEGACY_TOOL_NAMES.put("follow", "follow_player");
        LEGACY_TOOL_NAMES.put("say", "send_chat");
        LEGACY_TOOL_NAMES.put("ignite", "use_item");
        LEGACY_TOOL_NAMES.put("equip", "equip_item");
        LEGACY_TOOL_NAMES.put("drop", "drop_item");
        LEGACY_TOOL_NAMES.put("scan", "scan_area");
        LEGACY_TOOL_NAMES.put("break", "break_block");
        LEGACY_TOOL_NAMES.put("dig", "break_block");
        LEGACY_TOOL_NAMES.put("chop", "break_block");
        LEGACY_TOOL_NAMES.put("pickup_items", "pickup_item");
        LEGACY_TOOL_NAMES.put("collect", "pickup_item");
        LEGACY_TOOL_NAMES.put("crafting", "craft_item");
        LEGACY_TOOL_NAMES.put("flee_from", "flee");
        LEGACY_TOOL_NAMES.put("chat", "send_chat");
    }

    private AgentDecisionParser() {
    }

    /**
     * @param raw model output
     * @return the parsed decision, or {@code null} when nothing usable could be extracted
     */
    public static AgentDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            String jsonString = repair(raw.trim());
            JsonElement root = JsonParser.parseString(jsonString);

            JsonObject json;
            if (root.isJsonArray()) {
                // Some models answer with a bare action list - wrap it into a task decision.
                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("intent", "task");
                wrapper.add("actions", root.getAsJsonArray());
                json = wrapper;
            } else if (root.isJsonObject()) {
                json = root.getAsJsonObject();
            } else {
                SteveMod.LOGGER.warn("[Agent/Parse] 回复既不是对象也不是数组: {}", abbreviate(raw));
                return null;
            }

            return toDecision(json, raw);
        } catch (Exception e) {
            SteveMod.LOGGER.error("[Agent/Parse] 解析失败: {}", abbreviate(raw), e);
            return null;
        }
    }

    // ------------------------------------------------------------------

    private static AgentDecision toDecision(JsonObject json, String raw) {
        boolean chat = detectChat(json);

        String thought = firstString(json, "thought", "reasoning", "think");
        String reply = firstString(json, "reply", "answer", "message_text");

        if (chat) {
            // A chat answer must never carry work, even if the model also emitted actions.
            return new AgentDecision(AgentDecision.Intent.CHAT, thought, null,
                List.of(), reply, raw);
        }

        AgentDecision.GoalUpdate goalUpdate = parseGoalUpdate(json);
        List<ToolCall> actions = parseActions(json);

        if (!reply.isBlank() && actions.isEmpty()) {
            // The model answered conversationally without saying so - respect the content.
            return new AgentDecision(AgentDecision.Intent.CHAT, thought, null,
                List.of(), reply, raw);
        }

        return new AgentDecision(AgentDecision.Intent.TASK, thought, goalUpdate, actions, reply, raw);
    }

    private static boolean detectChat(JsonObject json) {
        String intent = firstString(json, "intent", "type", "mode").toLowerCase(Locale.ROOT);
        if (intent.contains("chat") || intent.contains("talk") || intent.contains("conversation")) {
            return true;
        }
        if (intent.contains("task") || intent.contains("action") || intent.contains("work")) {
            return false;
        }
        // No usable intent field: infer from the payload.
        boolean hasActions = json.has("actions") && json.get("actions").isJsonArray()
            && !json.getAsJsonArray("actions").isEmpty();
        boolean hasTasks = json.has("tasks") && json.get("tasks").isJsonArray()
            && !json.getAsJsonArray("tasks").isEmpty();
        boolean hasReply = !firstString(json, "reply").isBlank();
        return hasReply && !hasActions && !hasTasks;
    }

    private static AgentDecision.GoalUpdate parseGoalUpdate(JsonObject json) {
        String description = null;
        String type = null;
        Double priority = null;
        String parent = null;

        if (json.has("goal_update") && json.get("goal_update").isJsonObject()) {
            JsonObject update = json.getAsJsonObject("goal_update");
            description = firstString(update, "description", "goal", "name");
            type = firstString(update, "type", "kind");
            priority = readDouble(update, "priority");
            parent = firstString(update, "parent", "parent_id");
        } else if (json.has("goal")) {
            JsonElement goalElement = json.get("goal");
            if (goalElement.isJsonPrimitive()) {
                description = goalElement.getAsString();
            } else if (goalElement.isJsonObject()) {
                JsonObject goal = goalElement.getAsJsonObject();
                description = firstString(goal, "description", "goal", "name");
                type = firstString(goal, "type", "kind");
                priority = readDouble(goal, "priority");
            }
        }
        // The old prompt's top-level "plan" string doubles as a goal description.
        if (description == null || description.isBlank()) {
            description = firstString(json, "plan");
        }

        if ((description == null || description.isBlank()) && type == null && priority == null) {
            return null;
        }
        return new AgentDecision.GoalUpdate(description, type, priority, parent);
    }

    private static List<ToolCall> parseActions(JsonObject json) {
        List<ToolCall> calls = new ArrayList<>();

        JsonArray array = null;
        if (json.has("actions") && json.get("actions").isJsonArray()) {
            array = json.getAsJsonArray("actions");
        } else if (json.has("tasks") && json.get("tasks").isJsonArray()) {
            array = json.getAsJsonArray("tasks");
        } else if (json.has("tool_calls") && json.get("tool_calls").isJsonArray()) {
            array = json.getAsJsonArray("tool_calls");
        }

        if (array == null) {
            return calls;
        }

        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();

            // "tool" (new protocol) or "action" (legacy) or OpenAI-style nested "function".
            String tool = firstString(obj, "tool", "action", "name");
            JsonObject arguments = null;

            if (obj.has("arguments") && obj.get("arguments").isJsonObject()) {
                arguments = obj.getAsJsonObject("arguments");
            } else if (obj.has("parameters") && obj.get("parameters").isJsonObject()) {
                arguments = obj.getAsJsonObject("parameters");
            } else if (obj.has("function") && obj.get("function").isJsonObject()) {
                JsonObject function = obj.getAsJsonObject("function");
                if (tool == null || tool.isBlank()) {
                    tool = firstString(function, "name");
                }
                if (function.has("arguments")) {
                    JsonElement args = function.get("arguments");
                    if (args.isJsonObject()) {
                        arguments = args.getAsJsonObject();
                    } else if (args.isJsonPrimitive() && args.getAsJsonPrimitive().isString()) {
                        // Some providers double-encode the arguments as a JSON string.
                        try {
                            JsonElement decoded = JsonParser.parseString(args.getAsString());
                            if (decoded.isJsonObject()) {
                                arguments = decoded.getAsJsonObject();
                            }
                        } catch (Exception ignored) {
                            // fall through with no arguments
                        }
                    }
                }
            }

            if (tool == null || tool.isBlank()) {
                continue;
            }

            String mapped = LEGACY_TOOL_NAMES.getOrDefault(tool.toLowerCase(Locale.ROOT), tool);
            String reason = firstString(obj, "reason", "why", "thought");

            calls.add(new ToolCall(mapped, toMap(arguments), reason));
        }

        return calls;
    }

    private static Map<String, Object> toMap(JsonObject obj) {
        Map<String, Object> map = new HashMap<>();
        if (obj == null) {
            return map;
        }
        for (String key : obj.keySet()) {
            JsonElement value = obj.get(key);
            if (value.isJsonNull()) {
                continue;
            }
            if (value.isJsonPrimitive()) {
                var primitive = value.getAsJsonPrimitive();
                if (primitive.isNumber()) {
                    map.put(key, primitive.getAsNumber());
                } else if (primitive.isBoolean()) {
                    map.put(key, primitive.getAsBoolean());
                } else {
                    map.put(key, primitive.getAsString());
                }
            } else if (value.isJsonArray()) {
                List<Object> list = new ArrayList<>();
                for (JsonElement item : value.getAsJsonArray()) {
                    if (item.isJsonPrimitive()) {
                        var primitive = item.getAsJsonPrimitive();
                        if (primitive.isNumber()) {
                            list.add(primitive.getAsNumber());
                        } else if (primitive.isBoolean()) {
                            list.add(primitive.getAsBoolean());
                        } else {
                            list.add(primitive.getAsString());
                        }
                    }
                }
                map.put(key, list);
            }
        }
        return map;
    }

    // ------------------------------------------------------------------
    // JSON repair
    // ------------------------------------------------------------------

    /**
     * Strips markdown fences and repairs the mistakes models commonly make.
     *
     * <p>Kept local to this class rather than shared with {@code ResponseParser}: the old parser
     * serves the legacy path and must not change behaviour while the refactor lands.</p>
     */
    private static String repair(String response) {
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

        // Collapse newlines that would otherwise break string values.
        cleaned = cleaned.replaceAll("\\n\\s*", " ");

        // Insert commas the model forgot between array/object elements.
        cleaned = cleaned.replaceAll("}\\s*\\{", "},{");
        cleaned = cleaned.replaceAll("]\\s*\\[", "],[");
        cleaned = cleaned.replaceAll("}\\s*\\[", "},[");
        cleaned = cleaned.replaceAll("]\\s*\\{", "],{");

        return cleaned;
    }

    // ------------------------------------------------------------------

    private static String firstString(JsonObject json, String... keys) {
        for (String key : keys) {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                JsonElement element = json.get(key);
                if (element.isJsonPrimitive()) {
                    return element.getAsString();
                }
            }
        }
        return "";
    }

    private static Double readDouble(JsonObject json, String key) {
        if (json.has(key) && json.get(key).isJsonPrimitive()
            && json.getAsJsonPrimitive(key).isNumber()) {
            return json.get(key).getAsDouble();
        }
        return null;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
