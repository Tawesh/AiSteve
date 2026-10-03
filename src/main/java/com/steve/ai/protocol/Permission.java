package com.steve.ai.protocol;

/**
 * Capability class a {@link ToolSpec} may require before it is allowed to run.
 *
 * <p>This is the mechanism behind the architecture rule:</p>
 * <blockquote>
 *   AI 的能力边界由 Agent Runtime 决定，而不是由 Prompt 决定。
 * </blockquote>
 *
 * <p>The LLM may <em>ask</em> for anything, but only the {@code ToolDispatcher} decides what
 * actually executes. Tools that would break the "behaves like a real player" contract
 * (teleporting, spawning items, changing gamemode, killing players) carry
 * {@link Permission#ADMIN} and are never advertised to the model.</p>
 */
public enum Permission {

    /** Walk / look / jump / stop. Never bypasses physics. */
    MOVEMENT,

    /** Read-only queries about the world (scan, find, time, weather). */
    WORLD_READ,

    /** Mutating the world: breaking and placing blocks, using items on blocks. */
    WORLD_WRITE,

    /** The AI's own inventory: read, craft, equip, drop. */
    INVENTORY,

    /** Attacking or fleeing. */
    COMBAT,

    /** Chat and player-relationship management. */
    SOCIAL,

    /**
     * Escape hatches: teleport, give item out of thin air, gamemode, kill.
     *
     * <p>Never granted to the LLM. Reserved for human-issued commands
     * (for example {@code /as come}).</p>
     */
    ADMIN
}
