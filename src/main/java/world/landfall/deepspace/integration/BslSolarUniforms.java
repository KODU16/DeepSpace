package world.landfall.deepspace.integration;

import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.Vector3f;
import world.landfall.deepspace.render.SpaceSolarLighting;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registers three star positions and shared white-light values with every Iris material program.
 */
public final class BslSolarUniforms {
    private static final AtomicLong REGISTRATION_CALLS = new AtomicLong();
    private static final AtomicLong POSITION_EVALUATIONS = new AtomicLong();
    private static final AtomicLong ACTIVE_EVALUATIONS = new AtomicLong();
    private static final AtomicLong COUNT_EVALUATIONS = new AtomicLong();
    private static final AtomicLong INTENSITY_EVALUATIONS = new AtomicLong();
    private static final AtomicLong DISTANCE_EVALUATIONS = new AtomicLong();
    private static final Vector3f[] lastPositions = {new Vector3f(), new Vector3f(), new Vector3f()};
    private static volatile float lastActiveValue;

    private BslSolarUniforms() {
    }

    public static void register(UniformHolder uniforms) {
        REGISTRATION_CALLS.incrementAndGet();
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "deepspaceSolarPosition0", () -> evaluatePosition(0));
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "deepspaceSolarPosition1", () -> evaluatePosition(1));
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "deepspaceSolarPosition2", () -> evaluatePosition(2));
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "deepspaceSolarColor0", () -> evaluateColor(0));
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "deepspaceSolarColor1", () -> evaluateColor(1));
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "deepspaceSolarColor2", () -> evaluateColor(2));
        uniforms.uniform1f(
                UniformUpdateFrequency.PER_FRAME,
                "deepspaceSolarCount",
                BslSolarUniforms::evaluateCount
        );
        uniforms.uniform1f(
                UniformUpdateFrequency.PER_FRAME,
                "deepspaceSolarActive",
                BslSolarUniforms::evaluateActive
        );
        uniforms.uniform1f(
                UniformUpdateFrequency.ONCE,
                "deepspaceSolarIntensity",
                BslSolarUniforms::evaluateIntensity
        );
        uniforms.uniform1f(
                UniformUpdateFrequency.ONCE,
                "deepspaceSolarReferenceDistance",
                BslSolarUniforms::evaluateReferenceDistance
        );
    }

    private static Vector3f evaluatePosition(int index) {
        POSITION_EVALUATIONS.incrementAndGet();
        Vector3f position = SpaceSolarLighting.solarPositionRelativeToCamera(index);
        lastPositions[index] = new Vector3f(position);
        return position;
    }

    private static Vector3f evaluateColor(int index) {
        return SpaceSolarLighting.solarColorUniform(index);
    }

    private static float evaluateCount() {
        COUNT_EVALUATIONS.incrementAndGet();
        return SpaceSolarLighting.solarCountUniform();
    }

    private static float evaluateActive() {
        ACTIVE_EVALUATIONS.incrementAndGet();
        float active = SpaceSolarLighting.solarActiveUniform();
        lastActiveValue = active;
        return active;
    }

    private static float evaluateIntensity() {
        INTENSITY_EVALUATIONS.incrementAndGet();
        return SpaceSolarLighting.solarIntensityUniform();
    }

    private static float evaluateReferenceDistance() {
        DISTANCE_EVALUATIONS.incrementAndGet();
        return SpaceSolarLighting.solarReferenceDistanceUniform();
    }

    public static List<String> diagnosticLines() {
        Vector3f position = lastPositions[0];
        return List.of(
                "uniform.registrations=" + REGISTRATION_CALLS.get(),
                "uniform.evaluations=position:" + POSITION_EVALUATIONS.get()
                        + ",active:" + ACTIVE_EVALUATIONS.get()
                        + ",count:" + COUNT_EVALUATIONS.get()
                        + ",intensity:" + INTENSITY_EVALUATIONS.get()
                        + ",referenceDistance:" + DISTANCE_EVALUATIONS.get(),
                String.format(
                        Locale.ROOT,
                        "uniform.lastPositions=(%.2f,%.2f,%.2f);(%.2f,%.2f,%.2f);(%.2f,%.2f,%.2f) lastActive=%.1f",
                        position.x,
                        position.y,
                        position.z,
                        lastPositions[1].x,
                        lastPositions[1].y,
                        lastPositions[1].z,
                        lastPositions[2].x,
                        lastPositions[2].y,
                        lastPositions[2].z,
                        lastActiveValue
                )
        );
    }
}
