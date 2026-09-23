package world.landfall.deepspace.render;

import com.mojang.logging.LogUtils;
import foundry.veil.api.client.render.VeilRenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.integration.BslSolarUniforms;
import world.landfall.deepspace.integration.IrisIntegration;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.Galaxy;
import world.landfall.deepspace.planet.Sun;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Supplies up to three physical model stars to BSL's native material-lighting stage.
 */
public final class SpaceSolarLighting {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIAGNOSTIC_MARKER = "[DEEPSPACE-BSL-DIAG]";
    private static final ResourceLocation SPACE = Deepspace.path("space");
    private static final ResourceLocation LEGACY_SOLAR_POST = Deepspace.path("space_solar_light");
    // Keeps BSL material lighting below its bloom and warm block-light range.
    private static final float SOLAR_INTENSITY = 0.65F;
    private static final float SOLAR_REFERENCE_DISTANCE = 3_000.0F;
    private static final long TRACE_INTERVAL_NANOS = 5_000_000_000L;
    private static final AtomicLong UPDATE_CALLS = new AtomicLong();
    private static final AtomicLong REPORT_SEQUENCE = new AtomicLong();

    private static String lastEvent = "not_updated";
    private static boolean legacyPostRemoved;
    private static boolean traceEnabled;
    private static long nextTraceNanos;
    private static boolean lastUniformActive;
    @Nullable
    private static Vec3 lastCameraPosition;

    private SpaceSolarLighting() {
    }

    /**
     * Removes the obsolete screen-color pass and records the live BSL point-light state.
     */
    public static void update(@Nullable Level level, Vec3 cameraPosition) {
        UPDATE_CALLS.incrementAndGet();
        lastCameraPosition = cameraPosition;
        removeLegacySolarPost();
        lastUniformActive = isSolarUniformActive(level);
        if (!lastUniformActive) {
            lastEvent = inactiveReason(level);
        } else if (!BslSolarShaderPatcher.wasBslSourcePatched()) {
            lastEvent = "bsl_shader_source_not_detected";
        } else {
            lastEvent = "bsl_point_solar_active";
        }

        long now = System.nanoTime();
        if (traceEnabled && now >= nextTraceNanos) {
            nextTraceNanos = now + TRACE_INTERVAL_NANOS;
            LOGGER.info(
                    "{} trace updates={} event={} active={} patched={} dimension={}",
                    DIAGNOSTIC_MARKER,
                    UPDATE_CALLS.get(),
                    lastEvent,
                    lastUniformActive,
                    BslSolarShaderPatcher.wasBslSourcePatched(),
                    level == null ? "none" : level.dimension().location()
            );
        }
    }

    /**
     * Returns the sun center in BSL's camera-relative world-coordinate space.
     */
    public static Vector3f solarPositionRelativeToCamera() {
        return solarPositionRelativeToCamera(0);
    }

    /** Returns one of at most three synchronized white-light star positions. */
    public static Vector3f solarPositionRelativeToCamera(int index) {
        Level level = Minecraft.getInstance().level;
        List<Sun> suns = activeSuns(level);
        if (!isSolarUniformActive(level) || index < 0 || index >= suns.size()) {
            return new Vector3f();
        }

        Vec3 cameraPosition = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return suns.get(index).getCenter().subtract(cameraPosition).toVector3f();
    }

