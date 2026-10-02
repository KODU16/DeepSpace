package world.landfall.deepspace.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.integration.IrisIntegration;
import world.landfall.deepspace.planet.GalaxyDimensions;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;

/**
 * Renders the NASA Elsewhere star map as a camera-centred, full-bright space skybox.
 */
public final class SpaceSkyboxRenderer {
    private static final ResourceLocation SPACE = Deepspace.path("space");
    private static final float GALACTIC_CORE_TO_ZENITH_RADIANS = (float) Math.toRadians(135.0);
    private static final Face[] FACES = {
            new Face("north", 0.0F, 0.0F, 1.0F),
            new Face("south", 0.0F, 0.0F, -1.0F),
            new Face("west", 1.0F, 0.0F, 0.0F),
            new Face("east", -1.0F, 0.0F, 0.0F),
            new Face("up", 0.0F, -1.0F, 0.0F),
            new Face("down", 0.0F, 1.0F, 0.0F)
    };
    private static final RenderType[] NATIVE_RENDER_TYPES = createNativeRenderTypes();
    private static final RenderType[] IRIS_RENDER_TYPES = createIrisRenderTypes();
    private static final RenderType[] NATIVE_FADE_RENDER_TYPES = createFadeRenderTypes(false);
    private static final RenderType[] IRIS_FADE_RENDER_TYPES = createFadeRenderTypes(true);
    private static final StaticRenderMesh[] NATIVE_MESHES = new StaticRenderMesh[FACES.length];
    private static final StaticRenderMesh[] IRIS_MESHES = new StaticRenderMesh[FACES.length];

    private SpaceSkyboxRenderer() {
    }

    public static void init() {
        SpaceRenderSystem.registerRenderer(
                SpaceSkyboxRenderer::render,
                SpaceRenderSystem.BACKGROUND_STAGE
        );
    }

