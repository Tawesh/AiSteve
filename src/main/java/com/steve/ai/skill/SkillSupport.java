package com.steve.ai.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared keyword helpers for the built-in skills.
 *
 * <p>Kept in one place for the same reason {@code ActionUtils} centralises name resolution:
 * when every skill rolls its own matching, "木头" works in one skill and silently fails in the
 * next. One table, one behaviour.</p>
 */
final class SkillSupport {

    private SkillSupport() {
    }

    /** Query token -> canonical Minecraft block id. */
    private static final Map<String, String> BLOCK_TOKENS = new LinkedHashMap<>();
    /** Query token -> canonical entity id. */
    private static final Map<String, String> ENTITY_TOKENS = new LinkedHashMap<>();
    /** Structure token -> builder structure name. */
    private static final Map<String, String> STRUCTURE_TOKENS = new LinkedHashMap<>();
    /** Items a player would eat. */
    static final List<String> FOOD_ITEMS = List.of(
        "cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken",
        "cooked_cod", "cooked_salmon", "bread", "apple", "carrot", "potato",
        "baked_potato", "golden_apple", "melon_slice", "sweet_berries", "cookie");

    static {
        BLOCK_TOKENS.put("木头", "oak_log");
        BLOCK_TOKENS.put("原木", "oak_log");
        BLOCK_TOKENS.put("树", "oak_log");
        BLOCK_TOKENS.put("木", "oak_log");
        BLOCK_TOKENS.put("wood", "oak_log");
        BLOCK_TOKENS.put("log", "oak_log");
        BLOCK_TOKENS.put("tree", "oak_log");

        BLOCK_TOKENS.put("圆石", "cobblestone");
        BLOCK_TOKENS.put("石头", "stone");
        BLOCK_TOKENS.put("石", "stone");
        BLOCK_TOKENS.put("stone", "stone");
        BLOCK_TOKENS.put("cobble", "cobblestone");

        BLOCK_TOKENS.put("铁", "iron_ore");
        BLOCK_TOKENS.put("iron", "iron_ore");
        BLOCK_TOKENS.put("煤", "coal_ore");
        BLOCK_TOKENS.put("coal", "coal_ore");
        BLOCK_TOKENS.put("钻石", "diamond_ore");
        BLOCK_TOKENS.put("diamond", "diamond_ore");
        BLOCK_TOKENS.put("金", "gold_ore");
        BLOCK_TOKENS.put("gold", "gold_ore");
        BLOCK_TOKENS.put("铜", "copper_ore");
        BLOCK_TOKENS.put("copper", "copper_ore");
        BLOCK_TOKENS.put("红石", "redstone_ore");
        BLOCK_TOKENS.put("redstone", "redstone_ore");
        BLOCK_TOKENS.put("青金石", "lapis_ore");
        BLOCK_TOKENS.put("lapis", "lapis_ore");
        BLOCK_TOKENS.put("绿宝石", "emerald_ore");
        BLOCK_TOKENS.put("emerald", "emerald_ore");
        BLOCK_TOKENS.put("煤石", "coal_ore");

        BLOCK_TOKENS.put("沙", "sand");
        BLOCK_TOKENS.put("sand", "sand");
        BLOCK_TOKENS.put("泥土", "dirt");
        BLOCK_TOKENS.put("dirt", "dirt");
        BLOCK_TOKENS.put("南瓜", "pumpkin");
        BLOCK_TOKENS.put("pumpkin", "pumpkin");

        ENTITY_TOKENS.put("羊", "sheep");
        ENTITY_TOKENS.put("sheep", "sheep");
        ENTITY_TOKENS.put("牛", "cow");
        ENTITY_TOKENS.put("cow", "cow");
        ENTITY_TOKENS.put("猪", "pig");
        ENTITY_TOKENS.put("pig", "pig");
        ENTITY_TOKENS.put("鸡", "chicken");
        ENTITY_TOKENS.put("chicken", "chicken");
        ENTITY_TOKENS.put("兔子", "rabbit");
        ENTITY_TOKENS.put("rabbit", "rabbit");

        ENTITY_TOKENS.put("僵尸", "zombie");
        ENTITY_TOKENS.put("zombie", "zombie");
        ENTITY_TOKENS.put("骷髅", "skeleton");
        ENTITY_TOKENS.put("skeleton", "skeleton");
        ENTITY_TOKENS.put("苦力怕", "creeper");
        ENTITY_TOKENS.put("creeper", "creeper");
        ENTITY_TOKENS.put("蜘蛛", "spider");
        ENTITY_TOKENS.put("spider", "spider");
        ENTITY_TOKENS.put("怪物", "hostile");
        ENTITY_TOKENS.put("怪", "hostile");
        ENTITY_TOKENS.put("敌对", "hostile");
        ENTITY_TOKENS.put("hostile", "hostile");
        ENTITY_TOKENS.put("mob", "hostile");

        STRUCTURE_TOKENS.put("房子", "house");
        STRUCTURE_TOKENS.put("小屋", "house");
        STRUCTURE_TOKENS.put("house", "house");
        STRUCTURE_TOKENS.put("家", "house");
        STRUCTURE_TOKENS.put("城堡", "castle");
        STRUCTURE_TOKENS.put("castle", "castle");
        STRUCTURE_TOKENS.put("塔", "tower");
        STRUCTURE_TOKENS.put("tower", "tower");
        STRUCTURE_TOKENS.put("谷仓", "barn");
        STRUCTURE_TOKENS.put("barn", "barn");
        STRUCTURE_TOKENS.put("现代", "modern");
        STRUCTURE_TOKENS.put("modern", "modern");
        STRUCTURE_TOKENS.put("电站", "powerplant");
        STRUCTURE_TOKENS.put("powerplant", "powerplant");
    }

