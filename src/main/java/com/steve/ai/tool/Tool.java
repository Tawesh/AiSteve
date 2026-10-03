package com.steve.ai.tool;

import com.steve.ai.protocol.ToolCall;
import com.steve.ai.protocol.ToolResult;
import com.steve.ai.protocol.ToolSpec;

/**
 * An atomic capability: the AI's hands and feet.
 *
 * <p>"Tool 是原子能力 … Tool: 我能做什么？" Two contracts every implementation must honour:</p>
 * <ol>
 *   <li><b>Never cheat.</b> No teleporting, no conjuring items, no reading outside
 *       perception. If the capability needs a resource the AI does not have, return
 *       {@link ToolResult#missingTool} - do not invent it.</li>
 *   <li><b>Be honest when it cannot.</b> {@link ToolResult#unsupported} beats a silent no-op
 *       that leaves the brain believing the job is underway.</li>
 * </ol>
 *
 * <p>Long-running work is <em>queued</em>, not performed: return {@link ToolResult#scheduled}
 * and let {@code ActionExecutor} report the real outcome later.</p>
 */
public interface Tool {

    /** Metadata: name, description, parameters, required permission, risk. */
    ToolSpec spec();

    /** Runs the capability. Must never throw - return a failure result instead. */
    ToolResult invoke(ToolContext context, ToolCall call);

    /** Convenience: the tool's name, as the model will emit it. */
    default String name() {
        return spec().name();
    }
}
