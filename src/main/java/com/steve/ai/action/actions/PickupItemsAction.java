package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Walks to nearby dropped items and collects them into the Steve's inventory.
 *
 * <p>This is what lets the AI behave like a real player in "give and take" loops:
 * after it kills an animal (or the player throws something on the ground) it can
 * pick the drops up and later hand them back.</p>
 *
 * <p><b>Parameters:</b></p>
 * <ul>
 *   <li>{@code item} (optional) - only collect this item id, e.g. {@code cooked_mutton}.
 *       When omitted, everything nearby is collected.</li>
 * </ul>
 */
public class PickupItemsAction extends BaseAction {

    private static final double SEARCH_RADIUS = 24.0;
    private static final double REACH = 1.8;
    private static final int MAX_TICKS = 1200; // 60 seconds
    private static final int STUCK_LIMIT = 60;

    private String itemFilter;      // may be null
    private int ticksRunning;
    private int pickedUpCount;
    private int stuckTicks;
    private double lastX, lastZ;
    private ItemEntity currentTarget;

    public PickupItemsAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        itemFilter = task.getStringParameter("item", null);
        ticksRunning = 0;
        pickedUpCount = 0;
        stuckTicks = 0;
        steve.setFlying(false);
        findNextTarget();
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.success("Picked up " + pickedUpCount + " items (timeout)");
            return;
        }

        if (currentTarget == null || currentTarget.isRemoved()) {
            findNextTarget();
            if (currentTarget == null) {
                steve.getNavigation().stop();
                result = ActionResult.success("Picked up " + pickedUpCount + " items");
                return;
            }
        }

        double distance = steve.distanceTo(currentTarget);
        if (distance <= REACH) {
            collect(currentTarget);
            currentTarget = null;
            return;
        }

        steve.getNavigation().moveTo(currentTarget, 1.2);

        // Unstick: if we barely moved for a while, nudge straight towards the item
        double x = steve.getX();
        double z = steve.getZ();
        if (Math.abs(x - lastX) < 0.05 && Math.abs(z - lastZ) < 0.05) {
            stuckTicks++;
            if (stuckTicks > STUCK_LIMIT) {
                steve.teleportTo(currentTarget.getX(), currentTarget.getY(), currentTarget.getZ());
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
        lastX = x;
        lastZ = z;
    }

    private void collect(ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem().copy();
        int leftover = steve.getInventory().addItem(stack);
        int taken = stack.getCount() - leftover;
        pickedUpCount += taken;

        if (leftover <= 0) {
            itemEntity.discard();
        } else {
            itemEntity.getItem().setCount(leftover);
        }

        SteveMod.LOGGER.info("Steve '{}' picked up {}x {} (total this action: {})",
            steve.getSteveName(), taken, stack.getItem(), pickedUpCount);
    }

    private void findNextTarget() {
        AABB box = steve.getBoundingBox().inflate(SEARCH_RADIUS);
        List<Entity> entities = steve.level().getEntities(steve, box);
        Item wanted = itemFilter != null ? com.steve.ai.util.ActionUtils.parseItem(itemFilter) : null;

        ItemEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : entities) {
            if (!(entity instanceof ItemEntity itemEntity) || itemEntity.isRemoved()) {
                continue;
            }
            if (wanted != null && !itemEntity.getItem().is(wanted)) {
                continue;
            }
            double d = steve.distanceTo(itemEntity);
            if (d < nearestDist) {
                nearest = itemEntity;
                nearestDist = d;
            }
        }

        currentTarget = nearest;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Pick up items" + (itemFilter != null ? " (" + itemFilter + ")" : "");
    }
}
