package world.landfall.deepspace.physics;

/**
 * Applies the fixed gravity rules for space and planetary dimensions.
 */
public final class EntityGravityRegistry {
    private EntityGravityRegistry() {
    }

    /** Space dimensions are weightless; every planet retains vanilla gravity. */
    public static boolean isZeroGravityDimension(String dimensionId) {
        return dimensionId.equals("deepspace:space") || dimensionId.startsWith("deepspace:galaxy_");
    }

    /** Preserves native gravity outside space and removes it inside space. */
    public static double adjustGravity(String dimensionId, double nativeGravity) {
        return isZeroGravityDimension(dimensionId) ? 0.0D : nativeGravity;
    }
}