    static boolean containsAny(String haystack, String... needles) {
        if (haystack == null) {
            return false;
        }
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** First block id mentioned in the text, or {@code null}. */
    static String findBlockToken(String text) {
        return findToken(text, BLOCK_TOKENS);
    }

    /** First entity id mentioned in the text, or {@code null}. */
    static String findEntityToken(String text) {
        return findToken(text, ENTITY_TOKENS);
    }

    /** Structure name, defaulting to {@code house}. */
    static String findStructureToken(String text) {
        String found = findToken(text, STRUCTURE_TOKENS);
        return found == null ? "house" : found;
    }

    /** Longest matching key wins, so "圆石" beats "石". */
    private static String findToken(String text, Map<String, String> table) {
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String bestKey = null;
        for (String key : table.keySet()) {
            if (lower.contains(key) && (bestKey == null || key.length() > bestKey.length())) {
                bestKey = key;
            }
        }
        return bestKey == null ? null : table.get(bestKey);
    }

    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    /**
     * First number in the text, or the fallback.
     *
     * <p>Players and models both write "挖 8 个铁" / "mine 8 iron", so a plain first-number
     * extraction covers the realistic cases without a parser.</p>
     */
    static int readCount(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        Matcher matcher = NUMBER.matcher(text);
        if (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                return value > 0 && value <= 4096 ? value : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** The player name a request targets, from params first then from the description tail. */
    static String findPlayerName(SkillRequest request) {
        String fromParam = request.param("player");
        if (fromParam != null && !fromParam.isBlank()) {
            return fromParam;
        }
        String description = request.description();
        if (description == null || description.isBlank()) {
            return null;
        }
        // "跟随 陶哥" / "帮 陶哥 挖铁"
        String trimmed = description.replace("跟随", "").replace("跟着", "")
            .replace("帮我", "").replace("帮", "").replace("到", "").trim();
        int space = trimmed.indexOf(' ');
        String candidate = space > 0 ? trimmed.substring(0, space) : trimmed;
        candidate = candidate.replace("身边", "").replace("挖铁", "")
            .replace("挖", "").replace("去", "").trim();
        return candidate.isEmpty() ? null : candidate;
    }
}
