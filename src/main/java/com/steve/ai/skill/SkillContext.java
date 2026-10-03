package com.steve.ai.skill;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.MemoryManager;
import com.steve.ai.protocol.Observation;

/**
 * Everything a skill is allowed to look at.
 *
 * <p>Deliberately narrow: the entity (so it can turn names into real block/item ids), the
 * latest observation (so it can be honest about what is actually nearby) and memory (so it can
 * ask "did this already fail?". Skills never get the LLM, the action queue or the goal stack -
 * they only describe <em>what to do</em>, never <em>do it</em>.</p>
 */
public final class SkillContext {

    private final SteveEntity steve;
    private final Observation observation;
    private final MemoryManager memory;

    public SkillContext(SteveEntity steve, Observation observation, MemoryManager memory) {
        this.steve = steve;
        this.observation = observation;
        this.memory = memory;
    }

    public SteveEntity steve() {
        return steve;
    }

    public Observation observation() {
        return observation;
    }

    public MemoryManager memory() {
        return memory;
    }

    /** True when the AI carries at least one of the item. */
    public boolean hasItem(String itemName) {
        if (observation == null) {
            return false;
        }
        return observation.countItem(itemName) > 0;
    }
}
