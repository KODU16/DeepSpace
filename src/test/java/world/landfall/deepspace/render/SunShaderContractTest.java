package world.landfall.deepspace.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks the world-star shader to opaque output so distant rings cannot bleed through its surface. */
public final class SunShaderContractTest {
    private SunShaderContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        Path shaderPath = Path.of(
                "src/main/resources/assets/deepspace/pinwheel/shaders/program/sun.fsh"
        );
        String shader = Files.readString(shaderPath);
        String opaqueOutput = "fragColor = vec4(surface.rgb * vertexColor.rgb * ColorModulator.rgb, 1.0);";
        if (!shader.contains(opaqueOutput)) {
            throw new AssertionError("World-star fragment output must force alpha to 1.0");
        }
        if (shader.contains("fragColor = surface * vertexColor * ColorModulator")) {
            throw new AssertionError("World-star alpha must not inherit the distance multiplier");
        }
        String renderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/SunRenderer.java"
        ));
        if (renderer.contains("RenderStateShard.TRANSLUCENT_TRANSPARENCY")) {
            throw new AssertionError("Iris stars must retain their emissive shader without translucent blending");
        }
        String renderSystem = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/SpaceRenderSystem.java"
        ));
        int skyRing = renderSystem.indexOf("RingWorldSkyRenderer.init();");
        int spaceRing = renderSystem.indexOf("RingWorldRenderer.init();");
        int star = renderSystem.indexOf("SunRenderer.init();");
        if (skyRing < 0 || spaceRing < 0 || star < 0 || skyRing >= star || spaceRing >= star) {
            throw new AssertionError("Both surface and space rings must draw before the opaque star resolves depth");
        }
        int nightPlanets = renderSystem.indexOf("NightSkyPlanetRenderer.init();");
        int decorations = renderSystem.indexOf("PlanetDecorationsRenderer.init();");
        if (nightPlanets < 0 || decorations < 0 || star <= nightPlanets || star <= decorations) {
            throw new AssertionError("The star must be the final celestial renderer before the sky transition");
        }
        if (renderSystem.contains("SunRenderer.initDepthPrepass();")) {
            throw new AssertionError("A star depth prepass must not seed an incompatible ring depth coordinate system");
        }
        if (renderSystem.contains("RingWorldSkyRenderer.initDepthPass();")) {
            throw new AssertionError("Sky rings must not use a second depth-only pass");
        }
        String ringRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/RingWorldRenderer.java"
        ));
        if (ringRenderer.contains("RenderStateShard.COLOR_WRITE")
                || ringRenderer.contains("RenderStateShard.DEPTH_WRITE")) {
            throw new AssertionError("Ring sky geometry must use one combined colour/depth pass");
        }
        if (renderer.contains("RenderStateShard.NO_DEPTH_TEST")) {
            throw new AssertionError("The star must not draw over terrain or the physically nearer ring section");
        }
        if (!renderer.contains("SUN_DEPTH_BIAS_LAYER")) {
            throw new AssertionError("The star requires one tiny shared near-depth bias against opposite ring quantisation");
        }
        if (!renderer.contains("GL11.glDepthRange(0.0D, 0.9990D)")) {
            throw new AssertionError("The star depth band must leave precision for remote ring sections");
        }
        if (!ringRenderer.contains("STRICT_DEPTH_TEST")) {
            throw new AssertionError("Overlapping ring bones must reject equal-depth late fragments");
        }
        if (!ringRenderer.contains("SKY_OPPOSITE_RING_DEPTH_LAYER")
                || !ringRenderer.contains("SKY_RING_COLOR_RENDER_TYPE = ringColorRenderType(\n"
                + "            \"deepspace_ring_world_sky_color\", RING_TEXTURE, SKY_OPPOSITE_RING_DEPTH_LAYER")
                || !ringRenderer.contains("RenderStateShard.COLOR_DEPTH_WRITE,\n"
                        + "                        SKY_OPPOSITE_RING_DEPTH_LAYER")) {
            throw new AssertionError("Surface ring frame and texture must stay in the depth band behind the host star");
        }
        if (!ringRenderer.contains("SKY_OPPOSITE_RING_SURFACE_DEPTH_LAYER")
                || !ringRenderer.contains("SKY_FALLBACK_DEPTH_LAYER")) {
            throw new AssertionError("Ring-world sky surfaces need separate remote and inner-face depth layers");
        }
        if (!ringRenderer.contains("GL11.glDepthRange(0.9991D, 1.0D)")) {
            throw new AssertionError("Remote ring sections require a usable depth range");
        }
        if (!ringRenderer.contains("SKY_IRREPARABLE_RING_COLOR_RENDER_TYPE")
                || !ringRenderer.contains("SKY_REPAIRABLE_RING_COLOR_RENDER_TYPE")) {
            throw new AssertionError("Broken ring sections must share the remote-ring sky depth layer");
        }
    }
}
