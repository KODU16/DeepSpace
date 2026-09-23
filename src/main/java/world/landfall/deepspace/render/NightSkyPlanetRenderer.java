package world.landfall.deepspace.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.Level;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import world.landfall.deepspace.Config;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.integration.IrisIntegration;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.GalaxyDimensions;
import world.landfall.deepspace.render.shapes.Cube;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Draws a moon-phase-dependent selection of other planets in surface night skies.
 */
public final class NightSkyPlanetRenderer {
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation SPACE = Deepspace.path("space");
    private static final float CELESTIAL_RENDER_DISTANCE = 100.0f;
    private static final int NIGHT_SKY_PLANET_BRIGHTNESS = 230;

    private static String cachedObserverId = "";
    private static long cachedLunarEpoch = Long.MIN_VALUE;
    private static int cachedMoonPhase = -1;
    private static double cachedMinimumFraction = Double.NaN;
    private static double cachedMaximumFraction = Double.NaN;
    private static List<RenderedBody> cachedBodies = List.of();

    private NightSkyPlanetRenderer() {
    }

    public static void init() {
        refreshMeshes();
        SpaceRenderSystem.registerRenderer(
                NightSkyPlanetRenderer::render,
                SpaceRenderSystem.BACKGROUND_STAGE
        );
    }

    public static void refreshMeshes() {
        cachedObserverId = "";
        cachedLunarEpoch = Long.MIN_VALUE;
        cachedMoonPhase = -1;
        cachedMinimumFraction = Double.NaN;
        cachedMaximumFraction = Double.NaN;
        cachedBodies = List.of();
    }

    /** Invalidates the cached night-sky layout after one planet's texture changes. */
    public static void refreshPlanet(String planetId) {
        Planet planet = PlanetRegistry.getPlanet(planetId);
        if (planet == null || cachedBodies.isEmpty()) {
            return;
        }
        List<ResourceLocation> textures = PlanetRenderer.getSurfaceTextures(planet);
        cachedBodies = cachedBodies.stream()
                .map(body -> body.planetId().equals(planetId)
                        ? new RenderedBody(
                                body.planetId(),
                                body.mesh(),
                                body.position(),
                                new Quaternionf(body.rotation()),
                                List.copyOf(textures)
                        )
                        : body)
                .toList();
    }

