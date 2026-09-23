package world.landfall.deepspace.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks the relay to its imported atlas, 100-block scale, and upright inward orientation. */
public final class HyperRelayModelContractTest {
    private HyperRelayModelContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String model = Files.readString(Path.of(
                "src/main/resources/assets/deepspace/geo/hyper_relay.geo.json"
        ));
        require(model.contains("\"texture_width\": 512") && model.contains("\"texture_height\": 512"),
                "The imported relay model must retain its 512x512 UV coordinate space");
        require(model.contains("\"name\": \"group\"") && model.contains("\"name\": \"group2\""),
                "The imported relay model must retain both authored bone groups");

        String relayRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/HyperRelayGeoRenderer.java"
        ));
        require(relayRenderer.contains("100.0F * 16.0F / MODEL_MAX_EXTENT_PIXELS"),
                "The GeckoLib model must have a 100-block longest extent even in old saves");
        require(relayRenderer.contains("galaxyCenter.subtract(relay.getCenter())")
                        && relayRenderer.contains("Math.atan2(inward.x, inward.z)")
                        && relayRenderer.contains("new Quaternionf().rotationY(yaw)"),
                "Relay positive Z must face the galaxy center using yaw only");
        require(relayRenderer.contains("geo/hyper_relay.geo.json")
                        && relayRenderer.contains("textures/hyper_relay.png"),
                "The relay renderer must bind the imported model and recolored atlas");
        require(relayRenderer.contains(".setCullState(RenderStateShard.NO_CULL)"),
                "Mirrored thin relay parts must remain visible when viewed head-on");

        String planetRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/PlanetRenderer.java"
        ));
        require(planetRenderer.contains("x.isRingWorldEdge() || x.isHyperRelay()"),
                "Hyper relays must not retain the old blue cube mesh");
        require(planetRenderer.contains("HyperRelayGeoRenderer.draw"),
                "Space rendering must draw hyper relays through GeckoLib");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
