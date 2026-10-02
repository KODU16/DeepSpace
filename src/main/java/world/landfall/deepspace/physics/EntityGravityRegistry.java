package world.landfall.deepspace.physics;

import world.landfall.deepspace.planet.GalaxyDimensions;

/**
 * Applies the fixed gravity rules for space and planetary dimensions.
 */
public final class EntityGravityRegistry {
    private EntityGravityRegistry() {
    }

    /** Space dimensions are weightless; every planet retains vanilla gravity. */
    public static boolean isZeroGravityDimension(String dimensionId) {
        // Share the same galaxy classification as terrain loading and Sable physics.
        return GalaxyDimensions.isGalaxy(dimensionId);
    }

    /** Preserves native gravity outside space and removes it inside space. */
    public static double adjustGravity(String dimensionId, double nativeGravity) {
        return isZeroGravityDimension(dimensionId) ? 0.0D : nativeGravity;
    }
}
