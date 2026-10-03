package com.steve.ai.perception;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.protocol.Observation;
import com.steve.ai.util.ActionUtils;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Observes the AI's inventory.
 *
 * <p>Without this the model plans steps it cannot carry out ("用打火石点燃羊" with no flint
 * and steel). Reporting the real backpack - including the explicit "空" case - is what lets
 * the planner ask the player for what is missing instead of pretending.</p>
 */
public final class InventoryObserver {

    /** Never send more than this many stacks; keeps prompt size flat. */
    private static final int MAX_STACKS = 24;

    private InventoryObserver() {
    }

    public static List<Observation.ItemView> observe(SteveEntity steve) {
        List<Observation.ItemView> views = new ArrayList<>();
        if (steve.getInventory() == null || steve.getInventory().isEmpty()) {
            return views;
        }

        int shown = 0;
        for (ItemStack stack : steve.getInventory().getStacks()) {
            if (shown >= MAX_STACKS) {
                break;
            }
            if (stack.isEmpty()) {
                continue;
            }
            views.add(new Observation.ItemView(
                ActionUtils.itemName(stack.getItem()),
                stack.getCount()));
            shown++;
        }
        return views;
    }
}
