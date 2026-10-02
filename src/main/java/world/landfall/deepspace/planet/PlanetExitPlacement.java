package world.landfall.deepspace.planet;

import world.landfall.deepspace.planet.WormholeArrivalPlacement.Bounds;
import world.landfall.deepspace.planet.WormholeArrivalPlacement.Point;

/** Clears the full spacecraft from a planetary model with room for large or asymmetric hulls. */
public final class PlanetExitPlacement {
    private PlanetExitPlacement() {}

    public static Point place(Bounds planet, Bounds relativeHull, Point desired, Point outward) {
        // Three times the longest world AABB edge remains conservative for every departure direction.
        double size = Math.max(relativeHull.maxX() - relativeHull.minX(),
                Math.max(relativeHull.maxY() - relativeHull.minY(), relativeHull.maxZ() - relativeHull.minZ()));
        double extra = 3.0D * size;
        Point outside = WormholeArrivalPlacement.pushOutsideAlongDirection(planet, relativeHull, desired, outward,
                WormholeArrivalPlacement.DEFAULT_CLEARANCE + extra);
        // Never pull an already distant mapped exit back toward the planet while adding the requested margin.
        if (Math.abs(outward.x()) >= Math.abs(outward.z())) {
            double x = outward.x() >= 0.0D ? Math.max(desired.x() + extra, outside.x())
                    : Math.min(desired.x() - extra, outside.x());
            return new Point(x, desired.y(), desired.z());
        }
        double z = outward.z() >= 0.0D ? Math.max(desired.z() + extra, outside.z())
                : Math.min(desired.z() - extra, outside.z());
        return new Point(desired.x(), desired.y(), z);
    }
}
