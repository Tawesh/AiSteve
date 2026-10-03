package com.steve.ai.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple item storage for a Steve, so the AI can behave like a real player:
 * receive items from the player, pick drops up off the ground, use them,
 * and hand items back.
 *
 * <p>Not a full vanilla {@code Inventory} implementation - it stores a flat list of
 * stacks and merges compatible stacks automatically. That is enough for the agent
 * use cases (hold a flint and steel, collect cooked mutton, give it back).</p>
 *
 * <p><b>Persistence:</b> saved/loaded through {@link SteveEntity#addAdditionalSaveData(CompoundTag)}.</p>
 */
public class SteveInventory {

    /** Total item slots (same order of magnitude as a player hotbar + inventory). */
    public static final int MAX_SLOTS = 36;

    private static final String NBT_KEY = "SteveInventory";

    private final SteveEntity steve;
    private final List<ItemStack> slots = new ArrayList<>();

    public SteveInventory(SteveEntity steve) {
        this.steve = steve;
    }

    /**
     * Adds a stack to the inventory, merging into existing stacks when possible.
     *
     * @param stack Stack to add (not modified; a copy is stored)
     * @return the number of items that did NOT fit (0 when everything was stored)
     */
    public int addItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }

        int remaining = stack.getCount();

        // 1) Merge into existing compatible stacks first
        for (ItemStack existing : slots) {
            if (remaining <= 0) break;
            if (ItemStack.isSameItemSameTags(existing, stack) && existing.isStackable()) {
                int space = existing.getMaxStackSize() - existing.getCount();
                if (space > 0) {
                    int move = Math.min(space, remaining);
                    existing.grow(move);
                    remaining -= move;
                }
            }
        }

        // 2) Put the rest into free slots
        while (remaining > 0 && slots.size() < MAX_SLOTS) {
            ItemStack copy = stack.copy();
            copy.setCount(Math.min(remaining, copy.getMaxStackSize()));
            slots.add(copy);
            remaining -= copy.getCount();
        }

        return remaining;
    }

    /**
     * Removes up to {@code amount} items of the given type.
     *
     * @return the number actually removed
     */
    public int removeItem(Item item, int amount) {
        int removed = 0;
        for (int i = slots.size() - 1; i >= 0 && removed < amount; i--) {
            ItemStack stack = slots.get(i);
            if (!stack.is(item)) continue;

            int take = Math.min(stack.getCount(), amount - removed);
            stack.shrink(take);
            removed += take;

            if (stack.isEmpty()) {
                slots.remove(i);
            }
        }
        return removed;
    }

    /**
     * Removes and returns the first stack of the given item, or an empty stack if absent.
     */
    public ItemStack removeOneStack(Item item) {
        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = slots.get(i);
            if (stack.is(item)) {
                slots.remove(i);
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    public int count(Item item) {
        int total = 0;
        for (ItemStack stack : slots) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public boolean has(Item item) {
        return count(item) > 0;
    }

    public boolean has(Item item, int amount) {
        return count(item) >= amount;
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    public List<ItemStack> getStacks() {
        return slots;
    }

    /**
     * Returns a comma separated summary such as {@code "flint_and_steel x1, cooked_mutton x2"}.
     */
    public String describe() {
        if (slots.isEmpty()) {
            return "empty";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) sb.append(", ");
            ItemStack stack = slots.get(i);
            sb.append(stack.getItem().toString()).append(" x").append(stack.getCount());
        }
        return sb.toString();
    }

    /** Drops every stored item on the ground (used when the Steve is removed). */
    public void dropAll() {
        if (steve.level().isClientSide) {
            return;
        }
        for (ItemStack stack : slots) {
            steve.spawnAtLocation(stack);
        }
        slots.clear();
    }

    public void saveToNBT(CompoundTag parentTag) {
        ListTag list = new ListTag();
        for (ItemStack stack : slots) {
            if (!stack.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                stack.save(entry);
                list.add(entry);
            }
        }
        parentTag.put(NBT_KEY, list);
    }

    public void loadFromNBT(CompoundTag parentTag) {
        slots.clear();
        if (!parentTag.contains(NBT_KEY)) {
            return;
        }
        ListTag list = parentTag.getList(NBT_KEY, 10); // 10 = TAG_Compound
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.of(list.getCompound(i));
            if (!stack.isEmpty()) {
                slots.add(stack);
            }
        }
    }
}
