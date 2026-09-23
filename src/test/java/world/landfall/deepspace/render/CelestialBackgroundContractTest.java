package world.landfall.deepspace.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks DeepSpace celestial layers behind terrain, block entities and every ordinary entity renderer. */
public final class CelestialBackgroundContractTest {
    private CelestialBackgroundContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String renderSystem = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/SpaceRenderSystem.java"
        ));
        require(renderSystem.contains("BACKGROUND_STAGE = VeilRenderLevelStageEvent.Stage.AFTER_SKY"),
                "Celestial rendering must finish before terrain and entities");
        require(renderSystem.contains("RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX)"),
                "Celestial depth must be discarded before terrain and entities render");
        int ringIndex = renderSystem.indexOf("RingWorldRenderer.init()");
        int sunIndex = renderSystem.indexOf("SunRenderer.init()");
        int transitionIndex = renderSystem.indexOf("registerRenderer(SkyTransitionRenderer::render");
        int depthReleaseIndex = renderSystem.indexOf("RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT");
        require(ringIndex >= 0 && ringIndex < sunIndex && sunIndex < transitionIndex
                        && transitionIndex < depthReleaseIndex,
                "Ring and star depth ordering must finish before the final background depth release");

        for (String renderer : new String[]{
                "SpaceSkyboxRenderer.java", "PlanetRenderer.java", "RingWorldRenderer.java", "RingWorldSkyRenderer.java",
                "NightSkyPlanetRenderer.java", "PlanetDecorationsRenderer.java", "SunRenderer.java"
        }) {
            String source = Files.readString(Path.of(
                    "src/main/java/world/landfall/deepspace/render/" + renderer
            ));
            require(source.contains("SpaceRenderSystem.BACKGROUND_STAGE"),
                    renderer + " must use the shared background stage");
        }
        String sunRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/SunRenderer.java"
        ));
        require(sunRenderer.contains("PoseStack celestialPose = new PoseStack();")
                        && sunRenderer.contains("celestialPose.mulPose(new Matrix4f(frustumMatrix));"),
                "Galaxy and ring-world stars must use the AFTER_SKY frustum rotation");
        require(!sunRenderer.contains("PoseStack celestialPose = matrixStack.toPoseStack();"),
                "Stars must not use the empty AFTER_SKY event stack");
        String cubeRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/shapes/Cube.java"
        ));
        String fullBrightCube = section(
                cubeRenderer,
                "private void renderTrianglesFullBright(",
                "/** Renders one exterior face with the compact unlit GUI vertex format. */"
        );
        require(fullBrightCube.contains("stack.last().pose().transformPosition(vertex)"),
                "The full-bright star mesh must apply its frustum pose to emitted vertices");
        String skyboxRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/SpaceSkyboxRenderer.java"
        ));
        String skyboxDraw = section(
                skyboxRenderer,
                "public static void render(",
                "private static boolean isAirlessPlanetSurface"
        );
        require(skyboxDraw.contains("new Matrix4f(frustumMatrix)")
                        && !skyboxDraw.contains("matrixStack.toPoseStack()"),
                "The skybox must use the frustum rotation instead of the event camera matrix");
        require(skyboxDraw.contains("RenderSystem.setShaderFogStart(1_000_000.0F)")
                        && skyboxRenderer.contains("NO_DEPTH_TEST"),
                "The space skybox must ignore world fog and leftover sky depth");
        String staticMesh = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/StaticRenderMesh.java"
        ));
        require(staticMesh.contains("VertexBuffer.Usage.STATIC")
                        && skyboxRenderer.contains("StaticRenderMesh[]")
                        && !skyboxDraw.contains("Tesselator.getInstance().begin"),
                "Skybox faces must reside in static GPU buffers instead of being rebuilt every frame");
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        require(start >= 0 && end > start, "Expected source section is missing: " + startMarker);
        return source.substring(start, end);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
