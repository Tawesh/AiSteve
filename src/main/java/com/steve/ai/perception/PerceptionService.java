package com.steve.ai.perception;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.protocol.Observation;

import java.util.List;

/**
 * Turns the Minecraft world into an {@link Observation} the brain can reason about.
 *
 * <p><b>Two perception speeds.</b> The architecture document asks for perception at 5-10 Hz,
 * but a full block scan at that rate would be absurd - and the model does not need it. So the
 * work is split:</p>
 *
 * <table border="1">
 *   <tr><th>Cycle</th><th>Default</th><th>Content</th></tr>
 *   <tr><td>fast</td><td>every 10 ticks (2 Hz)</td>
 *       <td>self, inventory, nearby entities - all cheap field reads</td></tr>
 *   <tr><td>slow</td><td>every 60 ticks (3 s)</td>
 *       <td>block scan (resources / containers) - cached between refreshes</td></tr>
 * </table>
 *
 * <p>This is a deliberate deviation from the document's numbers, made for the same reason the
 * document gives for keeping the LLM out of the tick loop: cost must match value.</p>
 */
public final class PerceptionService {

    /** Entity scan radius (blocks). */
    private static final int DEFAULT_ENTITY_RADIUS = 32;
    /** Horizontal block scan radius (blocks). */
    private static final int DEFAULT_BLOCK_RADIUS = 20;
    private static final int DEFAULT_DOWN = 8;
    private static final int DEFAULT_UP = 8;
    /** Ticks between block scans (60 = 3 seconds). */
    private static final int DEFAULT_BLOCK_REFRESH_TICKS = 60;

    private final int entityRadius;
    private final int blockRadius;
    private final int verticalDown;
    private final int verticalUp;
    private final int blockRefreshTicks;

    private Observation last;
    private WorldObserver.Result cachedBlocks = WorldObserver.Result.empty();
    private long lastBlockScanTick = Long.MIN_VALUE;

    public PerceptionService() {
        this(DEFAULT_ENTITY_RADIUS, DEFAULT_BLOCK_RADIUS, DEFAULT_DOWN, DEFAULT_UP,
            DEFAULT_BLOCK_REFRESH_TICKS);
    }

    public PerceptionService(int entityRadius, int blockRadius, int verticalDown, int verticalUp,
                             int blockRefreshTicks) {
        this.entityRadius = entityRadius;
        this.blockRadius = blockRadius;
        this.verticalDown = verticalDown;
        this.verticalUp = verticalUp;
        this.blockRefreshTicks = Math.max(1, blockRefreshTicks);
    }

    /**
     * Builds a fresh observation.
     *
     * <p>Must be called from the server thread: the block scan touches the level.</p>
     *
     * @param steve        the AI
     * @param recentEvents short event lines already recorded by the working memory
     * @return an immutable snapshot; also cached as {@link #last()}
     */
    public Observation observe(SteveEntity steve, List<String> recentEvents) {
        long tick = steve.level().getGameTime();

        // Slow cycle: refresh the expensive block scan only when it has gone stale.
        if (tick - lastBlockScanTick >= blockRefreshTicks || lastBlockScanTick == Long.MIN_VALUE) {
            cachedBlocks = WorldObserver.scan(steve, blockRadius, verticalDown, verticalUp);
            lastBlockScanTick = tick;
        }

        EntityObserver.Result entities = EntityObserver.observe(steve, entityRadius);

        Observation observation = Observation.builder()
            .self(SelfObserver.observe(steve))
            .inventory(InventoryObserver.observe(steve))
            .players(entities.players())
            .hostiles(entities.hostiles())
            .animals(entities.animals())
            .resources(cachedBlocks.resources())
            .containers(cachedBlocks.containers())
            .recentEvents(recentEvents)
            .capturedAtTick(tick)
            .build();

        this.last = observation;
        return observation;
    }

    /** The most recent observation, or {@code null} before the first cycle. */
    public Observation last() {
        return last;
    }

    /** Forces the next {@link #observe} call to rescan blocks. */
    public void invalidateBlocks() {
        lastBlockScanTick = Long.MIN_VALUE;
    }
}
