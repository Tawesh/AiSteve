package com.steve.ai.entity;

import com.steve.ai.SteveMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of the (single) AI player entity.
 *
 * <p>Supports exactly <b>one</b> AI player, created with {@code /as create <name>}.</p>
 *
 * <p><b>Sync problem this class solves:</b> entities only exist in memory while their chunk
 * is loaded. A one-shot scan at login therefore misses an AI standing in unloaded chunks,
 * the registry looks empty, the player creates a second one - and the original later
 * reappears as an unresponsive "ghost". {@link #syncFromWorld} is called periodically so
 * whichever copy happens to be loaded is adopted, and any duplicates are removed.</p>
 */
public class SteveManager {

    private final Map<String, SteveEntity> activeSteves = new ConcurrentHashMap<>();
    private final Map<UUID, SteveEntity> stevesByUUID = new ConcurrentHashMap<>();

    public SteveManager() {
    }

    /**
     * Outcome of a world sync, for logging / messages.
     *
     * @param adopted   how many previously unknown AI entities were taken over
     * @param discarded how many duplicate AI entities were removed
     */
    public record SyncResult(int adopted, int discarded) {
        public boolean changed() {
            return adopted > 0 || discarded > 0;
        }
    }

    /**
     * Creates the AI player.
     *
     * @return the created Steve, or {@code null} if one already exists / creation failed
     */
    public SteveEntity spawnSteve(ServerLevel level, Vec3 position, String name) {
        if (!activeSteves.isEmpty()) {
            SteveMod.LOGGER.warn("An AI player already exists ('{}'). Use /as remove first.",
                activeSteves.keySet().iterator().next());
            return null;
        }

        if (name == null || name.isBlank()) {
            name = "Steve";
        }

        SteveEntity steve;
        try {
            steve = new SteveEntity(SteveMod.STEVE_ENTITY.get(), level);
        } catch (Throwable e) {
            SteveMod.LOGGER.error("Failed to create Steve entity", e);
            return null;
        }

        try {
            steve.setSteveName(name);
            steve.setPos(position.x, position.y, position.z);
            if (level.addFreshEntity(steve)) {
                activeSteves.put(name, steve);
                stevesByUUID.put(steve.getUUID(), steve);
                SteveMod.LOGGER.info("Created AI player '{}' ({}) at {}", name, steve.getUUID(), position);
                return steve;
            }
            SteveMod.LOGGER.error("addFreshEntity returned false - AI player was not created");
        } catch (Throwable e) {
            SteveMod.LOGGER.error("Exception during AI player creation", e);
        }

        return null;
    }

    /**
     * Re-registers a Steve that already exists in the world (loaded from the save file),
     * so it keeps working across sessions and across the login dance.
     */
    public void registerExisting(SteveEntity steve) {
        if (steve == null) {
            return;
        }
        activeSteves.put(steve.getSteveName(), steve);
        stevesByUUID.put(steve.getUUID(), steve);
        SteveMod.LOGGER.info("Re-registered existing AI player '{}'", steve.getSteveName());
    }

    /** True when this exact entity instance is already tracked. */
    public boolean isKnown(SteveEntity steve) {
        return steve != null && stevesByUUID.containsKey(steve.getUUID());
    }

    // ------------------------------------------------------------------
    // World synchronisation
    // ------------------------------------------------------------------

    /**
     * Reconciles the registry with whatever AI entities are currently loaded.
     *
     * <p>Called periodically and before any create/remove so the player never ends up with
     * a stray, unresponsive duplicate.</p>
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>Dead / removed entities are pruned from the registry.</li>
     *   <li>If nothing is registered, the first loaded AI is adopted (even if it was
     *       spawned by an older build).</li>
     *   <li>If one is already registered and still loaded, it wins; every other loaded AI
     *       is discarded as a duplicate.</li>
     * </ul>
     *
     * @param levels all server levels to scan (every dimension)
     * @return what changed, for logging
     */
    public SyncResult syncFromWorld(Iterable<ServerLevel> levels) {
        pruneDead();

        List<SteveEntity> loaded = new ArrayList<>();
        for (ServerLevel level : levels) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof SteveEntity steve && !steve.isRemoved()) {
                    loaded.add(steve);
                }
            }
        }

        int adopted = 0;
        int discarded = 0;

        SteveEntity registered = getSingleSteve();

        // If the registered entity is still loaded, respect it; otherwise the first loaded
        // one becomes the canonical AI.
        final UUID registeredId = (registered != null) ? registered.getUUID() : null;
        boolean registeredLoaded = registeredId != null
            && loaded.stream().anyMatch(s -> s.getUUID().equals(registeredId));

        for (SteveEntity candidate : loaded) {
            if (registeredLoaded && candidate.getUUID().equals(registered.getUUID())) {
                continue;   // this is the one we already track
            }

            if (!registeredLoaded && registered == null) {
                registerExisting(candidate);
                registered = candidate;
                registeredLoaded = true;
                adopted++;
                continue;
            }

            // Anything else is either a duplicate or a newcomer while we already have one.
            if (isKnown(candidate)) {
                continue;
            }

            SteveMod.LOGGER.warn("Discarding duplicate AI player '{}' ({})",
                candidate.getSteveName(), candidate.getUUID());
            candidate.discard();
            discarded++;
        }

        if (adopted > 0 || discarded > 0) {
            SteveMod.LOGGER.info("AI sync: adopted {}, discarded {}", adopted, discarded);
        }
        return new SyncResult(adopted, discarded);
    }

    /** Removes any registry entry whose entity was discarded or died. */
    private void pruneDead() {
        Iterator<Map.Entry<String, SteveEntity>> it = activeSteves.entrySet().iterator();
        while (it.hasNext()) {
            SteveEntity steve = it.next().getValue();
            if (steve == null || steve.isRemoved() || !steve.isAlive()) {
                it.remove();
                if (steve != null) {
                    stevesByUUID.remove(steve.getUUID());
                    SteveMod.LOGGER.info("Pruned AI player '{}' (no longer alive)",
                        steve.getSteveName());
                }
            }
        }
    }

    /**
     * Hard removal of every AI entity that can be found, registered or not.
     *
     * <p>Escape hatch for cleaning up leftovers from older builds.</p>
     *
     * @return how many entities were removed from the world
     */
    public int purgeAll(Iterable<ServerLevel> levels) {
        int removed = 0;
        for (ServerLevel level : levels) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof SteveEntity steve) {
                    steve.discard();
                    removed++;
                }
            }
        }
        activeSteves.clear();
        stevesByUUID.clear();
        SteveMod.LOGGER.info("Purged {} AI entity/entities from the world", removed);
        return removed;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    /** @return the single AI player, or {@code null} when none exists */
    public SteveEntity getSingleSteve() {
        return activeSteves.isEmpty() ? null : activeSteves.values().iterator().next();
    }

    public boolean hasSteve() {
        return !activeSteves.isEmpty();
    }

    public SteveEntity getSteve(String name) {
        return activeSteves.get(name);
    }

    public SteveEntity getSteve(UUID uuid) {
        return stevesByUUID.get(uuid);
    }

    public boolean removeSteve(String name) {
        SteveEntity steve = activeSteves.remove(name);
        if (steve != null) {
            stevesByUUID.remove(steve.getUUID());
            steve.discard();
            return true;
        }
        return false;
    }

    /** Removes the single AI player. @return true if one was removed */
    public boolean removeSingleSteve() {
        SteveEntity steve = getSingleSteve();
        if (steve == null) {
            return false;
        }
        steve.discard();
        activeSteves.remove(steve.getSteveName());
        stevesByUUID.remove(steve.getUUID());
        return true;
    }

    public void clearAllSteves() {
        SteveMod.LOGGER.info("Clearing {} AI player(s)", activeSteves.size());
        for (SteveEntity steve : activeSteves.values()) {
            steve.discard();
        }
        activeSteves.clear();
        stevesByUUID.clear();
    }

    public Collection<SteveEntity> getAllSteves() {
        return Collections.unmodifiableCollection(activeSteves.values());
    }

    public List<String> getSteveNames() {
        return List.copyOf(activeSteves.keySet());
    }

    public int getActiveCount() {
        return activeSteves.size();
    }

    public void tick(ServerLevel level) {
        pruneDead();
    }
}
