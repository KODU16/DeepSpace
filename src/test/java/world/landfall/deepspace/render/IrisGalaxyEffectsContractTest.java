package world.landfall.deepspace.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks Iris-only galaxy cloud suppression and depth-tested stellar bloom. */
public final class IrisGalaxyEffectsContractTest {
    private IrisGalaxyEffectsContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String mixin = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/mixin/MixinLevelRendererClouds.java"
        ));
        require(mixin.contains("IrisIntegration.isShaderPackEnabled()")
                        && mixin.contains("PlanetRegistry.getGalaxyByDimension(level.dimension())")
                        && mixin.contains("callback.cancel()"),
                "Iris galaxy cloud rendering must be cancelled at the LevelRenderer boundary");
        String sun = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/SunRenderer.java"
        ));
        require(sun.contains(".setWriteMaskState(RenderStateShard.COLOR_WRITE)"),
                "Stellar bloom must not overwrite the resolved occlusion depth");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
