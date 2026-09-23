package world.landfall.deepspace.physics;

/**
 * Keeps vacuum checks separate from entity gravity configuration.
 */
public final class SpaceEnvironment {
    private SpaceEnvironment() {
    }

    public static boolean isVacuumDimension(String dimensionId) {
        // Generated galaxies use the same vacuum environment as the primary space dimension.
        return dimensionId.equals("deepspace:space") || dimensionId.startsWith("deepspace:galaxy_");
    }
}
