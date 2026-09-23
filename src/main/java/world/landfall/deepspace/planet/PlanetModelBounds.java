package world.landfall.deepspace.planet;

/** Shared axis-aligned intersection math for rendered models and transition volumes. */
public final class PlanetModelBounds {
    private PlanetModelBounds() {
    }

    public static boolean intersects(
            double modelMinX,
            double modelMinY,
            double modelMinZ,
            double modelMaxX,
            double modelMaxY,
            double modelMaxZ,
            double objectMinX,
            double objectMinY,
            double objectMinZ,
            double objectMaxX,
            double objectMaxY,
            double objectMaxZ
    ) {
        // Contact at the model surface is a valid transition boundary, not a miss.
        return modelMinX <= objectMaxX && modelMaxX >= objectMinX
                && modelMinY <= objectMaxY && modelMaxY >= objectMinY
                && modelMinZ <= objectMaxZ && modelMaxZ >= objectMinZ;
    }
}
