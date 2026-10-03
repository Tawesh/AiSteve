package com.steve.ai.memory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Layer 3 of 4: long-term world knowledge - "how Minecraft works".
 *
 * <p>Facts such as <i>钻石需要铁镐</i> or <i>夜晚会生成敌对生物</i>. Seeded with a small core
 * rule set and extended at runtime whenever the AI learns something the hard way.</p>
 *
 * <p><b>Retrieval.</b> A full embedding store would be overkill inside a game tick, so this
 * uses a deliberately simple lexical scorer: latin words match on whole tokens, CJK text
 * matches per character. It behaves like a tiny RAG index - enough to pull the two or three
 * relevant rules into the prompt instead of dumping all of them.</p>
 */
public final class SemanticMemory {

    private static final String NBT_KEY = "AgentSemanticMemory";
    /** Never send more than this many facts to the model. */
    private static final int MAX_RETRIEVED = 6;

    /** One piece of knowledge. */
    public record Fact(String key, String text, List<String> tags, double weight) {}

    private final Map<String, Fact> facts = new LinkedHashMap<>();

    public SemanticMemory() {
        seedDefaults();
    }

    /** Idempotently stores/updates a fact. */
    public void learn(String key, String text, String... tags) {
        if (key == null || text == null || text.isBlank()) {
            return;
        }
        facts.put(key.toLowerCase(Locale.ROOT), new Fact(
            key.toLowerCase(Locale.ROOT), text.trim(),
            tags == null ? List.of() : List.of(tags), 1.0));
    }

    /** Bumps a fact's weight every time it proves useful. */
    public void reinforce(String key) {
        Fact existing = facts.get(key == null ? "" : key.toLowerCase(Locale.ROOT));
        if (existing != null && existing.weight() < 5.0) {
            facts.put(existing.key(), new Fact(existing.key(), existing.text(),
                existing.tags(), existing.weight() + 0.5));
        }
    }

    public int size() {
        return facts.size();
    }

    public List<Fact> all() {
        return List.copyOf(facts.values());
    }

    /** Removes a fact (used when reality contradicts it). */
    public void forget(String key) {
        facts.remove(key == null ? "" : key.toLowerCase(Locale.ROOT));
    }

