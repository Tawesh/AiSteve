package com.steve.ai.tool;

import com.steve.ai.protocol.ToolSpec;

/**
 * Base class carrying the {@link ToolSpec} so implementations only have to write
 * {@code invoke}.
 *
 * <p>Tool groups build these inline, which keeps six categories of capability readable in six
 * small files instead of thirty tiny ones.</p>
 */
public abstract class SimpleTool implements Tool {

    private final ToolSpec spec;

    protected SimpleTool(ToolSpec spec) {
        this.spec = spec;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }
}
