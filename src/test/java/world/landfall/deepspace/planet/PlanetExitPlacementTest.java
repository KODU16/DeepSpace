package world.landfall.deepspace.planet;

import world.landfall.deepspace.planet.WormholeArrivalPlacement.Bounds;
import world.landfall.deepspace.planet.WormholeArrivalPlacement.Point;

/** Regression fixtures for the long Pelagos craft, every outward face and offset mass centers. */
public final class PlanetExitPlacementTest {
    private PlanetExitPlacementTest() {}

    public static void main(String[] args) {
        Bounds planet = new Bounds(-110.0D, -100.0D, 2200.0D, 110.0D, 500.0D, 2400.0D);
        // This hull extends far behind its reference pose: half-size placement would underestimate it.
        Bounds hull = new Bounds(-12.0D, -7.0D, -210.0D, 41.0D, 29.0D, 42.0D);
        Point desired = new Point(-61.905D, 181.405D, 2435.795D);
        require(planet.intersectsOrTouches(hull.move(desired)), "The logged exit pattern must reproduce hull overlap");
        Point exit = PlanetExitPlacement.place(planet, hull, desired, new Point(0.0D, 0.0D, 1.0D));
        require(!planet.intersectsOrTouches(hull.move(exit)), "Long craft must not re-enter Pelagos on arrival");
        require(hull.move(exit).minZ() - planet.maxZ() >= 3.0D * 252.0D,
                "The nearest hull edge must retain three full hull lengths of clearance");
        require(exit.x() == desired.x() && exit.y() == desired.y(), "Mapped tangent and height must survive");
        for (Point direction : new Point[]{new Point(1, 0, 0), new Point(-1, 0, 0),
                new Point(0, 0, 1), new Point(0, 0, -1)}) {
            Point placed = PlanetExitPlacement.place(planet, hull, desired, direction);
            require(!planet.intersectsOrTouches(hull.move(placed)), "Every outward face must clear the entire hull");
            require(planet.axisGap(hull.move(placed)) >= 3.0D * 252.0D,
                    "Offset mass centers must not reduce clearance");
            require(placed.y() == desired.y(), "Exit must retain its mapped height");
        }
        Point distant = new Point(10000, 200, 0);
        Point farther = PlanetExitPlacement.place(planet, hull, distant, new Point(1, 0, 0));
        require(farther.x() >= distant.x() + 3.0D * 252.0D, "An already distant exit must move farther out");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