    private static RenderType createNativeRenderType(ResourceLocation texture) {
        var state = RenderType.CompositeState.builder()
                // The native sky path must not inherit entity lighting or fog state.
                .setShaderState(RenderStateShard.POSITION_TEX_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                .setCullState(RenderStateShard.NO_CULL)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setOutputState(RenderStateShard.MAIN_TARGET)
                .createCompositeState(true);
        return RenderType.create(
                "deepspace_space_skybox_native_" + texture.getPath().replace('/', '_'),
                DefaultVertexFormat.POSITION_TEX,
                VertexFormat.Mode.TRIANGLES,
                512,
                false,
                false,
                state
        );
    }

    private static RenderType createIrisRenderType(ResourceLocation texture) {
        var state = RenderType.CompositeState.builder()
                // Iris recognizes the entity-solid program and preserves the full-bright texture.
                .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_SOLID_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                .setCullState(RenderStateShard.NO_CULL)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        return RenderType.create(
                "deepspace_space_skybox_iris_" + texture.getPath().replace('/', '_'),
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                512,
                false,
                false,
                state
        );
    }

    private static RenderType[] createNativeRenderTypes() {
        RenderType[] renderTypes = new RenderType[FACES.length];
        for (int index = 0; index < FACES.length; index++) {
            renderTypes[index] = createNativeRenderType(FACES[index].texture());
        }
        return renderTypes;
    }

    private static RenderType[] createIrisRenderTypes() {
        RenderType[] renderTypes = new RenderType[FACES.length];
        for (int index = 0; index < FACES.length; index++) {
            renderTypes[index] = createIrisRenderType(FACES[index].texture());
        }
        return renderTypes;
    }

    /** Reuses the opaque skybox meshes with alpha blending during atmosphere exit. */
    private static RenderType[] createFadeRenderTypes(boolean irisEnabled) {
        RenderType[] renderTypes = new RenderType[FACES.length];
        for (int index = 0; index < FACES.length; index++) {
            ResourceLocation texture = FACES[index].texture();
            var state = RenderType.CompositeState.builder()
                    .setShaderState(irisEnabled
                            ? RenderStateShard.RENDERTYPE_ENTITY_SOLID_SHADER
                            : RenderStateShard.POSITION_TEX_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setLightmapState(irisEnabled ? RenderStateShard.LIGHTMAP : RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(irisEnabled ? RenderStateShard.OVERLAY : RenderStateShard.NO_OVERLAY)
                    .setOutputState(irisEnabled ? IrisIntegration.IRIS_TARGET : RenderStateShard.MAIN_TARGET)
                    .createCompositeState(true);
            renderTypes[index] = RenderType.create(
                    "deepspace_space_skybox_fade_" + (irisEnabled ? "iris_" : "native_") + index,
                    irisEnabled ? DefaultVertexFormat.NEW_ENTITY : DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.TRIANGLES,
                    512,
                    false,
                    false,
                    state
            );
        }
        return renderTypes;
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
        if (level == null) {
            return;
        }
        if (!GalaxyDimensions.isGalaxy(level.dimension()) && !isAirlessPlanetSurface(level.dimension())) {
            return;
        }

        drawSkybox(frustumMatrix, projectionMatrix, 1.0F, false);
    }

    /** Blends the galaxy skybox over an atmospheric planet as the exit boundary approaches. */
    static void renderTransition(Matrix4fc frustumMatrix, Matrix4fc projectionMatrix, float alpha) {
        if (alpha <= 0.0F || Minecraft.getInstance().level == null
                || PlanetRegistry.getPlanetByDimension(Minecraft.getInstance().level.dimension()) == null) {
            return;
        }
        drawSkybox(frustumMatrix, projectionMatrix, alpha, true);
    }

    private static void drawSkybox(Matrix4fc frustumMatrix, Matrix4fc projectionMatrix, float alpha, boolean fading) {
        // Keep every cube corner inside the far plane; 0.5 * far is conservative for sqrt(3) corners.
        float radius = Math.max(16.0F, Minecraft.getInstance().gameRenderer.getDepthFar() * 0.45F);
        boolean irisEnabled = IrisIntegration.isShaderPackEnabled();
        // AFTER_SKY supplies an empty event stack; frustumMatrix contains the world-locked camera rotation.
        Matrix4f pose = new Matrix4f(frustumMatrix)
                .rotateZ(GALACTIC_CORE_TO_ZENITH_RADIANS)
                .scale(radius);
        Matrix4f projection = new Matrix4f(projectionMatrix);
        RenderType[] renderTypes = fading
                ? (irisEnabled ? IRIS_FADE_RENDER_TYPES : NATIVE_FADE_RENDER_TYPES)
                : (irisEnabled ? IRIS_RENDER_TYPES : NATIVE_RENDER_TYPES);
        // 天空盒在远裁剪面附近，原版雾会把它染成纯黑，所以绘制期间关掉雾。
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        float[] shaderColor = RenderSystem.getShaderColor().clone();
        RenderSystem.setShaderFogStart(1_000_000.0F);
        RenderSystem.setShaderFogEnd(1_000_001.0F);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        try {
            for (int faceIndex = 0; faceIndex < FACES.length; faceIndex++) {
                mesh(faceIndex, irisEnabled).draw(
                        renderTypes[faceIndex], pose, projection
                );
            }
        } finally {
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
        }
    }

    private static StaticRenderMesh mesh(int faceIndex, boolean irisEnabled) {
        StaticRenderMesh[] meshes = irisEnabled ? IRIS_MESHES : NATIVE_MESHES;
        StaticRenderMesh mesh = meshes[faceIndex];
        if (mesh == null) {
            VertexFormat format = irisEnabled ? DefaultVertexFormat.NEW_ENTITY : DefaultVertexFormat.POSITION_TEX;
            Face face = FACES[faceIndex];
            // Unit faces remain resident; view rotation and far-plane radius stay in the draw matrix.
            mesh = new StaticRenderMesh(
                    format,
                    VertexFormat.Mode.TRIANGLES,
                    1024,
                    builder -> addFace(builder, face, irisEnabled)
            );
            meshes[faceIndex] = mesh;
        }
        return mesh;
    }

    /** 无大气星球地表也复用太空星空盒，模拟没有大气散射时的黑色星空。 */
    private static boolean isAirlessPlanetSurface(ResourceKey<Level> dimension) {
        Planet planet = PlanetRegistry.getPlanetByDimension(dimension);
        // Ring worlds retain their dimension's native sky; their remote segments are an additive layer.
        return planet != null && !planet.isRingWorldEdge() && !planet.hasAtmosphere();
    }

    /**
     * Uses UV corner samples that exactly match the conversion script's shared cube edges.
     */
    private static void addFace(
            BufferBuilder builder,
            Face face,
            boolean irisEnabled
    ) {
        float[][] corners = switch (face.name()) {
            case "north" -> new float[][] {
                    {-1.0F, 1.0F, -1.0F}, {-1.0F, -1.0F, -1.0F},
                    {1.0F, -1.0F, -1.0F}, {1.0F, 1.0F, -1.0F}
            };
            case "south" -> new float[][] {
                    {-1.0F, 1.0F, 1.0F}, {-1.0F, -1.0F, 1.0F},
                    {1.0F, -1.0F, 1.0F}, {1.0F, 1.0F, 1.0F}
            };
            case "west" -> new float[][] {
                    {-1.0F, 1.0F, -1.0F}, {-1.0F, -1.0F, -1.0F},
                    {-1.0F, -1.0F, 1.0F}, {-1.0F, 1.0F, 1.0F}
            };
            case "east" -> new float[][] {
                    {1.0F, 1.0F, -1.0F}, {1.0F, -1.0F, -1.0F},
                    {1.0F, -1.0F, 1.0F}, {1.0F, 1.0F, 1.0F}
            };
            case "up" -> new float[][] {
                    {-1.0F, 1.0F, -1.0F}, {-1.0F, 1.0F, 1.0F},
                    {1.0F, 1.0F, 1.0F}, {1.0F, 1.0F, -1.0F}
            };
            case "down" -> new float[][] {
                    {-1.0F, -1.0F, -1.0F}, {-1.0F, -1.0F, 1.0F},
                    {1.0F, -1.0F, 1.0F}, {1.0F, -1.0F, -1.0F}
            };
            default -> throw new IllegalStateException("Unknown skybox face: " + face.name());
        };

        int[] order = {0, 1, 2, 0, 2, 3};
        float[][] uvs = {{0.0F, 0.0F}, {0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}};
        for (int index : order) {
            float[] corner = corners[index];
            if (irisEnabled) {
                builder.addVertex(corner[0], corner[1], corner[2])
                        .setColor(0xFFFFFFFF)
                        .setUv(uvs[index][0], uvs[index][1])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightTexture.FULL_BRIGHT)
                        .setNormal(face.normalX(), face.normalY(), face.normalZ());
            } else {
                builder.addVertex(corner[0], corner[1], corner[2])
                        .setUv(uvs[index][0], uvs[index][1]);
            }
        }
    }

    private record Face(
            String name,
            float normalX,
            float normalY,
            float normalZ,
            ResourceLocation texture
    ) {
        private Face(String name, float normalX, float normalY, float normalZ) {
            this(
                    name,
                    normalX,
                    normalY,
                    normalZ,
                    // NativeImage uses stb_image, so JPEG keeps 4K detail without inflating the JAR.
                    Deepspace.path("textures/environment/space_skybox_" + name + ".jpg")
            );
        }
    }
}
