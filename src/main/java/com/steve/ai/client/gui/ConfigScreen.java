package com.steve.ai.client.gui;

import com.steve.ai.config.SteveConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 统一的 OpenAI 兼容 API 配置界面。
 *
 * <p>继承 {@link ScrollableSettingsScreen}：这一页有预设按钮、5 个输入框和两个底部按钮，
 * 纵向很容易超过窗口高度。改成可滚动之后，底部的"保存并应用/取消"在任何窗口尺寸下都够得着。</p>
 *
 * <p>左侧的文字标签画在 {@link #renderContent} 里，用输入框自身的 {@code getY()} 定位，
 * 因此会随滚动自动跟随（原来是写死的屏幕坐标，滚动后就会错位）。</p>
 */
public class ConfigScreen extends ScrollableSettingsScreen {

    private static final int FIELD_WIDTH = 350;
    private static final int LABEL_WIDTH = 80;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 10;

    private EditBox baseUrlField;
    private EditBox apiKeyField;
    private EditBox modelField;
    private EditBox maxTokensField;
    private EditBox temperatureField;

    private Button presetButton;
    private StringWidget presetHint;

    private int currentPreset = 0;
    private static final Preset[] PRESETS = {
        new Preset("自定义", "", "", ""),
        new Preset("DeepSeek", "https://api.deepseek.com", "deepseek-chat", "sk-"),
        new Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o", "sk-"),
        new Preset("Groq", "https://api.groq.com/openai/v1", "llama-3.1-8b-instant", "gsk_"),
        new Preset("SiliconFlow", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3", "sk-"),
        new Preset("阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "sk-")
    };

    public ConfigScreen(Screen parent) {
        super(Component.literal("大模型API配置"), parent);
    }

    @Override
    protected String hintText() {
        return "支持所有 OpenAI 兼容接口的 LLM 服务商（可用滚轮滚动）";
    }

    @Override
    protected void buildContent() {
        int x = this.width / 2 - FIELD_WIDTH / 2;
        int fieldX = x + LABEL_WIDTH;
        int fieldWidth = FIELD_WIDTH - LABEL_WIDTH;
        int y = 0;

        // ---- 预设 ----
        presetButton = Button.builder(
                presetLabel(),
                button -> {
                    currentPreset = (currentPreset + 1) % PRESETS.length;
                    button.setMessage(presetLabel());
                    if (currentPreset > 0) {
                        applyPreset(PRESETS[currentPreset]);
                    }
                })
            .bounds(x, y, FIELD_WIDTH, ROW_HEIGHT)
            .build();
        addContent(presetButton, y);
        y += ROW_HEIGHT + 4;

        presetHint = new StringWidget(x, y, FIELD_WIDTH, 12,
            Component.literal("§8点击切换预设可自动填入地址与模型名"), this.font);
        addContent(presetHint, y);
        y += 16;

        // ---- 输入框 ----
        baseUrlField = new EditBox(this.font, fieldX, y, fieldWidth, ROW_HEIGHT,
            Component.literal("API Base URL"));
        baseUrlField.setHint(Component.literal("https://api.example.com/v1"));
        String currentBaseUrl = SteveConfig.DEEPSEEK_BASE_URL.get();
        if (currentBaseUrl == null || currentBaseUrl.isEmpty()) {
            currentBaseUrl = "https://api.deepseek.com";
        }
        baseUrlField.setValue(currentBaseUrl);
        baseUrlField.setMaxLength(200);
        addContent(baseUrlField, y);
        y += ROW_HEIGHT + ROW_GAP;

        apiKeyField = new EditBox(this.font, fieldX, y, fieldWidth, ROW_HEIGHT,
            Component.literal("API Key"));
        apiKeyField.setHint(Component.literal("sk-..."));
        String apiKey = SteveConfig.DEEPSEEK_API_KEY.get();
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = SteveConfig.OPENAI_API_KEY.get();
        }
        apiKeyField.setValue(apiKey == null ? "" : apiKey);
        apiKeyField.setMaxLength(300);
        addContent(apiKeyField, y);
        y += ROW_HEIGHT + ROW_GAP;

        modelField = new EditBox(this.font, fieldX, y, fieldWidth, ROW_HEIGHT,
            Component.literal("Model"));
        modelField.setHint(Component.literal("deepseek-chat / gpt-4o"));
        String model = SteveConfig.DEEPSEEK_MODEL.get();
        if (model == null || model.isEmpty()) {
            model = SteveConfig.OPENAI_MODEL.get();
        }
        modelField.setValue(model == null ? "" : model);
        modelField.setMaxLength(100);
        addContent(modelField, y);
        y += ROW_HEIGHT + ROW_GAP;

        maxTokensField = new EditBox(this.font, fieldX, y, fieldWidth, ROW_HEIGHT,
            Component.literal("Max Tokens"));
        maxTokensField.setHint(Component.literal("100-65536"));
        maxTokensField.setValue(String.valueOf(SteveConfig.MAX_TOKENS.get()));
        maxTokensField.setMaxLength(10);
        addContent(maxTokensField, y);
        y += ROW_HEIGHT + ROW_GAP;

        temperatureField = new EditBox(this.font, fieldX, y, fieldWidth, ROW_HEIGHT,
            Component.literal("Temperature"));
        temperatureField.setHint(Component.literal("0.0-2.0"));
        temperatureField.setValue(String.valueOf(SteveConfig.TEMPERATURE.get()));
        temperatureField.setMaxLength(10);
        addContent(temperatureField, y);
        y += ROW_HEIGHT + ROW_GAP + 8;

        // ---- 底部按钮 ----
        int halfWidth = (FIELD_WIDTH - 20) / 2;

        addContent(Button.builder(
                Component.literal("保存并应用"),
                button -> saveAndClose())
            .bounds(x, y, halfWidth, ROW_HEIGHT)
            .build(), y);

        addContent(Button.builder(
                Component.literal("取消"),
                button -> goBack())
            .bounds(x + halfWidth + 20, y, halfWidth, ROW_HEIGHT)
            .build(), y);

        setContentHeight(y + ROW_HEIGHT);
    }

    private Component presetLabel() {
        return Component.literal("预设: " + PRESETS[currentPreset].name);
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int labelX = this.width / 2 - FIELD_WIDTH / 2;

        // 标签按输入框当前（已随滚动更新的）Y 定位，滚动时自动跟随。
        drawLabel(graphics, "API地址:", labelX, baseUrlField);
        drawLabel(graphics, "API密钥:", labelX, apiKeyField);
        drawLabel(graphics, "模型名称:", labelX, modelField);
        drawLabel(graphics, "最大令牌:", labelX, maxTokensField);
        drawLabel(graphics, "温度:", labelX, temperatureField);
    }

    private void drawLabel(GuiGraphics graphics, String text, int x, EditBox field) {
        if (field == null || !field.visible) {
            return;
        }
        graphics.drawString(this.font, text, x, field.getY() + 6, 0xFFFFFF);
    }

    private void applyPreset(Preset preset) {
        baseUrlField.setValue(preset.baseUrl);
        modelField.setValue(preset.model);
        boolean keyMatchesPreset = !apiKeyField.getValue().isEmpty()
            && apiKeyField.getValue().startsWith(preset.keyPrefix);
        if (!keyMatchesPreset) {
            apiKeyField.setValue(preset.keyPrefix);
            apiKeyField.moveCursorToEnd();
        }
    }

    private void saveAndClose() {
        try {
            // 统一保存到 deepseek 段（作为主配置）
            set(SteveConfig.DEEPSEEK_BASE_URL, baseUrlField.getValue());
            set(SteveConfig.DEEPSEEK_API_KEY, apiKeyField.getValue());
            set(SteveConfig.DEEPSEEK_MODEL, modelField.getValue());

            // 同步到 openai 段（兼容性）
            set(SteveConfig.OPENAI_API_KEY, apiKeyField.getValue());
            set(SteveConfig.OPENAI_MODEL, modelField.getValue());

            // 统一走 deepseek 客户端（OpenAI 兼容协议）
            set(SteveConfig.AI_PROVIDER, "deepseek");

            try {
                int maxTokens = Integer.parseInt(maxTokensField.getValue().trim());
                if (maxTokens >= 100 && maxTokens <= 65536) {
                    set(SteveConfig.MAX_TOKENS, maxTokens);
                }
            } catch (NumberFormatException ignored) {
                // 无效输入就直接保留原值，不打断保存
            }

            try {
                double temperature = Double.parseDouble(temperatureField.getValue().trim());
                if (temperature >= 0.0 && temperature <= 2.0) {
                    set(SteveConfig.TEMPERATURE, temperature);
                }
            } catch (NumberFormatException ignored) {
                // 同上
            }

            SteveConfig.SPEC.save();

            // 让新配置立即对运行中的 Agent 生效（活动半径、播报开关等）。
            com.steve.ai.config.RuntimeSettings.refresh();

            goBack();
        } catch (Exception e) {
            com.steve.ai.SteveMod.LOGGER.error("保存大模型配置失败", e);
        }
    }

    // Forge 的 ConfigValue#set 无泛型约束，统一收敛以免每个字段都写一次强转。
    private static void set(ForgeConfigSpec.ConfigValue<?> value, Object newValue) {
        @SuppressWarnings("unchecked")
        ForgeConfigSpec.ConfigValue<Object> cast = (ForgeConfigSpec.ConfigValue<Object>) value;
        cast.set(newValue);
    }

    private static class Preset {
        final String name;
        final String baseUrl;
        final String model;
        final String keyPrefix;

        Preset(String name, String baseUrl, String model, String keyPrefix) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.model = model;
            this.keyPrefix = keyPrefix;
        }
    }
}