    /** Returns the normalized RGB color used by one synchronized star's point light. */
    public static Vector3f solarColorUniform(int index) {
        Level level = Minecraft.getInstance().level;
        List<Sun> suns = activeSuns(level);
        if (!isSolarUniformActive(level) || index < 0 || index >= suns.size()) {
            return new Vector3f();
        }
        int rgb = suns.get(index).getColor();
        return new Vector3f(
                ((rgb >>> 16) & 0xFF) / 255.0F,
                ((rgb >>> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F
        );
    }

    public static float solarCountUniform() {
        return isSolarUniformActive(Minecraft.getInstance().level)
                ? activeSuns(Minecraft.getInstance().level).size()
                : 0.0F;
    }

    public static float solarActiveUniform() {
        return isSolarUniformActive(Minecraft.getInstance().level) ? 1.0F : 0.0F;
    }

    public static float solarIntensityUniform() {
        return SOLAR_INTENSITY;
    }

    public static float solarReferenceDistanceUniform() {
        return SOLAR_REFERENCE_DISTANCE;
    }

    private static boolean isSolarUniformActive(@Nullable Level level) {
        return level != null
                && PlanetRegistry.getGalaxyByDimension(level.dimension()) != null
                && activeSun(level) != null
                && IrisIntegration.isShaderPackEnabled();
    }

    public static DebugSnapshot debugSnapshot(@Nullable Level level) {
        Sun sun = activeSun(level);
        String shaderPack = IrisIntegration.getCurrentShaderPackName().orElse("none");
        boolean patched = BslSolarShaderPatcher.wasBslSourcePatched();
        return new DebugSnapshot(
                level == null ? "no_level" : level.dimension().location().toString(),
                level != null && PlanetRegistry.getGalaxyByDimension(level.dimension()) != null,
                IrisIntegration.isIrisAvailable(),
                IrisIntegration.isShaderPackEnabled(),
                shaderPack,
                patched,
                isSolarUniformActive(level) && patched,
                formatPosition(sun == null ? null : sun.getCenter()),
                formatPosition(lastCameraPosition),
                lastEvent
        );
    }

    /**
     * Writes a stable, grep-friendly diagnostic block to the active game log.
     */
    public static long logDiagnosticReport(@Nullable Level level) {
        long reportId = REPORT_SEQUENCE.incrementAndGet();
        LOGGER.info("{} BEGIN report={}", DIAGNOSTIC_MARKER, reportId);
        diagnosticLines(level).forEach(line ->
                LOGGER.info("{} report={} {}", DIAGNOSTIC_MARKER, reportId, line)
        );
        LOGGER.info("{} END report={}", DIAGNOSTIC_MARKER, reportId);
        return reportId;
    }

    public static boolean toggleTrace() {
        traceEnabled = !traceEnabled;
        nextTraceNanos = 0L;
        LOGGER.info("{} trace_enabled={}", DIAGNOSTIC_MARKER, traceEnabled);
        return traceEnabled;
    }

    public static List<String> diagnosticLines(@Nullable Level level) {
        Minecraft minecraft = Minecraft.getInstance();
        Sun sun = activeSun(level);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 relativeSun = sun == null ? null : sun.getCenter().subtract(camera);
        double distance = relativeSun == null ? -1.0 : relativeSun.length();
        DebugSnapshot snapshot = debugSnapshot(level);
        List<String> lines = new java.util.ArrayList<>();
        lines.add("schema=1");
        lines.add("runtime.dimension=" + snapshot.dimension()
                + " space=" + snapshot.spaceLevel()
                + " iris=" + snapshot.irisAvailable()
                + " shaderpackEnabled=" + snapshot.shaderPackEnabled());
        lines.add("runtime.pack=" + snapshot.shaderPack());
        lines.add("runtime.sun=" + snapshot.sunPosition()
                + " camera=" + formatPosition(camera)
                + " relativeSun=" + formatPosition(relativeSun)
                + " distance=" + String.format(Locale.ROOT, "%.2f", distance));
        lines.add("runtime.updateCalls=" + UPDATE_CALLS.get()
                + " lastUniformActive=" + lastUniformActive
                + " trace=" + traceEnabled
                + " event=" + lastEvent);
        lines.addAll(BslSolarShaderPatcher.diagnostics().lines());
        lines.addAll(BslSolarUniforms.diagnosticLines());
        lines.addAll(IrisIntegration.framebufferDiagnosticLines());
        lines.addAll(CelestialRenderDiagnostics.diagnosticLines());
        lines.add("assessment=" + (snapshot.pointSolarActive()
                ? "BSL_POINT_SOLAR_ACTIVE"
                : "BSL_POINT_SOLAR_INACTIVE"));
        return List.copyOf(lines);
    }

    private static void removeLegacySolarPost() {
        if (legacyPostRemoved) {
            return;
        }
        try {
            VeilRenderSystem.renderer().getPostProcessingManager().remove(LEGACY_SOLAR_POST);
            legacyPostRemoved = true;
        } catch (RuntimeException | LinkageError ignored) {
            // Veil can be between resource reload states while Iris rebuilds BSL.
        }
    }

    private static String inactiveReason(@Nullable Level level) {
        if (level == null) {
            return "no_level";
        }
        if (PlanetRegistry.getGalaxyByDimension(level.dimension()) == null) {
            return "outside_space";
        }
        if (activeSun(level) == null) {
            return "sun_missing";
        }
        if (!IrisIntegration.isIrisAvailable()) {
            return "iris_api_missing";
        }
        if (!IrisIntegration.isShaderPackEnabled()) {
            return "shaderpack_off";
        }
        return "inactive";
    }

    @Nullable
    private static Sun activeSun(@Nullable Level level) {
        return level == null ? null : PlanetRegistry.getSunForGalaxy(level.dimension());
    }

    private static List<Sun> activeSuns(@Nullable Level level) {
        if (level == null) {
            return List.of();
        }
        Galaxy galaxy = PlanetRegistry.getGalaxyByDimension(level.dimension());
        return galaxy == null ? List.of() : galaxy.suns();
    }

    private static String formatPosition(@Nullable Vec3 position) {
        return position == null
                ? "none"
                : String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", position.x, position.y, position.z);
    }

    public record DebugSnapshot(
            String dimension,
            boolean spaceLevel,
            boolean irisAvailable,
            boolean shaderPackEnabled,
            String shaderPack,
            boolean bslSourcePatched,
            boolean pointSolarActive,
            String sunPosition,
            String cameraPosition,
            String event
    ) {
        public List<String> lines() {
            return List.of(
                    "Deep Space BSL point solar",
                    "dimension=" + dimension + " space=" + spaceLevel,
                    "iris=" + irisAvailable + " shaderpack=" + shaderPackEnabled,
                    "pack=" + shaderPack + " bslPatch=" + bslSourcePatched,
                    "sun=" + sunPosition + " camera=" + cameraPosition,
                    "event=" + event,
                    "assessment=" + (pointSolarActive
                            ? "BSL_POINT_SOLAR_ACTIVE"
                            : "BSL_POINT_SOLAR_INACTIVE")
            );
        }
    }
}
