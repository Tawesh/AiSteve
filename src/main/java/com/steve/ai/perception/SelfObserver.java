package com.steve.ai.perception;

import com.steve.ai.config.RuntimeSettings;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.protocol.Observation;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Observes the AI itself: health, hunger, position, dimension, biome, time, weather.
 *
 * <p>Cheap enough to run every perception cycle (it reads cached entity fields and one biome
 * lookup), which is why it lives outside the throttled block scan.</p>
 */
public final class SelfObserver {

    private SelfObserver() {
    }

    public static Observation.SelfState observe(SteveEntity steve) {
        Level level = steve.level();
        BlockPos pos = steve.blockPosition();

        ItemStack mainHand = steve.getMainHandItem();
        String mainHandName = mainHand.isEmpty() ? "空手" : ActionUtils.itemName(mainHand.getItem());

        // Hunger: the AI owns a real FoodData (see SteveEntity), so this is an observed value
        // rather than a "not applicable" placeholder. -1 is still used when hunger is switched
        // off in the config, which is the honest way to say "there is no hunger bar".
        final int foodLevel = RuntimeSettings.hunger()
            ? steve.getFoodData().getFoodLevel()
            : -1;

        return new Observation.SelfState(
            steve.getHealth(),
            steve.getMaxHealth(),
            foodLevel,
            steve.getX(),
            steve.getY(),
            steve.getZ(),
            dimensionName(level),
            biomeName(level, pos),
            level.getGameTime(),
            level.isDay(),
            level.isRaining(),
            steve.onGround(),
            mainHandName
        );
    }

    private static String dimensionName(Level level) {
        ResourceLocation key = level.dimension().location();
        String path = key.getPath();
        return switch (path) {
            case "overworld" -> "主世界";
            case "the_nether" -> "下界";
            case "the_end" -> "末地";
            default -> path;
        };
    }

    private static String biomeName(Level level, BlockPos pos) {
        try {
            Biome biome = level.getBiome(pos).value();
            var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            ResourceLocation key = registry.getKey(biome);
            return key != null ? key.getPath() : "unknown";
        } catch (Exception e) {
            // A biome lookup must never be the thing that breaks perception.
            return "unknown";
        }
    }
}
