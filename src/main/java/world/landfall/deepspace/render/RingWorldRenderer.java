package world.landfall.deepspace.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.integration.IrisIntegration;
import world.landfall.deepspace.planet.Galaxy;
import world.landfall.deepspace.planet.SpaceObjectScale;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.PlanetTextureGenerator;
import world.landfall.deepspace.planet.RingWorldDamage;
import world.landfall.deepspace.planet.RingWorldDimensions;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders one complete GeckoLib ring around its star. Dynamic surface slabs replace
 * the model's four world bones at their exact baked positions to avoid duplicate depth.
 */
public final class RingWorldRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    // Complete GeckoLib frame texture; generated world surfaces remain separate dynamic textures.
    private static final ResourceLocation RING_TEXTURE = Deepspace.path("textures/ring_world_gecko.png");
    private static final ResourceLocation IRREPARABLE_RING_TEXTURE =
            Deepspace.path("textures/ring_world_irreparable.png");
    private static final ResourceLocation REPAIRABLE_RING_TEXTURE =
            Deepspace.path("textures/ring_world_reparable.png");
    private static final int SURFACE_WIDTH = 1055;
    private static final int SURFACE_HEIGHT = 112;
    private static final float RING_MODEL_SCALE = (float) RingWorldDimensions.MODEL_SCALE;
    private static final float WORLD_SURFACE_HALF_LENGTH = (float) RingWorldDimensions.WORLD_SURFACE_HALF_LENGTH;
    private static final float WORLD_SURFACE_HALF_HEIGHT = (float) RingWorldDimensions.SURFACE_HALF_HEIGHT;
    private static final float WORLD_SURFACE_HALF_THICKNESS = (float) RingWorldDimensions.WORLD_SURFACE_HALF_THICKNESS;
    private static final float WORLD_SURFACE_INWARD_OFFSET = (float) RingWorldDimensions.WORLD_SURFACE_INWARD_OFFSET;
    private static final float WORLD_SURFACE_TANGENT_OFFSET = (float) RingWorldDimensions.WORLD_SURFACE_TANGENT_OFFSET;
    private static final float MIN_PROJECTED_RING_DEPTH = 4.0F;
    private static final float DEFAULT_NEAR_PLANE = 0.05F;
    // Keep world-space cockpit HUDs at roughly 0.9 blocks safely beyond the near clip plane.
    private static final float MAXIMUM_RING_NEAR_PLANE = 0.25F;
    private static final SurfacePlacement SURFACE_A = new SurfacePlacement(
            new Vector3f(
                    (float) RingWorldDimensions.NORTH_SURFACE_X,
                    0.0F,
                    (float) RingWorldDimensions.NORTH_SURFACE_Z
            ),
            new Vector3f(1.0F, 0.0F, 0.0F),
            new Vector3f(0.0F, 0.0F, 1.0F)
    );
    private static final SurfacePlacement SURFACE_B = new SurfacePlacement(
            new Vector3f(
                    (float) RingWorldDimensions.WEST_SURFACE_X,
                    0.0F,
                    (float) RingWorldDimensions.WEST_SURFACE_Z
            ),
            new Vector3f(0.0F, 0.0F, -1.0F),
            new Vector3f(1.0F, 0.0F, 0.0F)
    );
    private static final SurfacePlacement SURFACE_C = new SurfacePlacement(
            new Vector3f(
                    (float) RingWorldDimensions.SOUTH_SURFACE_X,
                    0.0F,
                    (float) RingWorldDimensions.SOUTH_SURFACE_Z
            ),
            new Vector3f(-1.0F, 0.0F, 0.0F),
            new Vector3f(0.0F, 0.0F, -1.0F)
    );
    private static final SurfacePlacement SURFACE_D = new SurfacePlacement(
            new Vector3f(
                    (float) RingWorldDimensions.EAST_SURFACE_X,
                    0.0F,
                    (float) RingWorldDimensions.EAST_SURFACE_Z
            ),
            new Vector3f(0.0F, 0.0F, 1.0F),
            new Vector3f(-1.0F, 0.0F, 0.0F)
    );
    // Keep every dynamic surface tile below 32 blocks to prevent distant texture-derivative instability.
    private static final float MAX_SURFACE_TILE_SIZE_BLOCKS = 32.0F;
    // Full block and sky light keep the generated surface readable; vertex tint adds star-facing shading.
    private static final int RING_SURFACE_LIGHT = LightTexture.pack(15, 15);
    // Dim the galaxy frame and generated terrain together: 85% brightness reduced by another 30%.
    private static final float GALAXY_RING_BRIGHTNESS = 0.595F;

    private static final Map<String, ResourceLocation> SURFACE_TEXTURES = new HashMap<>();
    private static final java.util.Set<String> DIRTY_SURFACE_TEXTURES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<ResourceLocation, RenderType> SURFACE_RENDER_TYPES = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> SKY_SURFACE_RENDER_TYPES = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> SKY_FALLBACK_SURFACE_RENDER_TYPES = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> GUI_SURFACE_RENDER_TYPES = new HashMap<>();
    private static final StaticRenderMesh[] SURFACE_MESHES = new StaticRenderMesh[RingWorldDamage.SECTION_COUNT * 2];
    // GUI-only models use strict vanilla depth; world-space ring geometry uses reversed logarithmic depth below.
    private static final RenderStateShard.DepthTestStateShard STRICT_DEPTH_TEST =
            new RenderStateShard.DepthTestStateShard("deepspace_strict_less", GL11.GL_LESS);
    // The visible shell owns both colour and depth; invisible depth passes interfere with world-space HUDs.
    private static final RenderType RING_COLOR_RENDER_TYPE = galaxyRingColorRenderType(
            "deepspace_ring_world_color", RING_TEXTURE
    );
    // Damage frames follow the intact ring's depth policy so their dense cubes keep the same precision.
    private static final RenderType IRREPARABLE_RING_COLOR_RENDER_TYPE = galaxyRingColorRenderType(
            "deepspace_irreparable_ring_world_color", IRREPARABLE_RING_TEXTURE,
            GalaxyLogDepth.GREATER_DEPTH_TEST
    );
    private static final RenderType REPAIRABLE_RING_COLOR_RENDER_TYPE = galaxyRingColorRenderType(
            "deepspace_repairable_ring_world_color", REPAIRABLE_RING_TEXTURE,
            GalaxyLogDepth.GREATER_DEPTH_TEST
    );
    private static final RenderType IRREPARABLE_GUI_RING_COLOR_RENDER_TYPE = guiCutoutType(
            "deepspace_irreparable_gui_ring_world_color", IRREPARABLE_RING_TEXTURE, VertexFormat.Mode.QUADS,
            RingWorldRenderGeometry.cullFrameFaces(), STRICT_DEPTH_TEST
    );
    private static final RenderType REPAIRABLE_GUI_RING_COLOR_RENDER_TYPE = guiCutoutType(
            "deepspace_repairable_gui_ring_world_color", REPAIRABLE_RING_TEXTURE, VertexFormat.Mode.QUADS,
            RingWorldRenderGeometry.cullFrameFaces(), STRICT_DEPTH_TEST
    );
    private static final RenderType SKY_RING_COLOR_RENDER_TYPE = ringColorRenderType(
            "deepspace_ring_world_sky_color", RING_TEXTURE
    );
    private static final RenderType SKY_IRREPARABLE_RING_COLOR_RENDER_TYPE = ringColorRenderType(
            "deepspace_irreparable_ring_world_sky_color",
            IRREPARABLE_RING_TEXTURE,
            GalaxyLogDepth.GREATER_DEPTH_TEST
    );
    private static final RenderType SKY_REPAIRABLE_RING_COLOR_RENDER_TYPE = ringColorRenderType(
            "deepspace_repairable_ring_world_sky_color",
            REPAIRABLE_RING_TEXTURE,
            GalaxyLogDepth.GREATER_DEPTH_TEST
    );
    private static final RenderType SKY_FALLBACK_RING_COLOR_RENDER_TYPE = ringColorRenderType(
            "deepspace_ring_world_sky_fallback", RING_TEXTURE
    );
    private static final RenderType SKY_FALLBACK_IRREPARABLE_RING_COLOR_RENDER_TYPE = ringColorRenderType(
            "deepspace_irreparable_ring_world_sky_fallback",
            IRREPARABLE_RING_TEXTURE,
            GalaxyLogDepth.GREATER_DEPTH_TEST
    );
    private static final RenderType SKY_FALLBACK_REPAIRABLE_RING_COLOR_RENDER_TYPE = ringColorRenderType(
            "deepspace_repairable_ring_world_sky_fallback",
            REPAIRABLE_RING_TEXTURE,
            GalaxyLogDepth.GREATER_DEPTH_TEST
    );
    private static final RenderType GUI_RING_COLOR_RENDER_TYPE = guiCutoutType(
            "deepspace_gui_ring_world_color", RING_TEXTURE, VertexFormat.Mode.QUADS,
            RingWorldRenderGeometry.cullFrameFaces(), STRICT_DEPTH_TEST
    );
    private RingWorldRenderer() {
    }

    private static RenderType ringColorRenderType(String name, ResourceLocation texture) {
        return ringColorRenderType(name, texture, GalaxyLogDepth.GEQUAL_DEPTH_TEST);
    }

    /** Every surface-sky frame writes the same reversed logarithmic depth as its terrain. */
    private static RenderType ringColorRenderType(
            String name,
            ResourceLocation texture,
            RenderStateShard.DepthTestStateShard depthTest
    ) {
        var state = RenderType.CompositeState.builder()
                .setShaderState(GalaxyLogDepth.entityCutoutShader(texture))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, true))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setCullState(RingWorldRenderGeometry.cullFrameFaces()
                        ? RenderStateShard.CULL
                        : RenderStateShard.NO_CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setDepthTestState(depthTest)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                // Share Iris' active colour/depth target with the star so physical occlusion survives shader packs.
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        return RenderType.create(
                name,
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                786432,
                true,
                false,
                state
        );
    }

    /** Galaxy frames use the same reversed logarithmic depth as their surface slabs. */
    private static RenderType galaxyRingColorRenderType(String name, ResourceLocation texture) {
        return galaxyRingColorRenderType(name, texture, GalaxyLogDepth.GEQUAL_DEPTH_TEST);
    }

    /** Lets broken replacement sections win only when their quantized depth is strictly nearer. */
    private static RenderType galaxyRingColorRenderType(
            String name,
            ResourceLocation texture,
            RenderStateShard.DepthTestStateShard depthTest
    ) {
        var state = RenderType.CompositeState.builder()
                .setShaderState(GalaxyLogDepth.entityCutoutShader(texture))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, true))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setCullState(RingWorldRenderGeometry.cullFrameFaces()
                        ? RenderStateShard.CULL
                        : RenderStateShard.NO_CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setDepthTestState(depthTest)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        return RenderType.create(
                name,
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                786432,
                true,
                false,
                state
        );
    }

    public static void refreshMeshes() {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            RenderSystem.recordRenderCall(RingWorldRenderer::refreshMeshes);
            return;
        }
        var textureManager = Minecraft.getInstance().getTextureManager();
        SURFACE_TEXTURES.values().forEach(textureManager::release);
        SURFACE_TEXTURES.clear();
        DIRTY_SURFACE_TEXTURES.clear();
        SURFACE_RENDER_TYPES.clear();
        SKY_SURFACE_RENDER_TYPES.clear();
        SKY_FALLBACK_SURFACE_RENDER_TYPES.clear();
        GUI_SURFACE_RENDER_TYPES.clear();
        for (int index = 0; index < SURFACE_MESHES.length; index++) {
            if (SURFACE_MESHES[index] != null) {
                SURFACE_MESHES[index].close();
                SURFACE_MESHES[index] = null;
            }
        }
        RingWorldGeoRenderer.clearMeshes();
        BrokenRingWorldGeoRenderer.clearMeshes();
    }

    private static RenderType surfaceRenderType(ResourceLocation texture) {
        return SURFACE_RENDER_TYPES.computeIfAbsent(
                texture,
                key -> galaxyCutoutDepthType(
                        "deepspace_ring_world_surface_" + renderTypeSuffix(key),
                        key,
                        VertexFormat.Mode.TRIANGLES
                )
        );
    }

    private static RenderType galaxyCutoutDepthType(String name, ResourceLocation texture, VertexFormat.Mode mode) {
        var state = RenderType.CompositeState.builder()
                .setShaderState(GalaxyLogDepth.entityCutoutShader(texture))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, true))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setDepthTestState(GalaxyLogDepth.GEQUAL_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        return RenderType.create(name, DefaultVertexFormat.NEW_ENTITY, mode, 786432, true, false, state);
    }

    private static RenderType cutoutType(
            String name,
            ResourceLocation texture,
            VertexFormat.Mode mode,
            boolean mipmapped,
            RenderStateShard.WriteMaskStateShard writeMask
    ) {
        var state = RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_CUTOUT_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, mipmapped))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                // Ring slabs must remain visible from either side while the camera orbits the system.
                .setCullState(RenderStateShard.NO_CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                .setWriteMaskState(writeMask)
                // With no shader pack this preserves the current target; with Iris it binds the star's target.
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        return RenderType.create(
                name,
                DefaultVertexFormat.NEW_ENTITY,
                mode,
                786432,
                true,
                false,
                state
        );
    }

    private static RenderType skySurfaceRenderType(ResourceLocation texture) {
        return SKY_SURFACE_RENDER_TYPES.computeIfAbsent(
                texture,
                key -> skyCutoutType(
                        "deepspace_ring_world_sky_surface_" + renderTypeSuffix(key),
                        key,
                        VertexFormat.Mode.TRIANGLES,
                        true
                )
        );
    }

    private static RenderType skyFallbackSurfaceRenderType(ResourceLocation texture) {
        return SKY_FALLBACK_SURFACE_RENDER_TYPES.computeIfAbsent(
                texture,
                key -> skyCutoutType(
                        "deepspace_ring_world_sky_fallback_surface_" + renderTypeSuffix(key),
                        key,
                        VertexFormat.Mode.TRIANGLES,
                        true
                )
        );
    }

    /** Opaque sky terrain uses controlled brightness without Iris' translucent emissive material. */
    private static RenderType skyCutoutType(
            String name,
            ResourceLocation texture,
            VertexFormat.Mode mode,
            boolean mipmapped
    ) {
        var state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(() -> {
                    ShaderProgram shader = VeilRenderSystem.setShader(Deepspace.path("ring_sky_surface"));
                    shader.setTexture("Sampler0", texture);
                    return VeilRenderBridge.toShaderInstance(shader);
                }))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, mipmapped))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setDepthTestState(GalaxyLogDepth.GEQUAL_DEPTH_TEST)
                // Remote and local slabs must both occlude later ring geometry rather than bleed together.
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        return RenderType.create(name, DefaultVertexFormat.NEW_ENTITY, mode, 786432, true, false, state);
    }

    private static RenderType guiSurfaceRenderType(ResourceLocation texture) {
        return GUI_SURFACE_RENDER_TYPES.computeIfAbsent(
                texture,
                key -> guiCutoutType(
                        "deepspace_gui_ring_world_surface_" + renderTypeSuffix(key),
                        key,
                        VertexFormat.Mode.TRIANGLES,
                        // Star-map rotation can view the slab from either side, so keep generated terrain two-sided.
                        false
                )
        );
    }

    /** GUI ring geometry uses the main target while retaining an opaque depth policy. */
    private static RenderType guiCutoutType(
            String name,
            ResourceLocation texture,
            VertexFormat.Mode mode,
            boolean cullFaces
    ) {
        return guiCutoutType(name, texture, mode, cullFaces, RenderStateShard.LEQUAL_DEPTH_TEST);
    }

    /** GUI frame draws after surface slabs, so equal-depth model panels must not overwrite them. */
    private static RenderType guiCutoutType(
            String name,
            ResourceLocation texture,
            VertexFormat.Mode mode,
            boolean cullFaces,
            RenderStateShard.DepthTestStateShard depthTest
    ) {
        var state = RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_CUTOUT_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setCullState(cullFaces ? RenderStateShard.CULL : RenderStateShard.NO_CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setDepthTestState(depthTest)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                .setOutputState(RenderStateShard.MAIN_TARGET)
                .createCompositeState(true);
        return RenderType.create(
                name,
                DefaultVertexFormat.NEW_ENTITY,
                mode,
                786432,
                true,
                false,
                state
        );
    }

    private static String renderTypeSuffix(ResourceLocation texture) {
        return texture.toString().replace(':', '_').replace('/', '_');
    }

    private static ResourceLocation getSurfaceTexture(Planet planet) {
        if (DIRTY_SURFACE_TEXTURES.remove(planet.getId())) {
            ResourceLocation stale = SURFACE_TEXTURES.remove(planet.getId());
            if (stale != null) {
                Minecraft.getInstance().getTextureManager().release(stale);
                SURFACE_RENDER_TYPES.remove(stale);
                SKY_SURFACE_RENDER_TYPES.remove(stale);
                SKY_FALLBACK_SURFACE_RENDER_TYPES.remove(stale);
                GUI_SURFACE_RENDER_TYPES.remove(stale);
            }
        }
        ResourceLocation existing = SURFACE_TEXTURES.get(planet.getId());
        if (existing != null) {
            return existing;
        }
        ResourceLocation created = createSurfaceTexture(planet);
        SURFACE_TEXTURES.put(planet.getId(), created);
        return created;
    }

    /** Rebuild one ring texture lazily after the server publishes new scan data. */
    public static void invalidateSurfaceTexture(String planetId) {
        DIRTY_SURFACE_TEXTURES.add(planetId);
    }

    private static ResourceLocation createSurfaceTexture(Planet planet) {
        int[] pixels = createConfiguredSurfacePixels(planet);
        if (pixels == null) {
            // Resample only the texture data; sky-ring geometry, placement, and UVs stay unchanged.
            pixels = PlanetTextureGenerator.generateMapPixels(
                    planet.getGeneratedSurfaceMap(), SURFACE_WIDTH, SURFACE_HEIGHT, true);
            if (pixels.length == 0) {
                pixels = PlanetTextureGenerator.generatePixels(
                    planet.getGeneratedTextureSeed(),
                    planet.getGeneratedSurfaceColors(),
                    planet.getGeneratedTextureFragmentation(),
                    SURFACE_WIDTH,
                    SURFACE_HEIGHT
                );
            }
        }
        NativeImage image = new NativeImage(SURFACE_WIDTH, SURFACE_HEIGHT, false);
        for (int y = 0; y < SURFACE_HEIGHT; y++) {
            for (int x = 0; x < SURFACE_WIDTH; x++) {
                image.setPixelRGBA(x, y, FastColor.ABGR32.fromArgb32(pixels[y * SURFACE_WIDTH + x]));
            }
        }
        MipmappedDynamicTexture dynamicTexture = new MipmappedDynamicTexture(image);
        ResourceLocation location = Deepspace.path(
                "generated/ring_surface/" + Long.toUnsignedString(planet.getGeneratedTextureSeed(), 16)
        );
        Minecraft.getInstance().getTextureManager().register(location, dynamicTexture);
        return location;
    }

    /** Uses authored ring strips directly and stitches six configured world faces into one strip. */
    private static int[] createConfiguredSurfacePixels(Planet planet) {
        List<ResourceLocation> textures = planet.getTextures();
        if (textures.size() != 1 && textures.size() != 6) {
            return null;
        }
        if (textures.size() == 1 && planet.isRingWorldEdge()) {
            return readRingStripPixels(planet, textures.getFirst());
        }
        List<NativeImage> faces = new ArrayList<>(6);
        try {
            for (int face = 0; face < 6; face++) {
                ResourceLocation location = textures.get(textures.size() == 1 ? 0 : face);
                var resource = Minecraft.getInstance().getResourceManager().getResource(location)
                        .orElseThrow(() -> new IOException("Missing texture " + location));
                try (InputStream input = resource.open()) {
                    faces.add(NativeImage.read(input));
                }
            }
            int[] pixels = new int[SURFACE_WIDTH * SURFACE_HEIGHT];
            for (int y = 0; y < SURFACE_HEIGHT; y++) {
                for (int x = 0; x < SURFACE_WIDTH; x++) {
                    int face = RingWorldTextureProjection.stitchedFace(x, SURFACE_WIDTH);
                    NativeImage source = faces.get(face);
                    var sample = RingWorldTextureProjection.stitchedSample(
                            x, y, SURFACE_WIDTH, SURFACE_HEIGHT,
                            source.getWidth(), source.getHeight()
                    );
                    pixels[y * SURFACE_WIDTH + x] = abgrToArgb(
                            sampleBilinear(source, sample.u(), sample.v())
                    );
                }
            }
            return pixels;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not stitch configured ring texture for {}", planet.getId(), exception);
            return null;
        } finally {
            faces.forEach(NativeImage::close);
        }
    }

    /** Resamples an authored panoramic strip without applying spherical or cube-map distortion. */
    private static int[] readRingStripPixels(Planet planet, ResourceLocation location) {
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(location)
                    .orElseThrow(() -> new IOException("Missing texture " + location));
            try (InputStream input = resource.open(); NativeImage image = NativeImage.read(input)) {
                int[] pixels = new int[SURFACE_WIDTH * SURFACE_HEIGHT];
                for (int y = 0; y < SURFACE_HEIGHT; y++) {
                    double v = SURFACE_HEIGHT == 1 ? 0.0D : (double) y / (SURFACE_HEIGHT - 1);
                    for (int x = 0; x < SURFACE_WIDTH; x++) {
                        double u = SURFACE_WIDTH == 1 ? 0.0D : (double) x / (SURFACE_WIDTH - 1);
                        pixels[y * SURFACE_WIDTH + x] = abgrToArgb(sampleBilinear(image, u, v));
                    }
                }
                return pixels;
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not load authored ring texture for {}", planet.getId(), exception);
            return null;
        }
    }

    private static int sampleBilinear(NativeImage image, double u, double v) {
        double pixelX = u * (image.getWidth() - 1);
        double pixelY = v * (image.getHeight() - 1);
        int x0 = (int) Math.floor(pixelX);
        int y0 = (int) Math.floor(pixelY);
        int x1 = Math.min(image.getWidth() - 1, x0 + 1);
        int y1 = Math.min(image.getHeight() - 1, y0 + 1);
        double tx = pixelX - x0;
        double ty = pixelY - y0;
        return blendAbgr(
                image.getPixelRGBA(x0, y0),
                image.getPixelRGBA(x1, y0),
                image.getPixelRGBA(x0, y1),
                image.getPixelRGBA(x1, y1),
                tx,
                ty
        );
    }

    private static int blendAbgr(int topLeft, int topRight, int bottomLeft, int bottomRight,
                                 double tx, double ty) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            double top = ((topLeft >>> shift) & 0xFF) * (1.0 - tx)
                    + ((topRight >>> shift) & 0xFF) * tx;
            double bottom = ((bottomLeft >>> shift) & 0xFF) * (1.0 - tx)
                    + ((bottomRight >>> shift) & 0xFF) * tx;
            result |= ((int) Math.round(top * (1.0 - ty) + bottom * ty)) << shift;
        }
        return result;
    }

    private static int abgrToArgb(int color) {
        return (color & 0xFF00FF00) | ((color & 0xFF) << 16) | ((color >>> 16) & 0xFF);
    }

    /** Draws before the opaque star in their shared target so rear ring fragments cannot repaint it. */
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
        if (stage != SpaceRenderSystem.BACKGROUND_STAGE || instance.level == null) {
            return;
        }
        var galaxy = PlanetRegistry.getGalaxyByDimension(instance.level.dimension());
        if (galaxy == null) {
            return;
        }
        Planet observer = PlanetRegistry.getPlanetByDimension(instance.level.dimension());
        if (observer != null && observer.isRingWorldEdge()) {
            // RingWorldSkyRenderer already draws the remote and local sky sections for surface observers.
            return;
        }
        Vec3 starCenter = galaxy.sun().getCenter();
        // Use the same camera-rotation frame as the vanilla sky so yaw/pitch affect world-space ring positions.
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(new Matrix4f(frustumMatrix));
        var ringEdges = PlanetRegistry.getPlanetsForGalaxy(galaxy.dimension()).stream()
                .filter(Planet::isRingWorldEdge)
                .toList();
        Vec3 cameraPosition = camera.getPosition();
        if (ringEdges.isEmpty() && galaxy.brokenRingSections() == 0) {
            return;
        }
        poseStack.pushPose();
        FogRange fog = disableFog();
        float previousScale = GalaxyLogDepth.setGeometryScale(
                SunRenderer.galaxyRenderScale(starCenter.distanceTo(cameraPosition), true));
        try {
            // Match the star's camera compression so front and rear ring fragments retain physical depth.
            float galaxyScale = SunRenderer.galaxyRenderScale(starCenter.distanceTo(cameraPosition), true);
            poseStack.scale(galaxyScale, galaxyScale, galaxyScale);
            renderCompleteRing(
                    poseStack,
                    bufferSource,
                    galaxy,
                    ringEdges,
                    starCenter,
                    cameraPosition,
                    false,
                    false,
                    0
            );
            bufferSource.endBatch();
        } finally {
            // Flush the galaxy batch before restoring the depth scale for later bodies.
            GalaxyLogDepth.setGeometryScale(previousScale);
            restoreFog(fog);
            poseStack.popPose();
        }
    }

    public static void init() {
        var textureManager = Minecraft.getInstance().getTextureManager();
        // Dedicated mip chains fix distant ring shimmer without subdividing static geometry every frame.
        textureManager.register(RING_TEXTURE, new MipmappedTexture(RING_TEXTURE));
        textureManager.register(IRREPARABLE_RING_TEXTURE, new MipmappedTexture(IRREPARABLE_RING_TEXTURE));
        textureManager.register(REPAIRABLE_RING_TEXTURE, new MipmappedTexture(REPAIRABLE_RING_TEXTURE));
        SpaceRenderSystem.registerRenderer(
                RingWorldRenderer::render,
                SpaceRenderSystem.BACKGROUND_STAGE
        );
    }

    /** Keeps the ring in normal world scale while preserving the existing draw order. */
    static float spaceProjectionScale(Vec3 camera, Galaxy galaxy, List<Planet> ringEdges) {
        return 1.0F;
    }

    /** Draws one complete physical ring in the star-map GUI instead of four planet-shaped stand-ins. */
    public static void renderGuiRingWorld(
            GuiGraphics graphics,
            Galaxy galaxy,
            List<Planet> ringEdges,
            int centerX,
            int centerY,
            float worldScale,
            float depthLayer,
            Quaternionf viewRotation
    ) {
        if (ringEdges.isEmpty() && galaxy.brokenRingSections() == 0) {
            return;
        }
        graphics.flush();
        RenderSystem.enableDepthTest();
        // Keep the generated surfaces and GeckoLib frame in the GUI's own ordered buffer.
        MultiBufferSource.BufferSource bufferSource = graphics.bufferSource();
        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();
        poseStack.translate(centerX, centerY, depthLayer);
        poseStack.mulPose(new Quaternionf(viewRotation));
        float ringRotation = (float) RingWorldDimensions.ROTATION_DEGREES;
        poseStack.mulPose(Axis.YP.rotationDegrees(ringRotation));

        // Authored frame and surface meshes share the save scale in the system overview.
        worldScale *= (float) SpaceObjectScale.size();
        renderGuiModelSurfaces(poseStack, bufferSource, ringEdges, galaxy.sun().getCenter(), worldScale);
        poseStack.pushPose();
        float modelScale = worldScale * RING_MODEL_SCALE;
        poseStack.scale(modelScale, modelScale, modelScale);
        RingWorldGeoRenderer.drawGuiInterior(
                poseStack,
                GUI_RING_COLOR_RENDER_TYPE,
                RING_SURFACE_LIGHT,
                galaxy.brokenRingSections()
        );
        BrokenRingWorldGeoRenderer.drawSections(
                poseStack,
                IRREPARABLE_GUI_RING_COLOR_RENDER_TYPE,
                REPAIRABLE_GUI_RING_COLOR_RENDER_TYPE,
                RING_SURFACE_LIGHT,
                galaxy.brokenRingSections(),
                galaxy.repairableBrokenSections()
        );
        poseStack.popPose();
        poseStack.popPose();
        RenderSystem.disableDepthTest();
    }

    private static void renderCompleteRing(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            Galaxy galaxy,
            List<Planet> ringEdges,
            Vec3 starCenter,
            Vec3 observerOrigin,
            boolean skyCopy,
            boolean skyFallback,
            int hiddenSections
    ) {
        renderCompleteRing(
                poseStack, bufferSource, galaxy, ringEdges, starCenter, observerOrigin,
                skyCopy, skyFallback, hiddenSections, null
        );
    }

    private static void renderCompleteRing(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            Galaxy galaxy,
            List<Planet> ringEdges,
            Vec3 starCenter,
            Vec3 observerOrigin,
            boolean skyCopy,
            boolean skyFallback,
            int hiddenSections,
            Matrix4fc surfaceProjectionMatrix
    ) {
        if (ringEdges.isEmpty() && galaxy.brokenRingSections() == 0) {
            return;
        }
        float[] previousColor = RenderSystem.getShaderColor().clone();
        poseStack.pushPose();
        try {
            if (!skyCopy) {
                // Preserve alpha and restore the tint after immediate mesh draws to avoid affecting other bodies.
                RenderSystem.setShaderColor(
                        previousColor[0] * GALAXY_RING_BRIGHTNESS,
                        previousColor[1] * GALAXY_RING_BRIGHTNESS,
                        previousColor[2] * GALAXY_RING_BRIGHTNESS,
                        previousColor[3]
                );
            }
            poseStack.translate(
                    starCenter.x - observerOrigin.x,
                    starCenter.y - observerOrigin.y,
                    starCenter.z - observerOrigin.z
            );
            if (!skyCopy) {
                // Resize authored meshes around the ring host center; surface skies retain original geometry.
                float scale = (float) SpaceObjectScale.size();
                poseStack.scale(scale, scale, scale);
            }
            float ringRotation = (float) RingWorldDimensions.ROTATION_DEGREES;
            poseStack.mulPose(Axis.YP.rotationDegrees(ringRotation));
            if (skyCopy && !skyFallback) {
                // Frames and opaque terrain share reversed depth, so each visible face occludes farther geometry.
                renderRingFrames(poseStack, bufferSource, galaxy, true, false, hiddenSections);
                renderModelSurfaces(
                        poseStack, bufferSource, ringEdges, starCenter,
                        true, false, hiddenSections, surfaceProjectionMatrix
                );
            } else if (RingWorldRenderGeometry.surfaceAfterFrame(skyFallback)) {
                renderRingFrames(poseStack, bufferSource, galaxy, skyCopy, true, hiddenSections);
                renderModelSurfaces(
                        poseStack, bufferSource, ringEdges, starCenter,
                        skyCopy, true, hiddenSections, null
                );
            } else {
                // Surface textures resolve before the surrounding opaque frame.
                renderModelSurfaces(
                        poseStack, bufferSource, ringEdges, starCenter,
                        skyCopy, false, hiddenSections, null
                );
                renderRingFrames(poseStack, bufferSource, galaxy, skyCopy, false, hiddenSections);
            }
        } finally {
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            poseStack.popPose();
        }
    }

    private static void renderRingFrames(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            Galaxy galaxy,
            boolean skyCopy,
            boolean skyFallback,
            int hiddenSections
    ) {
        poseStack.pushPose();
        poseStack.scale(RING_MODEL_SCALE, RING_MODEL_SCALE, RING_MODEL_SCALE);
        RenderType frameType = skyCopy
                ? skyFallback ? SKY_FALLBACK_RING_COLOR_RENDER_TYPE : SKY_RING_COLOR_RENDER_TYPE
                : RING_COLOR_RENDER_TYPE;
        RingWorldGeoRenderer.draw(
                poseStack,
                frameType,
                RING_SURFACE_LIGHT,
                galaxy.brokenRingSections() | hiddenSections
        );
        // Broken models must use the same sky depth policy as their replaced healthy section.
        RenderType irreparableFrameType = skyCopy
                ? skyFallback ? SKY_FALLBACK_IRREPARABLE_RING_COLOR_RENDER_TYPE
                : SKY_IRREPARABLE_RING_COLOR_RENDER_TYPE
                : IRREPARABLE_RING_COLOR_RENDER_TYPE;
        RenderType repairableFrameType = skyCopy
                ? skyFallback ? SKY_FALLBACK_REPAIRABLE_RING_COLOR_RENDER_TYPE
                : SKY_REPAIRABLE_RING_COLOR_RENDER_TYPE
                : REPAIRABLE_RING_COLOR_RENDER_TYPE;
        BrokenRingWorldGeoRenderer.drawSections(
                poseStack,
                irreparableFrameType,
                repairableFrameType,
                RING_SURFACE_LIGHT,
                galaxy.brokenRingSections() & ~hiddenSections,
                galaxy.repairableBrokenSections() & ~hiddenSections
        );
        poseStack.popPose();
    }

    /** Draws every sky segment through one anisotropic scale so endpoints stay aligned. */
    static void renderSkySegments(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            Galaxy galaxy,
            Vec3 observerOrigin,
            List<Planet> ringEdges,
            int observerSection,
            Quaternionf physicalToSky,
            Vector3f physicalSun,
            float lateralProjectionScale,
            float depthProjectionScale
    ) {
        Vec3 starCenter = galaxy.sun().getUnscaledCenter();
        poseStack.pushPose();
        FogRange fog = disableFog();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        try {
            // Surface skies have no galaxy-depth owner; initialize the actual target before ring meshes draw.
            IrisIntegration.beginGalaxyLogDepthPhase();
            clearSkyRingDepth(0.0D);
            // Vanilla sky tint/alpha must not discard opaque ring frames in the cutout shader.
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            poseStack.mulPose(physicalToSky);
            poseStack.pushPose();
            applySkyPerspectiveScale(poseStack, physicalSun, lateralProjectionScale, depthProjectionScale);
            renderRemoteSkySegments(
                    poseStack, bufferSource, galaxy, ringEdges, starCenter,
                    observerOrigin, observerSection
            );
            // Keep the local texture on the same projected inner face as remote surface copies.
            renderLocalSkySegment(
                    poseStack, bufferSource, galaxy, ringEdges, starCenter,
                    observerOrigin, observerSection
            );
            poseStack.popPose();
            bufferSource.endBatch();
            // Loaded terrain keeps priority; SunRenderer follows this pass and resolves celestial overlap.
        } finally {
            // Release the isolated reversed attachment before normal surface-sky and terrain passes resume.
            clearSkyRingDepth(1.0D);
            IrisIntegration.endGalaxyLogDepthPhase();
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            restoreFog(fog);
            poseStack.popPose();
        }
    }

    /** Clears the same Iris attachment selected by the ring render types, or the native target. */
    private static void clearSkyRingDepth(double farDepth) {
        boolean iris = IrisIntegration.isShaderPackEnabled();
        if (iris) IrisIntegration.saveAndBindPipelineTarget();
        try {
            // glClear also obeys the depth write mask left by vanilla sky rendering.
            RenderSystem.depthMask(true);
            RenderSystem.clearDepth(farDepth);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        } finally {
            if (iris) IrisIntegration.restorePreviousFramebuffer();
        }
    }

    /** Preserves radial depth while compressing lateral span, giving the sky ring a stronger vanishing shape. */
    private static void applySkyPerspectiveScale(
            PoseStack poseStack,
            Vector3f depthAxis,
            float lateralScale,
            float depthScale
    ) {
        Vector3f axis = new Vector3f(depthAxis);
        if (axis.lengthSquared() <= 1.0E-8F) {
            poseStack.scale(lateralScale, lateralScale, lateralScale);
            return;
        }
        axis.normalize();

        float delta = depthScale - lateralScale;
        float x = axis.x();
        float y = axis.y();
        float z = axis.z();
        Matrix4f scale = new Matrix4f()
                .m00(lateralScale + delta * x * x)
                .m01(delta * x * y)
                .m02(delta * x * z)
                .m10(delta * y * x)
                .m11(lateralScale + delta * y * y)
                .m12(delta * y * z)
                .m20(delta * z * x)
                .m21(delta * z * y)
                .m22(lateralScale + delta * z * z);
        poseStack.mulPose(scale);
    }

    private static void renderRemoteSkySegments(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            Galaxy galaxy,
            List<Planet> ringEdges,
            Vec3 starCenter,
            Vec3 observerOrigin,
            int observerSection
    ) {
        // Use the active AFTER_SKY projection so remote textures share the frame and local segment's clipping.
        renderCompleteRing(
                poseStack, bufferSource, galaxy, ringEdges, starCenter, observerOrigin,
                true, false, 1 << observerSection
            );
    }

    private static void renderLocalSkySegment(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            Galaxy galaxy,
            List<Planet> ringEdges,
            Vec3 starCenter,
            Vec3 observerOrigin,
            int observerSection
    ) {
        int hiddenSections = RingWorldRenderGeometry.skyFallbackHiddenSections(
                observerSection, RingWorldDimensions.SECTION_COUNT
        );
        // Draw the observer section through the standard model-surface path so its texture stays on the inner face.
        renderCompleteRing(
                poseStack, bufferSource, galaxy, ringEdges, starCenter, observerOrigin,
                true, true, hiddenSections
        );
    }

    /** Defers fog beyond every ring segment while retaining depth testing and terrain occlusion. */
    private static FogRange disableFog() {
        FogRange previous = new FogRange(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
        RenderSystem.setShaderFogStart(1_000_000.0F);
        RenderSystem.setShaderFogEnd(1_000_001.0F);
        return previous;
    }

    private static void restoreFog(FogRange fog) {
        RenderSystem.setShaderFogStart(fog.start());
        RenderSystem.setShaderFogEnd(fog.end());
    }

    private record FogRange(float start, float end) {
    }

    private static void renderModelSurfaces(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            List<Planet> ringEdges,
            Vec3 starCenter,
            boolean skyCopy,
            boolean skyFallback,
            int hiddenSections,
            Matrix4fc projectionMatrix
    ) {
        for (Planet planet : ringEdges) {
            // Section identity is authored geometry and must stay stable across size multipliers.
            int sectionIndex = RingWorldDimensions.nearestSectionIndex(
                    planet.getUnscaledCenter().x, planet.getUnscaledCenter().z, starCenter.x, starCenter.z
            );
            if (RingWorldDamage.isBroken(hiddenSections, sectionIndex)) {
                // The local dimension's real terrain replaces its near-zero-depth sky duplicate.
                continue;
            }
            ResourceLocation texture = getSurfaceTexture(planet);
            RenderType surfaceType = skyFallback
                    ? skyFallbackSurfaceRenderType(texture)
                    : skyCopy ? skySurfaceRenderType(texture) : surfaceRenderType(texture);
            if (projectionMatrix == null) {
                surfaceMesh(sectionIndex, false).draw(surfaceType, poseStack);
            } else {
                // Remote sky copies use the same projection matrix as the stage frustum.
                surfaceMesh(sectionIndex, false).draw(surfaceType, poseStack, projectionMatrix);
            }
        }
    }

    /** Uses the same four authored surface placements with GUI scale and GUI-safe render targets. */
    private static void renderGuiModelSurfaces(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            List<Planet> ringEdges,
            Vec3 starCenter,
            float worldScale
    ) {
        poseStack.pushPose();
        poseStack.scale(worldScale, worldScale, worldScale);
        for (Planet planet : ringEdges) {
            int sectionIndex = RingWorldDimensions.nearestSectionIndex(
                    planet.getUnscaledCenter().x, planet.getUnscaledCenter().z, starCenter.x, starCenter.z
            );
            ResourceLocation texture = getSurfaceTexture(planet);
            RenderType surfaceType = guiSurfaceRenderType(texture);
            surfaceMesh(sectionIndex, true).draw(surfaceType, poseStack);
        }
        poseStack.popPose();
    }

    private static SurfacePlacement surfacePlacement(int sectionIndex) {
        // Keep texture placement in the exact Section1/Section4/Section3/Section2 model order.
        return switch (sectionIndex) {
            case 0 -> SURFACE_B;
            case 1 -> SURFACE_C;
            case 2 -> SURFACE_D;
            case 3 -> SURFACE_A;
            default -> throw new IllegalArgumentException("Unknown ring-world section " + sectionIndex);
        };
    }

    private static StaticRenderMesh surfaceMesh(int sectionIndex, boolean guiProjection) {
        int meshIndex = sectionIndex + (guiProjection ? RingWorldDamage.SECTION_COUNT : 0);
        StaticRenderMesh mesh = SURFACE_MESHES[meshIndex];
        if (mesh == null) {
            SurfacePlacement placement = surfacePlacement(sectionIndex);
            // The texture changes per planet, while the four local slab meshes remain static.
            mesh = new StaticRenderMesh(
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.TRIANGLES,
                    262144,
                    builder -> renderModelSurfaceSlab(new PoseStack(), builder, placement, guiProjection)
            );
            SURFACE_MESHES[meshIndex] = mesh;
        }
        return mesh;
    }

    private static void renderModelSurfaceSlab(
            PoseStack poseStack,
            VertexConsumer surfaceBuffer,
            SurfacePlacement placement,
            boolean guiProjection
    ) {
        Vector3f longAxis = placement.longAxis();
        Vector3f inwardAxis = placement.inwardAxis();
        Vector3f upAxis = new Vector3f(0.0F, 1.0F, 0.0F);
        // Both views place terrain by the inner wall; the GUI draws its star-facing side.
        Vector3f slabCenter = new Vector3f(placement.center())
                .add(new Vector3f(longAxis).mul(WORLD_SURFACE_TANGENT_OFFSET))
                .add(new Vector3f(inwardAxis).mul(WORLD_SURFACE_INWARD_OFFSET));
        Vector3f outwardAxis = new Vector3f(inwardAxis).negate();
        Vector3f faceNormal = guiProjection ? inwardAxis : outwardAxis;
        Vector3f frontCenter = new Vector3f(slabCenter).add(new Vector3f(faceNormal).mul(WORLD_SURFACE_HALF_THICKNESS));

        // The GUI texture lies just starward of the inner bone wall; the world mesh keeps its prior face.
        emitSurfaceFace(
                poseStack, surfaceBuffer, frontCenter, longAxis, upAxis, faceNormal,
                WORLD_SURFACE_HALF_LENGTH, WORLD_SURFACE_HALF_HEIGHT, inwardAxis,
                0.0F, 1.0F, 0.0F, 1.0F, guiProjection
        );
    }

    private record SurfacePlacement(Vector3f center, Vector3f longAxis, Vector3f inwardAxis) {
    }

    private static void emitSurfaceFace(
            PoseStack poseStack,
            VertexConsumer buffer,
            Vector3f center,
            Vector3f uAxis,
            Vector3f vAxis,
            Vector3f normal,
            float halfU,
            float halfV,
            Vector3f lightDirection,
            float minU,
            float maxU,
            float minV,
            float maxV,
            boolean guiProjection
    ) {
        Vector3f normalizedNormal = new Vector3f(normal).normalize();
        int colour = sunlightColour(0xFFFFFFFF, normalizedNormal, lightDirection);
        int columns = Math.max(1, (int) Math.ceil(halfU * 2.0F / MAX_SURFACE_TILE_SIZE_BLOCKS));
        int rows = Math.max(1, (int) Math.ceil(halfV * 2.0F / MAX_SURFACE_TILE_SIZE_BLOCKS));
        boolean forwardWinding = new Vector3f(uAxis).cross(vAxis).dot(normalizedNormal) >= 0.0F;
        if (guiProjection) {
            // GUI projection reverses model-space winding on the star-facing texture slab.
            forwardWinding = RingWorldRenderGeometry.guiSurfaceForwardWinding(forwardWinding);
        }

        for (int row = 0; row < rows; row++) {
            float v0Fraction = (float) row / rows;
            float v1Fraction = (float) (row + 1) / rows;
            float localV0 = lerp(-halfV, halfV, v0Fraction);
            float localV1 = lerp(-halfV, halfV, v1Fraction);
            float textureV0 = lerp(maxV, minV, v0Fraction);
            float textureV1 = lerp(maxV, minV, v1Fraction);
            for (int column = 0; column < columns; column++) {
                float u0Fraction = (float) column / columns;
                float u1Fraction = (float) (column + 1) / columns;
                float localU0 = lerp(-halfU, halfU, u0Fraction);
                float localU1 = lerp(-halfU, halfU, u1Fraction);
                float textureU0 = lerp(minU, maxU, u0Fraction);
                float textureU1 = lerp(minU, maxU, u1Fraction);

                Vector3f bottomLeft = facePoint(center, uAxis, vAxis, localU0, localV0);
                Vector3f bottomRight = facePoint(center, uAxis, vAxis, localU1, localV0);
                Vector3f topRight = facePoint(center, uAxis, vAxis, localU1, localV1);
                Vector3f topLeft = facePoint(center, uAxis, vAxis, localU0, localV1);
                emitSurfaceQuad(
                        buffer,
                        bottomLeft, bottomRight, topRight, topLeft,
                        poseStack, colour, normalizedNormal, forwardWinding,
                        textureU0, textureU1, textureV0, textureV1
                );
            }
        }
    }

    private static Vector3f facePoint(Vector3f center, Vector3f uAxis, Vector3f vAxis, float u, float v) {
        return new Vector3f(center).add(new Vector3f(uAxis).mul(u)).add(new Vector3f(vAxis).mul(v));
    }

    private static void emitSurfaceQuad(
            VertexConsumer buffer,
            Vector3f bottomLeft,
            Vector3f bottomRight,
            Vector3f topRight,
            Vector3f topLeft,
            PoseStack poseStack,
            int colour,
            Vector3f normal,
            boolean forwardWinding,
            float minU,
            float maxU,
            float bottomV,
            float topV
    ) {
        if (forwardWinding) {
            emitSurfaceVertex(buffer, bottomLeft, poseStack, colour, minU, bottomV, normal);
            emitSurfaceVertex(buffer, bottomRight, poseStack, colour, maxU, bottomV, normal);
            emitSurfaceVertex(buffer, topRight, poseStack, colour, maxU, topV, normal);
            emitSurfaceVertex(buffer, bottomLeft, poseStack, colour, minU, bottomV, normal);
            emitSurfaceVertex(buffer, topRight, poseStack, colour, maxU, topV, normal);
            emitSurfaceVertex(buffer, topLeft, poseStack, colour, minU, topV, normal);
            return;
        }
        emitSurfaceVertex(buffer, bottomLeft, poseStack, colour, minU, bottomV, normal);
        emitSurfaceVertex(buffer, topLeft, poseStack, colour, minU, topV, normal);
        emitSurfaceVertex(buffer, topRight, poseStack, colour, maxU, topV, normal);
        emitSurfaceVertex(buffer, bottomLeft, poseStack, colour, minU, bottomV, normal);
        emitSurfaceVertex(buffer, topRight, poseStack, colour, maxU, topV, normal);
        emitSurfaceVertex(buffer, bottomRight, poseStack, colour, maxU, bottomV, normal);
    }

    private static float lerp(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }

    private static void emitSurfaceVertex(
            VertexConsumer buffer,
            Vector3f position,
            PoseStack poseStack,
            int colour,
            float u,
            float v,
            Vector3f normal
    ) {
        PoseStack.Pose pose = poseStack.last();
        buffer.addVertex(pose.pose(), position.x(), position.y(), position.z())
                .setColor(colour)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(RING_SURFACE_LIGHT)
                .setNormal(pose, normal.x(), normal.y(), normal.z());
    }

    private static int sunlightColour(int argb, Vector3f normal, Vector3f lightDirection) {
        Vector3f normalizedLight = new Vector3f(lightDirection);
        if (normalizedLight.lengthSquared() == 0.0F) {
            normalizedLight.set(0.0F, 0.0F, 1.0F);
        } else {
            normalizedLight.normalize();
        }
        float diffuse = Math.max(0.0F, normal.dot(normalizedLight));
        float brightness = 0.96F + diffuse * 0.04F;
        int alpha = argb >>> 24;
        int red = Math.min(255, Math.round(((argb >>> 16) & 0xFF) * brightness));
        int green = Math.min(255, Math.round(((argb >>> 8) & 0xFF) * brightness));
        int blue = Math.min(255, Math.round((argb & 0xFF) * brightness));
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

}
