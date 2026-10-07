package com.steve.ai.action;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One unit of work: an action name plus its parameters.
 *
 * <p>Parameters arrive straight from the LLM's JSON, so their runtime types are whatever the
 * model decided to write: {@code 4}, {@code 4.0}, {@code "4"}, {@code "4 "}, {@code "4 个"},
 * even {@code true}. Every numeric getter therefore <b>coerces</b> instead of casting -
 * see {@link #coerceInt(Object)}.</p>
 */
public class Task {
    private final String action;
    private final Map<String, Object> parameters;

    public Task(String action, Map<String, Object> parameters) {
        this.action = action;
        this.parameters = parameters;
    }

    public String getAction() {
        return action;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public Object getParameter(String key) {
        return parameters.get(key);
    }

    public String getStringParameter(String key) {
        Object value = parameters.get(key);
        return value != null ? value.toString() : null;
    }

    public String getStringParameter(String key, String defaultValue) {
        Object value = parameters.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Reads an integer parameter, accepting every shape a model realistically emits.
     *
     * <p>This used to be {@code value instanceof Number ? ... : defaultValue}, which meant
     * {@code "quantity": "4"} from the model was <b>silently replaced by the default</b> -
     * the AI then crafted one item no matter what the player asked for, and nothing in the
     * logs explained why. Quantity is a user-visible promise: never drop it silently.</p>
     *
     * @param key          parameter name
     * @param defaultValue value used when the parameter is absent or unparseable
     */
    public int getIntParameter(String key, int defaultValue) {
        Integer value = coerceInt(parameters.get(key));
        return value != null ? value : defaultValue;
    }

    /** First number inside a string, so {@code "4 个"} and {@code "x4"} still mean 4. */
    private static final Pattern FIRST_NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /**
     * Turns a raw JSON value into an int, or {@code null} when it carries no usable number.
     *
     * <p>Handled: numbers (including {@code 4.0} / {@code 4.9} from JSON), numeric strings,
     * strings with surrounding text, and booleans (1/0).</p>
     */
    private static Integer coerceInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return (int) Math.round(number.doubleValue());
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        if (value instanceof String text) {
            String cleaned = text.trim().replace(",", "");
            if (cleaned.isEmpty()) {
                return null;
            }
            Matcher matcher = FIRST_NUMBER.matcher(cleaned);
            if (matcher.find()) {
                try {
                    return (int) Math.round(Double.parseDouble(matcher.group()));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public boolean hasParameters(String... keys) {
        for (String key : keys) {
            if (!parameters.containsKey(key)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "Task{action='" + action + "', parameters=" + parameters + "}";
    }
}
