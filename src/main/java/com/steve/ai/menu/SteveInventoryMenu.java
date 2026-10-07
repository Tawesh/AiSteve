package com.steve.ai.menu;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveInventory;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The container menu behind "right-click the AI with an empty hand to look inside its bag".
 *
 * <p>Implemented as a real vanilla menu rather than a chat dump or a bespoke widget, so the AI's
 * backpack behaves like every other container in the game: normal slots, tooltips, item
 * rendering, and the window closes by itself when you walk away.</p>
 *
 * <p><b>Two different containers, one layout.</b> Which {@link Container} backs the slots changes
 * by side, and the difference matters:</p>
 * <ul>
 *   <li><b>Server</b> - {@link SteveInventoryContainer}, a live read-through view of the real
 *       {@link SteveInventory}. {@code AbstractContainerMenu#broadcastChanges} polls every slot
 *       each tick, so if the AI crafts something while the window is open you see it appear
 *       without any change-notification plumbing.</li>
 *   <li><b>Client</b> - a plain {@link SimpleContainer}. This is not an oversight:
 *       {@code initializeContents} pushes the synced stacks back through {@code Slot#set}, i.e.
 *       into the client-side container. A read-only container here would silently discard
 *       everything the server sent and the window would always look empty.</li>
 * </ul>
 *
 * <p><b>Read-only by design.</b> The AI's inventory is the state its own actions plan against -
 * {@code CraftItemAction} decides what to consume one step before consuming it - so a player
 * pulling items out mid-step could break that plan. Handing items over already has two
 * first-class paths (right-click with an item, {@code /as give}) and taking them back has
 * {@code /as take}, both of which answer in chat. The slots refuse interaction and the container
 * no-ops, so there is no path that silently mutates the bag.</p>
 */
public class SteveInventoryMenu extends AbstractContainerMenu {

    /** Usable slots per row in a Minecraft container GUI. */
    public static final int COLUMNS = 9;
    /** The AI's bag is a flat 36-slot list, so it lays out as four rows. */
    public static final int ROWS = SteveInventory.MAX_SLOTS / COLUMNS;
    /** How many of this menu's slots belong to the AI (all of them are read-only). */
    public static final int AI_SLOT_COUNT = ROWS * COLUMNS;
    /** Slot pitch, in pixels. */
    public static final int SLOT_SIZE = 18;

    // Layout constants shared with the client screen, so the drawn panel and the real slots
    // can never drift apart.
    public static final int AI_SLOT_X = 8;
    public static final int AI_SLOT_Y = 18;
    public static final int PLAYER_INV_X = 8;
    public static final int PLAYER_INV_Y = 108;
    public static final int PLAYER_HOTBAR_Y = 166;
    public static final int IMAGE_WIDTH = 176;
    /** Panel height: hotbar bottom (184) plus room for the read-only hint line. */
    public static final int IMAGE_HEIGHT = 202;

    /** How far away you may wander before the window closes (8 blocks, squared). */
    public static final double VIEW_DISTANCE_SQR = 64.0;

    /** Client-side fallback search box, when the entity id did not arrive. */
    private static final double FALLBACK_SEARCH_RADIUS = 128.0;

    /** The AI whose bag is on display, or {@code null} if the client could not resolve it. */
    @Nullable
    private final SteveEntity steve;

    private final Container container;

    public SteveInventoryMenu(int windowId, Inventory playerInventory, @Nullable SteveEntity steve) {
        super(SteveMod.STEVE_INVENTORY_MENU.get(), windowId);
        this.steve = steve;
        this.container = containerFor(steve);

        // --- the AI's 36 slots (read-only) ------------------------------------------------
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                addSlot(new ReadOnlySlot(container, column + row * COLUMNS,
                    AI_SLOT_X + column * SLOT_SIZE, AI_SLOT_Y + row * SLOT_SIZE));
            }
        }

        // --- the viewer's own inventory, laid out exactly like a chest ---------------------
        // Included so the window reads as a normal container and the player can reorganise
        // their own things while looking. Shift-click is disabled (see quickMoveStack), so
        // there is no way to move items between the two halves.
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                addSlot(new Slot(playerInventory, column + row * COLUMNS + COLUMNS,
                    PLAYER_INV_X + column * SLOT_SIZE, PLAYER_INV_Y + row * SLOT_SIZE));
            }
        }
        for (int column = 0; column < COLUMNS; column++) {
            addSlot(new Slot(playerInventory, column,
                PLAYER_INV_X + column * SLOT_SIZE, PLAYER_HOTBAR_Y));
        }
    }

    /**
     * Picks the backing container for this side.
     *
     * @see SteveInventoryMenu class docs - the client copy has to be writable.
     */
    private static Container containerFor(@Nullable SteveEntity steve) {
        if (steve != null && !steve.level().isClientSide) {
            return new SteveInventoryContainer(steve);
        }
        return new SimpleContainer(ROWS * COLUMNS);
    }

    /**
     * Factory used by the registered {@code MenuType}.
     *
     * <p>Runs on the client when Forge delivers the open-container payload. The entity id is
     * appended by {@code NetworkHooks.openScreen} on the server; if it is missing we fall back
     * to locating the AI in the level, because this mod enforces exactly one AI
     * ({@code SteveManager}) and therefore "the AI" is unambiguous - far better than opening an
     * empty window and looking broken.</p>
     */
    public static SteveInventoryMenu fromNetwork(int windowId, Inventory playerInventory,
                                                 @Nullable FriendlyByteBuf data) {
        return new SteveInventoryMenu(windowId, playerInventory, resolveSteve(playerInventory, data));
    }

    @Nullable
    private static SteveEntity resolveSteve(Inventory playerInventory, @Nullable FriendlyByteBuf data) {
        Player player = playerInventory.player;
        if (player == null) {
            return null;
        }

        if (data != null && data.isReadable()) {
            int entityId = data.readVarInt();
            Entity entity = player.level().getEntity(entityId);
            if (entity instanceof SteveEntity steve) {
                return steve;
            }
        }

        List<SteveEntity> candidates = player.level().getEntitiesOfClass(
            SteveEntity.class, player.getBoundingBox().inflate(FALLBACK_SEARCH_RADIUS));

        SteveEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (SteveEntity candidate : candidates) {
            double distance = player.distanceToSqr(candidate);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    // ------------------------------------------------------------------
    // Menu contract
    // ------------------------------------------------------------------

    /**
     * Keeps the window honest: no AI (or a dead one, or one you walked away from) closes it.
     *
     * <p>The server checks this every tick, which is what makes "walk away and the window shuts"
     * work - the same rule vanilla chests use.</p>
     */
    @Override
    public boolean stillValid(Player player) {
        return steve != null && container.stillValid(player);
    }

    /**
     * Shift-click does nothing in this window.
     *
     * <p>Vanilla routes every shift-click through here, and there is no legitimate destination
     * for one: the AI's slots refuse items, and the player's own slots have nowhere to send
     * anything either (the only other container in this menu is read-only). Empty is therefore
     * the honest answer rather than a partial implementation - a viewer window is not a way to
     * rearrange your own backpack, and pressing E still is.</p>
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /**
     * The hard guarantee that nothing can leave or enter the AI's slots.
     *
     * <p>Slot flags ({@code mayPickup} / {@code mayPlace}) express the intent, but they are only
     * consulted on the paths vanilla happens to check them on. Reviewing the 1.20.1 bytecode of
     * {@code AbstractContainerMenu#doClick} showed why relying on them alone is not enough: the
     * hotbar-swap (number key / {@code F}) branch reads {@code slot.getItem()} and can grow the
     * player's stack from it while the actual removal is supposed to happen through the
     * container - and this container's removal methods are no-ops by design. A player could then
     * receive items while the AI's bag stayed exactly as it was.</p>
     *
     * <p>So the refusal happens one level up, where every click type has to pass: any click
     * aimed at one of the AI's slots is dropped before vanilla can interpret it. Player slots
     * and "clicked outside the window" keep vanilla behaviour.</p>
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId >= 0 && slotId < AI_SLOT_COUNT) {
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    // ------------------------------------------------------------------
    // Screen support
    // ------------------------------------------------------------------

    /** The AI being displayed, or {@code null} on a client that could not resolve it. */
    @Nullable
    public SteveEntity getSteve() {
        return steve;
    }

    /** True when there is nothing to show - used by the screen for its "empty" hint. */
    public boolean isAiInventoryEmpty() {
        return container.isEmpty();
    }

    /**
     * A slot that shows an item but refuses to be interacted with.
     *
     * <p>{@code mayPickup}/{@code mayPlace} are the hard stop: vanilla checks them before
     * anything else in {@code clicked()} and {@code moveItemStackTo}, so clicks and shift-clicks
     * do nothing and the cursor never picks a ghost item up.</p>
     */
    private static final class ReadOnlySlot extends Slot {

        ReadOnlySlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }
}
