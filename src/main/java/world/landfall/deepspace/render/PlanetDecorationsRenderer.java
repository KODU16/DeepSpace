package world.landfall.deepspace.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.logging.LogUtils;
import foundry.veil.Veil;
import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
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
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.ModOptions;
import world.landfall.deepspace.integration.DeepspaceOptions;
import world.landfall.deepspace.integration.IrisIntegration;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.Sun;
import world.landfall.deepspace.render.shapes.Cube;

import java.awt.*;
import java.util.HashMap;
import java.util.List;

public class PlanetDecorationsRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HashMap<String, Atmosphere> ATMOSPHERE_MESHES = new HashMap<>();
    private static final ResourceLocation ATMOSPHERE_SHADER = Deepspace.path("atmosphere");
    private static final ResourceLocation ATMOSPHERE_BLOOM_SHADER = Deepspace.path("atmosphere_bloom");
    private static final ResourceLocation ATMOSPHERE_CLOUD_SHADER = Deepspace.path("atmosphere_cloud");
    private static final ResourceLocation ATMOSPHERE_TEXTURE = Deepspace.path("textures/atmosphere.png");
    private static final ResourceLocation WHITE_TEXTURE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    private static final boolean RENDER_ATMOSPHERE_CLOUDS = true;
    private static final RenderStateShard.ShaderStateShard ATMOSPHERE_RENDER_TYPE = new RenderStateShard.ShaderStateShard(() -> {
        ShaderProgram shader = VeilRenderSystem.setShader(ATMOSPHERE_SHADER);
        return VeilRenderBridge.toShaderInstance(shader);
    });
    private static final RenderStateShard.ShaderStateShard ATMOSPHERE_CLOUD_RENDER_TYPE = new RenderStateShard.ShaderStateShard(() -> {
        ShaderProgram shader = VeilRenderSystem.setShader(ATMOSPHERE_CLOUD_SHADER);
        shader.setTexture("Sampler0", ATMOSPHERE_TEXTURE);
        return VeilRenderBridge.toShaderInstance(shader);
    });
    private static final RenderStateShard.ShaderStateShard ATMOSPHERE_BLOOM_RENDER_TYPE = new RenderStateShard.ShaderStateShard(() -> {
        ShaderProgram shader = VeilRenderSystem.setShader(ATMOSPHERE_BLOOM_SHADER);
        return VeilRenderBridge.toShaderInstance(shader);
    });
    private static final HashMap<String, Asteroids> ASTEROIDS = new HashMap<>();
    private static final HashMap<String, ResourceLocation> ASTEROID_PARTICLE_EFFECTS = new HashMap<>();
    private static final List<ResourceLocation> PARTICLE_EFFECT_POOL = List.of(
            ResourceLocation.parse("deepspace:asteroid_particle")
    );

    public static void refreshMeshes() {
        ATMOSPHERE_MESHES.clear();
        ASTEROIDS.clear();
        ASTEROID_PARTICLE_EFFECTS.clear();
        CelestialRenderDiagnostics.resetAtmosphereColors();
        for (var x : PlanetRegistry.getAllPlanets()) {
            // 环世界棱的大气由棱体自身的行星天空承担，不再绘制环绕方块的大气壳。
            if (x.isRingWorldEdge()) {
                continue;
            }
            var decorations = x.getDecorations();
            if (decorations.isEmpty()) continue;
            for (var decoration : decorations.get()) {
                if (decoration.type().equals(Planet.PlanetDecoration.ATMOSPHERE)) {
                    float haloScale = thinAtmosphereScale(decoration.scale());
                    // Zero is the codec sentinel for deriving the atmosphere from the surface textures.
                    int atmosphereColor = decoration.color() == 0
                            ? x.getGeneratedAtmosphereColor() == 0
                                    ? PlanetRenderer.getAverageSurfaceColor(x)
                                    : 0xFF000000 | x.getGeneratedAtmosphereColor()
                            : decoration.color();
                    CelestialRenderDiagnostics.recordAtmosphereColor(
                            x.getId(),
                            decoration.color() != 0,
                            atmosphereColor
                    );
                    ATMOSPHERE_MESHES.put(x.getId(), new Atmosphere(
                            new Cube(
                                    x.getBoundingBoxMin().toVector3f(),
                                    x.getBoundingBoxMax().toVector3f(),
                                    cloudAtmosphereScale(decoration.scale()),
                                    true
                            ),
                            new Cube(
                                    x.getBoundingBoxMin().toVector3f(),
                                    x.getBoundingBoxMax().toVector3f(),
                                    haloScale,
                                    true
                            ),
                            haloScale,
                            atmosphereColor
                    ));
                } else if (decoration.type().equals(Planet.PlanetDecoration.ASTEROIDS)) {
                    ASTEROIDS.put(x.getId(), new Asteroids(
                            x.getCenter().toVector3f(),
                            decoration.scale(),
                            decoration.color()
                    ));
                    // Choose once per planet so its particle appearance remains stable across frames.
                    int index = Math.floorMod(x.getId().hashCode(), PARTICLE_EFFECT_POOL.size());
                    ASTEROID_PARTICLE_EFFECTS.put(x.getId(), PARTICLE_EFFECT_POOL.get(index));
                }
            }
        }
        VeilRenderSystem.renderer().getParticleManager().clear();
        for (var x : ASTEROIDS.entrySet()) {
            var asteroid = x.getValue();
            ResourceLocation effect = ASTEROID_PARTICLE_EFFECTS.get(x.getKey());
            if (effect == null) continue;
            var emitter = VeilRenderSystem.renderer().getParticleManager().createEmitter(effect);
            if (emitter == null) continue;
            emitter.setPosition(new Vec3(asteroid.pos));
            VeilRenderSystem.renderer().getParticleManager().addParticleSystem(emitter);
        }
    }
    public static void init() {
        refreshMeshes();
        SpaceRenderSystem.registerRenderer(PlanetDecorationsRenderer::render, SpaceRenderSystem.BACKGROUND_STAGE);
    }

    /**
     * 外层色壳略大于云层，让边缘散射能离开地表。
     */
    private static float thinAtmosphereScale(float configuredScale) {
        float thickness = (configuredScale - 1.0F) * 0.70F;
        return 1.0F + Math.max(0.06F, Math.min(0.18F, thickness));
    }
    /**
     * Keeps the textured cloud gas below the optical glow and close to the terrain.
     */
    private static float cloudAtmosphereScale(float configuredScale) {
        float thickness = (configuredScale - 1.0F) * 0.12F;
        return 1.0F + Math.max(0.008F, Math.min(0.022F, thickness));
    }
    private static RenderType atmosphereCloudRenderType() {
        var state = RenderType.CompositeState.builder()
                .setShaderState(ATMOSPHERE_CLOUD_RENDER_TYPE)
                .setTextureState(new RenderStateShard.TextureStateShard(ATMOSPHERE_TEXTURE, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.CullStateShard.NO_CULL)
                .setDepthTestState(GalaxyLogDepth.GREATER_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .createCompositeState(true);
        return RenderType.create(
                "atmosphere_cloud",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432, true, false,
                state
        );
    }

    /**
     * Keeps the density-remapping cloud shader while routing its result into Iris.
     */
    private static RenderType irisAtmosphereCloudRenderType() {
        var state = RenderType.CompositeState.builder()
                .setShaderState(ATMOSPHERE_CLOUD_RENDER_TYPE)
                .setTextureState(new RenderStateShard.TextureStateShard(ATMOSPHERE_TEXTURE, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.CULL)
                .setDepthTestState(GalaxyLogDepth.GREATER_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                // The active Iris shader binds the framebuffer and its bloom-capable attachments.
                .createCompositeState(true);
        return RenderType.create(
                "planet_atmosphere_cloud_iris",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432,
                true,
                false,
                state
        );
    }

    /**
     * Iris 大气必须写与星球相同的对数深度，不能走原版信标着色器。
     */
    private static RenderType irisAtmosphereBloomRenderType() {
        var state = RenderType.CompositeState.builder()
                .setShaderState(ATMOSPHERE_RENDER_TYPE)
                .setTextureState(new RenderStateShard.TextureStateShard(WHITE_TEXTURE, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setDepthTestState(GalaxyLogDepth.GREATER_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setOutputState(CelestialRenderDiagnostics.ATMOSPHERE_PROBE)
                .createCompositeState(true);
        return RenderType.create(
                "planet_atmosphere_bloom_iris",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432,
                true,
                false,
                state
        );
    }

    private static RenderType atmosphereRenderType() {
        var renderType = RenderType.CompositeState.builder()
                .setShaderState(ATMOSPHERE_RENDER_TYPE)
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.CullStateShard.NO_CULL)
                .setDepthTestState(GalaxyLogDepth.GREATER_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .createCompositeState(true);
        return RenderType.create(
                "atmosphere",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432, true, false,
                renderType
        );
    }
    private static RenderType atmosphereGlowRenderType() {
        var bloomState = RenderType.CompositeState.builder()
                .setShaderState(ATMOSPHERE_BLOOM_RENDER_TYPE)
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.CullStateShard.NO_CULL)
                .setDepthTestState(GalaxyLogDepth.GREATER_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .createCompositeState(true);
        var bloomType = RenderType.create(
                "atmosphere_bloom",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                786432, true, false,
                bloomState
        );
        return VeilRenderType.layered(atmosphereRenderType(), bloomType);
    }
    /**
     * Provides a translucent vanilla path for atmospheres under Iris.
     */
    private static RenderType irisDecorationRenderType(
            String name,
            ResourceLocation texture,
            boolean emissive
    ) {
        var state = RenderType.CompositeState.builder()
                .setShaderState(emissive
                        ? RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER
                        : RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setDepthTestState(GalaxyLogDepth.GREATER_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                // Do not replace the framebuffer selected by Iris's entity or spider-eyes program.
                .createCompositeState(true);
        return RenderType.create(
                name,
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
        var instance = Minecraft.getInstance();
        if (instance.level == null || PlanetRegistry.getGalaxyByDimension(instance.level.dimension()) == null)
            return;
        DeepspaceOptions.Detail atmosphereDetail = ModOptions.options().atmosphereDetail;
        if (atmosphereDetail == DeepspaceOptions.Detail.NONE) {
            return;
        }
        var atmosphereRenderType = atmosphereRenderType();
        var atmosphereCloudRenderType = atmosphereCloudRenderType();
        var atmosphereGlowRenderType = atmosphereGlowRenderType();
        var irisAtmosphereRenderType = irisAtmosphereBloomRenderType();
        var irisAtmosphereCloudRenderType = irisAtmosphereCloudRenderType();
        boolean irisEnabled = IrisIntegration.isGalaxyLogDepthPhaseActive();
        // Match the planet body's world-locked AFTER_SKY frame so clouds and halos cannot drift.
        var poseStack = new com.mojang.blaze3d.vertex.PoseStack();
        poseStack.mulPose(new Matrix4f(frustumMatrix));
        poseStack.pushPose();
        List<java.util.Map.Entry<String, Atmosphere>> atmospheres = ATMOSPHERE_MESHES.entrySet().stream()
                .sorted(java.util.Comparator.comparingDouble((java.util.Map.Entry<String, Atmosphere> entry) -> {
                    Planet body = PlanetRegistry.getPlanet(entry.getKey());
                    return body == null ? 0.0D : body.getCenter().distanceToSqr(camera.getPosition());
                }).reversed())
                .toList();
        for (var x : atmospheres) {

            // Planet Atmosphere
            var color = new Color(x.getValue().color);
            var planet = PlanetRegistry.getPlanet(x.getKey());
            if (planet == null || !planet.getGalaxy().equals(instance.level.dimension())) {
                continue;
            }
            var sun = PlanetRegistry.getSunForPlanet(planet);
            // Tesselator exposes one active builder, so each mesh must finish before the next begins.
            MeshData cloudMesh = null;
            if (RENDER_ATMOSPHERE_CLOUDS) {
                BufferBuilder cloudBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                if (irisEnabled) {
                    int cloudColor = FastColor.ARGB32.color(255, color.getRed(), color.getGreen(), color.getBlue());
                    // BSL supplies the Iris cloud lighting, avoiding a second CPU solar tint.
                    x.getValue().cloudCube.renderOutwardTinted(
                            poseStack,
                            cloudBuilder,
                            camera.getPosition().toVector3f().mul(-1),
                            new Quaternionf(),
                            cloudColor
                    );
                } else {
                    x.getValue().cloudCube.render(
                            poseStack,
                            cloudBuilder,
                            camera.getPosition().toVector3f().mul(-1),
                            new Quaternionf()
                    );
                }
                cloudMesh = cloudBuilder.buildOrThrow();
            }

            BufferBuilder atmosphereBuilder = Tesselator.getInstance().begin(
                    VertexFormat.Mode.TRIANGLES,
                    DefaultVertexFormat.NEW_ENTITY
            );
            // Iris 也要画完整色壳：侧影条带在掠射时投影宽度接近 0，外层大气会彻底消失。
            if (irisEnabled) {
                int vertexColor = FastColor.ARGB32.color(180, color.getRed(), color.getGreen(), color.getBlue());
                x.getValue().glowCube.renderOutwardTinted(
                        poseStack,
                        atmosphereBuilder,
                        camera.getPosition().toVector3f().mul(-1),
                        new Quaternionf(),
                        vertexColor
                );
            } else {
                x.getValue().glowCube.render(
                        poseStack,
                        atmosphereBuilder,
                        camera.getPosition().toVector3f().mul(-1),
                        new Quaternionf()
                );
            }
            // During a dimension hand-off the camera can briefly be inside the old space shell.
            // In that view no silhouette triangles are emitted, which is a valid empty mesh.
            var atmosphereMesh = atmosphereBuilder.build();
            if (irisEnabled) {
                CelestialRenderDiagnostics.recordAtmosphereMesh(atmosphereMesh != null);
            }
            // 太阳位置必须与星球顶点同属相机空间，否则昼夜面和地表对不上。
            Vector3f sunPosition = cameraSpaceSunPosition(sun, camera, poseStack);
            if (RENDER_ATMOSPHERE_CLOUDS) {
                VeilRenderSystem.setShader(ATMOSPHERE_CLOUD_SHADER);
                VeilRenderSystem.getShader().getUniform("Time")
                        .setFloat(camera.getPartialTickTime() + renderTick);
                VeilRenderSystem.getShader().getUniform("SunPosition")
                        .setVector(sunPosition);
            }
            setShaderSunPosition(ATMOSPHERE_SHADER, sunPosition);
            setShaderSunPosition(ATMOSPHERE_BLOOM_SHADER, sunPosition);
            // Iris receives its tint from vertices because shader packs may replace ColorModulator.
            RenderSystem.setShaderColor(
                    irisEnabled ? 1f : color.getRed() / 255f,
                    irisEnabled ? 1f : color.getGreen() / 255f,
                    irisEnabled ? 1f : color.getBlue() / 255f,
                    1f
            );
            if (RENDER_ATMOSPHERE_CLOUDS) {
                RenderSystem.setShaderTexture(0, ATMOSPHERE_TEXTURE);
                var cloudType = irisEnabled ? irisAtmosphereCloudRenderType : atmosphereCloudRenderType;
                cloudType.draw(cloudMesh);
            }
            if (atmosphereMesh != null) {
                RenderSystem.setShaderTexture(0, irisEnabled ? WHITE_TEXTURE : ATMOSPHERE_TEXTURE);
                var atmosphereType = irisEnabled
                        ? irisAtmosphereRenderType
                        : atmosphereDetail == DeepspaceOptions.Detail.EXPENSIVE
                                ? atmosphereGlowRenderType
                                : atmosphereRenderType;
                atmosphereType.draw(atmosphereMesh);
            }
            RenderSystem.setShaderColor(1, 1, 1, 1);
        }
        for (var x : ASTEROIDS.entrySet()) {

        }
        poseStack.popPose();

    }
    /** 把恒星位置变到 AFTER_SKY 的相机空间，供大气着色器做朗伯光照。 */
    private static Vector3f cameraSpaceSunPosition(Sun sun, Camera camera, PoseStack poseStack) {
        Vector3f sunPosition = sun == null
                ? new Vector3f(0.0F, 1.0F, 0.0F)
                : sun.getCenter().subtract(camera.getPosition()).toVector3f();
        poseStack.last().pose().transformPosition(sunPosition);
        return sunPosition;
    }

    private static void setShaderSunPosition(ResourceLocation shaderLocation, Vector3f sunPosition) {
        VeilRenderSystem.setShader(shaderLocation)
                .getUniform("SunPosition")
                .setVector(sunPosition);
    }

    private record Atmosphere(Cube cloudCube, Cube glowCube, float scale, int color) {}
    private record Asteroids(Vector3f pos, float scale, int color) {}
}
