package world.landfall.deepspace.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks surface ring skies to a fixed celestial frame rather than player-relative screen space. */
public final class RingWorldSurfaceSkyTransformContractTest {
    private static final double EPSILON = 1.0E-6D;

    private RingWorldSurfaceSkyTransformContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        assertNear(720.0D, RingWorldRenderGeometry.surfaceSkyObserverDistance(720.0D, 0.0D),
                "ground observer distance");
        assertNear(720.0D, RingWorldRenderGeometry.surfaceSkyObserverDistance(720.0D, 320.0D),
                "high observer distance");
        assertNear(720.0D, RingWorldRenderGeometry.surfaceSkyObserverDistance(720.0D, -64.0D),
                "below-origin observer distance");
        assertNear(8.0D, RingWorldRenderGeometry.localSkySurfaceDrop(),
                "local sky surface sits below the camera");

        String renderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/RingWorldSkyRenderer.java"
        ));
        require(renderer.contains("new PoseStack()")
                        && renderer.contains("new Matrix4f(frustumMatrix)"),
                "Surface ring sky must render from the fixed frustum sky frame");
        require(!renderer.contains("PoseStack skyPoseStack = matrixStack.toPoseStack();"),
                "Surface ring sky must not reuse the event matrix stack directly");
        require(!renderer.contains("inwardSurfaceDistance + Math.max(0.0D, camera.getPosition().y)"),
                "Surface ring sky origin must not include player height");
        require(renderer.contains("surfaceSkyObserverDistance(inwardSurfaceDistance, camera.getPosition().y)"),
                "Surface ring sky must share the tested observer-distance rule");

        String sharedRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/RingWorldRenderer.java"
        ));
        String localSegment = localSkySegmentBody(sharedRenderer);
        require(localSegment.contains("renderCompleteRing(")
                        && localSegment.contains("true, true, hiddenSections"),
                "Local sky segment must use the standard fallback frame and inner-surface placement");
        require(!localSegment.contains("renderLocalSkySegmentSurface("),
                "Local sky segment must not rebuild its surface in a second coordinate system");
    }

    private static String localSkySegmentBody(String source) {
        int start = source.indexOf("private static void renderLocalSkySegment(");
        int end = source.indexOf("/** Defers fog", start);
        if (start < 0 || end < 0) {
            throw new AssertionError("Could not locate local sky segment body");
        }
        return source.substring(start, end);
    }

    private static void assertNear(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new AssertionError(label + " expected " + expected + " but got " + actual);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
