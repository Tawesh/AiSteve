package com.steve.ai.menu;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveInventory;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Read-only {@link Container} view onto an AI companion's {@link SteveInventory}.
 *
 * <p>Exists so the vanilla slot machinery can display the AI's backpack without the mod
 * having to hand-roll its own list widget: vanilla slots take a {@code Container}, so wrapping
 * the inventory is what makes the standard GUI, tooltips and item rendering work for free.</p>
 *
 * <p><b>Deliberately read-only.</b> Every mutating method is a no-op rather than an
 * unimplemented hole:</p>
 * <ul>
 *   <li>the AI's inventory is the state its own actions reason about
 *       ({@code CraftItemAction} plans consumption a step ahead of executing it), so letting a
 *       player pull items out mid-step would desync that plan;</li>
 *   <li>moving items <em>into</em> the bag is already a first-class interaction
 *       (right-click with an item in hand, {@code /as give}), and it reports back in chat;</li>
 *   <li>taking items back has its own command ({@code /as take}) with a clear yes/no answer.</li>
 * </ul>
 * The screen's slots enforce the same thing via {@code mayPickup}/{@code mayPlace} = false, so
 * the no-ops here are the second line of defence, not the first.</p>
 *
 * <p><b>Why it reads live instead of copying.</b> {@code AbstractContainerMenu#broadcastChanges}
 * polls every slot each server tick, so a container that reads straight through to the real
 * inventory gets live updates (the AI crafting something while you watch) with no
 * change-notification plumbing at all.</p>
 */
public final class SteveInventoryContainer implements Container {

    private final SteveEntity steve;

    public SteveInventoryContainer(SteveEntity steve) {
        this.steve = steve;
    }

    /** Live view of the backing inventory, or an empty list when the entity lost its bag. */
    private List<ItemStack> stacks() {
        SteveInventory inventory = steve.getInventory();
        return inventory == null ? List.of() : inventory.getStacks();
    }

    /**
     * Slot count for the GUI.
     *
     * <p>Fixed at the inventory's capacity rather than its current content: the slot layout must
     * be identical on both sides, and the client never sees the real list anyway - it receives
     * the synced copy.</p>
     */
    @Override
    public int getContainerSize() {
        return SteveInventory.MAX_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        return stacks().isEmpty();
    }

    /**
     * The stack in a slot - returned as a <b>copy</b>.
     *
     * <p>Deliberately a copy rather than the live stack, and this is a correctness fix, not
     * tidiness. Vanilla has click paths that read {@code slot.getItem()} and then grow the
     * <em>player's</em> stack out of it (the hotbar-swap / number-key merge is the clearest
     * example), with the removal happening separately through the container. A live reference
     * therefore hands out items that were never taken out of the bag - the bag stays unchanged
     * while the player gains items.</p>
     *
     * <p>There is no cost to copying: {@code AbstractContainerMenu#broadcastChanges} copies the
     * result before comparing and sending it, so this is the same allocation it already makes.</p>
     */
    @Override
    public ItemStack getItem(int slot) {
        List<ItemStack> stacks = stacks();
        if (slot < 0 || slot >= stacks.size()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = stacks.get(slot);
        return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    // ------------------------------------------------------------------
    // Read-only: everything that would mutate the bag is a deliberate no-op
    // ------------------------------------------------------------------

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        // No-op. On the client the menu uses a SimpleContainer instead (the synced copy has to
        // be writable), so this is only ever reached on the server - where the slots refuse
        // interaction anyway.
    }

    @Override
    public void setChanged() {
        // Nothing to persist: the real inventory saves itself with the entity.
    }

    @Override
    public void clearContent() {
        // Read-only view: never wipes the AI's belongings.
    }

    /**
     * Kept honest about the entity: an AI that died or that you walked away from closes the
     * window, exactly like a chest would.
     */
    @Override
    public boolean stillValid(Player player) {
        return steve.isAlive()
            && player.level() == steve.level()
            && player.distanceToSqr(steve) <= SteveInventoryMenu.VIEW_DISTANCE_SQR;
    }
}
