package com.steve.ai.tool;

import com.steve.ai.protocol.RiskLevel;
import com.steve.ai.protocol.ToolSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogue of the installed tools.
 *
 * <p>Also the source of truth for the model-visible tool list: {@link #describeForPrompt()}
 * skips anything with {@link RiskLevel#FORBIDDEN}, which is how the permission boundary gets
 * expressed in the prompt rather than hoped for.</p>
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        if (tool != null && tool.spec() != null) {
            tools.put(tool.spec().name(), tool);
        }
    }

    public Tool get(String name) {
        return name == null ? null : tools.get(name);
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public int size() {
        return tools.size();
    }

    /** Every tool the model is allowed to know about. */
    public List<ToolSpec> modelVisibleSpecs() {
        List<ToolSpec> specs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (tool.spec().risk().isAvailableToModel()) {
                specs.add(tool.spec());
            }
        }
        return specs;
    }

    /** Renders the tool catalogue for the prompt, grouped by category. */
    public String describeForPrompt() {
        Map<String, List<ToolSpec>> byCategory = new LinkedHashMap<>();
        for (ToolSpec spec : modelVisibleSpecs()) {
            byCategory.computeIfAbsent(spec.category(), k -> new ArrayList<>()).add(spec);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<ToolSpec>> entry : byCategory.entrySet()) {
            sb.append("[").append(entry.getKey()).append("]\n");
            for (ToolSpec spec : entry.getValue()) {
                sb.append("  ").append(spec.toPromptLine()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Registry with all six built-in tool groups installed. */
    public static ToolRegistry createDefault() {
        ToolRegistry registry = new ToolRegistry();
        MovementTools.register(registry);
        InteractionTools.register(registry);
        InventoryTools.register(registry);
        CombatTools.register(registry);
        WorldTools.register(registry);
        SocialTools.register(registry);
        return registry;
    }

    public Map<String, Tool> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(tools));
    }
}