    /**
     * Retrieves the facts most relevant to a query.
     *
     * @param query natural language, e.g. "怎么挖钻石"
     * @param k     maximum hits
     */
    public List<Fact> retrieve(String query, int k) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        Map<String, Integer> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        List<Scored> scored = new ArrayList<>();
        for (Fact fact : facts.values()) {
            double score = score(fact, tokens);
            if (score > 0) {
                scored.add(new Scored(fact, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<Fact> result = new ArrayList<>();
        int limit = Math.min(k, MAX_RETRIEVED);
        for (int i = 0; i < scored.size() && i < limit; i++) {
            result.add(scored.get(i).fact);
        }
        return result;
    }

    private double score(Fact fact, Map<String, Integer> tokens) {
        String haystack = (fact.text() + ' ' + fact.key() + ' ' + String.join(" ", fact.tags()))
            .toLowerCase(Locale.ROOT);
        double score = 0;
        for (Map.Entry<String, Integer> token : tokens.entrySet()) {
            if (haystack.contains(token.getKey())) {
                // Rare/long tokens are more informative than common short ones.
                score += token.getValue() * (1.0 + Math.min(3, token.getKey().length()) * 0.5);
            }
        }
        return score * fact.weight();
    }

    /**
     * Splits a query into weighted search tokens.
     *
     * <p>Latin/alphanumeric runs become whole tokens; CJK is split per character, because
     * Chinese is not whitespace-delimited and per-character matching is what actually works
     * for "挖钻石" → 挖 / 钻 / 石.</p>
     */
    private static Map<String, Integer> tokenize(String query) {
        Map<String, Integer> tokens = new HashMap<>();
        StringBuilder latin = new StringBuilder();

        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (Character.isLetterOrDigit(c) && c < 0x2E80) {
                latin.append(Character.toLowerCase(c));
            } else {
                if (latin.length() > 1) {
                    tokens.merge(latin.toString(), 1, Integer::sum);
                }
                latin.setLength(0);
                if (c >= 0x4E00 && c <= 0x9FFF) {
                    tokens.merge(String.valueOf(c), 1, Integer::sum);
                }
            }
        }
        if (latin.length() > 1) {
            tokens.merge(latin.toString(), 1, Integer::sum);
        }
        return tokens;
    }

    // ------------------------------------------------------------------

    public String toPromptText(String query, int k) {
        List<Fact> hits = retrieve(query, k);
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【相关知识】\n");
        for (Fact fact : hits) {
            sb.append("- ").append(fact.text()).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Seed knowledge
    // ------------------------------------------------------------------

    private void seedDefaults() {
        learn("diamond_tool", "钻石矿石必须用铁镐及以上等级的镐才能挖，石镐挖了不掉落。", "钻石", "镐", "挖矿");
        learn("iron_from_stone", "铁矿石需要用石镐及以上挖取，然后在熔炉里烧成铁锭。", "铁", "熔炉", "挖矿");
        learn("village_loot", "村庄的箱子里常有面包、胡萝卜、马铃薯等现成食物。", "村庄", "食物", "箱子");
        learn("night_mobs", "夜晚会生成僵尸、骷髅、苦力怕等敌对生物，低血量时应该回家或躲进掩体。", "夜晚", "危险", "战斗");
        learn("creeper", "苦力怕靠近后会自爆，不要贴脸打，保持距离或让队友处理。", "苦力怕", "危险", "战斗");
        learn("cook_meat", "用打火石点燃动物再击杀，掉落的是熟肉，吃了更回血。", "食物", "打火石", "熟肉");
        learn("crafting_chain", "木镐需要 3 木板 + 2 木棍；木板由原木合成，木棍由木板合成。", "合成", "工具", "木镐");
        learn("torch", "火把 = 1 煤炭/木炭 + 1 木棍，下矿时插火把可以防止刷怪。", "火把", "挖矿", "照明");
        learn("nether_lava", "下界到处是岩浆，搭桥时贴边走，掉进去基本没救。", "下界", "岩浆", "危险");
        learn("bed_explode", "在下界和末地睡觉会爆炸，不要在那些维度用床。", "床", "下界", "末地");
        learn("water_bucket", "水桶可以灭火、也能把自己从高处安全送下去。", "水桶", "生存");
        learn("food_sources", "稳定的食物来源：农田收割、水边钓鱼、村庄箱子、猎杀动物。", "食物", "钓鱼", "农田");
        learn("shield", "副手拿盾牌可以挡住大部分伤害，遇到骷髅很有用。", "盾牌", "战斗", "防御");
        learn("fall_damage", "从高处掉下会受伤甚至死亡，注意不要直接跳下悬崖。", "摔落", "危险", "生存");
        learn("end_dragon", "末影龙在末地，需要先找到要塞并激活末地传送门。", "末影龙", "末地");
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public void saveToNBT(CompoundTag parent) {
        ListTag list = new ListTag();
        for (Fact fact : facts.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Key", fact.key());
            tag.putString("Text", fact.text());
            tag.putDouble("Weight", fact.weight());
            ListTag tags = new ListTag();
            for (String t : fact.tags()) {
                tags.add(net.minecraft.nbt.StringTag.valueOf(t));
            }
            tag.put("Tags", tags);
            list.add(tag);
        }
        parent.put(NBT_KEY, list);
    }

    public void loadFromNBT(CompoundTag parent) {
        if (!parent.contains(NBT_KEY)) {
            return;
        }
        ListTag list = parent.getList(NBT_KEY, 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            String key = tag.getString("Key");
            if (key.isEmpty()) {
                continue;
            }
            List<String> tags = new ArrayList<>();
            ListTag tagList = tag.getList("Tags", 8);   // 8 = string
            for (int j = 0; j < tagList.size(); j++) {
                tags.add(tagList.getString(j));
            }
            facts.put(key, new Fact(key, tag.getString("Text"), tags,
                tag.getDouble("Weight") == 0 ? 1.0 : tag.getDouble("Weight")));
        }
    }

    private record Scored(Fact fact, double score) {}
}
