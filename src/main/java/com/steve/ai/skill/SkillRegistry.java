package com.steve.ai.skill;

import com.steve.ai.protocol.Observation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lookup table for the installed skills.
 *
 * <p>Order matters: the first skill that claims a request wins, so more specific skills are
 * registered before general ones.</p>
 */
public final class SkillRegistry {

    private final List<Skill> skills = new ArrayList<>();

    public void register(Skill skill) {
        if (skill != null) {
            skills.add(skill);
        }
    }

    public List<Skill> all() {
        return List.copyOf(skills);
    }

    public int size() {
        return skills.size();
    }

    /** The first skill willing to handle this request. */
    public Optional<Skill> find(SkillRequest request, Observation observation) {
        for (Skill skill : skills) {
            try {
                if (skill.canHandle(request, observation)) {
                    return Optional.of(skill);
                }
            } catch (Exception e) {
                // A misbehaving skill must not break planning for every other skill.
                com.steve.ai.SteveMod.LOGGER.warn("Skill '{}' threw during canHandle: {}",
                    skill.name(), e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** Registry with the six built-in skills, in priority order. */
    public static SkillRegistry createDefault() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new SurvivalSkill());
        registry.register(new SocialSkill());
        registry.register(new MiningSkill());
        registry.register(new CombatSkill());
        registry.register(new BuildingSkill());
        registry.register(new ExplorationSkill());
        return registry;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Skill skill : skills) {
            sb.append(skill.name()).append(' ');
        }
        return sb.toString().trim();
    }
}
