package com.steve.ai.memory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Layer 4 of 4: what the AI knows about the humans it plays with.
 *
 * <p>The architecture document calls this "让 AI 真正像真人玩家的关键模块之一", and it is easy
 * to see why. With this, "走，去找钻石" is not an order to be parsed - it is a request from
 * someone the AI knows:</p>
 * <pre>
 * 陶哥最近一直在挖矿。
 * 他现在装备还不错。
 * 附近可能有洞穴。
 * 我可以跟着他。
 * </pre>
 *
 * <p>Persisted in NBT, so relationships survive restarts. Bounded per player.</p>
 */
public final class SocialMemory {

    private static final String NBT_KEY = "AgentSocialMemory";
    /** Max remembered facts per player. */
    private static final int MAX_FACTS_PER_PLAYER = 12;
    /** Max tracked players. */
    private static final int MAX_PLAYERS = 16;

    /** One player's relationship record. */
    public static final class Relationship {
        private final String name;
        private double trust;
        private int interactions;
        private final List<String> facts = new ArrayList<>();
        private long lastSeenTick;

        Relationship(String name, double trust, int interactions, long lastSeenTick) {
            this.name = name;
            this.trust = trust;
            this.interactions = interactions;
            this.lastSeenTick = lastSeenTick;
        }

        public String name() { return name; }
        public double trust() { return trust; }
        public int interactions() { return interactions; }
        public List<String> facts() { return List.copyOf(facts); }
        public long lastSeenTick() { return lastSeenTick; }

        /** Coarse label used in prompts ("好友" / "熟人" / "陌生人"). */
        public String label() {
            if (trust >= 0.75) return "好友";
            if (trust >= 0.4) return "熟人";
            return "陌生人";
        }

        void addFact(String fact) {
            if (fact == null || fact.isBlank()) {
                return;
            }
            if (facts.contains(fact)) {
                return;
            }
            facts.add(fact.trim());
            while (facts.size() > MAX_FACTS_PER_PLAYER) {
                facts.remove(0);
            }
        }
    }

    private final Map<String, Relationship> players = new LinkedHashMap<>();

    /** Get-or-create the relationship for a player. */
    public Relationship relationship(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            playerName = "unknown";
        }
        String key = playerName.toLowerCase(Locale.ROOT);
        Relationship existing = players.get(key);
        if (existing != null) {
            return existing;
        }
        Relationship created = new Relationship(playerName, 0.5, 0, 0);
        players.put(key, created);
        evictIfNeeded(key);
        return created;
    }

    /**
     * Records one interaction and nudges trust.
     *
     * @param playerName who
     * @param note       short note ("想找钻石")
     * @param trustDelta positive for friendly acts, negative otherwise; clamped to 0..1
     * @param tick       game time of the interaction
     */
    public void recordInteraction(String playerName, String note, double trustDelta, long tick) {
        Relationship rel = relationship(playerName);
        rel.interactions++;
        rel.trust = Math.max(0.0, Math.min(1.0, rel.trust + trustDelta));
        rel.lastSeenTick = tick;
        if (note != null && !note.isBlank()) {
            rel.addFact(note);
        }
    }

    /** Stores a durable fact about a player ("喜欢建房子"). */
    public void remember(String playerName, String fact) {
        relationship(playerName).addFact(fact);
    }

    public Relationship get(String playerName) {
        return playerName == null ? null : players.get(playerName.toLowerCase(Locale.ROOT));
    }

    public List<Relationship> all() {
        return List.copyOf(players.values());
    }

    public int size() {
        return players.size();
    }

    public void clear() {
        players.clear();
    }

    /** Evicts the least-interacted player once the roster is full. */
    private void evictIfNeeded(String justAdded) {
        while (players.size() > MAX_PLAYERS) {
            String victim = null;
            int fewest = Integer.MAX_VALUE;
            for (Map.Entry<String, Relationship> entry : players.entrySet()) {
                if (entry.getKey().equals(justAdded)) {
                    continue;
                }
                if (entry.getValue().interactions() < fewest) {
                    fewest = entry.getValue().interactions();
                    victim = entry.getKey();
                }
            }
            if (victim == null) {
                return;
            }
            players.remove(victim);
        }
    }

    public String toPromptText() {
        if (players.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【认识的玩家】\n");
        for (Relationship rel : players.values()) {
            sb.append("- ").append(rel.name()).append("（").append(rel.label())
              .append("，信任 ").append(Math.round(rel.trust * 100)).append("%")
              .append("，互动 ").append(rel.interactions()).append(" 次）");
            if (!rel.facts().isEmpty()) {
                sb.append("：").append(String.join("、", rel.facts()));
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
        for (Relationship rel : players.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Name", rel.name());
            tag.putDouble("Trust", rel.trust);
            tag.putInt("Interactions", rel.interactions);
            tag.putLong("LastSeen", rel.lastSeenTick);
            ListTag facts = new ListTag();
            for (String fact : rel.facts()) {
                facts.add(net.minecraft.nbt.StringTag.valueOf(fact));
            }
            tag.put("Facts", facts);
            list.add(tag);
        }
        parent.put(NBT_KEY, list);
    }

    public void loadFromNBT(CompoundTag parent) {
        players.clear();
        if (!parent.contains(NBT_KEY)) {
            return;
        }
        ListTag list = parent.getList(NBT_KEY, 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            String name = tag.getString("Name");
            if (name.isEmpty()) {
                continue;
            }
            Relationship rel = new Relationship(name, tag.getDouble("Trust"),
                tag.getInt("Interactions"), tag.getLong("LastSeen"));
            ListTag facts = tag.getList("Facts", 8);
            for (int j = 0; j < facts.size(); j++) {
                rel.addFact(facts.getString(j));
            }
            players.put(name.toLowerCase(Locale.ROOT), rel);
        }
    }
}
