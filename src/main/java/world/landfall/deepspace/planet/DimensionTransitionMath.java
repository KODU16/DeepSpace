package world.landfall.deepspace.planet;

public final class DimensionTransitionMath {
    private static final double PLANET_EXIT_FADE_FRACTION = 0.3;
    private static final double SPACE_APPROACH_FADE_FRACTION = 0.1;

    private DimensionTransitionMath() {
    }

    /**
     * Starts fading when the player is 30% of the boundary height below the transfer plane.
     */
    public static double planetExitProgress(double playerY, double boundaryY) {
        if (boundaryY <= 0.0) {
            return playerY >= boundaryY ? 1.0 : 0.0;
        }
        double fadeDistance = boundaryY * PLANET_EXIT_FADE_FRACTION;
        return clamp01((playerY - (boundaryY - fadeDistance)) / fadeDistance);
    }

    /**
     * Starts fading at 10% of the target planet's diameter outside its bounds.
     */
    public static double spaceApproachProgress(double distanceToBounds, double planetDiameter) {
        if (planetDiameter <= 0.0) {
            return distanceToBounds <= 0.0 ? 1.0 : 0.0;
        }
        double fadeDistance = planetDiameter * SPACE_APPROACH_FADE_FRACTION;
        return clamp01(1.0 - Math.max(0.0, distanceToBounds) / fadeDistance);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
