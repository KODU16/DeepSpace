package world.landfall.deepspace.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.ModOptions;
import world.landfall.deepspace.integration.IrisIntegration;
import world.landfall.deepspace.planet.Galaxy;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.RingWorldNoonTime;
import world.landfall.deepspace.planet.RingWorldDimensions;
import world.landfall.deepspace.planet.Sun;
import world.landfall.deepspace.render.shapes.Cube;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class SunRenderer {
    // Native surface stars are intentionally never rejected by terrain depth.
    private static final RenderStateShard.DepthTestStateShard SURFACE_SUN_DEPTH_TEST =
            new RenderStateShard.DepthTestStateShard("deepspace_surface_sun_always", GL11.GL_ALWAYS);
    // Galaxy suns share the reversed logarithmic depth with ring geometry so the nearer surface wins.
    // Equal quantized log-depth values must not let a later star repaint a nearer ring fragment.
    private static final RenderStateShard.DepthTestStateShard GALAXY_SUN_DEPTH_TEST =
            GalaxyLogDepth.GREATER_DEPTH_TEST;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation SPACE = Deepspace.path("space");
    private static final float CELESTIAL_RENDER_DISTANCE = 100.0f;
    private static final Map<String, List<SunMesh>> SPACE_MESHES = new HashMap<>();
    private static final Map<ResourceKey<Level>, List<SunMesh>> SURFACE_MESHES = new HashMap<>();
    private static final Map<String, ResourceLocation> SPECTRAL_TEXTURES = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> SUN_TYPES = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> SUN_BLOOM_TYPES = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> IRIS_SUN_TYPES = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> GALAXY_SUN_TYPES = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> GALAXY_SUN_BLOOM_TYPES = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> IRIS_GALAXY_SUN_TYPES = new HashMap<>();
    private static final ResourceLocation TEXTURE = Deepspace.path("textures/sun.png");
    private static final ResourceLocation SUN_SHADER = Deepspace.path("sun");
    private static final ResourceLocation SUN_LOG_SHADER = Deepspace.path("sun_log");
    private static final ResourceLocation SUN_GLOW_SHADER = Deepspace.path("sun_glow");
    private static final ResourceLocation SUN_GLOW_LOG_SHADER = Deepspace.path("sun_glow_log");
    private static final ResourceLocation SUN_GLOW_TEXTURE = Deepspace.path("generated/sun_glow");
    private static boolean glowTextureRegistered;
    private static RenderType irisSurfaceGlowType;
    private static RenderType irisGalaxyGlowType;
    /** Binds Sampler0 explicitly because Veil shaders do not inherit vanilla's texture slot reliably. */
    private static RenderStateShard.ShaderStateShard sunShaderState(ResourceLocation texture, boolean logDepth) {
        return new RenderStateShard.ShaderStateShard(() -> {
            ShaderProgram shader = VeilRenderSystem.setShader(logDepth ? SUN_LOG_SHADER : SUN_SHADER);
            shader.setTexture("Sampler0", texture);
            GalaxyLogDepth.applyGeometryScale(shader);
            return VeilRenderBridge.toShaderInstance(shader);
        });
    }
    private static RenderType sunRenderType(ResourceLocation texture) {
        return SUN_TYPES.computeIfAbsent(texture, SunRenderer::createSunRenderType);
    }

    private static RenderType createSunRenderType(ResourceLocation texture) {
        return createSunRenderType(texture, false);
    }

    private static RenderType sunRenderType(ResourceLocation texture, boolean surfaceView) {
        return surfaceView
                ? sunRenderType(texture)
                : GALAXY_SUN_TYPES.computeIfAbsent(texture, key -> createSunRenderType(key, true));
    }

    private static RenderType createSunRenderType(
            ResourceLocation texture,
            boolean galaxyLogDepth
    ) {
        var sunState = RenderType.CompositeState.builder()
                .setShaderState(sunShaderState(texture, galaxyLogDepth))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setDepthTestState(galaxyLogDepth ? GALAXY_SUN_DEPTH_TEST : SURFACE_SUN_DEPTH_TEST)
                // Exterior winding must cull rear faces even when depth testing is always-pass.
                .setCullState(RenderStateShard.CULL)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                // Share the ring renderer's active colour/depth target so celestial occlusion is deterministic.
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        var sunRenderType = RenderType.create(
                "sun",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432, true, false,
                sunState
        );
        return VeilRenderType.layered(
                sunRenderType
        );
    }
    private static RenderType sunBloomRenderType(ResourceLocation texture) {
        return SUN_BLOOM_TYPES.computeIfAbsent(texture, SunRenderer::createSunBloomRenderType);
    }

    private static RenderType createSunBloomRenderType(ResourceLocation texture) {
        return createSunBloomRenderType(texture, false);
    }

    private static RenderType sunBloomRenderType(ResourceLocation texture, boolean surfaceView) {
        return surfaceView
                ? sunBloomRenderType(texture)
                : GALAXY_SUN_BLOOM_TYPES.computeIfAbsent(
                        texture,
                        key -> createSunBloomRenderType(key, true)
                );
    }

    private static RenderType createSunBloomRenderType(
            ResourceLocation texture,
            boolean galaxyLogDepth
    ) {
        var sunState = RenderType.CompositeState.builder()
                .setShaderState(sunShaderState(texture, galaxyLogDepth))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setDepthTestState(galaxyLogDepth ? GALAXY_SUN_DEPTH_TEST : SURFACE_SUN_DEPTH_TEST)
                .setCullState(RenderStateShard.CULL)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        var sunRenderType = RenderType.create(
                "sun",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432, true, false,
                sunState
        );
        var bloomState = RenderType.CompositeState.builder()
                .setShaderState(sunShaderState(texture, galaxyLogDepth))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setDepthTestState(galaxyLogDepth ? GALAXY_SUN_DEPTH_TEST : SURFACE_SUN_DEPTH_TEST)
                .setCullState(RenderStateShard.CULL)
                // Bloom follows stellar occlusion but must not replace the resolved depth.
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setOutputState(VeilRenderSystem.BLOOM_SHARD)
                .createCompositeState(true);
        var bloomRenderType = RenderType.create(
                "sun",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432, true, false,
                bloomState
        );
        // Galaxy bloom reads ring depth before the opaque sun writes its own depth.
        return galaxyLogDepth
                ? VeilRenderType.layered(bloomRenderType, sunRenderType)
                : VeilRenderType.layered(sunRenderType, bloomRenderType);
    }

    /** Exposes the world-rendered star surface texture to GUI celestial views. */
    public static ResourceLocation getSurfaceTexture() {
        return TEXTURE;
    }

    /** Returns a texture with the star's generated spectral color baked into its pixels. */
    public static ResourceLocation getSurfaceTexture(Sun sun) {
        String key = sun.getStage().toUpperCase(Locale.ROOT) + '_' + String.format("%06X", sun.getColor());
        return SPECTRAL_TEXTURES.computeIfAbsent(key, ignored -> createSpectralTexture(sun, key));
    }

    /** Uses vertex tint only when texture generation had to fall back to the neutral source. */
    public static int getSurfaceVertexTint(Sun sun) {
        return getSurfaceTexture(sun).equals(TEXTURE) ? 0xFF000000 | sun.getColor() : 0xFFFFFFFF;
    }

    /**
     * Keeps the sun visible through Iris without relying on the Veil sun shader.
     */
    private static RenderType irisSunRenderType(ResourceLocation texture) {
        return IRIS_SUN_TYPES.computeIfAbsent(texture, SunRenderer::createIrisSunRenderType);
    }

    private static RenderType createIrisSunRenderType(ResourceLocation texture) {
        return createIrisSunRenderType(texture, false);
    }

    private static RenderType irisSunRenderType(ResourceLocation texture, boolean surfaceView) {
        return surfaceView
                ? irisSunRenderType(texture)
                : IRIS_GALAXY_SUN_TYPES.computeIfAbsent(
                        texture,
                        key -> createIrisSunRenderType(key, true)
                );
    }

    private static RenderType createIrisSunRenderType(
            ResourceLocation texture,
            boolean galaxyLogDepth
    ) {
        var state = RenderType.CompositeState.builder()
                // Galaxy stars must write the same log depth as Gecko rings even with Iris active.
                .setShaderState(galaxyLogDepth ? sunShaderState(texture, true)
                        : RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setDepthTestState(galaxyLogDepth ? GALAXY_SUN_DEPTH_TEST : SURFACE_SUN_DEPTH_TEST)
                // Prevent rear faces from overwriting the opaque visible stellar surface.
                .setCullState(RenderStateShard.CULL)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        return RenderType.create(
                "sun_iris",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432,
                true,
                false,
                state
        );
    }

    /** Draws visible Iris glow directly into the active sky target, including ring-world depth. */
    private static RenderType irisGlowRenderType(boolean galaxyLogDepth) {
        RenderType cached = galaxyLogDepth ? irisGalaxyGlowType : irisSurfaceGlowType;
        if (cached != null) return cached;
        ResourceLocation shader = galaxyLogDepth ? SUN_GLOW_LOG_SHADER : SUN_GLOW_SHADER;
        var state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(() -> {
                    ShaderProgram program = VeilRenderSystem.setShader(shader);
                    // Veil requires an explicit sampler binding for generated glow textures under Iris.
                    program.setTexture("Sampler0", SUN_GLOW_TEXTURE);
                    GalaxyLogDepth.applyGeometryScale(program);
                    return VeilRenderBridge.toShaderInstance(program);
                }))
                .setTextureState(new RenderStateShard.TextureStateShard(SUN_GLOW_TEXTURE, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setDepthTestState(galaxyLogDepth ? GALAXY_SUN_DEPTH_TEST : SURFACE_SUN_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        RenderType created = RenderType.create("sun_iris_glow", DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 256, true, false, state);
        if (galaxyLogDepth) irisGalaxyGlowType = created;
        else irisSurfaceGlowType = created;
        return created;
    }

    /** Generates a broad direction-independent glow around the opaque square star model. */
    private static void ensureGlowTexture() {
        if (glowTextureRegistered) return;
        int size = 128;
        NativeImage image = new NativeImage(size, size, false);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double dx = (x + 0.5D - size / 2.0D) / (size / 2.0D);
                double dy = (y + 0.5D - size / 2.0D) / (size / 2.0D);
                double radius = Math.hypot(dx, dy);
                double core = Math.exp(-radius * radius * 8.0D) * 0.38D;
                double surroundingLight = Math.exp(-radius * radius * 2.5D) * 0.24D;
                int alpha = (int) Math.round(255.0D * Math.clamp(core + surroundingLight, 0.0D, 0.62D));
                image.setPixelRGBA(x, y, alpha << 24 | 0x00FFFFFF);
            }
        }
        Minecraft.getInstance().getTextureManager().register(SUN_GLOW_TEXTURE, new DynamicTexture(image));
        glowTextureRegistered = true;
    }

    public static void refreshMeshes() {
        var textureManager = Minecraft.getInstance().getTextureManager();
        if (glowTextureRegistered) {
            textureManager.release(SUN_GLOW_TEXTURE);
            glowTextureRegistered = false;
        }
        SPECTRAL_TEXTURES.values().stream()
                .filter(texture -> !texture.equals(TEXTURE))
                .forEach(textureManager::release);
        SPECTRAL_TEXTURES.clear();
        SUN_TYPES.clear();
        SUN_BLOOM_TYPES.clear();
        IRIS_SUN_TYPES.clear();
        GALAXY_SUN_TYPES.clear();
        GALAXY_SUN_BLOOM_TYPES.clear();
        IRIS_GALAXY_SUN_TYPES.clear();
        SPACE_MESHES.clear();
        SURFACE_MESHES.clear();
        for (var galaxy : PlanetRegistry.getAllGalaxies()) {
            SPACE_MESHES.put(galaxy.id(), galaxy.suns().stream()
                    .map(galaxySun -> new SunMesh(galaxySun, new Cube(
                            galaxySun.getBoundingBoxMin().subtract(galaxySun.getCenter()).toVector3f(),
                            galaxySun.getBoundingBoxMax().subtract(galaxySun.getCenter()).toVector3f(),
                            1f,
                            false
                    ), (float) galaxySun.getModelRadius()))
                    .toList());
        }
        for (Planet planet : PlanetRegistry.getAllPlanets()) {
            var galaxy = PlanetRegistry.getGalaxyByDimension(planet.getGalaxy());
            // 主世界同样需要 deepspace 太阳替换，因此不再排除 overworld。
            if (!planet.isWormhole() && galaxy != null) {
                Sun hostSun = PlanetRegistry.getSunForPlanet(planet);
                SURFACE_MESHES.put(planet.getDimension(), galaxy.suns().stream()
                        .map(star -> new SunMesh(
                                star,
                                createSurfaceMesh(star, planet, hostSun),
                                surfaceStarApparentRadius(star, planet, hostSun)
                        ))
                        .toList());
            }
        }
    }

    private static ResourceLocation createSpectralTexture(Sun sun, String key) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(TEXTURE);
        if (resource.isEmpty()) {
            LOGGER.warn("Could not create spectral texture for {}: missing {}", sun.getName(), TEXTURE);
            return TEXTURE;
        }
        try (InputStream input = resource.get().open(); NativeImage source = NativeImage.read(input)) {
            NativeImage tinted = new NativeImage(source.getWidth(), source.getHeight(), false);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    // Preserve spectral color and texture detail beneath a gentle full-face glow.
                    tinted.setPixelRGBA(
                            x,
                            y,
                            SunRenderMath.brightenSpectralFaceAbgr(
                                    source.getPixelRGBA(x, y), sun.getColor(),
                                    (x + 0.5D) / source.getWidth(),
                                    (y + 0.5D) / source.getHeight())
                    );
                }
            }
            DynamicTexture dynamicTexture = new DynamicTexture(tinted);
            return Minecraft.getInstance().getTextureManager().register(
                    "deepspace_sun_" + key.toLowerCase(Locale.ROOT),
                    dynamicTexture
            );
        } catch (IOException exception) {
            LOGGER.warn("Could not create spectral texture for {} ({})", sun.getName(), sun.getStage(), exception);
            return TEXTURE;
        }
    }

    private static Cube createSurfaceMesh(Sun star, Planet planet, Sun hostSun) {
        var sunCenter = star.getCenter();
        // Keep cached surface meshes independent of the render distance, which changes across dimensions.
        float scale = SunRenderMath.apparentScale(
                1.0F,
                surfaceStarDistance(star, planet, hostSun)
        ) * surfaceStarVisualScale(star, planet, hostSun);
        return new Cube(
                star.getBoundingBoxMin().subtract(sunCenter).toVector3f(),
                star.getBoundingBoxMax().subtract(sunCenter).toVector3f(),
                scale,
                false
        );
    }

    /** Converts each star's model radius to the same apparent units used by cluster offsets. */
    private static float surfaceStarApparentRadius(Sun star, Planet observer, Sun hostSun) {
        return (float) star.getModelRadius()
                * SunRenderMath.apparentScale(1.0F, surfaceStarDistance(star, observer, hostSun))
                * surfaceStarVisualScale(star, observer, hostSun);
    }

    /** Computes a bounded host-star enlargement from the ring and star's actual dimensions. */
    private static float ringWorldHostStarVisualScale(Sun sun, Planet observer) {
        Vec3 size = sun.getBoundingBoxMax().subtract(sun.getBoundingBoxMin());
        double transverseDiameter = Math.min(size.y, Math.max(size.x, size.z));
        double starDistance = sun.getCenter().distanceTo(observer.getCenter());
        return SunRenderMath.ringWorldStarVisualScale(
                transverseDiameter,
                starDistance,
                RingWorldDimensions.MODEL_HALF_HEIGHT * 2.0D * RingWorldSkyRenderer.skyLateralPerspectiveFraction(),
                starDistance * 2.0D
        );
    }

    /** Uses the same host-star enlargement for eagerly refreshed and lazily created surface meshes. */
    private static float surfaceStarVisualScale(Sun star, Planet observer, Sun hostSun) {
        Planet scaleObserver = surfaceStarScaleObserver(star, observer, hostSun);
        return scaleObserver.isRingWorldEdge() && star.equals(hostSun)
                ? ringWorldHostStarVisualScale(star, scaleObserver)
                : 1.0F;
    }

    private static double surfaceStarDistance(Sun star, Planet observer, Sun hostSun) {
        Planet scaleObserver = surfaceStarScaleObserver(star, observer, hostSun);
        return star.getCenter().distanceTo(scaleObserver.getCenter());
    }

    private static Planet surfaceStarScaleObserver(Sun star, Planet observer, Sun hostSun) {
        if (!observer.isRingWorldEdge() || !star.equals(hostSun)) {
            return observer;
        }
        Planet overworld = PlanetRegistry.getPlanetByDimension(Level.OVERWORLD);
        // Primary-ring segments share the Overworld edge so the fixed host star keeps one apparent size.
        if (overworld != null && overworld.isRingWorldEdge() && overworld.getGalaxy().equals(observer.getGalaxy())) {
            return overworld;
        }
        return observer;
    }

    /** Keeps the sun beyond the near plane and inside the far plane. */
    private static float celestialRenderDistance() {
        var gameRenderer = Minecraft.getInstance().gameRenderer;
        if (gameRenderer == null) {
            return 100.0F;
        }
        return Math.max(100.0F, gameRenderer.getDepthFar() * 0.9F);
    }

    /**
     * Uses one condition for the vanilla renderer and Iris' wrapped celestial draw call.
     */
    public static boolean shouldReplaceVanillaSun() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        Planet planet = PlanetRegistry.getPlanetByDimension(level.dimension());
        boolean replace = planet != null
                && PlanetRegistry.getGalaxyByDimension(planet.getGalaxy()) != null
                && PlanetRegistry.getSunForPlanet(planet) != null;
        return replace;
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
        renderInternal(stage, levelRenderer, bufferSource, matrixStack, frustumMatrix, projectionMatrix,
                renderTick, partialTicks, camera, frustum);
    }

    private static void renderInternal(
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
        var instance = Minecraft.getInstance();
        if (instance.level == null) {
            return;
        }

        var dimension = instance.level.dimension();
        var dimensionId = dimension.location();
        List<RenderedSun> renderedSuns = new ArrayList<>();
        var galaxy = PlanetRegistry.getGalaxyByDimension(dimension);
        boolean irisEnabled = IrisIntegration.isShaderPackEnabled();
        if (galaxy != null) {
            // A failed Iris phase switch falls back to DeepSpace's local logarithmic shader.
            irisEnabled = IrisIntegration.isGalaxyLogDepthPhaseActive();
        }
        if (galaxy != null) {
            // Registry synchronization may happen after the first client render refresh.
            boolean ringGalaxy = hasRingWorldGeometry(galaxy);
            List<SunMesh> meshes = SPACE_MESHES.computeIfAbsent(galaxy.id(), key -> galaxy.suns().stream()
                    .map(star -> new SunMesh(
                    star,
                    new Cube(
                                    star.getBoundingBoxMin().subtract(star.getCenter()).toVector3f(),
                                    star.getBoundingBoxMax().subtract(star.getCenter()).toVector3f(),
                                    1.0F,
                                    false
                            ), (float) star.getModelRadius()
                    ))
                    .toList());
            if (meshes.isEmpty()) {
                return;
            }
            // Galaxy-space stars share logarithmic depth with planets; surface skies retain normal depth.
            boolean surfaceView = false;
            meshes.forEach(mesh -> {
                Vec3 physicalOffset = mesh.sun().getCenter().subtract(camera.getPosition());
                float compression = galaxyRenderScale(physicalOffset.length(), ringGalaxy);
                // Position and size use the same factor, preserving apparent angular size inside the far plane.
                renderedSuns.add(new RenderedSun(
                        mesh, physicalOffset.scale(compression).toVector3f(), compression, surfaceView
                ));
            });
        } else {
            Planet planet = PlanetRegistry.getPlanetByDimension(dimension);
            if (planet == null) {
                return;
            }
            var hostGalaxy = PlanetRegistry.getGalaxyByDimension(planet.getGalaxy());
            Sun hostSun = PlanetRegistry.getSunForPlanet(planet);
            if (hostGalaxy == null || hostSun == null) {
                return;
            }
            List<SunMesh> meshes = SURFACE_MESHES.computeIfAbsent(
                    dimension,
                    key -> hostGalaxy.suns().stream()
                            .map(star -> new SunMesh(
                                    star,
                                    createSurfaceMesh(
                                            star,
                                            planet,
                                            hostSun
                                    ),
                                    surfaceStarApparentRadius(star, planet, hostSun)
                            ))
                            .toList()
            );
            float[] hostCelestial;
            if (RingWorldNoonTime.isNoonLockedSurface(planet.getDimension())) {
                // Ring-world galaxies use one fixed zenith direction across all hosted surfaces.
                hostCelestial = ringWorldCelestialDirection();
            } else {
                // getTimeOfDay expects the 0..1 render partial tick, not the frame delta tick.
                float renderPartialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
                float skyAngle = instance.level.getTimeOfDay(renderPartialTick);
                float pathRotation = irisEnabled ? IrisIntegration.getSunPathRotation() : 0.0F;
                hostCelestial = IrisIntegration.isBslShaderPack()
                        ? SunRenderMath.bslCelestialDirection(skyAngle, pathRotation)
                        : SunRenderMath.celestialDirection(skyAngle, pathRotation);
            }
            float[] hostPhysical = directionFromPlanet(planet, hostSun);
            float surfaceRenderDistance = celestialRenderDistance();
            float[] apparentRadii = new float[meshes.size()];
            for (int index = 0; index < meshes.size(); index++) {
                SunMesh mesh = meshes.get(index);
                apparentRadii[index] = mesh.apparentRadius() * surfaceRenderDistance;
            }
            float[][] clusterOffsets = SunRenderMath.multiStarOffsets(
                    apparentRadii,
                    hostGalaxy.id().hashCode() * 0x9E3779B97F4A7C15L + planet.getId().hashCode()
            );
            for (int index = 0; index < meshes.size(); index++) {
                SunMesh mesh = meshes.get(index);
                float[] direction = meshes.size() == 1
                        ? SunRenderMath.relativeCelestialDirection(
                                hostPhysical,
                                directionFromPlanet(planet, mesh.sun()),
                                hostCelestial
                        )
                        : SunRenderMath.offsetCelestialDirection(
                                hostCelestial,
                                clusterOffsets[index][0],
                                clusterOffsets[index][1],
                                surfaceRenderDistance
                        );
                renderedSuns.add(new RenderedSun(
                        mesh,
                        new Vector3f(direction[0], direction[1], direction[2]).mul(surfaceRenderDistance),
                        surfaceRenderDistance,
                        true
                ));
            }
        }

        // AFTER_SKY supplies an empty event stack; frustumMatrix keeps every star fixed in world direction.
        PoseStack celestialPose = new PoseStack();
        celestialPose.mulPose(new Matrix4f(frustumMatrix));
        for (RenderedSun renderedSun : renderedSuns) {
            drawSun(celestialPose, renderedSun, irisEnabled, camera);
        }
    }

    /** Draws the exterior winding so back-face culling exposes the star surface rather than its interior. */
    private static void drawSun(
            PoseStack matrixStack,
            RenderedSun rendered,
            boolean irisEnabled,
            Camera camera
    ) {
        var poseStack = matrixStack;
        poseStack.pushPose();
        // Surface skies retain their existing distance; galaxy bodies recover physical depth.
        float previousScale = GalaxyLogDepth.setGeometryScale(
                rendered.surfaceView() ? 1.0F : rendered.geometryScale());
        try {
            Sun sun = rendered.mesh().sun();
            // Both Iris and Veil read the same baked spectral texture with an evenly bright face.
            ResourceLocation texture = getSurfaceTexture(sun);
            int tint = getSurfaceVertexTint(sun);
            // Tesselator owns one active BufferBuilder, so finish the glow before opening the disc builder.
            if (irisEnabled) drawIrisGlow(poseStack, rendered, camera);
            BufferBuilder sunBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            rendered.mesh().cube().renderOutwardTintedFullBright(
                    poseStack, sunBuilder, rendered.position(), new Quaternionf(), tint, rendered.geometryScale()
            );
            if (irisEnabled) {
                // Galaxy stars keep the local log-depth shader; surface stars use Iris with depth-always occlusion.
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                CelestialRenderDiagnostics.recordSunDraw();
                irisSunRenderType(texture, rendered.surfaceView()).draw(sunBuilder.buildOrThrow());
            } else {
                VeilRenderSystem.setShader(Deepspace.path("sun"));
                RenderSystem.setShaderTexture(0, texture);
                // The Deep Space sun shader reads the spectral vertex tint directly.
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                switch (ModOptions.options().atmosphereDetail) {
                    case NONE, BASIC -> sunRenderType(texture, rendered.surfaceView()).draw(sunBuilder.buildOrThrow());
                    case EXPENSIVE -> sunBloomRenderType(texture, rendered.surfaceView()).draw(sunBuilder.buildOrThrow());
                }
            }
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        } finally {
            GalaxyLogDepth.setGeometryScale(previousScale);
            poseStack.popPose();
        }
    }

    private static void drawIrisGlow(PoseStack poseStack, RenderedSun rendered, Camera camera) {
        ensureGlowTexture();
        float radius = rendered.mesh().apparentRadius() * rendered.geometryScale() * 4.8F;
        Vector3f right = new Vector3f(1.0F, 0.0F, 0.0F).rotate(camera.rotation());
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(camera.rotation());
        int color = 0xFF000000 | rendered.mesh().sun().getColor();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.NEW_ENTITY);
        addGlowVertex(builder, poseStack, rendered.position(), right, up, radius, -1.0F, -1.0F, 0.0F, 1.0F, color);
        addGlowVertex(builder, poseStack, rendered.position(), right, up, radius, 1.0F, -1.0F, 1.0F, 1.0F, color);
        addGlowVertex(builder, poseStack, rendered.position(), right, up, radius, 1.0F, 1.0F, 1.0F, 0.0F, color);
        addGlowVertex(builder, poseStack, rendered.position(), right, up, radius, -1.0F, 1.0F, 0.0F, 0.0F, color);
        irisGlowRenderType(!rendered.surfaceView()).draw(builder.buildOrThrow());
    }

    private static void addGlowVertex(BufferBuilder builder, PoseStack poseStack, Vector3f center,
                                      Vector3f right, Vector3f up, float radius, float x, float y,
                                      float u, float v, int color) {
        Vector3f position = new Vector3f(center).fma(x * radius, right).fma(y * radius, up);
        builder.addVertex(poseStack.last().pose(), position.x(), position.y(), position.z())
                .setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(0.0F, 0.0F, 1.0F);
    }

    /** Keeps galaxy stars and their surrounding ring in the same compressed camera frame. */
    static float galaxyRenderScale(double distance, boolean ringGalaxy) {
        // Reserve room for the far side of a ring while keeping its star in the same frame.
        double ringExtent = ringGalaxy
                ? Math.hypot(RingWorldDimensions.OUTER_RADIUS, RingWorldDimensions.MODEL_HALF_HEIGHT)
                : 0.0D;
        return SunRenderMath.boundedCelestialScale(celestialRenderDistance(), distance + ringExtent);
    }

    /** Ring-world geometry and its host star stay locked at the noon zenith in every renderer. */
    static float[] ringWorldCelestialDirection() {
        return SunRenderMath.fixedRingWorldCelestialDirection();
    }

    private static float[] directionFromPlanet(Planet planet, Sun sun) {
        Vector3f direction = sun.getCenter().subtract(planet.getCenter()).normalize().toVector3f();
        return new float[]{direction.x, direction.y, direction.z};
    }

    private static boolean hasRingWorldGeometry(Galaxy galaxy) {
        return galaxy.brokenRingSections() != 0
                || PlanetRegistry.getPlanetsForGalaxy(galaxy.dimension()).stream().anyMatch(Planet::isRingWorldEdge);
    }

    private record SunMesh(Sun sun, Cube cube, float apparentRadius) {
    }

    private record RenderedSun(SunMesh mesh, Vector3f position, float geometryScale, boolean surfaceView) {
    }

    public static void init() {
        refreshMeshes();
        SpaceRenderSystem.registerRenderer(SunRenderer::render, SpaceRenderSystem.BACKGROUND_STAGE);
    }


}
