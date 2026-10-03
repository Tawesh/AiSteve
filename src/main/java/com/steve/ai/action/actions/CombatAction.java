package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Hunts and kills nearby entities - hostile mobs <i>or</i> specific animals.
 *
 * <p>Upgraded from the original "attack hostiles" only implementation so the agent can
 * carry out real survival tasks such as "kill a sheep and bring me the meat".</p>
 *
 * <p><b>Parameters:</b></p>
 * <ul>
 *   <li>{@code target} - entity name ({@code sheep}, {@code cow}, {@code creeper}, ...)
 *       or one of {@code hostile}/{@code monster}/{@code mob}/{@code any} for hostile mobs</li>
 *   <li>{@code quantity} (optional) - how many to kill before the task is done (default 1)</li>
 *   <li>{@code collect} (optional) - when true (default), pick up the drops afterwards</li>
 * </ul>
 */
public class CombatAction extends BaseAction {
    private static final double ATTACK_RANGE = 3.2;
    private static final double SEARCH_RADIUS = 40.0;
    private static final int MAX_TICKS = 2400; // 2 minutes hard cap
    private static final int STUCK_LIMIT = 40;
    /** How long to keep actively searching before admitting defeat (ticks). */
    private static final int SEARCH_TIMEOUT_TICKS = 900; // 45 seconds
    /** Wander target is re-picked every N ticks while searching. */
    private static final int WANDER_REPICK_TICKS = 60;
    /** Radius of the wandering search around the starting point. */
    private static final double WANDER_RADIUS = 16.0;

    private String targetType;
    private int wantedKills;
    private boolean collectDrops;

    private LivingEntity target;
    private int ticksRunning;
    private int ticksStuck;
    private int kills;
    private double lastX, lastZ;
    /** Anchor used while wandering around looking for prey. */
    private double searchOriginX, searchOriginY, searchOriginZ;
    private int nextWanderTick;

    public CombatAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        targetType = task.getStringParameter("target", "hostile");
        wantedKills = Math.max(1, task.getIntParameter("quantity", 1));
        collectDrops = Boolean.parseBoolean(task.getStringParameter("collect", "true"));

        ticksRunning = 0;
        ticksStuck = 0;
        kills = 0;
        searchOriginX = steve.getX();
        searchOriginY = steve.getY();
        searchOriginZ = steve.getZ();
        nextWanderTick = WANDER_REPICK_TICKS;

