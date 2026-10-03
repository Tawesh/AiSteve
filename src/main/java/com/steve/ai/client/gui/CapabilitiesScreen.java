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
 * <p>文本全部为 {@code translatable}，跟随各客户端自己的语言。</p>
 */
public class CapabilitiesScreen extends ScrollableSettingsScreen {

    private static final int FIELD_WIDTH = 280;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;

    /** 能力开关，顺序与保存时的写入顺序一致。 */
    private final List<CycleButton<Boolean>> capabilityButtons = new ArrayList<>();

    public CapabilitiesScreen(Screen parent) {
        super(Component.translatable("aisteve.screen.cap.title"), parent);
    }

    @Override
    protected Component hintText() {
        return Component.translatable("aisteve.screen.cap.hint");
    }

    @Override
    protected void buildContent() {
        capabilityButtons.clear();

        int x = this.width / 2 - FIELD_WIDTH / 2;
        int y = 0;

        y = addToggle(x, y, "aisteve.screen.cap.mine", ActionCapabilities.canMine());
        y = addToggle(x, y, "aisteve.screen.cap.build", ActionCapabilities.canBuild());
        y = addToggle(x, y, "aisteve.screen.cap.craft", ActionCapabilities.canCraft());
        y = addToggle(x, y, "aisteve.screen.cap.combat", ActionCapabilities.canCombat());
        y = addToggle(x, y, "aisteve.screen.cap.fish", ActionCapabilities.canFish());
        y = addToggle(x, y, "aisteve.screen.cap.farm", ActionCapabilities.canFarm());
        y = addToggle(x, y, "aisteve.screen.cap.explore", ActionCapabilities.canExplore());
        y = addToggle(x, y, "aisteve.screen.cap.loot", ActionCapabilities.canLoot());

        y += 8;
        int halfWidth = (FIELD_WIDTH - 10) / 2;

        addContent(Button.builder(
                Component.translatable("aisteve.screen.cap.enable_all"),
                button -> setAll(true))
            .bounds(x, y, halfWidth, ROW_HEIGHT)
            .build(), y);

        addContent(Button.builder(
                Component.translatable("aisteve.screen.cap.disable_all"),
                button -> setAll(false))
            .bounds(x + halfWidth + 10, y, halfWidth, ROW_HEIGHT)
            .build(), y);
        y += ROW_HEIGHT + 16;

        addContent(Button.builder(
                Component.translatable("aisteve.screen.cap.save"),
                button -> saveAndClose())
            .bounds(x, y, halfWidth, ROW_HEIGHT)
            .build(), y);

        addContent(Button.builder(
                Component.translatable("aisteve.screen.cap.back"),
                button -> goBack())
            .bounds(x + halfWidth + 10, y, halfWidth, ROW_HEIGHT)
            .build(), y);

        setContentHeight(y + ROW_HEIGHT);
    }

    /** {@code labelKey} 是语言键，渲染时才会按客户端语言解析。 */
    private int addToggle(int x, int y, String labelKey, boolean initial) {
        CycleButton<Boolean> button = CycleButton.booleanBuilder(
                Component.translatable("aisteve.screen.cap.on"),
                Component.translatable("aisteve.screen.cap.off"))
            .withInitialValue(initial)
            .create(x, y, FIELD_WIDTH, ROW_HEIGHT,
                Component.translatable(labelKey),
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
