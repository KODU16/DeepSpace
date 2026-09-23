package world.landfall.deepspace.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks healthy ring geometry and the two independently selectable damage models. */
public final class RingWorldModelContractTest {
    private RingWorldModelContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String model = Files.readString(Path.of(
                "src/main/resources/assets/deepspace/geo/ring_world.geo.json"
        ));
        for (String bone : new String[]{
                "Ring__World_Section1",
                "Ring__World_Section2",
                "Ring__World_Section3",
                "Ring__World_Section4",
        }) {
            require(model.contains("\"name\": \"" + bone + "\""), "Missing compact-model bone " + bone);
        }

        String healthyRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/RingWorldGeoRenderer.java"
        ));
        require(healthyRenderer.contains("isDamageVariantBone"),
                "The healthy pass must always skip the side-authored damage tree");

        String damagedRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/BrokenRingWorldGeoRenderer.java"
        ));
        require(damagedRenderer.contains("geo/ring_world_irreparable.geo.json")
                        && damagedRenderer.contains("geo/ring_world_repairable.geo.json"),
                "The damage pass must use the dedicated damage models");
        require(damagedRenderer.contains("isDamageVariantBone"),
                "The damage pass must reject every healthy section tree");
        require(damagedRenderer.contains("DAMAGE_SOURCE_X_PIXELS")
                        && damagedRenderer.contains("DAMAGE_SOURCE_Z_PIXELS"),
                "The side-authored damage tree must be recentered before placement");
        require(damagedRenderer.contains("DAMAGE_SOURCE_X_PIXELS = 0.0F")
                        && damagedRenderer.contains("DAMAGE_SOURCE_Z_PIXELS = 0.0F")
                        && damagedRenderer.contains("RingWorldDimensions.damageSectionTransform(index)"),
                "Broken replacements must use the shared complete-ring section centres");
        require(damagedRenderer.contains("textures/ring_world_irreparable.png")
                        && damagedRenderer.contains("textures/ring_world_reparable.png"),
                "Damage variants must bind their dedicated textures");

        String sharedRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/RingWorldRenderer.java"
        ));
        require(sharedRenderer.contains("repairableBrokenSections()"),
                "Every damage draw must receive the synchronized repairable-section mask");
        require(sharedRenderer.contains("SKY_FALLBACK_FRAME_DEPTH_LAYER")
                        && sharedRenderer.contains("GL11.glDepthRange(0.99998D, 0.99999D)")
                        && sharedRenderer.contains("GL11.glDepthRange(0.99999D, 1.0D)"),
                "The local frame must remain in front of its world texture while both stay behind terrain");
        require(sharedRenderer.contains("renderRemoteSkySegments")
                        && sharedRenderer.contains("renderLocalSkySegment"),
                "Remote and local sky segments must use independent projection transforms");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
