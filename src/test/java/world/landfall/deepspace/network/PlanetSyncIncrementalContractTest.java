package world.landfall.deepspace.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks the single-planet synchronization path used by generated texture completion. */
public final class PlanetSyncIncrementalContractTest {
    private PlanetSyncIncrementalContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String packet = read("src/main/java/world/landfall/deepspace/network/PlanetSyncPacket.java");
        require(packet.contains("changedPlanetId")
                        && packet.contains("createPlanetUpdatePacket")
                        && packet.contains("PlanetRenderer.refreshPlanet(packet.changedPlanetId)"),
                "incremental planet packets must refresh only the changed planet");
        require(packet.contains("if (packet.changedPlanetId == null)"),
                "full mesh refresh must remain limited to full synchronization packets");
    }

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(file));
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
