package com.steve.ai.client.gui;

import com.steve.ai.config.ActionCapabilities;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 能力开关页。
 *
 * <p>继承 {@link ScrollableSettingsScreen}。这一页原来有 12 个控件、总高度约 335 像素，
 * 用的是硬编码 Y 坐标，因此在窗口较矮（或 GUI 缩放较大）时底部的"保存/返回"必然被裁掉 ——
 * 就是玩家反馈的"底部按钮被遮挡"。现在内容可滚动，底部永远够得着。</p>
 */
public class CapabilitiesScreen extends ScrollableSettingsScreen {

    private static final int FIELD_WIDTH = 280;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;

    /** 能力开关，顺序与保存时的写入顺序一致。 */
    private final List<CycleButton<Boolean>> capabilityButtons = new ArrayList<>();

    public CapabilitiesScreen(Screen parent) {
        super(Component.literal("AI能力开关"), parent);
    }

    @Override
    protected String hintText() {
        return "控制 AI 可以执行的动作类型（可用滚轮滚动）";
    }

    @Override
    protected void buildContent() {
        capabilityButtons.clear();

        int centerX = this.width / 2;
        int x = centerX - FIELD_WIDTH / 2;
        int y = 0;

        y = addToggle(x, y, "采矿 (Mine)", ActionCapabilities.canMine());
        y = addToggle(x, y, "建造 (Build)", ActionCapabilities.canBuild());
        y = addToggle(x, y, "合成 (Craft)", ActionCapabilities.canCraft());
        y = addToggle(x, y, "战斗 (Combat)", ActionCapabilities.canCombat());
        y = addToggle(x, y, "钓鱼 (Fish)", ActionCapabilities.canFish());
        y = addToggle(x, y, "种田 (Farm)", ActionCapabilities.canFarm());
        y = addToggle(x, y, "探索 (Explore)", ActionCapabilities.canExplore());
        y = addToggle(x, y, "翻箱子 (Loot)", ActionCapabilities.canLoot());

        y += 8;

        // 批量开关
        int halfWidth = (FIELD_WIDTH - 10) / 2;

        Button enableAll = Button.builder(
                Component.literal("全部启用"),
                button -> setAll(true))
            .bounds(x, y, halfWidth, ROW_HEIGHT)
            .build();
        addContent(enableAll, y);

        Button disableAll = Button.builder(
                Component.literal("全部禁用"),
                button -> setAll(false))
            .bounds(x + halfWidth + 10, y, halfWidth, ROW_HEIGHT)
            .build();
        addContent(disableAll, y);
        y += ROW_HEIGHT + 16;

        // 保存 / 返回
        Button saveButton = Button.builder(
                Component.literal("保存"),
                button -> saveAndClose())
            .bounds(x, y, halfWidth, ROW_HEIGHT)
            .build();
        addContent(saveButton, y);

        Button backButton = Button.builder(
                Component.literal("返回"),
                button -> goBack())
            .bounds(x + halfWidth + 10, y, halfWidth, ROW_HEIGHT)
            .build();
        addContent(backButton, y);

        setContentHeight(y + ROW_HEIGHT);
    }

    private int addToggle(int x, int y, String label, boolean initial) {
        CycleButton<Boolean> button = CycleButton.booleanBuilder(
                Component.literal("✓ 开启"), Component.literal("✗ 关闭"))
            .withInitialValue(initial)
            .create(x, y, FIELD_WIDTH, ROW_HEIGHT,
                Component.literal(label),
                (cb, value) -> {});
        addContent(button, y);
        capabilityButtons.add(button);
        return y + ROW_HEIGHT + ROW_GAP;
    }

    private void setAll(boolean value) {
        for (CycleButton<Boolean> button : capabilityButtons) {
            button.setValue(value);
        }
    }

    private void saveAndClose() {
        ActionCapabilities.setCanMine(capabilityButtons.get(0).getValue());
        ActionCapabilities.setCanBuild(capabilityButtons.get(1).getValue());
        ActionCapabilities.setCanCraft(capabilityButtons.get(2).getValue());
        ActionCapabilities.setCanCombat(capabilityButtons.get(3).getValue());
        ActionCapabilities.setCanFish(capabilityButtons.get(4).getValue());
        ActionCapabilities.setCanFarm(capabilityButtons.get(5).getValue());
        ActionCapabilities.setCanExplore(capabilityButtons.get(6).getValue());
        ActionCapabilities.setCanLoot(capabilityButtons.get(7).getValue());

        ActionCapabilities.save();

        goBack();
    }
}
