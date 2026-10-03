package com.steve.ai.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A request from the brain to run one atomic capability.
 *
 * <p>Mirrors the architecture document's structured protocol:</p>
 * <pre>
 * {
 *   "tool": "follow_player",
 *   "arguments": { "player": "陶哥" },
 *   "reason": "跟随陶哥进入洞穴"
 * }
 * </pre>
 *
 * <p>{@code reason} is not decoration - it is what makes the decision auditable in logs and
 * what the {@code Reflection} layer quotes back when a step fails.</p>
 */
public final class ToolCall {

    private final String tool;
    private final Map<String, Object> arguments;
    private final String reason;

    public ToolCall(String tool, Map<String, Object> arguments, String reason) {
        this.tool = tool;
        this.arguments = arguments == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        this.reason = reason == null ? "" : reason;
    }

    public static ToolCall of(String tool, Map<String, Object> arguments) {
        return new ToolCall(tool, arguments, "");
    }

    /** Convenience for a single-argument call. */
    public static ToolCall of(String tool, String key, Object value) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(key, value);
        return new ToolCall(tool, args, "");
    }

    public String tool() {
        return tool;
    }

    public Map<String, Object> arguments() {
        return arguments;
    }

    public String reason() {
        return reason;
    }

    // ------------------------------------------------------------------
    // Typed argument access (models emit a messy mix of types)
    // ------------------------------------------------------------------

    public String string(String key) {
        return string(key, null);
    }

    public String string(String key, String fallback) {
        Object v = arguments.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    public int integer(String key, int fallback) {
        Object v = arguments.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public double decimal(String key, double fallback) {
        Object v = arguments.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public boolean flag(String key, boolean fallback) {
        Object v = arguments.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return fallback;
    }

    public boolean has(String key) {
        return arguments.get(key) != null;
    }

    /** @return true when every listed key is present and non-blank */
    public boolean hasAll(String... keys) {
        for (String key : keys) {
            if (!has(key)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return tool + arguments + (reason.isEmpty() ? "" : " // " + reason);
    }
}
