package com.steve.ai.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 主设置菜单。
 *
 * <p>所有文本使用 {@link Component#translatable}，因此界面语言由 Minecraft 自己按
 * <b>每个客户端</b>的语言设置渲染 —— 中文客户端看到中文，英文客户端看到英文，
 * 同一个服务器上可以并存，不需要任何配置。</p>
 */
public class MainSettingsScreen extends ScrollableSettingsScreen {

    private static final int BUTTON_WIDTH = 300;
    private static final int ROW_HEIGHT = 20;

    public MainSettingsScreen(Screen parent) {
        super(Component.translatable("aisteve.screen.main.title"), parent);
    }

    @Override
    protected Component hintText() {
        return Component.translatable("aisteve.screen.main.hint");
    }

    @Override
    protected void buildContent() {
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int y = 0;
        int gap = 10;

        addContent(Button.builder(
                Component.translatable("aisteve.screen.main.llm"),
                button -> open(new ConfigScreen(this)))
            .bounds(x, y, BUTTON_WIDTH, ROW_HEIGHT)
            .build(), y);
        y += ROW_HEIGHT + gap;

        addContent(Button.builder(
                Component.translatable("aisteve.screen.main.permissions"),
                button -> open(new PermissionsScreen(this)))
            .bounds(x, y, BUTTON_WIDTH, ROW_HEIGHT)
            .build(), y);
        y += ROW_HEIGHT + gap;

        addContent(Button.builder(
                Component.translatable("aisteve.screen.main.capabilities"),
                button -> open(new CapabilitiesScreen(this)))
            .bounds(x, y, BUTTON_WIDTH, ROW_HEIGHT)
            .build(), y);
        y += ROW_HEIGHT + gap * 2;

        addContent(Button.builder(
                Component.translatable("aisteve.screen.main.done"),
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
}
