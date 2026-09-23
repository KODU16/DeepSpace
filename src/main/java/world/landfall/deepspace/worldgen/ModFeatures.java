package world.landfall.deepspace.worldgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import world.landfall.deepspace.Deepspace;

/**
 * Registers custom feature types used by Deep Space dimensions.
 */
public final class ModFeatures {
    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, Deepspace.MODID);
    public static final DeferredHolder<Feature<?>, Feature<TaggedOreConfiguration>> TAGGED_ORE =
            FEATURES.register("tagged_ore", () -> new TaggedOreFeature(TaggedOreConfiguration.CODEC));

    private ModFeatures() {
    }

    public static void register(IEventBus eventBus) {
        FEATURES.register(eventBus);
    }
}
