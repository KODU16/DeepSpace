package world.landfall.deepspace.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks celestial render paths to the reversed galaxy depth policy. */
public final class CelestialDepthPolicyContractTest {
    private CelestialDepthPolicyContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String ringRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/RingWorldRenderer.java"
        ));
        require(ringRenderer.contains(".setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)"),
                "Surface sky rings must use normal LEQUAL depth");
        require(count(ringRenderer, ".setDepthTestState(GalaxyLogDepth.GEQUAL_DEPTH_TEST)") >= 2,
                "Galaxy ring frames and surfaces must share reversed GEQUAL depth");

        String sunRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/SunRenderer.java"
        ));
        require(sunRenderer.contains(": SURFACE_SUN_DEPTH_TEST)"),
                "Iris surface stars must cover the opposite sky ring");
        require(sunRenderer.contains("irisSunRenderType(texture, rendered.surfaceView())"),
                "Iris galaxy stars must retain the emissive material path");

        String depthState = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/GalaxyLogDepth.java"
        ));
        require(depthState.contains("GL_GEQUAL") && depthState.contains("clearCelestialDepth"),
                "Galaxy celestial depth must retain the reversed-depth state");
        require(!depthState.contains("GL_LEQUAL"),
                "Galaxy depth must not be globally converted to forward LEQUAL depth");

        String planetRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/PlanetRenderer.java"
        ));
        require(count(planetRenderer, "GalaxyLogDepth.GREATER_DEPTH_TEST") >= 3,
                "Planet surfaces must keep the strict reversed logarithmic depth test");
        require(planetRenderer.contains("distanceToSqr(camera.getPosition())).reversed()"),
                "Planets must be submitted far-to-near as an Iris depth-write fallback");

        String sunDepthShader = Files.readString(Path.of(
                "src/main/resources/assets/deepspace/pinwheel/shaders/program/sun_log.fsh"
        ));
        require(sunDepthShader.contains("1.0 - log2(1.0 + viewDepth)"),
                "Galaxy sun shader must keep reversed logarithmic depth");

        String decorationsRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/PlanetDecorationsRenderer.java"
        ));
        require(!decorationsRenderer.contains("RING_RENDER_TYPE")
                        && !decorationsRenderer.contains("irisRingRenderType"),
                "Planet decorations must not retain a planet-ring render path");
        require(!decorationsRenderer.contains("TRANSLUCENT_TARGET")
                        && !decorationsRenderer.contains("BLOOM_SHARD")
                        && !decorationsRenderer.contains("BEACON_BEAM"),
                "Atmospheres must share the planet depth buffer instead of a later overlay target");
        require(decorationsRenderer.contains("glowCube.renderOutwardTinted")
                        && !decorationsRenderer.contains("renderAtmosphereBloomHalo"),
                "Iris atmospheres must draw the full colored shell instead of a silhouette ribbon");
        require(decorationsRenderer.contains("cameraSpaceSunPosition(sun, camera, poseStack)")
                        && decorationsRenderer.contains("setShaderSunPosition(ATMOSPHERE_SHADER, sunPosition)")
                        && decorationsRenderer.contains("setShaderSunPosition(ATMOSPHERE_BLOOM_SHADER, sunPosition)"),
                "Inner and outer atmospheres must receive the planet-space sun even under Iris");

        String atmosphereShader = Files.readString(Path.of(
                "src/main/resources/assets/deepspace/pinwheel/shaders/program/atmosphere.fsh"
        ));
        String cloudShader = Files.readString(Path.of(
                "src/main/resources/assets/deepspace/pinwheel/shaders/program/atmosphere_cloud.fsh"
        ));
        require(atmosphereShader.contains("0.045 + solarDiffuse * 0.70")
                        && !atmosphereShader.contains("vec3(0.78)")
                        && cloudShader.contains("0.045 + solarDiffuse * 0.70"),
                "Atmosphere shells must use the planet surface brightness curve");

        String nightSkyRenderer = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/render/NightSkyPlanetRenderer.java"
        ));
        require(nightSkyRenderer.contains("randomRotation(body.planetId())")
                        && nightSkyRenderer.contains("new Quaternionf(body.rotation())"),
                "Night-sky planets must use a stable non-vertical rotation");
    }

    private static int count(String source, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
