package world.landfall.deepspace.planet;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

/**
 * Centralizes the noon-lock rule so ring-world time does not leak into unrelated galaxies.
 */
public final class RingWorldNoonTime {
    public static final long NOON_DAY_TIME = 6000L;

    private RingWorldNoonTime() {
    }

    /** Returns true for planet surface dimensions hosted by a galaxy that contains ring-world edges. */
    public static boolean isNoonLockedSurface(@NotNull ResourceKey<Level> dimension) {
        Planet observer = PlanetRegistry.getPlanetByDimension(dimension);
        return observer != null && isRingWorldGalaxy(observer.getGalaxy());
    }

    public static boolean isRingWorldGalaxy(@NotNull ResourceKey<Level> galaxyDimension) {
        Galaxy galaxy = PlanetRegistry.getGalaxyByDimension(galaxyDimension);
        return galaxy != null && PlanetRegistry.getPlanetsForGalaxy(galaxy.dimension()).stream()
                .anyMatch(Planet::isRingWorldEdge);
    }
}
