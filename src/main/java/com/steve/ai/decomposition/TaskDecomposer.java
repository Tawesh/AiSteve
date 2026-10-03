package com.steve.ai.decomposition;

import com.steve.ai.action.Task;
import com.steve.ai.context.WorldContext;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.llm.PromptBuilder;
import com.steve.ai.llm.ResponseParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decomposes high-level tasks into executable actions with precondition validation.
 */
public class TaskDecomposer {

    /**
     * Validates and potentially decomposes tasks based on world context.
     * Returns original tasks if all preconditions met, or modified task list with
     * procurement steps if materials are missing.
     */
    public static DecompositionResult decompose(List<Task> originalTasks, WorldContext worldContext,
                                                 SteveEntity steve) {
        return decomposeRecursive(originalTasks, worldContext, steve, 0);
    }

    private static DecompositionResult decomposeRecursive(List<Task> tasks, WorldContext worldContext,
                                                          SteveEntity steve, int depth) {
        // Prevent infinite recursion
        if (depth > 5) {
            com.steve.ai.SteveMod.LOGGER.error("[TaskDecomposer] Dependency chain too deep at depth {}", depth);
            return new DecompositionResult(tasks, List.of("Dependency chain too deep"), false);
        }

        com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer] Depth {}: Validating {} tasks", depth, tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer]   Task {}: {} ", i + 1, task.getAction(), task.getParameters());
        }

        List<String> missingRequirements = PreconditionValidator.validate(tasks, worldContext, steve);

        if (missingRequirements.isEmpty()) {
            com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer] Depth {}: All preconditions met, {} tasks ready", depth, tasks.size());
            return new DecompositionResult(tasks, List.of(), true);
        }

        com.steve.ai.SteveMod.LOGGER.warn("[TaskDecomposer] Depth {}: Missing requirements: {}", depth, missingRequirements);

        // Generate procurement tasks for missing materials
        List<Task> procurementTasks = generateProcurementTasks(missingRequirements, worldContext);

        if (procurementTasks.isEmpty()) {
            com.steve.ai.SteveMod.LOGGER.error("[TaskDecomposer] Depth {}: Could not generate procurement tasks for: {}", depth, missingRequirements);
            return new DecompositionResult(tasks, missingRequirements, false);
        }

        com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer] Depth {}: Generated {} procurement tasks", depth, procurementTasks.size());
        for (int i = 0; i < procurementTasks.size(); i++) {
            Task task = procurementTasks.get(i);
            com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer]   Procurement {}: {} {}", i + 1, task.getAction(), task.getParameters());
        }

        // Recursively decompose procurement tasks
        DecompositionResult procurementResult = decomposeRecursive(
            procurementTasks, worldContext, steve, depth + 1);

        if (!procurementResult.canProceed()) {
            com.steve.ai.SteveMod.LOGGER.error("[TaskDecomposer] Depth {}: Procurement decomposition failed", depth);
            return procurementResult;
        }

        // Combine decomposed procurement tasks with original tasks
        List<Task> decomposedTasks = new ArrayList<>();
        decomposedTasks.addAll(procurementResult.getTasks());
        decomposedTasks.addAll(tasks);

        com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer] Depth {}: Final decomposed task count: {}", depth, decomposedTasks.size());

        return new DecompositionResult(decomposedTasks, missingRequirements, true);
    }

    private static List<Task> generateProcurementTasks(List<String> missingRequirements,
                                                        WorldContext worldContext) {
        List<Task> procurementTasks = new ArrayList<>();

        com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer] Generating procurement tasks for {} requirements", missingRequirements.size());

        for (String requirement : missingRequirements) {
            com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer] Processing requirement: '{}'", requirement);

            // Parse requirement and generate appropriate procurement task
            if (requirement.contains("fishing rod")) {
                // Need to craft a fishing rod
                Map<String, Object> params = new HashMap<>();
                params.put("item", "fishing_rod");
                params.put("quantity", 1);
                procurementTasks.add(new Task("craft", params));
            } else if (requirement.contains("crafting table")) {
                // Need crafting table
                Map<String, Object> params = new HashMap<>();
                params.put("item", "crafting_table");
                params.put("quantity", 1);
                procurementTasks.add(new Task("craft", params));
            } else if (requirement.startsWith("Missing block in inventory:")) {
                // Extract block name and add gather task
                String blockName = requirement.substring("Missing block in inventory:".length()).trim();
                Map<String, Object> params = new HashMap<>();
                params.put("resource", blockName);
                params.put("quantity", 1);
                procurementTasks.add(new Task("gather", params));
            } else if (requirement.startsWith("Missing item in inventory:")) {
                // Extract item name - may need crafting or gathering
                String itemName = requirement.substring("Missing item in inventory:".length()).trim();
                // For simple items, try to craft
                Map<String, Object> params = new HashMap<>();
                params.put("item", itemName);
                params.put("quantity", 1);
                procurementTasks.add(new Task("craft", params));
            } else if (requirement.startsWith("Not enough")) {
                // Extract item and quantity
                // Format: "Not enough X (have Y, need Z)"
                String itemName = extractItemName(requirement);
                int neededCount = extractNeededCount(requirement);
                com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer] Extracted from 'Not enough': item='{}', neededCount={}", itemName, neededCount);

                if (itemName != null && neededCount > 0) {
                    // Determine if this should be crafted or gathered
                    boolean isCraftable = isCraftableItem(itemName);
                    com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer] Item '{}' isCraftable={}", itemName, isCraftable);

                    if (isCraftable) {
                        // Craftable items: chest, crafting_table, tools, etc.
                        Map<String, Object> params = new HashMap<>();
                        params.put("item", itemName);
                        params.put("quantity", neededCount);
                        Task craftTask = new Task("craft", params);
                        procurementTasks.add(craftTask);
                        com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer] Created CRAFT task: {} x{}", itemName, neededCount);
                    } else {
                        // Natural resources: wood, stone, ores, etc.
                        Map<String, Object> params = new HashMap<>();
                        params.put("resource", itemName);
                        params.put("quantity", neededCount);
                        Task gatherTask = new Task("gather", params);
                        procurementTasks.add(gatherTask);
                        com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer] Created GATHER task: {} x{}", itemName, neededCount);
                    }
                }
            }
        }

        com.steve.ai.SteveMod.LOGGER.info("[TaskDecomposer] Generated {} procurement tasks total", procurementTasks.size());

        return procurementTasks;
    }

    private static String extractItemName(String requirement) {
        // "Not enough oak_planks (have 2, need 8)"
        int start = requirement.indexOf("Not enough ") + "Not enough ".length();
        int end = requirement.indexOf(" (have");
        if (start > 0 && end > start) {
            return requirement.substring(start, end).trim();
        }
        return null;
    }

    private static int extractNeededCount(String requirement) {
        // Extract "need Z" from "(have Y, need Z)"
        int needIndex = requirement.indexOf("need ");
        if (needIndex > 0) {
            int endIndex = requirement.indexOf(")", needIndex);
            if (endIndex > needIndex) {
                String countStr = requirement.substring(needIndex + 5, endIndex).trim();
                try {
                    return Integer.parseInt(countStr);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /**
     * Determines if an item should be crafted rather than gathered.
     * Craftable items: tools, containers, utility blocks, etc.
     * Gatherable resources: raw materials like wood, stone, ores.
     */
    private static boolean isCraftableItem(String itemName) {
        // Common craftable items that shouldn't use 'gather'
        return itemName.contains("chest")
            || itemName.contains("crafting_table")
            || itemName.contains("furnace")
            || itemName.contains("pickaxe")
            || itemName.contains("axe")
            || itemName.contains("shovel")
            || itemName.contains("hoe")
            || itemName.contains("sword")
            || itemName.contains("fishing_rod")
            || itemName.contains("boat")
            || itemName.contains("door")
            || itemName.contains("fence")
            || itemName.contains("stairs")
            || itemName.contains("slab")
            || itemName.contains("torch")
            || itemName.contains("bed")
            || itemName.contains("stick")  // stick is crafted from planks
            || itemName.contains("plank");
    }

    /**
     * Result of task decomposition.
     */
    public static class DecompositionResult {
        private final List<Task> tasks;
        private final List<String> missingRequirements;
        private final boolean canProceed;

        public DecompositionResult(List<Task> tasks, List<String> missingRequirements, boolean canProceed) {
            this.tasks = tasks;
            this.missingRequirements = missingRequirements;
            this.canProceed = canProceed;
        }

        public List<Task> getTasks() {
            return tasks;
        }

        public List<String> getMissingRequirements() {
            return missingRequirements;
        }

        public boolean canProceed() {
            return canProceed;
        }
    }
}
