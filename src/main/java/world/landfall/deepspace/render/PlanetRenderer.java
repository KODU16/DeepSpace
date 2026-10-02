package world.landfall.deepspace.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.ModOptions;
import world.landfall.deepspace.integration.IrisIntegration;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.PlanetTextureGenerator;
import world.landfall.deepspace.planet.PlanetTextureLayout;
import world.landfall.deepspace.planet.PlanetCubeTextureLayout;
import world.landfall.deepspace.render.shapes.Cube;

import java.util.HashMap;
import java.util.List;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.IntStream;
import java.io.InputStream;
import org.slf4j.Logger;
import java.util.HashSet;

public class PlanetRenderer {

    private static final Logger logger = LogUtils.getLogger();

    private static final HashMap<String, Cube> MESHES = new HashMap<>();
    private static final HashMap<String, List<ResourceLocation>> GENERATED_TEXTURES = new HashMap<>();
    private static final HashMap<String, List<RenderType>> SHADED_RENDER_TYPES = new HashMap<>();
    private static final HashMap<String, List<RenderType>> UNSHADED_RENDER_TYPES = new HashMap<>();
    private static final HashMap<String, List<RenderType>> IRIS_RENDER_TYPES = new HashMap<>();
    private static final ResourceLocation PLANET_SHADER = Deepspace.path("planet");
    private static final ResourceLocation PLANET_UNSHADED_SHADER = Deepspace.path("planet_unshaded");
    private static final DateTimeFormatter DEBUG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Explicitly binds Sampler0 because Veil does not always inherit vanilla's dynamic texture slot.
     */
    private static RenderStateShard.ShaderStateShard planetShaderState(
            ResourceLocation shaderLocation,
            ResourceLocation texture
    ) {
        return new RenderStateShard.ShaderStateShard(() -> {
            ShaderProgram shader = VeilRenderSystem.setShader(shaderLocation);
            shader.setTexture("Sampler0", texture);
            GalaxyLogDepth.applyGeometryScale(shader);
            return VeilRenderBridge.toShaderInstance(shader);
        });
    }

