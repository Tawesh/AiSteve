package com.steve.ai.client.gui;

import com.steve.ai.config.SteveConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * AI 权限与行为设置页。
 *
 * <p>继承 {@link ScrollableSettingsScreen}：控件按内容坐标顺序排下去，窗口不够高时可以滚动，
 * 不会再出现底部"保存/返回"被裁掉、点不到的情况（见该类注释）。</p>
 */
public class PermissionsScreen extends ScrollableSettingsScreen {

    private static final int FIELD_WIDTH = 300;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 12;
    private static final int SECTION_GAP = 10;

    private CycleButton<Boolean> chatResponseButton;
    private CycleButton<Boolean> agentEnabledButton;
    private CycleButton<Boolean> autonomyButton;
    private CycleButton<Boolean> idleChatButton;
    private CycleButton<Boolean> narrationButton;
    private Button roamRadiusButton;

    private int roamRadius;

    public PermissionsScreen(Screen parent) {
        super(Component.literal("AI权限与行为"), parent);
        this.roamRadius = SteveConfig.ROAM_RADIUS.get();
    }

    @Override
    protected String hintText() {
        return "配置 AI 的行为和权限设置（可用滚轮滚动）";
    }

    @Override
    protected void buildContent() {
        int centerX = this.width / 2;
        int x = centerX - FIELD_WIDTH / 2;
        int y = 0;

        // ---- 分层 Agent ------------------------------------------------------
        y = addHeader("智能体", y);

        agentEnabledButton = CycleButton.booleanBuilder(
                Component.literal("已启用"), Component.literal("已关闭"))
            .withInitialValue(SteveConfig.ENABLE_AGENT.get())
            .create(x, y, FIELD_WIDTH, ROW_HEIGHT,
                Component.literal("分层 Agent（目标/技能/记忆）"),
                (button, value) -> {});
        addContent(agentEnabledButton, y);
        y += ROW_HEIGHT + ROW_GAP;

        autonomyButton = CycleButton.booleanBuilder(
                Component.literal("已启用"), Component.literal("已关闭"))
            .withInitialValue(SteveConfig.ENABLE_AUTONOMY.get())
            .create(x, y, FIELD_WIDTH, ROW_HEIGHT,
                Component.literal("自主行为（自己找事做）"),
                (button, value) -> {});
        addContent(autonomyButton, y);
        y += ROW_HEIGHT + ROW_GAP;

        roamRadiusButton = Button.builder(
                radiusLabel(),
                button -> {
                    // Cycle through sensible radii rather than fiddling with a slider.
                    int[] steps = {24, 32, 48, 64, 96, 128};
                    int next = steps[0];
                    for (int i = 0; i < steps.length; i++) {
                        if (steps[i] > roamRadius) {
                            next = steps[i];
                            break;
                        }
                        next = steps[0];
                    }
                    roamRadius = next;
                    button.setMessage(radiusLabel());
                })
            .bounds(x, y, FIELD_WIDTH, ROW_HEIGHT)
            .build();
        addContent(roamRadiusButton, y);
        y += ROW_HEIGHT + ROW_GAP + SECTION_GAP;

        // ---- 对话 ------------------------------------------------------------
        y = addHeader("对话", y);

        chatResponseButton = CycleButton.booleanBuilder(
                Component.literal("已启用"), Component.literal("已关闭"))
            .withInitialValue(SteveConfig.ENABLE_CHAT_RESPONSES.get())
            .create(x, y, FIELD_WIDTH, ROW_HEIGHT,
                Component.literal("聊天响应"),
                (button, value) -> {});
        addContent(chatResponseButton, y);
        y += ROW_HEIGHT + ROW_GAP;

        idleChatButton = CycleButton.booleanBuilder(
                Component.literal("已启用"), Component.literal("已关闭"))
            .withInitialValue(SteveConfig.ENABLE_IDLE_CHAT.get())
            .create(x, y, FIELD_WIDTH, ROW_HEIGHT,
                Component.literal("主动说话"),
                (button, value) -> {});
        addContent(idleChatButton, y);
        y += ROW_HEIGHT + ROW_GAP;

        narrationButton = CycleButton.booleanBuilder(
                Component.literal("已启用"), Component.literal("已关闭"))
            .withInitialValue(SteveConfig.ENABLE_PROGRESS_NARRATION.get())
            .create(x, y, FIELD_WIDTH, ROW_HEIGHT,
                Component.literal("进度播报（先说要做啥，做完汇报）"),
                (button, value) -> {});
        addContent(narrationButton, y);
        y += ROW_HEIGHT + ROW_GAP + SECTION_GAP;

        // ---- 按钮 ------------------------------------------------------------
        int halfWidth = (FIELD_WIDTH - 20) / 2;

        Button saveButton = Button.builder(
                Component.literal("保存"),
                button -> saveAndClose())
            .bounds(x, y, halfWidth, ROW_HEIGHT)
            .build();
        addContent(saveButton, y);

        Button backButton = Button.builder(
                Component.literal("返回"),
                button -> goBack())
            .bounds(x + halfWidth + 20, y, halfWidth, ROW_HEIGHT)
            .build();
        addContent(backButton, y);

        setContentHeight(y + ROW_HEIGHT);
    }

    /** 分区标题占一行；返回下一行的 Y。 */
    private int addHeader(String text, int y) {
        StringWidget label = new StringWidget(
            this.width / 2 - FIELD_WIDTH / 2, y, FIELD_WIDTH, ROW_HEIGHT,
            Component.literal("§7— " + text + " —"), this.font);
        addContent(label, y);
        return y + ROW_HEIGHT + 4;
    }

    private Component radiusLabel() {
        return Component.literal("跟随距离: " + roamRadius + " 格（超出就回来）");
    }

    private void saveAndClose() {
        try {
            apply(SteveConfig.ENABLE_CHAT_RESPONSES, chatResponseButton.getValue());
            apply(SteveConfig.ENABLE_AGENT, agentEnabledButton.getValue());
            apply(SteveConfig.ENABLE_AUTONOMY, autonomyButton.getValue());
            apply(SteveConfig.ENABLE_IDLE_CHAT, idleChatButton.getValue());
            apply(SteveConfig.ENABLE_PROGRESS_NARRATION, narrationButton.getValue());
            apply(SteveConfig.ROAM_RADIUS, roamRadius);

            SteveConfig.SPEC.save();

            // 让改动立即生效，不必重启游戏
            com.steve.ai.config.RuntimeSettings.refresh();

            goBack();
        } catch (Exception e) {
            com.steve.ai.SteveMod.LOGGER.error("保存 AI 行为配置失败", e);
        }
    }

    // Forge 的 ConfigValue#set 不带泛型约束，这里统一收敛，避免每个字段写一遍强转。
    private static void apply(ForgeConfigSpec.ConfigValue<?> value, Object newValue) {
        @SuppressWarnings("unchecked")
        ForgeConfigSpec.ConfigValue<Object> cast = (ForgeConfigSpec.ConfigValue<Object>) value;
        cast.set(newValue);
    }
}
