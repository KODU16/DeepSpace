package world.landfall.deepspace.server;

import world.landfall.deepspace.planet.WormholeArrivalPlacement;
import world.landfall.deepspace.planet.PlanetExitPlacement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Prevents a surface-to-space transfer from placing a Sable craft back inside a long ring segment. */
public final class RingWorldExitPlacementContractTest {
    private RingWorldExitPlacementContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        var segment = new WormholeArrivalPlacement.Bounds(
                -7_827.835D, -600.0D, -6_954.409D,
                -6_067.286D, 1_000.0D, 6_940.711D
        );
        var craft = new WormholeArrivalPlacement.Bounds(-8.0D, -5.0D, -9.0D, 8.0D, 5.0D, 9.0D);
        var desired = new WormholeArrivalPlacement.Point(-6_700.0D, 103.5D, -1_093.0D);
        var exit = PlanetExitPlacement.place(
                segment,
                craft,
                desired,
                new WormholeArrivalPlacement.Point(-6_900.0D, 0.0D, -400.0D)
        );
        require(!segment.intersectsOrTouches(craft.move(exit)),
                "The complete craft must clear the elongated ring-segment model");
        require(exit.y() == desired.y() && exit.z() == desired.z(),
                "Safe placement must retain the mapped surface height and tangent coordinate");

        String transferCode = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/server/SubLevelEvents.java"
        ));
        require(transferCode.contains("calculateSafePlanetExitPosition(")
                        && transferCode.contains("private static Vec3 calculateSafePlanetExitPosition"),
                "Ring-world exits must use model bounds rather than the planet X radius");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
