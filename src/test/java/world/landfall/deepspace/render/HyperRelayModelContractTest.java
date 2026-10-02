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
        require(model.contains("\"texture_width\": 128") && model.contains("\"texture_height\": 128"),
                "The replacement relay model must use its 128x128 texture atlas");
        require(model.contains("\"name\": \"group\"") && model.contains("\"name\": \"group2\""),
                "The imported relay model must retain both authored bone groups");

        String relayRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/HyperRelayGeoRenderer.java"
        ));
        require(relayRenderer.contains("100.0F * 16.0F / MODEL_MAX_EXTENT_PIXELS"),
                "The GeckoLib model must have a 100-block longest extent even in old saves");
        require(relayRenderer.contains("MODEL_MAX_EXTENT_PIXELS = 111.68194F")
                        && relayRenderer.contains("MODEL_CENTER_X_PIXELS = 0.76718F")
                        && relayRenderer.contains("MODEL_CENTER_Y_PIXELS = 4.40119F")
                        && relayRenderer.contains("MODEL_CENTER_Z_PIXELS = 0.0F"),
                "The relay model must use its bounds after cube and bone rotations");
        require(relayRenderer.contains("galaxyCenter.subtract(relay.getCenter())")
                        && relayRenderer.contains("Math.atan2(inward.x, inward.z)")
                        && relayRenderer.contains("new Quaternionf().rotationY(yaw)"),
                "Relay positive Z must face the galaxy center using yaw only");
        require(relayRenderer.contains("geo/hyper_relay.geo.json")
                        && relayRenderer.contains("textures/hyper_relay.png"),
                "The relay renderer must bind the imported model and recolored atlas");
        require(relayRenderer.contains(".setCullState(RenderStateShard.NO_CULL)"),
                "Mirrored thin relay parts must remain visible when viewed head-on");
        // Optional effects keep their authored scale/orientation after isolation from the model.
        String relayEffects = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/PhotonHyperRelayEffects.java"
        ));
        require(relayEffects.contains("RELAY_FX_RADIUS = 25.0F")
                        && relayEffects.contains("RELAY_FX_RADIUS / AUTHORED_FX_RADIUS")
                        && relayEffects.contains("rotationY(yaw)")
                        && relayEffects.contains("rotationX((float) (-Math.PI / 2.0D))"),
                "The relay FX must scale its authored 8-block radius to 25 blocks and align +Y with -Z");

        String planetRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/PlanetRenderer.java"
        ));
        require(planetRenderer.contains("x.isRingWorldEdge() || x.isHyperRelay()"),
                "Hyper relays must not retain the old blue cube mesh");
        require(planetRenderer.contains("HyperRelayGeoRenderer.draw"),
                "Space rendering must draw hyper relays through GeckoLib");
        require(Files.exists(Path.of("src/main/resources/assets/photon/fx/hyper_relay.fx")),
                "The Photon relay FX must be bundled in the mod resources");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
