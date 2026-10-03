package com.steve.ai.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 主设置菜单。
 *
 * <p>改为继承 {@link ScrollableSettingsScreen} 后，即使窗口很矮也不会再出现按钮掉出屏幕、
 * 无法点击的问题。</p>
 */
public class MainSettingsScreen extends ScrollableSettingsScreen {

    private static final int BUTTON_WIDTH = 300;
    private static final int ROW_HEIGHT = 20;

    public MainSettingsScreen(Screen parent) {
        super(Component.literal("AiSteve 设置"), parent);
    }

    @Override
    protected String hintText() {
        return "配置 AI 玩家的功能和行为";
    }

    @Override
    protected void buildContent() {
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int y = 0;
        int gap = 10;

        addContent(Button.builder(
                Component.literal("大模型配置"),
                button -> open(new ConfigScreen(this)))
            .bounds(x, y, BUTTON_WIDTH, ROW_HEIGHT)
            .build(), y);
        y += ROW_HEIGHT + gap;

        addContent(Button.builder(
                Component.literal("AI权限与行为"),
                button -> open(new PermissionsScreen(this)))
            .bounds(x, y, BUTTON_WIDTH, ROW_HEIGHT)
            .build(), y);
        y += ROW_HEIGHT + gap;

        addContent(Button.builder(
                Component.literal("AI能力开关"),
                button -> open(new CapabilitiesScreen(this)))
            .bounds(x, y, BUTTON_WIDTH, ROW_HEIGHT)
            .build(), y);
        y += ROW_HEIGHT + gap * 2;

        addContent(Button.builder(
                Component.literal("完成"),
                button -> goBack())
            .bounds(this.width / 2 - 75, y, 150, ROW_HEIGHT)
            .build(), y);

        setContentHeight(y + ROW_HEIGHT);
    }

    private void open(Screen screen) {
        if (this.minecraft != null) {
            this.minecraft.setScreen(screen);
        }
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 本页没有额外的文字标签需要跟随滚动。
    }
}
