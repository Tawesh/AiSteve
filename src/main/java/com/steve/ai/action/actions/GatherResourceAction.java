package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/**
 * High level "go get me X" action.
 *
 * <p>Its only real job is deciding <b>which primitive fits</b>: animal products are obtained
 * by hunting, everything else by collecting blocks. Block-name aliases are resolved in
 * {@link com.steve.ai.util.ActionUtils}, so this class no longer keeps a duplicate table
 * (the old copy drifted out of sync and was behind the
 * {@code Invalid block type: meat} failures).</p>
 *
 * <p><b>Parameters:</b> {@code resource} (e.g. {@code meat}, {@code wood}, {@code mutton}),
 * {@code quantity}.</p>
 */
public class GatherResourceAction extends BaseAction {

    /** Animal product -> the animal that provides it. */
    private static final Map<String, String> ANIMAL_PRODUCTS = new HashMap<>();

    static {
        ANIMAL_PRODUCTS.put("mutton", "sheep");
        ANIMAL_PRODUCTS.put("羊排", "sheep");
        ANIMAL_PRODUCTS.put("beef", "cow");
        ANIMAL_PRODUCTS.put("牛肉", "cow");
        ANIMAL_PRODUCTS.put("porkchop", "pig");
        ANIMAL_PRODUCTS.put("pork", "pig");
        ANIMAL_PRODUCTS.put("猪肉", "pig");
        ANIMAL_PRODUCTS.put("chicken", "chicken");
        ANIMAL_PRODUCTS.put("鸡肉", "chicken");
        ANIMAL_PRODUCTS.put("feather", "chicken");
        ANIMAL_PRODUCTS.put("leather", "cow");
        ANIMAL_PRODUCTS.put("wool", "sheep");
    }

    /**
     * Vague words meaning "some food / some meat".
     *
     * <p>Without these, a perfectly sensible plan like {@code gather(resource="meat")} fell
     * through to the mining branch and failed with "Invalid block type: meat", after which
     * the AI gave up and merely asked the player for food.</p>
     */
    private static final Map<String, String> GENERIC_FOOD = new HashMap<>();

    static {
        GENERIC_FOOD.put("meat", "sheep");
        GENERIC_FOOD.put("肉", "sheep");
        GENERIC_FOOD.put("food", "cow");
        GENERIC_FOOD.put("食物", "cow");
        GENERIC_FOOD.put("吃的", "cow");
        GENERIC_FOOD.put("animal", "cow");
        GENERIC_FOOD.put("动物", "cow");
        GENERIC_FOOD.put("livestock", "cow");
        GENERIC_FOOD.put("protein", "cow");
    }

    private String resourceType;
    private int quantity;
    private BaseAction delegate;

    public GatherResourceAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        resourceType = task.getStringParameter("resource", "wood");
        quantity = Math.max(1, task.getIntParameter("quantity", 1));

        String key = resourceType == null ? "" : resourceType.toLowerCase().trim();

        String animal = resolveAnimal(key);
        if (animal != null) {
            // Hunt the animal. With a flint and steel the kill drops cooked meat, which is
            // what "帮我弄点肉" usually means.
            boolean canRoast = steve.getInventory() != null
                && steve.getInventory().has(Items.FLINT_AND_STEEL);

            Map<String, Object> params = new HashMap<>();
            params.put("target", animal);
            params.put("quantity", quantity);
            params.put("ignite", canRoast);

            delegate = new CombatAction(steve, new Task("attack", params));
            delegate.start();
            return;
        }

        // Everything else is a collect request; alias resolution happens inside.
        Map<String, Object> params = new HashMap<>();
        params.put("block", key);
        params.put("quantity", quantity);

        delegate = new MineBlockAction(steve, new Task("mine", params));
        delegate.start();
    }

    /** Maps a resource word onto an animal, or null when it isn't an animal product. */
    private String resolveAnimal(String key) {
        if (key.isEmpty()) {
            return null;
        }

        String exact = ANIMAL_PRODUCTS.get(key);
        if (exact != null) {
            return exact;
        }

        // Contains-match, longest key first so "cooked_mutton" prefers "mutton".
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, String> entry : ANIMAL_PRODUCTS.entrySet()) {
            if (key.contains(entry.getKey()) && entry.getKey().length() > bestLength) {
                best = entry.getValue();
                bestLength = entry.getKey().length();
            }
        }
        if (best != null) {
            return best;
        }

        String generic = GENERIC_FOOD.get(key);
        if (generic != null) {
            return generic;
        }
        for (Map.Entry<String, String> entry : GENERIC_FOOD.entrySet()) {
            if (key.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    protected void onTick() {
        if (delegate == null) {
            if (result == null) {
                result = ActionResult.failure("No strategy for gathering: " + resourceType);
            }
            return;
        }
        delegate.tick();
        if (delegate.isComplete()) {
            result = delegate.getResult();
        }
    }

    @Override
    protected void onCancel() {
        if (delegate != null) {
            delegate.cancel();
        }
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Gather " + quantity + " " + resourceType;
    }
}
