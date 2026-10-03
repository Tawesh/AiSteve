package com.steve.ai.memory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Layer 2 of 4: event memory - "what actually happened to me".
 *
 * <p>Persisted in the entity's NBT, so this is what gives the AI a past across sessions.
 * The example the architecture document gives:</p>
 * <pre>
 * 2026-10-02  我和陶哥第一次进入下界。陶哥掉进岩浆，我搭桥救了他，陶哥说"谢谢"。
 * </pre>
 * <p>Next time it stands at a lava edge it can recall that - which is where the feeling of
 * "this thing has been playing with me for a while" comes from.</p>
 *
 * <p>Bounded: newest {@value #MAX_EPISODES} episodes are kept, and {@link #prune()} keeps the
 * NBT tag from growing without limit.</p>
 */
public final class EpisodicMemory {

    /** Hard cap on retained episodes. */
    public static final int MAX_EPISODES = 200;

    private static final String NBT_KEY = "AgentEpisodicMemory";

    /** One remembered event. */
    public record Episode(long tick, String summary, String participants, String outcome,
                          double importance) {

        /** Importance weighted by recency - used for the "most memorable" query. */
        double salience(long nowTick) {
            long age = Math.max(0, nowTick - tick);
            double recency = 1.0 / (1.0 + age / 24000.0);   // one Minecraft day half-life-ish
            return importance * (0.5 + 0.5 * recency);
        }
    }

    private final List<Episode> episodes = new ArrayList<>();

    /** Records an event. */
    public void record(long tick, String summary, String participants, String outcome,
                       double importance) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        episodes.add(new Episode(tick, summary.trim(),
            participants == null ? "" : participants,
            outcome == null ? "" : outcome,
            Math.max(0.0, Math.min(1.0, importance))));
        prune();
    }

    /** Convenience for a plain note with default importance. */
    public void record(long tick, String summary) {
        record(tick, summary, "", "", 0.5);
    }

    public int size() {
        return episodes.size();
    }

    /** Newest first. */
    public List<Episode> recent(int n) {
        List<Episode> copy = new ArrayList<>(episodes);
        java.util.Collections.reverse(copy);
        return copy.size() <= n ? copy : copy.subList(0, n);
    }

    /**
     * The most "memorable" episodes as of {@code nowTick}, newest/most important first.
     *
     * <p>This is the retrieval used to answer "does anything I remember relate to this?"</p>
     */
    public List<Episode> salient(long nowTick, int n) {
        List<Episode> copy = new ArrayList<>(episodes);
        copy.sort(Comparator.comparingDouble((Episode e) -> e.salience(nowTick)).reversed());
        return copy.size() <= n ? copy : copy.subList(0, n);
    }

    /** Keyword search over summaries and participants. */
    public List<Episode> search(String keyword, int n) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String needle = keyword.toLowerCase(Locale.ROOT);
        List<Episode> hits = new ArrayList<>();
        for (int i = episodes.size() - 1; i >= 0 && hits.size() < n; i--) {
            Episode e = episodes.get(i);
            if (e.summary().toLowerCase(Locale.ROOT).contains(needle)
                || e.participants().toLowerCase(Locale.ROOT).contains(needle)) {
                hits.add(e);
            }
        }
        return hits;
    }

    /** Drops the oldest episodes beyond {@link #MAX_EPISODES}. */
    private void prune() {
        while (episodes.size() > MAX_EPISODES) {
            episodes.remove(0);
        }
    }

    public void clear() {
        episodes.clear();
    }

    public String toPromptText(long nowTick, int n) {
        List<Episode> recent = salient(nowTick, n);
        if (recent.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【经历记忆】\n");
        for (Episode e : recent) {
            sb.append("- ").append(e.summary());
            if (!e.outcome().isBlank()) {
                sb.append("（").append(e.outcome()).append("）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public void saveToNBT(CompoundTag parent) {
        ListTag list = new ListTag();
        for (Episode e : episodes) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("Tick", e.tick());
            tag.putString("Summary", e.summary());
            tag.putString("Participants", e.participants());
            tag.putString("Outcome", e.outcome());
            tag.putDouble("Importance", e.importance());
            list.add(tag);
        }
        parent.put(NBT_KEY, list);
    }

    public void loadFromNBT(CompoundTag parent) {
        episodes.clear();
        if (!parent.contains(NBT_KEY)) {
            return;
        }
        ListTag list = parent.getList(NBT_KEY, 10);   // 10 = compound
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            episodes.add(new Episode(
                tag.getLong("Tick"),
                tag.getString("Summary"),
                tag.getString("Participants"),
                tag.getString("Outcome"),
                tag.getDouble("Importance")));
        }
        prune();
    }
}
