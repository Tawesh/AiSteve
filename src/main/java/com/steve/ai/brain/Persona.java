package com.steve.ai.brain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Personality - the part that makes two AI players with identical skills behave differently.
 *
 * <p>Traits are 0..1 weights that bias decisions rather than hard rules:</p>
 * <table border="1">
 *   <tr><th>Trait</th><th>High</th><th>Low</th></tr>
 *   <tr><td>curiosity</td><td>进洞看看</td><td>这洞看着不太安全</td></tr>
 *   <tr><td>courage</td><td>正面刚苦力怕</td><td>先躲开再说</td></tr>
 *   <tr><td>humor</td><td>钻石！卧槽！</td><td>发现钻石矿。</td></tr>
 *   <tr><td>helpfulness</td><td>主动帮陶哥砍木头</td><td>只管自己的事</td></tr>
 *   <tr><td>risk_aversion</td><td>低血立刻撤</td><td>还能再打两下</td></tr>
 * </table>
 *
 * <p>{@link #speakingStyle()} is injected verbatim into the prompt, which is what actually
 * controls the voice in chat.</p>
 */
public final class Persona {

    private String name;
    private final Map<String, Double> traits = new LinkedHashMap<>();
    private final String speakingStyle;

    public Persona(String name, String speakingStyle) {
        this.name = name == null || name.isBlank() ? "Steve" : name;
        this.speakingStyle = speakingStyle == null
            ? "自然、简短、偶尔开玩笑，像一个普通玩家"
            : speakingStyle;

        // Balanced defaults: a normal, reasonably helpful player.
        traits.put("curiosity", 0.7);
        traits.put("courage", 0.6);
        traits.put("humor", 0.5);
        traits.put("helpfulness", 0.8);
        traits.put("risk_aversion", 0.5);
    }

    public String name() {
        return name;
    }

    /** Re-labels the persona when the companion is renamed with {@code /as create <name>}. */
    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public String speakingStyle() {
        return speakingStyle;
    }

    public double trait(String key) {
        return traits.getOrDefault(key, 0.5);
    }

    public Persona withTrait(String key, double value) {
        traits.put(key, Math.max(0.0, Math.min(1.0, value)));
        return this;
    }

    public Map<String, Double> traits() {
        return Map.copyOf(traits);
    }

    /** Coarse descriptor used when the AI talks about itself. */
    public String archetype() {
        if (trait("helpfulness") > 0.75 && trait("risk_aversion") > 0.6) {
            return "稳妥的可靠队友";
        }
        if (trait("courage") > 0.7 && trait("curiosity") > 0.7) {
            return "爱冒险的探索者";
        }
        if (trait("helpfulness") > 0.7) {
            return "贴心的搭档";
        }
        return "普通玩家";
    }

    public String toPromptText() {
        return "【人格】\n"
            + "- 名字：" + name + "（" + archetype() + "）\n"
            + "- 性格权重：好奇 " + fmt("curiosity")
            + "，勇气 " + fmt("courage")
            + "，幽默 " + fmt("humor")
            + "，热心 " + fmt("helpfulness")
            + "，怕危险 " + fmt("risk_aversion") + "\n"
            + "- 说话风格：" + speakingStyle + "\n";
    }

    private String fmt(String key) {
        return String.valueOf(Math.round(trait(key) * 100) / 100.0);
    }

    @Override
    public String toString() {
        return "Persona{" + name + ", " + archetype() + "}";
    }
}