    /**
     * Deep Space owns every visible celestial body on managed Infinity planets.
     */
    public static boolean shouldRemoveVanillaMoon() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        Planet planet = PlanetRegistry.getPlanetByDimension(level.dimension());
        return level.dimension().location().equals(OVERWORLD)
                || (planet != null && level.dimension().location().getNamespace().equals("infinity"));
    }

    private static RenderType skyPlanetRenderType(ResourceLocation texture, boolean irisEnabled) {
        var state = RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.CULL)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setOutputState(irisEnabled ? IrisIntegration.IRIS_TARGET : RenderStateShard.MAIN_TARGET)
                .createCompositeState(true);
        return RenderType.create(
                "deepspace_night_sky_planet_" + texture.toString().replace(':', '_').replace('/', '_'),
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432,
                true,
                false,
                state
        );
    }

    public static void render(
            VeilRenderLevelStageEvent.Stage stage,
            LevelRenderer levelRenderer,
            MultiBufferSource.BufferSource bufferSource,
            MatrixStack matrixStack,
            Matrix4fc frustumMatrix,
            Matrix4fc projectionMatrix,
            int renderTick,
            DeltaTracker partialTicks,
            Camera camera,
            Frustum frustum
    ) {
        var level = Minecraft.getInstance().level;
        if (level == null || GalaxyDimensions.isGalaxy(level.dimension())) {
            return;
        }

        Planet observer = PlanetRegistry.getPlanetByDimension(level.dimension());
        // Generated Infinity planet surfaces are valid Deep Space observers once they are registered.
        if (observer == null) {
            return;
        }

        // getTimeOfDay expects the interpolated 0..1 partial tick, not elapsed frame ticks.
        float renderPartialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        float visibility = NightSkyPlanetLayout.nightVisibility(level.getTimeOfDay(renderPartialTick));
        if (visibility <= 0.0f) {
            return;
        }

        long lunarEpoch = Math.floorDiv(level.getDayTime(), 24_000L);
        int moonPhase = level.getMoonPhase();
        ensureLayout(observer, lunarEpoch, moonPhase);
        if (cachedBodies.isEmpty()) {
            return;
        }

        // Keep distant planets visibly emissive while retaining the night fade at the horizon.
        int vertexColor = FastColor.ARGB32.color(
                Math.clamp(Math.round(visibility * 255.0f), 0, 255),
                NIGHT_SKY_PLANET_BRIGHTNESS,
                NIGHT_SKY_PLANET_BRIGHTNESS,
                NIGHT_SKY_PLANET_BRIGHTNESS
        );
        boolean irisEnabled = IrisIntegration.isShaderPackEnabled();
        // AFTER_SKY supplies an empty stack; apply the camera rotation used by the working sun renderer.
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(new Matrix4f(frustumMatrix));
        poseStack.pushPose();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        for (RenderedBody body : cachedBodies) {
            // Night-sky cubes bind the same individual faces as their full-size galaxy counterparts.
            for (int face = 0; face < 6; face++) {
                BufferBuilder builder = Tesselator.getInstance().begin(
                        VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                body.mesh().renderFaceOutwardTintedFullBright(
                        poseStack, builder, body.position(), new Quaternionf(body.rotation()), face, vertexColor);
                ResourceLocation texture = body.textures().get(body.textures().size() == 1 ? 0 : face);
                skyPlanetRenderType(texture, irisEnabled).draw(builder.buildOrThrow());
            }
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        poseStack.popPose();
    }

    private static void ensureLayout(Planet observer, long lunarEpoch, int moonPhase) {
        double minimumFraction = Config.NIGHT_SKY_PLANET_MIN_FRACTION.get();
        double maximumFraction = Config.NIGHT_SKY_PLANET_MAX_FRACTION.get();
        if (cachedObserverId.equals(observer.getId())
                && cachedLunarEpoch == lunarEpoch
                && cachedMoonPhase == moonPhase
                && cachedMinimumFraction == minimumFraction
                && cachedMaximumFraction == maximumFraction) {
            return;
        }

        Map<String, Planet> planetsById = new HashMap<>();
        List<NightSkyPlanetLayout.Candidate> candidates = new ArrayList<>();
        for (Planet candidate : PlanetRegistry.getAllPlanets()) {
            if (candidate.isRingWorldEdge()
                    || candidate.getId().equals(observer.getId())
                    || !candidate.getGalaxy().equals(observer.getGalaxy())) {
                continue;
            }
            double distance = observer.getCenter().distanceTo(candidate.getCenter());
            double diameter = planetDiameter(candidate);
            if (distance > 0.0 && diameter > 0.0 && !PlanetRenderer.getSurfaceTextures(candidate).isEmpty()) {
                planetsById.put(candidate.getId(), candidate);
                candidates.add(new NightSkyPlanetLayout.Candidate(candidate.getId(), distance, diameter));
            }
        }

        List<RenderedBody> rendered = new ArrayList<>();
        for (NightSkyPlanetLayout.SkyBody body : NightSkyPlanetLayout.select(
                observer.getGeneratedTextureSeed(),
                lunarEpoch,
                moonPhase,
                candidates,
                minimumFraction,
                maximumFraction
        )) {
            Planet planet = planetsById.get(body.planetId());
            List<ResourceLocation> textures = planet == null ? List.of() : PlanetRenderer.getSurfaceTextures(planet);
            if (textures.isEmpty()) {
                continue;
            }
            float halfDiameter = body.renderedDiameter() * 0.5f;
            Cube mesh = new Cube(
                    new Vector3f(-halfDiameter, -halfDiameter, -halfDiameter),
                    new Vector3f(halfDiameter, halfDiameter, halfDiameter),
                    1.0f,
                    false
            );
            rendered.add(new RenderedBody(
                    body.planetId(),
                    mesh,
                    skyPosition(body),
                    randomRotation(body.planetId()),
                    List.copyOf(textures)
            ));
        }

        cachedObserverId = observer.getId();
        cachedLunarEpoch = lunarEpoch;
        cachedMoonPhase = moonPhase;
        cachedMinimumFraction = minimumFraction;
        cachedMaximumFraction = maximumFraction;
        cachedBodies = List.copyOf(rendered);
    }

    private static double planetDiameter(Planet planet) {
        var min = planet.getBoundingBoxMin();
        var max = planet.getBoundingBoxMax();
        return Math.max(
                Math.abs(max.x - min.x),
                Math.max(Math.abs(max.y - min.y), Math.abs(max.z - min.z))
        );
    }

    private static Vector3f skyPosition(NightSkyPlanetLayout.SkyBody body) {
        double yaw = Math.toRadians(body.yawDegrees());
        double pitch = Math.toRadians(body.pitchDegrees());
        float horizontal = (float) Math.cos(pitch);
        return new Vector3f(
                horizontal * (float) Math.cos(yaw),
                (float) Math.sin(pitch),
                horizontal * (float) Math.sin(yaw)
        ).mul(CELESTIAL_RENDER_DISTANCE);
    }

    /** Gives each night-sky planet a stable but non-uniform orientation. */
    private static Quaternionf randomRotation(String planetId) {
        SplittableRandom random = new SplittableRandom(planetId.hashCode() * 0x9E3779B97F4A7C15L);
        return new Quaternionf()
                .rotateXYZ(
                        (float) random.nextDouble(0.0, Math.PI * 2.0),
                        (float) random.nextDouble(0.0, Math.PI * 2.0),
                        (float) random.nextDouble(0.0, Math.PI * 2.0)
                );
    }

    private record RenderedBody(
            String planetId,
            Cube mesh,
            Vector3f position,
            Quaternionf rotation,
            List<ResourceLocation> textures
    ) {
    }
}
