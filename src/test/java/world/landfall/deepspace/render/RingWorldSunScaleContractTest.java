package world.landfall.deepspace.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Prevents the prebuilt Overworld surface mesh from bypassing ring-world host-star enlargement. */
public final class RingWorldSunScaleContractTest {
    private RingWorldSunScaleContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/SunRenderer.java"
        ));
        require(source.contains("surfaceStarVisualScale(star, planet, hostSun)"),
                "Prebuilt and lazy surface meshes must share one host-star scaling rule");
        require(!source.contains("createSurfaceMesh(star, planet, 1.0f)"),
                "Registry refresh must not cache an unscaled Overworld ring star");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
