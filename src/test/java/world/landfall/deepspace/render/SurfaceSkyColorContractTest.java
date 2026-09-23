package world.landfall.deepspace.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Prevents unlit vanilla sky shaders from turning surface ring copies black. */
public final class SurfaceSkyColorContractTest {
    private SurfaceSkyColorContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/RingWorldRenderer.java"
        ));
        int skyMethod = source.indexOf("private static RenderType skyCutoutType(");
        if (skyMethod < 0) {
            throw new AssertionError("Surface sky must have a dedicated color-preserving render type");
        }
        String body = source.substring(skyMethod);
        if (!body.contains("RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER")) {
            throw new AssertionError("Surface sky copies must bypass the zero world lightmap");
        }
        String integration = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/integration/InfiniteDimensionsIntegration.java"
        ));
        if (!integration.contains("sun.getColor()") || integration.contains("SpectralClass.G.color()")) {
            throw new AssertionError("Ring-world stars must retain their generated spectral colours");
        }
    }
}
