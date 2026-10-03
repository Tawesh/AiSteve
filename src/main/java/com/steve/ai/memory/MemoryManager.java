package com.steve.ai.memory;

import net.minecraft.nbt.CompoundTag;

/**
 * Facade over the four memory layers, so the rest of the agent has exactly one memory object
 * to hold and one place where persistence is defined.
 *
 * <table border="1">
 *   <tr><th>Layer</th><th>Class</th><th>Lifetime</th><th>Persisted</th></tr>
 *   <tr><td>Working</td><td>{@link WorkingMemory}</td><td>minutes</td><td>no</td></tr>
 *   <tr><td>Episodic</td><td>{@link EpisodicMemory}</td><td>forever</td><td>yes</td></tr>
 *   <tr><td>Semantic</td><td>{@link SemanticMemory}</td><td>forever</td><td>yes</td></tr>
 *   <tr><td>Social</td><td>{@link SocialMemory}</td><td>forever</td><td>yes</td></tr>
 * </table>
 *
 * <p>The legacy {@code SteveMemory} / {@code WorldMemory} are intentionally <b>not</b> folded
 * in here: {@code SteveMemory} is the per-session conversation log used by the old prompt path,
 * and {@code WorldMemory} stores landmark coordinates. Both keep working unchanged so the
 * refactor does not regress behaviour.</p>
 */
public final class MemoryManager {

    /** NBT container key for everything the agent remembers long-term. */
    public static final String NBT_KEY = "AgentMemory";

    private final WorkingMemory working = new WorkingMemory();
    private final EpisodicMemory episodic = new EpisodicMemory();
    private final SemanticMemory semantic = new SemanticMemory();
    private final SocialMemory social = new SocialMemory();

    public WorkingMemory working() {
        return working;
    }

    public EpisodicMemory episodic() {
        return episodic;
    }

    public SemanticMemory semantic() {
        return semantic;
    }

    public SocialMemory social() {
        return social;
    }

    /**
     * Builds the memory section of the agent prompt.
     *
     * <p>Ordered by relevance: what is happening now, who I am with, what I remember doing,
     * and finally the rules that apply to the current goal.</p>
     *
     * @param nowTick      current game time
     * @param goalQuery    the active goal description, used to retrieve relevant knowledge
     */
    public String toPromptText(long nowTick, String goalQuery) {
        StringBuilder sb = new StringBuilder();

        String workingText = working.toPromptText();
        if (!workingText.isBlank()) {
            sb.append(workingText).append('\n');
        }

        String socialText = social.toPromptText();
        if (!socialText.isBlank()) {
            sb.append(socialText).append('\n');
        }

        String episodicText = episodic.toPromptText(nowTick, 4);
        if (!episodicText.isBlank()) {
            sb.append(episodicText).append('\n');
        }

        String semanticText = semantic.toPromptText(goalQuery, 4);
        if (!semanticText.isBlank()) {
            sb.append(semanticText).append('\n');
        }

        return sb.toString();
    }

    /** Short one-line summary for {@code /as memory}. */
    public String describe() {
        return "工作记忆 " + working.size() + " 条"
            + "，经历 " + episodic.size() + " 条"
            + "，知识 " + semantic.size() + " 条"
            + "，认识的玩家 " + social.size() + " 人";
    }

    // ------------------------------------------------------------------
    // Persistence (all four layers live under one NBT compound)
    // ------------------------------------------------------------------

    public void saveToNBT(CompoundTag parent) {
        CompoundTag tag = new CompoundTag();
        episodic.saveToNBT(tag);
        semantic.saveToNBT(tag);
        social.saveToNBT(tag);
        parent.put(NBT_KEY, tag);
    }

    public void loadFromNBT(CompoundTag parent) {
        if (!parent.contains(NBT_KEY)) {
            return;
        }
        CompoundTag tag = parent.getCompound(NBT_KEY);
        episodic.loadFromNBT(tag);
        semantic.loadFromNBT(tag);
        social.loadFromNBT(tag);
        // Working memory is intentionally session-scoped and never restored.
    }
}
