package com.steve.ai.plugin;

import com.steve.ai.action.actions.*;
import com.steve.ai.di.ServiceContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core plugin that registers all built-in AiSteve actions.
 *
 * <p>This plugin is loaded first (priority 1000) and provides the fundamental
 * actions that Steve can perform: mining, building, combat, pathfinding, etc.</p>
 *
 * <p><b>Registered Actions:</b></p>
 * <ul>
 *   <li><b>pathfind</b>: Navigate to coordinates (x, y, z)</li>
 *   <li><b>mine</b>: Mine blocks (block type, quantity)</li>
 *   <li><b>place</b>: Place blocks at coordinates</li>
 *   <li><b>craft</b>: Craft items (item, quantity)</li>
 *   <li><b>attack</b>: Attack entities (target)</li>
 *   <li><b>follow</b>: Follow a player</li>
 *   <li><b>gather</b>: Gather resources (resource, quantity)</li>
 *   <li><b>build</b>: Build structures (structure type, blocks, dimensions)</li>
 * </ul>
 *
 * @since 1.1.0
 * @see ActionPlugin
 */
public class CoreActionsPlugin implements ActionPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(CoreActionsPlugin.class);

    private static final String PLUGIN_ID = "core-actions";
    private static final String VERSION = "1.0.0";

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void onLoad(ActionRegistry registry, ServiceContainer container) {
        LOGGER.info("Loading CoreActionsPlugin v{}", VERSION);

        // Register all core actions with high priority
        int priority = getPriority();

        // Navigation
        registry.register("pathfind",
            (steve, task, ctx) -> new PathfindAction(steve, task),
            priority, PLUGIN_ID);

        // Resource gathering
        registry.register("mine",
            (steve, task, ctx) -> new MineBlockAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("gather",
            (steve, task, ctx) -> new GatherResourceAction(steve, task),
            priority, PLUGIN_ID);

        // Building
        registry.register("place",
            (steve, task, ctx) -> new PlaceBlockAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("build",
            (steve, task, ctx) -> new BuildStructureAction(steve, task),
            priority, PLUGIN_ID);

        // Crafting
        registry.register("craft",
            (steve, task, ctx) -> new CraftItemAction(steve, task),
            priority, PLUGIN_ID);

        // Combat
        registry.register("attack",
            (steve, task, ctx) -> new CombatAction(steve, task),
            priority, PLUGIN_ID);

        // Player interaction
        registry.register("follow",
            (steve, task, ctx) -> new FollowPlayerAction(steve, task),
            priority, PLUGIN_ID);

        // Real-player-like item handling
        registry.register("pickup",
            (steve, task, ctx) -> new PickupItemsAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("give",
            (steve, task, ctx) -> new GiveItemAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("say",
            (steve, task, ctx) -> new SayAction(steve, task),
            priority, PLUGIN_ID);

        // Generic "right click with an item" - the primitive that lets the LLM compose
        // arbitrary item-based tasks (ignite, eat, place, use on block/entity/self).
        registry.register("use_item",
            (steve, task, ctx) -> new UseItemAction(steve, task),
            priority, PLUGIN_ID);

        // World interaction: opening containers and going out to look for things
        registry.register("loot_container",
            (steve, task, ctx) -> new LootContainerAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("explore",
            (steve, task, ctx) -> new ExploreAction(steve, task),
            priority, PLUGIN_ID);

        // Renewables and self-sufficiency
        registry.register("craft",
            (steve, task, ctx) -> new CraftItemAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("fish",
            (steve, task, ctx) -> new FishingAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("farm",
            (steve, task, ctx) -> new FarmAction(steve, task),
            priority, PLUGIN_ID);


        LOGGER.info("CoreActionsPlugin loaded {} actions", registry.getActionCount());
    }

    @Override
    public void onUnload() {
        LOGGER.info("CoreActionsPlugin unloading");
    }

    @Override
    public int getPriority() {
        return 1000; // Core plugin - highest priority
    }

    @Override
    public String[] getDependencies() {
        return new String[0]; // No dependencies - this is the base plugin
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public String getDescription() {
        return "Core AiSteve actions: mining, building, combat, pathfinding, and more";
    }
}
