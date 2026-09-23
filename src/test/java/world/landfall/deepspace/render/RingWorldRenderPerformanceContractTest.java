package world.landfall.deepspace.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks the single-pass surface ring and persistent static-frame rules. */
public final class RingWorldRenderPerformanceContractTest {
    private RingWorldRenderPerformanceContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String ringRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/RingWorldRenderer.java"
        ));
        require(ringRenderer.contains("observer != null && observer.isRingWorldEdge()")
                        && ringRenderer.contains("RingWorldSkyRenderer already draws"),
                "Surface observers must not render a duplicate physical complete ring");

        String geoRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/RingWorldGeoRenderer.java"
        ));
        require(!geoRenderer.contains("emitTessellatedQuad")
                        && geoRenderer.contains("super.createVerticesOfQuad"),
                "Static ring frames must use baked quads instead of per-frame tessellation");
        String brokenGeoRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/BrokenRingWorldGeoRenderer.java"
        ));
        String staticMesh = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/StaticRenderMesh.java"
        ));
        require(staticMesh.contains("VertexBuffer.Usage.STATIC")
                        && geoRenderer.contains("StaticRenderMesh")
                        && brokenGeoRenderer.contains("StaticRenderMesh"),
                "Healthy and broken ring models must reside in persistent static GPU buffers");
        require(ringRenderer.contains("surfaceMesh(sectionIndex, false).draw")
                        && ringRenderer.contains("surfaceMesh(sectionIndex, true).draw"),
                "Galaxy and surface-sky terrain slabs must share persistent static meshes");

        // GeckoLib may bake the cache once, but the frame loop must only draw the cached VBO.
        String healthyDraw = methodBody(geoRenderer, "static void draw(");
        String brokenDraw = methodBody(brokenGeoRenderer, "static void drawSections(");
        require(!healthyDraw.contains(".render(") && !brokenDraw.contains(".render("),
                "Per-frame ring drawing must not traverse GeoObjectRenderer.render");
        require(ringRenderer.contains("new MipmappedTexture(RING_TEXTURE)")
                        && ringRenderer.contains("TextureStateShard(texture, false, true)"),
                "World ring textures must retain mipmaps without blurring close magnification");
    }

    private static String methodBody(String source, String methodMarker) {
        int methodStart = source.indexOf(methodMarker);
        int bodyStart = source.indexOf('{', methodStart);
        require(methodStart >= 0 && bodyStart > methodStart,
                "Expected source method is missing: " + methodMarker);
        int depth = 0;
        for (int index = bodyStart; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(bodyStart, index + 1);
            }
        }
        throw new AssertionError("Unclosed source method: " + methodMarker);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
