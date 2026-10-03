package com.steve.ai.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Static description of a {@code Tool} - "what am I and what do I need".
 *
 * <p>Deliberately provider-agnostic: it is rendered into the prompt as plain text
 * (see {@link #toPromptLine()}) rather than being tied to OpenAI's {@code tools} JSON shape,
 * so the same spec works for DeepSeek / GPT / Qwen / Claude / a local model.</p>
 *
 * <p><b>Example</b></p>
 * <pre>
 * ToolSpec.builder("move_to", "走到指定坐标")
 *     .category("movement")
 *     .param("x", "number", "目标 X")
 *     .param("y", "number", "目标 Y")
 *     .param("z", "number", "目标 Z")
 *     .permission(Permission.MOVEMENT)
 *     .risk(RiskLevel.LOW)
 *     .build();
 * </pre>
 */
public final class ToolSpec {

    private final String name;
    private final String description;
    private final String category;
    private final Map<String, String> parameters;
    private final Permission permission;
    private final RiskLevel risk;

    private ToolSpec(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.category = b.category;
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(b.parameters));
        this.permission = b.permission;
        this.risk = b.risk;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** Coarse grouping: movement / interaction / inventory / combat / world / social. */
    public String category() {
        return category;
    }

    /** Parameter name -> "type: description" (insertion ordered). */
    public Map<String, String> parameters() {
        return parameters;
    }

    public Permission permission() {
        return permission;
    }

    public RiskLevel risk() {
        return risk;
    }

    /**
     * One line of the tool catalogue handed to the model.
     *
     * @return e.g. {@code move_to {"x":"number: 目标X","z":"number: 目标Z"} - 走到指定坐标 [movement/low]}
     */
    public String toPromptLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        if (!parameters.isEmpty()) {
            sb.append(" {");
            boolean first = true;
            for (Map.Entry<String, String> e : parameters.entrySet()) {
                if (!first) sb.append(", ");
                sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
                first = false;
            }
            sb.append('}');
        }
        sb.append(" - ").append(description)
          .append(" [").append(category).append('/').append(risk.name().toLowerCase()).append(']');
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ToolSpec{" + name + ", " + category + ", " + permission + ", " + risk + "}";
    }

    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    /** Fluent builder. */
    public static final class Builder {
        private final String name;
        private final String description;
        private String category = "general";
        private final Map<String, String> parameters = new LinkedHashMap<>();
        private Permission permission = Permission.WORLD_READ;
        private RiskLevel risk = RiskLevel.LOW;

        private Builder(String name, String description) {
            this.name = Objects.requireNonNull(name, "tool name");
            this.description = Objects.requireNonNull(description, "tool description");
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        /**
         * Declares one parameter.
         *
         * @param name parameter key as the model will emit it
         * @param type short type hint ("number", "string", "boolean", "array")
         * @param desc human/LLM readable meaning
         */
        public Builder param(String name, String type, String desc) {
            this.parameters.put(name, type + ": " + desc);
            return this;
        }

        public Builder required(String name, String type, String desc) {
            return param(name, type, desc + "（必填）");
        }

        public Builder permission(Permission permission) {
            this.permission = permission;
            return this;
        }

        public Builder risk(RiskLevel risk) {
            this.risk = risk;
            return this;
        }

        public ToolSpec build() {
            return new ToolSpec(this);
        }
    }
}
