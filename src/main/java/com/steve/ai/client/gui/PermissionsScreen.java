package com.steve.ai.client.gui;

import com.steve.ai.config.RuntimeSettings;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.i18n.AgentLang;
import com.steve.ai.i18n.ConversationLanguage;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * AI 权限与行为设置页。
 *
 * <p>界面文本全部走 {@code translatable}，因此这一页本身会自动适配每个客户端的语言。
 * 只有"AI 说话语言"那一项显示的是语言的自称（简体中文 / English），这是语言选择器的惯例 ——
 * 你无法阅读的语言名对你没有帮助。</p>
 */
public class PermissionsScreen extends ScrollableSettingsScreen {

    private static final int FIELD_WIDTH = 300;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 12;
    private static final int SECTION_GAP = 10;

    private CycleButton<Boolean> agentEnabledButton;
    private CycleButton<Boolean> autonomyButton;
    private CycleButton<Boolean> chatResponseButton;
    private CycleButton<Boolean> idleChatButton;
    private CycleButton<Boolean> narrationButton;
    private CycleButton<Boolean> defendButton;
    private Button roamRadiusButton;
    private Button languageButton;

    private int roamRadius;
    private ConversationLanguage language;

    public PermissionsScreen(Screen parent) {
        super(Component.translatable("aisteve.screen.perm.title"), parent);
        this.roamRadius = SteveConfig.ROAM_RADIUS.get();
        this.language = RuntimeSettings.conversationLanguage();
    }

    @Override
    protected Component hintText() {
        return Component.translatable("aisteve.screen.perm.hint");
    }

    @Override
    protected void buildContent() {
        int x = this.width / 2 - FIELD_WIDTH / 2;
        int y = 0;

        // ---- 智能体 ----------------------------------------------------------
        y = addHeader("aisteve.screen.perm.section.agent", y);

        agentEnabledButton = boolButton(x, y, "aisteve.screen.perm.agent_enabled",
            SteveConfig.ENABLE_AGENT.get());
        y += ROW_HEIGHT + ROW_GAP;

        autonomyButton = boolButton(x, y, "aisteve.screen.perm.autonomy",
            SteveConfig.ENABLE_AUTONOMY.get());
        y += ROW_HEIGHT + ROW_GAP;

        roamRadiusButton = Button.builder(roamLabel(), button -> {
                // 在几个合理档位之间循环，比拖滑块省事
                int[] steps = {24, 32, 48, 64, 96, 128};
                int next = steps[0];
                for (int step : steps) {
                    if (step > roamRadius) {
                        next = step;
                        break;
                    }
                }
                roamRadius = next;
                button.setMessage(roamLabel());
            })
            .bounds(x, y, FIELD_WIDTH, ROW_HEIGHT)
            .build();
        addContent(roamRadiusButton, y);
        y += ROW_HEIGHT + ROW_GAP + SECTION_GAP;

        // ---- 对话 ------------------------------------------------------------
        y = addHeader("aisteve.screen.perm.section.chat", y);

        chatResponseButton = boolButton(x, y, "aisteve.screen.perm.chat_response",
            SteveConfig.ENABLE_CHAT_RESPONSES.get());
        y += ROW_HEIGHT + ROW_GAP;

        idleChatButton = boolButton(x, y, "aisteve.screen.perm.idle_chat",
            SteveConfig.ENABLE_IDLE_CHAT.get());
        y += ROW_HEIGHT + ROW_GAP;

        narrationButton = boolButton(x, y, "aisteve.screen.perm.narration",
            SteveConfig.ENABLE_PROGRESS_NARRATION.get());
        y += ROW_HEIGHT + ROW_GAP;

        defendButton = boolButton(x, y, "aisteve.screen.perm.defend",
            SteveConfig.DEFEND_AGAINST_PLAYERS.get());
        y += ROW_HEIGHT + ROW_GAP;

        languageButton = Button.builder(languageLabel(), button -> {
                language = language.opposite();
                button.setMessage(languageLabel());
            })
            .bounds(x, y, FIELD_WIDTH, ROW_HEIGHT)
            .build();
        addContent(languageButton, y);
        y += ROW_HEIGHT + 2;

        addContent(new StringWidget(x, y, FIELD_WIDTH, 12,
            Component.translatable("aisteve.screen.perm.language.hint"), this.font), y);
        y += 16 + SECTION_GAP;

        // ---- 底部按钮 --------------------------------------------------------
        int halfWidth = (FIELD_WIDTH - 20) / 2;

        addContent(Button.builder(
                Component.translatable("aisteve.screen.perm.save"),
                button -> saveAndClose())
            .bounds(x, y, halfWidth, ROW_HEIGHT)
            .build(), y);

        addContent(Button.builder(
                Component.translatable("aisteve.screen.perm.back"),
                button -> goBack())
            .bounds(x + halfWidth + 20, y, halfWidth, ROW_HEIGHT)
            .build(), y);

        setContentHeight(y + ROW_HEIGHT);
    }

