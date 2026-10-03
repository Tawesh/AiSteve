package com.steve.ai.execution;

import com.steve.ai.entity.SteveEntity;

/**
 * The only sanctioned way to make the AI move.
 *
 * <p>Exists to enforce the architecture document's most important single rule:</p>
 * <blockquote>
 *   AI 决定"我要去哪里"，而不是决定"每一帧怎么走"。<br>
 *   {@code move_to(200,64,-300)} 不能变成 {@code player.setPos(200,64,-300)}，否则 AI 就会瞬移。
 * </blockquote>
 *
 * <p>Everything here goes through vanilla navigation, which in turn goes through physics.
 * There is deliberately <b>no</b> {@code teleport} method on this class. (The one teleport left
 * in the codebase is the anti-lost recovery in {@code ActionExecutor}, and it is reachable only
 * from a human command or a stuck-detector - never from a tool call.)</p>
 */
public final class MovementController {

    /** Default walking speed multiplier (vanilla player walk is 1.0). */
    public static final double WALK_SPEED = 1.0;
    /** Jogging speed used to catch up. */
    public static final double JOG_SPEED = 1.2;

    private final SteveEntity steve;

    public MovementController(SteveEntity steve) {
        this.steve = steve;
    }

    /**
     * Requests navigation to a position.
     *
     * <p>Non-blocking and non-instant: the entity will walk there over the following ticks
     * under normal physics. Callers must not assume arrival.</p>
     *
     * @return true when a path was requested
     */
    public boolean navigateTo(double x, double y, double z, double speed) {
        return steve.getNavigation().moveTo(x, y, z, speed);
    }

    public boolean navigateTo(double x, double y, double z) {
        return navigateTo(x, y, z, WALK_SPEED);
    }

    /** Requests navigation toward an entity (used by following). */
    public boolean navigateTo(net.minecraft.world.entity.Entity target, double speed) {
        return steve.getNavigation().moveTo(target, speed);
    }

    /** Cancels any in-flight path. */
    public void stop() {
        steve.getNavigation().stop();
    }

    public boolean isNavigating() {
        return steve.getNavigation().isInProgress();
    }

    /** Turns the head toward a point (no movement). */
    public void lookAt(double x, double y, double z) {
        steve.getLookControl().setLookAt(x, y, z);
    }

    /** A single jump input. */
    public void jump() {
        steve.getJumpControl().jump();
    }

    public boolean isOnGround() {
        return steve.onGround();
    }

    /** Distance from the AI to a point. */
    public double distanceTo(double x, double y, double z) {
        return Math.sqrt(steve.distanceToSqr(x, y, z));
    }
}
