package world.landfall.deepspace.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Vector3fc;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Captures the actual Iris program and framebuffer used by celestial RenderTypes.
 */
public final class CelestialRenderDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MARKER = "[DEEPSPACE-CELESTIAL-DIAG]";
    private static final int GL_CURRENT_PROGRAM = 35725;
    private static final int GL_DRAW_FRAMEBUFFER_BINDING = 36006;
    private static final int GL_READ_FRAMEBUFFER_BINDING = 36010;
    private static final int GL_DEPTH_WRITEMASK = 2930;
    private static final int GL_DEPTH_FUNC = 2932;

    private static final AtomicLong ATMOSPHERE_DRAWS = new AtomicLong();
    private static final AtomicLong ATMOSPHERE_EMPTY_MESHES = new AtomicLong();
    private static final ConcurrentHashMap<String, String> ATMOSPHERE_COLORS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtmosphereLightingStats> ATMOSPHERE_LIGHTING = new ConcurrentHashMap<>();
    private static final AtomicLong SUN_DRAWS = new AtomicLong();
    private static final AtomicBoolean ATMOSPHERE_FIRST_DRAW_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean PLANET_FIRST_DRAW_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean SUN_FIRST_DRAW_LOGGED = new AtomicBoolean();
    private static volatile String atmosphereSetup = "not_captured";
    private static volatile String atmosphereClear = "not_captured";
    private static volatile String planetSetup = "not_captured";
    private static volatile String planetClear = "not_captured";
    private static volatile String sunSetup = "not_captured";
    private static volatile String sunClear = "not_captured";

    public static final RenderStateShard.OutputStateShard ATMOSPHERE_PROBE =
            new RenderStateShard.OutputStateShard(
                    "deepspace_atmosphere_probe",
                    () -> atmosphereSetup = captureState(),
                    () -> {
                        atmosphereClear = captureState();
                        logFirstDraw("atmosphere", ATMOSPHERE_FIRST_DRAW_LOGGED, atmosphereSetup, atmosphereClear);
                    }
            );

    public static final RenderStateShard.OutputStateShard PLANET_PROBE =
            new RenderStateShard.OutputStateShard(
                    "deepspace_planet_probe",
                    () -> planetSetup = captureState(),
                    () -> {
                        planetClear = captureState();
                        logFirstDraw("planet", PLANET_FIRST_DRAW_LOGGED, planetSetup, planetClear);
                    }
            );

    public static final RenderStateShard.OutputStateShard SUN_PROBE =
            new RenderStateShard.OutputStateShard(
                    "deepspace_sun_probe",
                    () -> sunSetup = captureState(),
                    () -> {
                        sunClear = captureState();
                        logFirstDraw("sun", SUN_FIRST_DRAW_LOGGED, sunSetup, sunClear);
                    }
            );

    private CelestialRenderDiagnostics() {
    }

    public static void recordAtmosphereMesh(boolean present) {
        if (present) {
            ATMOSPHERE_DRAWS.incrementAndGet();
        } else {
            ATMOSPHERE_EMPTY_MESHES.incrementAndGet();
        }
    }

    /**
     * Retains representative CPU vertex colors so feedback can distinguish lighting math from shader-pack output.
     */
    public static void recordAtmosphereLighting(
            String planetId,
            float lightDotNormal,
            float surfaceBrightness,
            float emissiveBrightness,
            float outwardFraction,
            int baseArgb,
            int vertexArgb,
            Vector3fc lightDirection
    ) {
        ATMOSPHERE_LIGHTING.computeIfAbsent(planetId, ignored -> new AtmosphereLightingStats())
                .record(
                        lightDotNormal,
                        surfaceBrightness,
                        emissiveBrightness,
                        outwardFraction,
                        baseArgb,
                        vertexArgb,
                        lightDirection
                );
    }

    public static void resetAtmosphereColors() {
        ATMOSPHERE_COLORS.clear();
        ATMOSPHERE_LIGHTING.clear();
    }

    /**
     * Reports whether each atmosphere uses a configured color or a surface-derived color.
     */
    public static void recordAtmosphereColor(String planetId, boolean configured, int resolvedArgb) {
        ATMOSPHERE_COLORS.put(
                planetId,
                (configured ? "manual" : "surface-average") + ":" + String.format(Locale.ROOT, "%08X", resolvedArgb)
        );
    }

    public static void recordSunDraw() {
        SUN_DRAWS.incrementAndGet();
    }

    public static List<String> diagnosticLines() {
        return List.of(
                "render.atmosphere=draws:" + ATMOSPHERE_DRAWS.get()
                        + ",emptyMeshes:" + ATMOSPHERE_EMPTY_MESHES.get(),
                "render.atmosphere.setup=" + atmosphereSetup,
                "render.atmosphere.clear=" + atmosphereClear,
                "render.atmosphere.lighting=" + atmosphereLightingSummary(),
                "render.atmosphere.colors=" + atmosphereColorSummary(),
                "render.planet.setup=" + planetSetup,
                "render.planet.clear=" + planetClear,
                "render.sun=draws:" + SUN_DRAWS.get(),
                "render.sun.setup=" + sunSetup,
                "render.sun.clear=" + sunClear
        );
    }

    public static List<String> overlayLines() {
        return List.of(
                "atmosphere draws=" + ATMOSPHERE_DRAWS.get() + " empty=" + ATMOSPHERE_EMPTY_MESHES.get(),
                "atmosphere " + atmosphereSetup,
                "atmos light " + atmosphereLightingSummary(),
                "sun draws=" + SUN_DRAWS.get(),
                "sun " + sunSetup
        );
    }

    private static String captureState() {
        ShaderInstance shader = RenderSystem.getShader();
        return "shader=" + (shader == null ? "none" : shader.getName())
                + ",program=" + GlStateManager._getInteger(GL_CURRENT_PROGRAM)
                + ",drawFbo=" + GlStateManager._getInteger(GL_DRAW_FRAMEBUFFER_BINDING)
                + ",readFbo=" + GlStateManager._getInteger(GL_READ_FRAMEBUFFER_BINDING)
                + ",depthFunc=" + GlStateManager._getInteger(GL_DEPTH_FUNC)
                + ",depthWrite=" + GlStateManager._getInteger(GL_DEPTH_WRITEMASK);
    }

    private static String atmosphereLightingSummary() {
        if (ATMOSPHERE_LIGHTING.isEmpty()) {
            return "not_captured";
        }
        return ATMOSPHERE_LIGHTING.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "={" + entry.getValue().summary() + "}")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String atmosphereColorSummary() {
        return ATMOSPHERE_COLORS.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String formatVector(Vector3fc vector) {
        return String.format(Locale.ROOT, "%.3f/%.3f/%.3f", vector.x(), vector.y(), vector.z());
    }

    private static final class AtmosphereLightingStats {
        private long samples;
        private float minDot = Float.POSITIVE_INFINITY;
        private float maxDot = Float.NEGATIVE_INFINITY;
        private float minSurfaceBrightness = Float.POSITIVE_INFINITY;
        private float maxSurfaceBrightness = Float.NEGATIVE_INFINITY;
        private float minEmissiveBrightness = Float.POSITIVE_INFINITY;
        private float maxEmissiveBrightness = Float.NEGATIVE_INFINITY;
        private int baseArgb;
        private int darkestArgb;
        private int brightestArgb;
        private String lightDirection = "not_captured";

        private void record(
                float lightDotNormal,
                float surfaceBrightness,
                float emissiveBrightness,
                float outwardFraction,
                int baseArgb,
                int vertexArgb,
                Vector3fc lightDirection
        ) {
            samples++;
            this.baseArgb = baseArgb;
            this.lightDirection = formatVector(lightDirection);
            if (outwardFraction != 0.0F) {
                return;
            }
            minDot = Math.min(minDot, lightDotNormal);
            maxDot = Math.max(maxDot, lightDotNormal);
            minSurfaceBrightness = Math.min(minSurfaceBrightness, surfaceBrightness);
            maxSurfaceBrightness = Math.max(maxSurfaceBrightness, surfaceBrightness);
            if (emissiveBrightness < minEmissiveBrightness) {
                minEmissiveBrightness = emissiveBrightness;
                darkestArgb = vertexArgb;
            }
            if (emissiveBrightness > maxEmissiveBrightness) {
                maxEmissiveBrightness = emissiveBrightness;
                brightestArgb = vertexArgb;
            }
        }

        private String summary() {
            return String.format(
                    Locale.ROOT,
                    "mode=rgb_surface_match+extended_alpha_fade,samples=%d,light=%s,dot=%.3f..%.3f,surface=%.3f..%.3f,emissive=%.3f..%.3f,base=%08X,dark=%08X,bright=%08X",
                    samples,
                    lightDirection,
                    minDot,
                    maxDot,
                    minSurfaceBrightness,
                    maxSurfaceBrightness,
                    minEmissiveBrightness,
                    maxEmissiveBrightness,
                    baseArgb,
                    darkestArgb,
                    brightestArgb
            );
        }
    }

    private static void logFirstDraw(String pass, AtomicBoolean guard, String setup, String clear) {
        if (guard.compareAndSet(false, true)) {
            LOGGER.info("{} pass={} setup=[{}] clear=[{}]", MARKER, pass, setup, clear);
        }
    }
}
