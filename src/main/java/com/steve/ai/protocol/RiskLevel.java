package com.steve.ai.protocol;

/**
 * How dangerous a tool is - to the world, to the player, or to the illusion that the AI is
 * just another player.
 *
 * <p>Risk is metadata for the dispatcher and for the prompt: {@link #FORBIDDEN} tools are
 * filtered out of the model-visible tool list entirely, and {@link #HIGH} tools are listed
 * with an explicit warning so the model treats them as a last resort.</p>
 */
public enum RiskLevel {

    /** Harmless and reversible (walk, look, query, chat). */
    LOW,

    /** Meaningful side effects but normal player behaviour (mine, place, attack a mob). */
    MEDIUM,

    /** Could waste a lot of resources or endanger the AI (deep mining without torches). */
    HIGH,

    /** Must never be callable by the LLM (teleport, spawn items, change gamemode). */
    FORBIDDEN;

    /** True when this tool may ever be offered to the model. */
    public boolean isAvailableToModel() {
        return this != FORBIDDEN;
    }
}
