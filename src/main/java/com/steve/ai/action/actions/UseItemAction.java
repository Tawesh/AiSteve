package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Generic "right click with an item" action - the workhorse that lets the AI use
 * <i>any</i> item it is holding, on <i>any</i> target, the way a real player would.
 *
 * <p><b>Why a generic action instead of one action per scenario?</b><br>
 * Hard-coding "ignite sheep to get cooked mutton" would only ever solve that one task.
 * With a generic primitives + LLM composition model, the model can chain
 * {@code use_item(flint_and_steel -> sheep)} + {@code attack(sheep)} + {@code pickup} +
 * {@code give(player)} to solve it by itself, and the very same primitive also covers
 * lighting a portal, setting a mob on fire, eating food, placing a block the player handed
 * over, and so on.</p>
 *
 * <p><b>Parameters:</b></p>
 * <ul>
 *   <li>{@code item} - item id to use, e.g. {@code flint_and_steel}. Must be in the Steve's inventory.</li>
 *   <li>{@code target} (optional) - entity name to use the item on, e.g. {@code sheep}</li>
 *   <li>{@code block} (optional) - {@code [x,y,z]} to use the item on</li>
 *   <li>{@code self} (optional) - {@code true} to use the item on the Steve itself (eating)</li>
 * </ul>
 *
 * <p>Supported behaviours are intentionally a practical, extensible subset of vanilla:</p>
 * <ul>
 *   <li><b>Flint and steel</b> on an entity - sets it alight (so animals drop cooked meat)</li>
 *   <li><b>Flint and steel</b> on a block  - places a fire block</li>
 *   <li><b>Any food</b> on self - consumes it and heals</li>
 *   <li><b>Block item</b> on a block - places the block</li>
 * </ul>
 */
public class UseItemAction extends BaseAction {

    private static final double REACH = 3.0;
    private static final double SEARCH_RADIUS = 32.0;
    private static final int MAX_TICKS = 900; // 45 seconds
    private static final int STUCK_LIMIT = 60;

    private Item item = Items.AIR;
    private String targetEntityName;
    private BlockPos targetBlock;
    private boolean useOnSelf;

    private LivingEntity entityTarget;
    private int ticksRunning;
    private int stuckTicks;
    private double lastX, lastZ;

    public UseItemAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        ticksRunning = 0;
        stuckTicks = 0;
        steve.setFlying(false);

        item = ActionUtils.parseItem(task.getStringParameter("item", ""));
        targetEntityName = task.getStringParameter("target", null);
        useOnSelf = Boolean.parseBoolean(task.getStringParameter("self", "false"));

        if (item == Items.AIR) {
            result = ActionResult.failure("I don't know which item to use");
            return;
        }

        if (!steve.getInventory().has(item)) {
            // Important: this message is also fed back to the LLM so it can ask the player
            steve.sendChatMessage("我没有 " + item + "，能给我一个吗？");
            result = ActionResult.failure("I don't have '" + item + "' in my inventory");
            return;
        }

        Object blockParam = task.getParameter("block");
        if (blockParam instanceof List<?> list && list.size() >= 3
            && list.get(0) instanceof Number nx
            && list.get(1) instanceof Number ny
            && list.get(2) instanceof Number nz) {
            targetBlock = new BlockPos(nx.intValue(), ny.intValue(), nz.intValue());
        }

        if (useOnSelf) {
            useOnSelf();
            return;
        }

        if (targetBlock != null) {
            useOnBlock();
            return;
        }

        if (targetEntityName != null) {
            findEntityTarget();
            if (entityTarget == null) {
                result = ActionResult.failure("No '" + targetEntityName + "' nearby to use " + item + " on");
            }
            return;
        }

