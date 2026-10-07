package com.steve.ai.client.gui;

import com.steve.ai.menu.SteveInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Draws the AI backpack viewer opened by right-clicking the AI with an empty hand.
 *
 * <p>Extends the vanilla {@link AbstractContainerScreen} for the behaviour that matters -
 * centring, slot hit-testing, item rendering, tooltips, and closing on {@code E} - and draws its
 * own background instead of blitting a texture. That is deliberate and matches the rest of this
 * mod's GUI (see {@code ScrollableSettingsScreen}): the only GUI texture vanilla would offer here
 * is the 6-row chest, which is the wrong shape for a 4-row bag and would ship a stale copy of
 * Mojang's artwork.</p>
 *
 * <p>Note the world is <em>not</em> dimmed behind the panel, unlike the mod's settings screens
 * which call {@code renderBackground} themselves. That is vanilla's behaviour for container
 * screens in 1.20.1 (neither {@code AbstractContainerScreen} nor {@code Screen} calls
 * {@code renderBackground} on that path), and matching it keeps this window feeling like a normal
 * chest rather than a modal dialog.</p>
 */
public class SteveInventoryScreen extends AbstractContainerScreen<SteveInventoryMenu> {

    // Vanilla-like palette, reproduced with fills: a light panel with a black outline and
    // beveled slots. Kept as named constants so the look is one edit away if it needs tuning.
    private static final int PANEL_FILL = 0xFFC6C6C6;
    private static final int PANEL_EDGE_DARK = 0xFF000000;
    private static final int PANEL_EDGE_LIGHT = 0xFFFFFFFF;
    private static final int SLOT_FRAME = 0xFF373737;
    private static final int SLOT_FILL = 0xFF8B8B8B;
    private static final int SLOT_BEVEL = 0xFFFFFFFF;

    private static final int HINT_COLOR = 0xFF707070;
    private static final int EMPTY_COLOR = 0xFF8A8A8A;

    /** Distance from the panel top to the first row of the AI's slots, mirrored from the menu. */
    private static final int SLOT_PITCH = SteveInventoryMenu.SLOT_SIZE;

    public SteveInventoryScreen(SteveInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);

        this.imageWidth = SteveInventoryMenu.IMAGE_WIDTH;
        this.imageHeight = SteveInventoryMenu.IMAGE_HEIGHT;

        // The inherited title/player-inventory labels already sit where they should; only the
        // player label's Y has to move, because this panel is a different shape from a chest.
        this.inventoryLabelX = SteveInventoryMenu.PLAYER_INV_X;
        this.inventoryLabelY = SteveInventoryMenu.PLAYER_INV_Y - 10;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = this.leftPos;
        int top = this.topPos;

        // Panel: black outline, flat fill, single-pixel highlight along the top and left.
        graphics.fill(left - 1, top - 1, left + this.imageWidth + 1, top + this.imageHeight + 1,
            PANEL_EDGE_DARK);
        graphics.fill(left, top, left + this.imageWidth, top + this.imageHeight, PANEL_FILL);
        graphics.fill(left, top, left + this.imageWidth, top + 1, PANEL_EDGE_LIGHT);
        graphics.fill(left, top, left + 1, top + this.imageHeight, PANEL_EDGE_LIGHT);

        // Slots. Drawn from the menu's own constants, so the visuals can never disagree with
        // where the real slots are.
        for (int row = 0; row < SteveInventoryMenu.ROWS; row++) {
            for (int column = 0; column < SteveInventoryMenu.COLUMNS; column++) {
                drawSlot(graphics,
                    left + SteveInventoryMenu.AI_SLOT_X + column * SLOT_PITCH,
                    top + SteveInventoryMenu.AI_SLOT_Y + row * SLOT_PITCH);
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < SteveInventoryMenu.COLUMNS; column++) {
                drawSlot(graphics,
                    left + SteveInventoryMenu.PLAYER_INV_X + column * SLOT_PITCH,
                    top + SteveInventoryMenu.PLAYER_INV_Y + row * SLOT_PITCH);
            }
        }
        for (int column = 0; column < SteveInventoryMenu.COLUMNS; column++) {
            drawSlot(graphics,
                left + SteveInventoryMenu.PLAYER_INV_X + column * SLOT_PITCH,
                top + SteveInventoryMenu.PLAYER_HOTBAR_Y);
        }

        // An empty grid with no explanation looks like a broken window, so say so.
        if (this.menu.isAiInventoryEmpty()) {
            int gridHeight = SteveInventoryMenu.ROWS * SLOT_PITCH;
            graphics.drawCenteredString(this.font,
                Component.translatable("aisteve.gui.inventory.empty"),
                left + this.imageWidth / 2,
                top + SteveInventoryMenu.AI_SLOT_Y + gridHeight / 2 - 4,
                EMPTY_COLOR);
        }
    }

    /** One 18x18 beveled slot: dark frame, grey inside, white highlight bottom-right. */
    private static void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_FRAME);
        graphics.fill(x, y, x + 16, y + 16, SLOT_FILL);
        graphics.fill(x + 16, y, x + 17, y + 17, SLOT_BEVEL);
        graphics.fill(x, y + 16, x + 16, y + 17, SLOT_BEVEL);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Title ("<name> 的背包") and the vanilla "Inventory" label.
        super.renderLabels(graphics, mouseX, mouseY);

        // Read-only note, below the player's hotbar row. Coordinates here are panel-relative:
        // AbstractContainerScreen has already translated the pose by (leftPos, topPos).
        graphics.drawString(this.font,
            Component.translatable("aisteve.gui.inventory.readonly"),
            SteveInventoryMenu.AI_SLOT_X,
            SteveInventoryMenu.PLAYER_HOTBAR_Y + 22,
            HINT_COLOR,
            false);
    }
}
