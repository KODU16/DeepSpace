package world.landfall.deepspace.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks the no-attraction and independent-sublevel transfer rules. */
public final class SubLevelTickPerformanceContractTest {
    private SubLevelTickPerformanceContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/server/SubLevelEvents.java"
        ));

        require(source.contains("AABB structureBounds = subLevelBounds(subLevel, position);")
                        && source.contains("couldContactPlanet(structureBounds, planet)")
                        && source.contains("planet.intersectsModel(structureBounds)"),
                "Galaxy contact checks must cull distant bodies before exact model tests");
        require(source.contains("if (planet.isWormhole())")
                        && !source.contains("subLevelTouchesPlanetModel"),
                "Explicit-only wormholes must not use tick-time model collision checks");
        require(!source.contains("applyPlanetGravity")
                        && !source.contains("calculateGravitationalSpeedDelta"),
                "Planets must not attract Sable sublevels");
        int exitMethodStart = source.indexOf("private static void processPlanetExits");
        int exitMethodEnd = source.indexOf("static boolean claimTransfer", exitMethodStart);
        String exitMethod = source.substring(exitMethodStart, exitMethodEnd);
        require(exitMethod.contains("Collection<SubLevel> transferSubLevels = List.of(subLevel);")
                        && !exitMethod.contains("getConnectedChain"),
                "Planet exits must transfer only the independent triggering sublevel");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
