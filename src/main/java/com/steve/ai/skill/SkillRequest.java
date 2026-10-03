package com.steve.ai.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A "what do you want done" request handed to the skill layer.
 *
 * <p>Produced by {@code brain.Planner} from a {@code Goal}. Intentionally decoupled from the
 * brain package (it carries the goal <em>type as a string</em>, not a {@code GoalType}) so that
 * skills stay a leaf layer with no upward dependencies.</p>
 */
public final class SkillRequest {

    private final String description;
    private final String type;
    private final Map<String, Object> params;

    public SkillRequest(String description, String type, Map<String, Object> params) {
        this.description = description == null ? "" : description;
        this.type = type == null ? "" : type;
        this.params = params == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public static SkillRequest of(String description, String type) {
        return new SkillRequest(description, type, null);
    }

    public static SkillRequest of(String description, String type, Map<String, Object> params) {
        return new SkillRequest(description, type, params);
    }

    public String description() {
        return description;
    }

    /** Goal type name, e.g. {@code RESOURCE}. */
    public String type() {
        return type;
    }

    public Map<String, Object> params() {
        return params;
    }

    public String param(String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public int intParam(String key, int fallback) {
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    /** Lower-cased description, for keyword matching. */
    public String lower() {
        return description.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String toString() {
        return "SkillRequest{" + type + " '" + description + "'}";
    }
}
