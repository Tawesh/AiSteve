package com.steve.ai.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing that happened to the AI.
 *
 * <p>A single class with a {@link AgentEventType} discriminator, rather than a dozen subclasses.
 * The event bus is a hot path (every perception tick may post several), and the payloads are
 * genuinely heterogeneous - a flat record keeps the bus simple and the logs readable.</p>
 *
 * <p>{@link #summary()} is the line that ends up in working memory and, from there, in the
 * prompt - so it is written for a reader ("陶哥 让我：跟我去找钻石"), not for a parser.</p>
 */
public final class AgentEvent {

    private final AgentEventType type;
    private final String source;
    private final String summary;
    private final Map<String, Object> payload;
    private final long gameTime;
    private final boolean wakesBrain;

    public AgentEvent(AgentEventType type, String source, String summary,
                      Map<String, Object> payload, long gameTime) {
        this.type = type;
        this.source = source == null ? "" : source;
        this.summary = summary == null ? type.label() : summary;
        this.payload = payload == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        this.gameTime = gameTime;
        this.wakesBrain = type != null && type.wakesBrain();
    }

    public static AgentEvent of(AgentEventType type, String source, String summary, long gameTime) {
        return new AgentEvent(type, source, summary, null, gameTime);
    }

    public AgentEventType type() {
        return type;
    }

    public String source() {
        return source;
    }

    public String summary() {
        return summary;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public long gameTime() {
        return gameTime;
    }

    /** True when this event should interrupt an otherwise idle agent. */
    public boolean wakesBrain() {
        return wakesBrain;
    }

    public String payloadString(String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public int payloadInt(String key, int fallback) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    @Override
    public String toString() {
        return "AgentEvent{" + type + ", '" + summary + "'}";
    }
}
