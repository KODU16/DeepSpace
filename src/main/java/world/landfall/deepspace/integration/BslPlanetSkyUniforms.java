package world.landfall.deepspace.integration;

import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.Vector3f;
import world.landfall.deepspace.client.PlanetSkyColor;

/** Provides the planet atmosphere sky color to BSL sources that bypass vanilla sky uniforms. */
public final class BslPlanetSkyUniforms {
    private BslPlanetSkyUniforms() {
    }

    public static void register(UniformHolder uniforms) {
        // BSL procedural clouds bypass LevelRenderer.renderClouds entirely.
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "deepspaceCloudsDisabled",
                () -> world.landfall.deepspace.client.PlanetCloudPolicy.suppressClouds() ? 1.0F : 0.0F);
        uniforms.uniform3f(
                UniformUpdateFrequency.PER_FRAME,
                "deepspacePlanetSkyColor",
                BslPlanetSkyUniforms::evaluateColor
        );
        uniforms.uniform1f(
                UniformUpdateFrequency.PER_FRAME,
                "deepspacePlanetSkyActive",
                BslPlanetSkyUniforms::evaluateActive
        );
    }

    private static Vector3f evaluateColor() {
        var skyColor = PlanetSkyColor.current();
        return skyColor == null
                ? new Vector3f()
                : new Vector3f((float) skyColor.x, (float) skyColor.y, (float) skyColor.z);
    }

    private static float evaluateActive() {
        return PlanetSkyColor.current() == null ? 0.0F : 1.0F;
    }
}
