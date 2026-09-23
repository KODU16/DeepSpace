package world.landfall.deepspace.planet;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import world.landfall.deepspace.Deepspace;

/** Shared identification for the built-in and runtime-created space dimensions. */
public final class GalaxyDimensions {
    private GalaxyDimensions() {
    }

    public static boolean isGalaxy(@NotNull ResourceKey<Level> dimension) {
        return isGalaxy(dimension.location());
    }

    public static boolean isGalaxy(@NotNull ResourceLocation dimension) {
        return dimension.getNamespace().equals(Deepspace.MODID)
                && (dimension.getPath().equals("space") || dimension.getPath().startsWith("galaxy_"));
    }

    public static boolean isGalaxy(@NotNull String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id != null && isGalaxy(id);
    }
}