    /** 分区标题占一行；返回下一行的 Y。 */
    private int addHeader(String key, int y) {
        addContent(new StringWidget(
            this.width / 2 - FIELD_WIDTH / 2, y, FIELD_WIDTH, ROW_HEIGHT,
            Component.translatable(key), this.font), y);
        return y + ROW_HEIGHT + 4;
    }

    private CycleButton<Boolean> boolButton(int x, int y, String labelKey, boolean initial) {
        CycleButton<Boolean> button = CycleButton.booleanBuilder(
                Component.translatable("aisteve.screen.perm.on"),
                Component.translatable("aisteve.screen.perm.off"))
            .withInitialValue(initial)
            .create(x, y, FIELD_WIDTH, ROW_HEIGHT, Component.translatable(labelKey),
                (cb, value) -> {});
        addContent(button, y);
        return button;
    }

    private Component roamLabel() {
        return Component.translatable("aisteve.screen.perm.roam", roamRadius);
    }

    /**
     * 语言按钮显示的是语言的自称。
     *
     * <p>刻意不做翻译：一个你看不懂的语言名对你没有帮助，所有语言选择器都是这个做法。</p>
     */
    private Component languageLabel() {
        return Component.translatable("aisteve.screen.perm.language", language.displayName());
    }

    private void saveAndClose() {
        try {
            apply(SteveConfig.ENABLE_AGENT, agentEnabledButton.getValue());
            apply(SteveConfig.ENABLE_AUTONOMY, autonomyButton.getValue());
            apply(SteveConfig.ROAM_RADIUS, roamRadius);
            apply(SteveConfig.ENABLE_CHAT_RESPONSES, chatResponseButton.getValue());
            apply(SteveConfig.ENABLE_IDLE_CHAT, idleChatButton.getValue());
            apply(SteveConfig.ENABLE_PROGRESS_NARRATION, narrationButton.getValue());
            apply(SteveConfig.DEFEND_AGAINST_PLAYERS, defendButton.getValue());
            apply(SteveConfig.AGENT_LANGUAGE, language.code());

            SteveConfig.SPEC.save();

            // 让新设置立即对运行中的 Agent 生效，不必重启
            RuntimeSettings.refresh();
            AgentLang.setCurrent(language);

            goBack();
        } catch (Exception e) {
            com.steve.ai.SteveMod.LOGGER.error("保存 AI 行为配置失败", e);
        }
    }

    // Forge 的 ConfigValue#set 不带泛型约束，统一收敛，避免每个字段写一遍强转。
    private static void apply(ForgeConfigSpec.ConfigValue<?> value, Object newValue) {
        @SuppressWarnings("unchecked")
        ForgeConfigSpec.ConfigValue<Object> cast = (ForgeConfigSpec.ConfigValue<Object>) value;
        cast.set(newValue);
    }
}
