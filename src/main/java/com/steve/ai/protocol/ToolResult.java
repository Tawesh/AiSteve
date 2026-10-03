package com.steve.ai.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome of one {@link ToolCall}.
 *
 * <p>Three independent things are reported, because the layers above need all of them:</p>
 * <ul>
 *   <li>{@code success} - did the capability run at all?</li>
 *   <li>{@code status} - a stable machine-readable code
 *       ({@code scheduled}, {@code denied}, {@code missing_tool}, {@code not_found}, ...)
 *       that {@code Reflection} switches on.</li>
 *   <li>{@code message} - the human/LLM readable sentence fed back to the model.</li>
 * </ul>
 *
 * <p>Note the deliberate gap between the two: dispatching a tool that enqueues a long-running
 * action returns {@code success=true, status="scheduled"}. The action's own
 * {@code ActionResult} later produces a second, terminal {@code ToolResult}.</p>
 */
public final class ToolResult {

    // ---- Well-known status codes (kept as constants so Reflection can switch on them) ----

    /** The tool was accepted and work was queued into the executor. */
    public static final String SCHEDULED = "scheduled";
    /** Completed synchronously and successfully. */
    public static final String OK = "ok";
    /** Permission gate rejected the call. */
    public static final String DENIED = "denied";
    /** Unknown tool name. */
    public static final String UNKNOWN_TOOL = "unknown_tool";
    /** Required argument missing or malformed. */
    public static final String BAD_ARGUMENTS = "bad_arguments";
    /** The AI does not own the item/tool this capability needs. */
    public static final String MISSING_TOOL = "missing_tool";
    /** The target block/entity/item could not be located. */
    public static final String NOT_FOUND = "not_found";
    /** The capability is not implemented (honest refusal, never a silent no-op). */
    public static final String UNSUPPORTED = "unsupported";
    /** Anything unforeseen. */
    public static final String ERROR = "error";

    private final boolean success;
    private final String status;
    private final String message;
    private final Map<String, Object> data;

    private ToolResult(boolean success, String status, String message, Map<String, Object> data) {
        this.success = success;
        this.status = status;
        this.message = message == null ? "" : message;
        this.data = data == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /** Work was queued; the long-running action will report separately. */
    public static ToolResult scheduled(String message) {
        return new ToolResult(true, SCHEDULED, message, null);
    }

    /** Completed right now with a concrete answer. */
    public static ToolResult ok(String message) {
        return new ToolResult(true, OK, message, null);
    }

    public static ToolResult ok(String message, Map<String, Object> data) {
        return new ToolResult(true, OK, message, data);
    }

    /** The permission gate refused this call. */
    public static ToolResult denied(String message) {
        return new ToolResult(false, DENIED, message, null);
    }

    public static ToolResult unknownTool(String tool) {
        return new ToolResult(false, UNKNOWN_TOOL, "未知工具：" + tool, null);
    }

    public static ToolResult badArguments(String message) {
        return new ToolResult(false, BAD_ARGUMENTS, message, null);
    }

    public static ToolResult missingTool(String message) {
        return new ToolResult(false, MISSING_TOOL, message, null);
    }

    public static ToolResult notFound(String message) {
        return new ToolResult(false, NOT_FOUND, message, null);
    }

    public static ToolResult unsupported(String message) {
        return new ToolResult(false, UNSUPPORTED, message, null);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, ERROR, message, null);
    }

    /**
     * A long-running action that was queued earlier has now failed.
     *
     * <p>Used by the agent loop's execution listener: the tool call itself returned
     * {@link #scheduled}, so the real outcome only becomes known ticks later.</p>
     */
    public static ToolResult failed(String message) {
        return new ToolResult(false, ERROR, message, null);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public boolean success() {
        return success;
    }

    public String status() {
        return status;
    }

    public String message() {
        return message;
    }

    public Map<String, Object> data() {
        return data;
    }

    /** True when the tool could not run at all (as opposed to merely being queued). */
    public boolean isHardFailure() {
        return !success;
    }

    @Override
    public String toString() {
        return "ToolResult{" + status + ", success=" + success + ", '" + message + "'}";
    }
}
