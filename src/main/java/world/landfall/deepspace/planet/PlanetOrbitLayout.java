package world.landfall.deepspace.planet;

import net.minecraft.world.phys.Vec3;

/**
 * Places square planet bounds at a fixed radius and angle around the Deep Space sun.
 */
final class PlanetOrbitLayout {
    private PlanetOrbitLayout() {
    }

    /**
     * Preserves orbital radius while distributing a planet across the horizontal ecliptic plane.
     */
    static Bounds bounds(
            double radius,
            double angleDegrees,
            double centerY,
            double halfExtent
    ) {
        double angleRadians = Math.toRadians(angleDegrees);
        double centerX = Math.cos(angleRadians) * radius;
        double centerZ = Math.sin(angleRadians) * radius;
        return new Bounds(
                new Vec3(centerX - halfExtent, centerY - halfExtent, centerZ - halfExtent),
                new Vec3(centerX + halfExtent, centerY + halfExtent, centerZ + halfExtent)
        );
    }

    record Bounds(Vec3 min, Vec3 max) {
    }
}