        steve.setFlying(false);
        steve.setInvulnerableBuilding(true);
        findTarget();
        if (target == null) {
            SteveMod.LOGGER.info("Steve '{}' found no '{}' within {}m - will search around",
                steve.getSteveName(), targetType, (int) SEARCH_RADIUS);
        }
    }

    @Override
    protected void onTick() {
        if (result != null) {
            return;
        }

        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            cleanup();
            result = ActionResult.failure("Hunt timed out after killing " + kills + "/" + wantedKills);
            return;
        }

        // Done?
        if (kills >= wantedKills) {
            collectDropsIfWanted();
            cleanup();
            result = ActionResult.success("Killed " + kills + " " + targetType);
            return;
        }

        // (Re)acquire a target
        if (target == null || !target.isAlive() || target.isRemoved()) {
            if (target != null && (target.isRemoved() || !target.isAlive())) {
                kills++;
                SteveMod.LOGGER.info("Steve '{}' killed a {} ({}/{})",
                    steve.getSteveName(), targetType, kills, wantedKills);
                target = null;

                if (kills >= wantedKills) {
                    return; // handled next tick
                }
            }
            if (ticksRunning % 20 == 0) {
                findTarget();
            }
            if (target == null) {
                searchAround();   // wander instead of giving up immediately
                return;
            }
        }

        double distance = steve.distanceTo(target);

        steve.getNavigation().moveTo(target, 2.2);

        // Unstick
        double x = steve.getX();
        double z = steve.getZ();
        if (Math.abs(x - lastX) < 0.1 && Math.abs(z - lastZ) < 0.1) {
            ticksStuck++;
            if (ticksStuck > STUCK_LIMIT && distance > ATTACK_RANGE) {
                double dx = target.getX() - steve.getX();
                double dz = target.getZ() - steve.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 0.001) {
                    double step = Math.min(4.0, dist - ATTACK_RANGE);
                    steve.teleportTo(
                        steve.getX() + (dx / dist) * step,
                        steve.getY(),
                        steve.getZ() + (dz / dist) * step
                    );
                }
                ticksStuck = 0;
            }
        } else {
            ticksStuck = 0;
        }
        lastX = x;
        lastZ = z;

        if (distance <= ATTACK_RANGE) {
            steve.getLookControl().setLookAt(target);
            steve.doHurtTarget(target);
            steve.swing(InteractionHand.MAIN_HAND, true);
        }
    }

    /** Picks up nearby drops so the AI actually ends up holding the loot. */
    private void collectDropsIfWanted() {
        if (!collectDrops) {
            return;
        }
        AABB box = steve.getBoundingBox().inflate(8.0);
        List<Entity> drops = steve.level().getEntities(steve, box);
        for (Entity entity : drops) {
            if (entity instanceof ItemEntity itemEntity && !itemEntity.isRemoved()) {
                ItemStack stack = itemEntity.getItem().copy();
                int leftover = steve.getInventory().addItem(stack);
                if (leftover <= 0) {
                    itemEntity.discard();
                } else {
                    itemEntity.getItem().setCount(leftover);
                }
                SteveMod.LOGGER.info("Steve '{}' collected drop {}x {}",
                    steve.getSteveName(), stack.getCount() - leftover, stack.getItem());
            }
        }
    }

    /**
     * Actively looks for prey by wandering around the area instead of immediately failing.
     *
     * <p>A real player told to "go get some meat" would walk around until they found an
     * animal. The previous implementation gave up after 10 seconds, which made the AI look
     * useless whenever no animal happened to be standing next to it.</p>
     */
    private void searchAround() {
        if (ticksRunning > SEARCH_TIMEOUT_TICKS) {
            cleanup();
            result = ActionResult.failure("Searched around but found no '" + targetType + "'");
            return;
        }

        // Re-scan regularly at increasing distance
        if (ticksRunning % 20 == 0) {
            findTarget();
            if (target != null) {
                SteveMod.LOGGER.info("Steve '{}' spotted a {} while searching",
                    steve.getSteveName(), targetType);
                return;
            }
        }

        // Pick a new wander destination every couple of seconds
        if (ticksRunning >= nextWanderTick) {
            nextWanderTick = ticksRunning + WANDER_REPICK_TICKS;

            double angle = steve.getRandom().nextDouble() * Math.PI * 2.0;
            double radius = 6.0 + steve.getRandom().nextDouble() * (WANDER_RADIUS - 6.0);
            double tx = searchOriginX + Math.cos(angle) * radius;
            double tz = searchOriginZ + Math.sin(angle) * radius;

            steve.getNavigation().moveTo(tx, searchOriginY, tz, 1.0);
            SteveMod.LOGGER.debug("Steve '{}' searching for {} around {}",
                steve.getSteveName(), targetType, (int) radius);
        }
    }

    private void cleanup() {
        target = null;
    }

    @Override
    protected void onCancel() {
        cleanup();
    }

    @Override
    public String getDescription() {
        return "Hunt " + targetType + " (" + kills + "/" + wantedKills + ")";
    }

    private void findTarget() {
        AABB searchBox = steve.getBoundingBox().inflate(SEARCH_RADIUS);
        List<Entity> entities = steve.level().getEntities(steve, searchBox);

        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Entity entity : entities) {
            if (entity instanceof LivingEntity living && isValidTarget(living)) {
                double distance = steve.distanceTo(living);
                if (distance < nearestDistance) {
                    nearest = living;
                    nearestDistance = distance;
                }
            }
        }

        target = nearest;
        if (target != null) {
            SteveMod.LOGGER.info("Steve '{}' locked onto: {} at {}m",
                steve.getSteveName(), target.getType(), (int) nearestDistance);
        }
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (!entity.isAlive() || entity.isRemoved()) {
            return false;
        }
        // Never attack the player or another Steve
        if (entity instanceof SteveEntity || entity instanceof Player) {
            return false;
        }

        String targetLower = targetType.toLowerCase();

        // Hostile-mob shortcuts
        if (targetLower.contains("mob") || targetLower.contains("hostile")
            || targetLower.contains("monster") || targetLower.equals("any")) {
            return entity instanceof Monster;
        }

        // Specific entity type match (works for passive animals too: sheep, cow, pig...)
        String entityTypeName = entity.getType().toString().toLowerCase();
        return entityTypeName.contains(targetLower);
    }
}
