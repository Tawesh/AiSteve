package com.steve.ai.memory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Layer 1 of 4: short-term, minutes-scale scratchpad.
 *
 * <p>Holds the "right now" narrative a player would keep in their head without writing it
 * down:</p>
 * <pre>
 * 正在和陶哥去找村庄
 * 陶哥刚才说想找铁
 * 已经走了 300 格
 * 前面发现了一条河
 * 附近有两个僵尸
 * </pre>
 *
 * <p>Memory-only by design - it expires with the session and is deliberately not persisted.
 * That is what keeps it "working memory" rather than another database.</p>
 */
public final class WorkingMemory {

    /** Max retained notes; older ones fall off the front. */
    private static final int MAX_NOTES = 32;

    /** A note with the game tick it was written at. */
    public record Note(long tick, String text) {}

    private final LinkedList<Note> notes = new LinkedList<>();
    private String context = "";

    /** Appends a note, trimming the oldest when full. */
    public void remember(long tick, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        notes.addLast(new Note(tick, text.trim()));
        while (notes.size() > MAX_NOTES) {
            notes.removeFirst();
        }
    }

    /** Replaces the current free-form context line (e.g. "和陶哥寻找钻石"). */
    public void setContext(String context) {
        this.context = context == null ? "" : context;
    }

    public String context() {
        return context;
    }

    /** The most recent {@code n} notes, oldest first. */
    public List<String> recent(int n) {
        List<String> result = new ArrayList<>();
        int start = Math.max(0, notes.size() - n);
        for (int i = start; i < notes.size(); i++) {
            result.add(notes.get(i).text());
        }
        return result;
    }

    /** All notes, oldest first. */
    public List<Note> all() {
        return List.copyOf(notes);
    }

    public int size() {
        return notes.size();
    }

    /** Drops notes older than {@code ageTicks} (used to stop stale facts haunting decision-making). */
    public void prune(long currentTick, long ageTicks) {
        while (!notes.isEmpty() && currentTick - notes.getFirst().tick() > ageTicks) {
            notes.removeFirst();
        }
    }

    public void clear() {
        notes.clear();
        context = "";
    }

    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        if (!context.isBlank()) {
            sb.append("当前在进行：").append(context).append('\n');
        }
        if (notes.isEmpty()) {
            return sb.toString();
        }
        sb.append("刚才发生：\n");
        for (String note : recent(8)) {
            sb.append("- ").append(note).append('\n');
        }
        return sb.toString();
    }
}