        result = ActionResult.failure("I need a target (entity, block, or self) to use " + item + " on");
    }

    @Override
    protected void onTick() {
        if (result != null) {
            return;
        }

        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Could not get in range to use " + item);
            return;
        }

        if (targetBlock != null) {
            walkTo(targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5);
            if (withinReach(targetBlock.getX() + 0.5, targetBlock.getY() + 0.5, targetBlock.getZ() + 0.5)) {
                useOnBlock();
            }
            return;
        }

        if (entityTarget != null) {
            if (!entityTarget.isAlive() || entityTarget.isRemoved()) {
                entityTarget = null;
                findEntityTarget();
                if (entityTarget == null) {
                    result = ActionResult.success("Target is already gone");
                }
                return;
            }
            walkTo(entityTarget.getX(), entityTarget.getY(), entityTarget.getZ());
            if (steve.distanceTo(entityTarget) <= REACH) {
                useOnEntity(entityTarget);
            }
        }
    }

    // ------------------------------------------------------------------
    // Behaviour implementations
    // ------------------------------------------------------------------

    private void useOnEntity(LivingEntity target) {
        steve.getNavigation().stop();
        steve.getLookControl().setLookAt(target);
        steve.swing(InteractionHand.MAIN_HAND, true);

        if (item == Items.FLINT_AND_STEEL) {
            target.setSecondsOnFire(15);
            consumeDurability(Items.FLINT_AND_STEEL);
            SteveMod.LOGGER.info("Steve '{}' used flint and steel on {}",
                steve.getSteveName(), target.getType());
            result = ActionResult.success("Set " + targetEntityName + " on fire");
            return;
        }

        // Unknown item/target combination - report clearly so the LLM can replan
        result = ActionResult.failure("I don't know how to use " + item + " on " + target.getType());
    }

    private void useOnBlock() {
        BlockPos pos = targetBlock;
        steve.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        steve.swing(InteractionHand.MAIN_HAND, true);

        if (item == Items.FLINT_AND_STEEL) {
            BlockPos firePos = steve.level().getBlockState(pos).isAir() ? pos : pos.above();
            if (steve.level().getBlockState(firePos).isAir()) {
                steve.level().setBlock(firePos, BaseFireBlock.getState(steve.level(), firePos), 11);
                consumeDurability(Items.FLINT_AND_STEEL);
                SteveMod.LOGGER.info("Steve '{}' lit a fire at {}", steve.getSteveName(), firePos);
                result = ActionResult.success("Lit a fire at " + firePos.toShortString());
            } else {
                result = ActionResult.failure("There is no room to light a fire at "
                    + firePos.toShortString());
            }
            return;
        }

        if (item instanceof BlockItem blockItem) {
            BlockPos placePos = steve.level().getBlockState(pos).isAir() ? pos : pos.above();
            if (steve.level().getBlockState(placePos).isAir()) {
                steve.level().setBlock(placePos, blockItem.getBlock().defaultBlockState(), 3);
                steve.getInventory().removeItem(item, 1);
                SteveMod.LOGGER.info("Steve '{}' placed {} at {}",
                    steve.getSteveName(), item, placePos);
                result = ActionResult.success("Placed " + item + " at " + placePos.toShortString());
            } else {
                result = ActionResult.failure("No room to place a block at "
                    + placePos.toShortString());
            }
            return;
        }

        result = ActionResult.failure("I don't know how to use " + item + " on a block");
    }

    private void useOnSelf() {
        steve.swing(InteractionHand.MAIN_HAND, true);

        if (item.isEdible()) {
            // Take the stack, eat exactly one, and put the rest back.
            //
            // This used to remove the WHOLE stack and heal by the food's nutrition directly:
            // a stack of 8 bread vanished in one bite, and hunger was never involved at all.
            // Now the item's real nutrition feeds the AI's food bar, and health comes back the
            // way it does for a player - through the normal regeneration that a full bar gives.
            ItemStack stack = steve.getInventory().removeOneStack(item);
            if (stack.isEmpty()) {
                result = ActionResult.failure("我身上没有 " + item + " 了");
                return;
            }

            boolean ate = steve.eat(stack);
            if (!stack.isEmpty()) {
                int leftover = steve.getInventory().addItem(stack);
                if (leftover > 0) {
                    steve.spawnAtLocation(stack.copyWithCount(leftover));
                }
            }

            if (!ate) {
                result = ActionResult.failure("我不太会吃 " + item);
                return;
            }

            SteveMod.LOGGER.info("Steve '{}' ate {} (food now {}/20)",
                steve.getSteveName(), item, steve.getFoodData().getFoodLevel());
            result = ActionResult.success("吃掉了一个 " + item);
            return;
        }

        result = ActionResult.failure("我不能对自己使用 " + item);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void consumeDurability(Item tool) {
        ItemStack stack = steve.getInventory().removeOneStack(tool);
        if (stack.isEmpty()) {
            return;
        }
        stack.setDamageValue(stack.getDamageValue() + 1);
        if (stack.getDamageValue() < stack.getMaxDamage()) {
            steve.getInventory().addItem(stack);
        } else {
            SteveMod.LOGGER.info("Steve '{}' broke their {}", steve.getSteveName(), tool);
        }
    }

    private boolean withinReach(double x, double y, double z) {
        double dx = steve.getX() - x;
        double dy = steve.getY() - y;
        double dz = steve.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= (REACH + 1) * (REACH + 1);
    }

    private void walkTo(double x, double y, double z) {
        steve.getNavigation().moveTo(x, y, z, 1.2);

        double cx = steve.getX();
        double cz = steve.getZ();
        if (Math.abs(cx - lastX) < 0.05 && Math.abs(cz - lastZ) < 0.05) {
            stuckTicks++;
            if (stuckTicks > STUCK_LIMIT) {
                steve.teleportTo(x, y, z);
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
        lastX = cx;
        lastZ = cz;
    }

    private void findEntityTarget() {
        String wanted = targetEntityName.toLowerCase();
        AABB box = steve.getBoundingBox().inflate(SEARCH_RADIUS);
        List<Entity> entities = steve.level().getEntities(steve, box);

        LivingEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isRemoved()) {
                continue;
            }
            if (living instanceof Player || living instanceof SteveEntity) {
                continue;
            }
            if (!living.getType().toString().toLowerCase().contains(wanted)) {
                continue;
            }
            double d = steve.distanceTo(living);
            if (d < nearestDist) {
                nearest = living;
                nearestDist = d;
            }
        }

        entityTarget = nearest;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Use " + item + (targetEntityName != null ? " on " + targetEntityName : "");
    }
}
