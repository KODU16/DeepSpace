package world.landfall.deepspace.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks ring-world rendering to normal camera space while preserving depth-based occlusion. */
public final class RingWorldViewContractTest {
    private RingWorldViewContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String ringRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/RingWorldRenderer.java"
        ));
        require(!ringRenderer.contains("poseStack.scale(projectionScale, projectionScale, projectionScale)"),
                "Ring-world geometry must no longer be camera-compressed");
        require(!ringRenderer.contains("spaceNearPlane("),
                "Ring-world rendering must not replace the vanilla near plane");
        require(ringRenderer.contains("return 1.0F;"),
                "Ring-world projection helper should stay neutral");

        String sunRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/SunRenderer.java"
        ));
        require(!sunRenderer.contains("rendered.projectionScale()"),
                "Ring-world stars must not be rendered through an extra scale layer");
        require(!sunRenderer.contains("RingWorldRenderer.spaceProjectionScale"),
                "Ring-world stars must stay in the normal camera transform");
        require(sunRenderer.contains("physicalOffset = mesh.sun().getCenter().subtract(camera.getPosition())")
                        && sunRenderer.contains("boundedCelestialScale(")
                        && sunRenderer.contains("physicalOffset.scale(compression).toVector3f(), compression"),
                "Galaxy stars must preserve angular size while remaining inside the far plane");

        String mixin = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/mixin/MixinGameRenderer.java"
        ));
        require(mixin.contains("return DEFAULT_NEAR_PLANE;"),
                "Ring-world projection must keep the vanilla near plane");
        require(!mixin.contains("RingWorldRenderer.spaceNearPlane"),
                "Near-plane lifting must not be reintroduced");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
