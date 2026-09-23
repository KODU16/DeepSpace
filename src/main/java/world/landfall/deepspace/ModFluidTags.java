package world.landfall.deepspace;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;

public final class ModFluidTags {
    // NeoForge's common namespace lets DeepSpace and Mekanism share oxygen without a hard dependency.
    public static final TagKey<Fluid> OXYGEN = TagKey.create(
            Registries.FLUID,
            ResourceLocation.fromNamespaceAndPath("c", "oxygen"));

    private ModFluidTags() {
    }
}