    private static RenderType planetRenderType(String planetId, int faceIndex, ResourceLocation texture) {
        var renderType = RenderType.CompositeState.builder()
                .setShaderState(planetShaderState(PLANET_SHADER, texture))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setCullState(RenderStateShard.CULL)
                .setDepthTestState(GalaxyLogDepth.GREATER_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                // Keep native and Iris fallback planets in the shared celestial depth target.
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        return RenderType.create(
                planetRenderTypeName("shaded", planetId, faceIndex),
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432, true, false,
                renderType
        );
    }
    private static RenderType planetUnshadedRenderType(String planetId, int faceIndex, ResourceLocation texture) {
        var renderType = RenderType.CompositeState.builder()
                .setShaderState(planetShaderState(PLANET_UNSHADED_SHADER, texture))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setCullState(RenderStateShard.CULL)
                .setDepthTestState(GalaxyLogDepth.GREATER_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                // Keep native and Iris fallback planets in the shared celestial depth target.
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        return RenderType.create(
                planetRenderTypeName("unshaded", planetId, faceIndex),
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432, true, false,
                renderType
        );
    }

    /**
     * Uses an Iris-recognized entity program so shader packs can route the planet surface reliably.
     */
    private static RenderType irisPlanetRenderType(String planetId, int faceIndex, ResourceLocation texture) {
        var state = RenderType.CompositeState.builder()
                // Iris still uses the shared galaxy depth buffer; the local cutout shader writes its log depth.
                .setShaderState(GalaxyLogDepth.entityCutoutShader(texture))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setCullState(RenderStateShard.CULL)
                // Planet depth must exist before translucent atmospheres and rings are drawn.
                .setDepthTestState(GalaxyLogDepth.GREATER_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                .setOutputState(CelestialRenderDiagnostics.PLANET_PROBE)
                .createCompositeState(true);
        return RenderType.create(
                planetRenderTypeName("iris", planetId, faceIndex),
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432,
                true,
                false,
                state
        );
    }

    /**
     * Keeps Iris and render-state caches from merging different planets or cube faces.
     */
    private static String planetRenderTypeName(String mode, String planetId, int faceIndex) {
        return "deepspace_planet_" + mode + "_" + planetId.replace(':', '_') + "_face_" + faceIndex;
    }

    public static void refreshMeshes() {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            // Network synchronization may also request a refresh away from the render thread.
            RenderSystem.recordRenderCall(PlanetRenderer::refreshMeshes);
            return;
        }
        var textureManager = Minecraft.getInstance().getTextureManager();
        CelestialGuiRenderer.clearRenderTypes();
        GENERATED_TEXTURES.values().stream().flatMap(List::stream).forEach(textureManager::release);
        GENERATED_TEXTURES.clear();
        MESHES.clear();
        HyperRelayGeoRenderer.clearMesh();
        SHADED_RENDER_TYPES.clear();
        UNSHADED_RENDER_TYPES.clear();
        IRIS_RENDER_TYPES.clear();
        for (var x : PlanetRegistry.getAllPlanets()) {
            List<ResourceLocation> textures = x.getTextures();
            if (textures.isEmpty()) {
                textures = createGeneratedTextures(
                        x.getId(),
                        x.getGeneratedTextureSeed(),
                        x.getGeneratedSurfaceColors(),
                        x.getGeneratedTextureFragmentation(),
                        x.getGeneratedSurfaceMap()
                );
            } else if (textures.size() == 1) {
                textures = createCrossTextures(x.getId(), textures.getFirst());
            }
            // 环世界和超空间中继器都有独立 GeckoLib 渲染器，不再生成普通行星方块网格。
            if (x.isRingWorldEdge() || x.isHyperRelay()) {
                continue;
            }
            MESHES.put(x.getId(),new Cube(x.getBoundingBoxMin().toVector3f(), x.getBoundingBoxMax().toVector3f(), 1f, false));
            // A one-entry list repeats on every face; six entries bind in Cube.renderFace order.
            List<ResourceLocation> boundTextures = textures;
            SHADED_RENDER_TYPES.put(x.getId(), IntStream.range(0, boundTextures.size())
                    .mapToObj(face -> planetRenderType(x.getId(), face, boundTextures.get(face)))
                    .toList());
            UNSHADED_RENDER_TYPES.put(x.getId(), IntStream.range(0, boundTextures.size())
                    .mapToObj(face -> planetUnshadedRenderType(x.getId(), face, boundTextures.get(face)))
                    .toList());
            IRIS_RENDER_TYPES.put(x.getId(), IntStream.range(0, boundTextures.size())
                    .mapToObj(face -> irisPlanetRenderType(x.getId(), face, boundTextures.get(face)))
                    .toList());
            logger.info("Made mesh for planet {}",x.getName());
        }
        // Rebuild night-sky choices only after generated textures are available.
        NightSkyPlanetRenderer.refreshMeshes();
    }

    /** Rebuilds only one planet after an incremental texture synchronization. */
    public static void refreshPlanet(String planetId) {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            RenderSystem.recordRenderCall(() -> refreshPlanet(planetId));
            return;
        }
        Planet planet = PlanetRegistry.getPlanet(planetId);
        if (planet == null) {
            return;
        }
        var textureManager = Minecraft.getInstance().getTextureManager();
        List<ResourceLocation> oldTextures = GENERATED_TEXTURES.remove(planetId);
        if (oldTextures != null) {
            oldTextures.forEach(textureManager::release);
        }
        MESHES.remove(planetId);
        SHADED_RENDER_TYPES.remove(planetId);
        UNSHADED_RENDER_TYPES.remove(planetId);
        IRIS_RENDER_TYPES.remove(planetId);

        List<ResourceLocation> textures = planet.getTextures();
        if (textures.isEmpty()) {
            textures = createGeneratedTextures(
                    planet.getId(),
                    planet.getGeneratedTextureSeed(),
                    planet.getGeneratedSurfaceColors(),
                    planet.getGeneratedTextureFragmentation(),
                    planet.getGeneratedSurfaceMap()
            );
        } else if (textures.size() == 1) {
            textures = createCrossTextures(planet.getId(), textures.getFirst());
        }
        if (!planet.isRingWorldEdge() && !planet.isHyperRelay()) {
            MESHES.put(planetId, new Cube(
                    planet.getBoundingBoxMin().toVector3f(),
                    planet.getBoundingBoxMax().toVector3f(),
                    1f,
                    false
            ));
            List<ResourceLocation> boundTextures = textures;
            SHADED_RENDER_TYPES.put(planetId, IntStream.range(0, boundTextures.size())
                    .mapToObj(face -> planetRenderType(planetId, face, boundTextures.get(face))).toList());
            UNSHADED_RENDER_TYPES.put(planetId, IntStream.range(0, boundTextures.size())
                    .mapToObj(face -> planetUnshadedRenderType(planetId, face, boundTextures.get(face))).toList());
            IRIS_RENDER_TYPES.put(planetId, IntStream.range(0, boundTextures.size())
                    .mapToObj(face -> irisPlanetRenderType(planetId, face, boundTextures.get(face))).toList());
        }
        // Layout selection is shared, but only its lightweight cached result is invalidated.
        NightSkyPlanetRenderer.refreshPlanet(planetId);
    }

    /**
     * Registers a world-and-planet-seeded texture using the server-sampled surface palette.
     */
    private static List<ResourceLocation> createGeneratedTextures(
            String planetId,
            long textureSeed,
            List<Planet.SurfaceColor> colors,
            float fragmentation,
            short[] surfaceMap
    ) {
        // Keep preview textures at their native resolution instead of allocating six full-size faces.
        var tier = world.landfall.deepspace.planet.PlanetTextureTier.fromPixelCount(surfaceMap.length);
        if (tier == null) tier = world.landfall.deepspace.planet.PlanetTextureTier.COARSE;
        int width = tier.width();
        int height = tier.height();
        int[] pixels = PlanetTextureGenerator.generateMapPixels(surfaceMap, width, height);
        if (pixels.length == 0) {
            pixels = PlanetTextureGenerator.generatePixels(textureSeed, colors, fragmentation, width, height);
        }
        List<ResourceLocation> locations = registerAtlasFaces(planetId, pixels, width, height);
        GENERATED_TEXTURES.put(planetId, locations);
        return locations;
    }

    /** Splits configured 3x2 atlases directly while retaining compatibility with legacy resource maps. */
    private static List<ResourceLocation> createCrossTextures(String planetId, ResourceLocation source) {
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(source);
            if (resource.isEmpty()) return List.of(source);
            try (InputStream stream = resource.get().open(); NativeImage sourceImage = NativeImage.read(stream)) {
                int width = sourceImage.getWidth();
                int height = sourceImage.getHeight();
                int[] pixels = new int[width * height];
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int abgr = sourceImage.getPixelRGBA(x, y);
                    pixels[y * width + x] = FastColor.ARGB32.color(FastColor.ABGR32.alpha(abgr),
                            FastColor.ABGR32.red(abgr), FastColor.ABGR32.green(abgr), FastColor.ABGR32.blue(abgr));
                }
                if (PlanetCubeTextureLayout.isAtlas(width, height)) {
                    List<ResourceLocation> locations = registerAtlasFaces(planetId, pixels, width, height);
                    GENERATED_TEXTURES.put(planetId, locations);
                    return locations;
                }
                int faceSize = PlanetTextureLayout.cubeCrossFaceSize(width, height);
                int[] cross = PlanetTextureLayout.equirectangularToCross(pixels, width, height);
                NativeImage image = new NativeImage(faceSize * 4, faceSize * 3, false);
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                    image.setPixelRGBA(x, y, FastColor.ABGR32.fromArgb32(cross[y * image.getWidth() + x]));
                List<ResourceLocation> locations = registerCrossFaces(planetId, image, faceSize);
                GENERATED_TEXTURES.put(planetId, locations);
                return locations;
            }
        } catch (IOException | RuntimeException exception) {
            logger.warn("Could not convert single planet texture {}", source, exception);
            return List.of(source);
        }
    }

    /** Registers six distinct flat tiles with color-preserving patch seams in Cube face order. */
    private static List<ResourceLocation> registerAtlasFaces(String planetId, int[] pixels, int width, int height) {
        int faceSize = width / 3;
        int[][] faces = PlanetCubeTextureLayout.splitAtlas(pixels, width, height);
        List<ResourceLocation> locations = new java.util.ArrayList<>(6);
        for (int face = 0; face < faces.length; face++) {
            NativeImage image = new NativeImage(faceSize, faceSize, false);
            for (int y = 0; y < faceSize; y++) {
                for (int x = 0; x < faceSize; x++) {
                    image.setPixelRGBA(x, y, FastColor.ABGR32.fromArgb32(faces[face][y * faceSize + x]));
                }
            }
            locations.add(Minecraft.getInstance().getTextureManager().register(
                    "deepspace_planet_" + planetId.replace(':', '_') + "_face_" + face,
                    new DynamicTexture(image)));
        }
        return List.copyOf(locations);
    }

    private static List<ResourceLocation> registerCrossFaces(String planetId, NativeImage cross, int faceSize) {
        List<ResourceLocation> locations = new java.util.ArrayList<>(6);
        int[][] origins = {{faceSize, faceSize * 2}, {faceSize, faceSize}, {0, faceSize},
                {faceSize * 2, faceSize}, {faceSize * 3, faceSize}, {faceSize, 0}};
        for (int face = 0; face < 6; face++) {
            NativeImage faceImage = new NativeImage(faceSize, faceSize, false);
            for (int y = 0; y < faceSize; y++) for (int x = 0; x < faceSize; x++)
                faceImage.setPixelRGBA(x, y, cross.getPixelRGBA(origins[face][0] + x, origins[face][1] + y));
            locations.add(Minecraft.getInstance().getTextureManager().register(
                    "deepspace_planet_" + planetId.replace(':', '_') + "_face_" + face,
                    new DynamicTexture(faceImage)));
        }
        cross.close();
        return List.copyOf(locations);
    }

    /**
     * Reuses the first available surface texture for a distant night-sky representation.
     */
    public static ResourceLocation getSkyTexture(Planet planet) {
        List<ResourceLocation> textures = getSurfaceTextures(planet);
        return textures.isEmpty() ? null : textures.getFirst();
    }

    /** Returns the same one-or-six surface textures bound by the world renderer. */
    public static List<ResourceLocation> getSurfaceTextures(Planet planet) {
        // Converted resource faces and generated maps must use the same bindings in every renderer.
        return GENERATED_TEXTURES.getOrDefault(planet.getId(), planet.getTextures());
    }

    /**
     * Resolves the average visible color across all configured or generated surface textures.
     */
    public static int getAverageSurfaceColor(Planet planet) {
        PlanetSurfaceColorAccumulator colors = new PlanetSurfaceColorAccumulator();
        List<ResourceLocation> locations = getSurfaceTextures(planet);
        var minecraft = Minecraft.getInstance();
        for (ResourceLocation location : locations) {
            var texture = minecraft.getTextureManager().getTexture(location);
            if (texture instanceof DynamicTexture dynamicTexture && dynamicTexture.getPixels() != null) {
                accumulateSurfaceColors(dynamicTexture.getPixels(), colors);
                continue;
            }
            try {
                var resource = minecraft.getResourceManager().getResource(location);
                if (resource.isPresent()) {
                    try (InputStream input = resource.get().open(); NativeImage image = NativeImage.read(input)) {
                        accumulateSurfaceColors(image, colors);
                    }
                }
            } catch (IOException exception) {
                logger.warn("Could not sample surface texture {} for planet {}", location, planet.getId(), exception);
            }
        }
        return colors.toOpaqueArgb(0xFF7F7F7F);
    }

    private static void accumulateSurfaceColors(NativeImage image, PlanetSurfaceColorAccumulator colors) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.addAbgr(image.getPixelRGBA(x, y));
            }
        }
    }

    /**
     * Exports client-side texture pixels and bindings for a user-reportable rendering snapshot.
     */
    public static Path dumpGeneratedTextureDiagnostics() throws IOException {
        Path outputDirectory = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("deepspace-debug")
                .resolve("planet-textures-" + DEBUG_TIMESTAMP.format(LocalDateTime.now()));
        Files.createDirectories(outputDirectory);

        StringBuilder report = new StringBuilder()
                .append("dimension=")
                .append(Minecraft.getInstance().level == null
                        ? "none"
                        : Minecraft.getInstance().level.dimension().location())
                .append('\n')
                .append("shading=")
                .append(ModOptions.options().shadingDetail)
                .append('\n')
                .append("irisShaderPack=")
                .append(IrisIntegration.isShaderPackEnabled())
                .append('\n');

        var textureManager = Minecraft.getInstance().getTextureManager();
        for (Planet planet : PlanetRegistry.getAllPlanets()) {
            List<ResourceLocation> locations = getSurfaceTextures(planet);
            ResourceLocation location = locations.isEmpty() ? null : locations.getFirst();
            var texture = location == null ? null : textureManager.getTexture(location);
            report.append('\n')
                    .append("planet=").append(planet.getId()).append('\n')
                    .append("textures=").append(locations).append('\n')
                    .append("textureClass=").append(texture == null ? "missing" : texture.getClass().getName()).append('\n')
                    .append("textureId=").append(texture == null ? 0 : texture.getId()).append('\n')
                    // Distinguish an unsynchronized placeholder from an uploaded surface map.
                    .append("surfaceMapTier=").append(planet.getGeneratedTextureTier()).append('\n')
                    .append("surfaceMapPixels=").append(planet.getGeneratedSurfaceMap().length).append('\n')
                    .append("textureSeed=").append(planet.getGeneratedTextureSeed()).append('\n')
                    .append("fragmentation=").append(planet.getGeneratedTextureFragmentation()).append('\n')
                    .append("palette=");
            for (Planet.SurfaceColor color : planet.getGeneratedSurfaceColors()) {
                report.append(String.format("#%06X*%d ", color.rgb(), color.weight()));
            }
            report.append('\n');

            if (texture instanceof DynamicTexture dynamicTexture && dynamicTexture.getPixels() != null) {
                NativeImage pixels = dynamicTexture.getPixels();
                Path png = outputDirectory.resolve(planet.getId().replace(':', '_') + ".png");
                pixels.writeToFile(png);
                HashSet<Integer> uniqueColors = new HashSet<>();
                for (int y = 0; y < pixels.getHeight(); y++) {
                    for (int x = 0; x < pixels.getWidth(); x++) {
                        uniqueColors.add(pixels.getPixelRGBA(x, y));
                    }
                }
                report.append("size=").append(pixels.getWidth()).append('x').append(pixels.getHeight()).append('\n')
                        .append("uniquePixelColors=").append(uniqueColors.size()).append('\n')
                        .append("firstPixelABGR=")
                        .append(String.format("0x%08X", pixels.getPixelRGBA(0, 0)))
                        .append('\n')
                        .append("png=").append(png.toAbsolutePath()).append('\n');
            }
        }

        Path reportPath = outputDirectory.resolve("report.txt");
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
        logger.info("Exported planet texture diagnostics to {}", outputDirectory.toAbsolutePath());
        return outputDirectory;
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
        var instance = Minecraft.getInstance();
        if (instance.level == null || PlanetRegistry.getGalaxyByDimension(instance.level.dimension()) == null)
            return;
        if (!stage.equals(SpaceRenderSystem.BACKGROUND_STAGE))
            return;
        boolean irisEnabled = IrisIntegration.isGalaxyLogDepthPhaseActive();
        // AFTER_SKY provides an empty event stack; the frustum matrix locks planets and relays to world space.
        var poseStack = new com.mojang.blaze3d.vertex.PoseStack();
        poseStack.mulPose(new Matrix4f(frustumMatrix));
        poseStack.pushPose();
        var galaxyPlanets = PlanetRegistry.getPlanetsForGalaxy(instance.level.dimension());
        java.util.Set<String> activeRelayIds = new java.util.HashSet<>();
        for (Planet planet : galaxyPlanets) {
            if (planet.isHyperRelay()) {
                if (HyperRelayGeoRenderer.draw(poseStack, planet, camera.getPosition(), projectionMatrix)) {
                    activeRelayIds.add(planet.getId());
                }
            }
        }
        HyperRelayGeoRenderer.updateGalaxyEffects(activeRelayIds);
        // Far-to-near submission remains correct if an Iris program temporarily misses its depth write.
        List<Planet> renderedPlanets = PlanetRegistry.getPlanetsForGalaxy(instance.level.dimension()).stream()
                .filter(planet -> MESHES.containsKey(planet.getId()))
                .sorted(java.util.Comparator.comparingDouble(
                        (Planet planet) -> planet.getCenter().distanceToSqr(camera.getPosition())).reversed())
                .toList();
        for (Planet planet : renderedPlanets) {
            Cube mesh = MESHES.get(planet.getId());
            if (mesh == null) {
                continue;
            }
            var sun = PlanetRegistry.getSunForPlanet(planet);
            if (sun == null) {
                continue;
            }
            var center = sun.getCenter();
            Vector3f sunlightDirection = center.subtract(planet.getCenter()).normalize().toVector3f();
            Vector3f cameraSpaceSunPosition = center.subtract(camera.getPosition()).toVector3f();
            // Planet vertices are camera-space at this stage, so shader lighting must use the same frame.
            poseStack.last().pose().transformPosition(cameraSpaceSunPosition);
            // Both native and Iris render paths shade from the same Deep Space sun position.
            VeilRenderSystem.setShader(PLANET_SHADER)
                    .getUniform("SunPosition")
                    .setVector(cameraSpaceSunPosition);

            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            List<RenderType> renderTypes;
            if (irisEnabled) {
                renderTypes = IRIS_RENDER_TYPES.get(planet.getId());
            } else {
                renderTypes = switch (ModOptions.options().shadingDetail) {
                    case NONE -> UNSHADED_RENDER_TYPES.get(planet.getId());
                    case BASIC, EXPENSIVE -> SHADED_RENDER_TYPES.get(planet.getId());
                };
            }
            renderPlanetFaces(
                    mesh,
                    renderTypes,
                    poseStack,
                    camera.getPosition().toVector3f().mul(-1),
                    new Quaternionf(),
                    irisEnabled ? sunlightDirection : null
            );

            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }
        poseStack.popPose();
    }

    private static void renderPlanetFaces(
            Cube mesh,
            List<RenderType> renderTypes,
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            org.joml.Vector3f position,
            Quaternionf rotation,
            Vector3fc sunlightDirection
    ) {
        if (renderTypes.size() == 1) {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            if (sunlightDirection == null) {
                mesh.renderOutward(poseStack, builder, position, rotation);
            } else {
                mesh.renderOutwardSolarGradient(poseStack, builder, position, rotation, sunlightDirection);
            }
            renderTypes.getFirst().draw(builder.buildOrThrow());
            return;
        }
        for (int face = 0; face < 6; face++) {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            if (sunlightDirection == null) {
                mesh.renderFaceOutward(poseStack, builder, position, rotation, face);
            } else {
                mesh.renderFaceOutwardSolarGradient(poseStack, builder, position, rotation, face, sunlightDirection);
            }
            renderTypes.get(face).draw(builder.buildOrThrow());
        }
    }

    public static void init() {
        refreshMeshes();
        SpaceRenderSystem.registerRenderer(PlanetRenderer::render, SpaceRenderSystem.BACKGROUND_STAGE);
        // This just causes more problems than it resolves
//        SpaceRenderSystem.registerRenderer(PlanetRenderer::render, VeilRenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS);

    }
    private static Matrix4f projectionMatrix(double fov, GameRenderer gameRenderer) {
        Matrix4f mat = new Matrix4f();
        return mat.perspective(
                (float)(fov * (float)(Math.PI / 180.0)),
                (float)gameRenderer.getMinecraft().getWindow().getWidth() / (float)gameRenderer.getMinecraft().getWindow().getHeight(),
                0.05f,
                gameRenderer.getDepthFar() * 4f
        );
    }
}

