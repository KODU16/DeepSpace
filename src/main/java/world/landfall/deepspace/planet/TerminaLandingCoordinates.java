package world.landfall.deepspace.planet;

import net.minecraft.world.phys.Vec3;

/** Maps approaches to the vanilla End's central island or its outer island region. */
public final class TerminaLandingCoordinates {
    private static final double CENTRAL_LANDING_RADIUS = 48.0D;
    private static final double OUTER_ISLAND_START = 1200.0D;

    private TerminaLandingCoordinates() {
    }

    public static Vec3 avoidEmptyInnerRing(Vec3 position) {
        double radius = Math.hypot(position.x, position.z);
        if (radius <= CENTRAL_LANDING_RADIUS || radius >= OUTER_ISLAND_START) {
            return position;
        }
        double scale = CENTRAL_LANDING_RADIUS / radius;
        return new Vec3(position.x * scale, position.y, position.z * scale);
    }
}
