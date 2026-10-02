package world.landfall.deepspace.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.VeilRenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.integration.IrisIntegration;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.Map;

/** Optional Photon implementation; callers must check mod presence before loading this class. */
final class PhotonHyperRelayEffects {
    // Keep optional API types out of the always-loaded relay model renderer.
    private static final ResourceLocation RELAY_FX = ResourceLocation.fromNamespaceAndPath("photon", "hyper_relay");
    private static final ResourceLocation PARTICLE_SHADER = Deepspace.path("galaxy_relay_particle");
    private static final float RELAY_FX_RADIUS = 25.0F;
    private static final float AUTHORED_FX_RADIUS = 8.0F;
    private static final Map<ResourceLocation, RenderType> PARTICLE_RENDER_TYPES = new HashMap<>();
    private static VertexBuffer particleBuffer;
    private static final Map<String, RelayEffect> RELAY_EFFECTS = new HashMap<>();

    private PhotonHyperRelayEffects() {
    }

    static void updateGalaxyEffects(Set<String> activeIds) {
        RELAY_EFFECTS.entrySet().removeIf(entry -> {
            if (activeIds.contains(entry.getKey())) return false;
            entry.getValue().destroy();
            return true;
        });
    }

    /** Draws supported Photon passes before the galaxy depth attachment is released. */
    static void renderGalaxyParticles(Camera camera, Matrix4fc frustumMatrix, Matrix4fc projectionMatrix) {
        if (RELAY_EFFECTS.isEmpty()) return;
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || PlanetRegistry.getGalaxyByDimension(minecraft.level.dimension()) == null) return;

        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        for (RelayEffect effect : RELAY_EFFECTS.values()) {
            effect.renderParticles(camera, partialTick, frustumMatrix, projectionMatrix);
        }
    }

    private static void drawParticles(Queue<IParticle> particles, List<MaterialSetting> materials,
                                      Camera camera, float partialTick, Matrix4fc frustumMatrix,
                                      Matrix4fc projectionMatrix, float distanceScale) {
        if (particles.isEmpty()) return;
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        for (IParticle particle : particles) {
            if (!particle.isRemoved()) particle.render(builder, camera, partialTick);
        }
        MeshData meshData = builder.build();
        if (meshData == null) return;
        if (particleBuffer == null) particleBuffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        particleBuffer.bind();
        try {
            particleBuffer.upload(meshData);
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(frustumMatrix);
            for (MaterialSetting setting : materials) {
                TextureMaterial material = (TextureMaterial) setting.getMaterial();
                RenderType type = PARTICLE_RENDER_TYPES.computeIfAbsent(material.getTexture(),
                        PhotonHyperRelayEffects::createParticleRenderType);
                type.setupRenderState();
                try {
                    // Keep Photon material settings when an FX resource changes its texture or blend.
                    setting.getBlendMode().apply();
                    if (setting.isCull()) RenderSystem.enableCull();
                    else RenderSystem.disableCull();
                    ShaderProgram veilShader = VeilRenderSystem.getShader();
                    var shader = RenderSystem.getShader();
                    if (veilShader == null || shader == null) continue;
                    // Particles use the same physical depth frame as their compressed relay.
                    veilShader.getUniform("GeometryDepthScale").setFloat(distanceScale);
                    veilShader.getUniform("DiscardThreshold").setFloat(material.getDiscardThreshold());
                    var hdr = material.getHdr();
                    veilShader.getUniform("HDR").setVector(hdr);
                    veilShader.getUniform("HDRMode").setInt(material.getHdrMode().mode);
                    particleBuffer.drawWithShader(modelView, new Matrix4f(projectionMatrix), shader);
                } finally {
                    setting.getBlendMode().reset();
                    type.clearRenderState();
                }
            }
        } finally {
            VertexBuffer.unbind();
        }
    }

    static void clearEffects() {
        RELAY_EFFECTS.values().forEach(RelayEffect::destroy);
        RELAY_EFFECTS.clear();
    }

    // Each relay owns an overlapping Photon executor so shared block-cache positions do not suppress effects.
    static void updateEffect(Planet relay, Vec3 cameraPosition, float distanceScale, float yaw) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        FX fx = FXHelper.getFX(RELAY_FX);
        if (fx == null) return;
        // Start newly emitted particles at the same compressed anchor as the visible mesh.
        Vec3 renderedCenter = relay.getCenter().subtract(cameraPosition).scale(distanceScale).add(cameraPosition);
        RelayEffect effect = RELAY_EFFECTS.get(relay.getId());
        if (effect == null || effect.fx != fx || effect.level != level) {
            if (effect != null) effect.destroy();
            effect = new RelayEffect(fx, level, renderedCenter, yaw, distanceScale);
            RELAY_EFFECTS.put(relay.getId(), effect);
        }
        effect.update(renderedCenter, yaw, distanceScale);
    }

    private static final class RelayEffect extends BlockEffectExecutor {
        private final FX fx;
        private final Level level;
        private Vec3 center;
        private float distanceScale;

        private RelayEffect(FX fx, Level level, Vec3 center, float yaw, float distanceScale) {
            super(fx, level, BlockPos.ZERO);
            this.fx = fx;
            this.level = level;
            this.center = center;
            this.distanceScale = distanceScale;
            setAllowMulti(true);
            setCheckState(false);
            // Photon emits immediately in start(); initialize its root at the model center first.
            setOffset(center.toVector3f().sub(0.5F, 0.5F, 0.5F));
            setRotation(effectRotation(yaw));
            setScale(new Vector3f(RELAY_FX_RADIUS / AUTHORED_FX_RADIUS * distanceScale));
            start();
            // Only passes supported by the galaxy shader leave Photon's later world pass.
            if (runtime != null) {
                runtime.objects.values().stream()
                        .filter(ParticleEmitter.class::isInstance)
                        .map(ParticleEmitter.class::cast)
                        .forEach(emitter -> emitter.setSelfVisible(!supportsGalaxyPass(emitter)));
            }
        }

        private void renderParticles(Camera camera, float partialTick, Matrix4fc frustumMatrix,
                                     Matrix4fc projectionMatrix) {
            if (runtime == null) return;
            for (IFXObject object : runtime.objects.values()) {
                if (!(object instanceof ParticleEmitter emitter)) continue;
                boolean supported = supportsGalaxyPass(emitter);
                emitter.setSelfVisible(!supported);
                if (!supported) continue;
                for (var entry : emitter.getParticles().entrySet()) {
                    drawParticles(entry.getValue(), entry.getKey().rendererSetting.getMaterials(),
                            camera, partialTick, frustumMatrix, projectionMatrix, distanceScale);
                }
            }
        }

        private void update(Vec3 center, float yaw, float distanceScale) {
            this.center = center;
            this.distanceScale = distanceScale;
            if (runtime == null || runtime.root == null) return;
            runtime.root.updatePos(center.toVector3f());
            // Keep Photon local +Y aligned with relay local -Z after its galaxy yaw.
            runtime.root.updateRotation(effectRotation(yaw));
            runtime.root.updateScale(new org.joml.Vector3f(
                    RELAY_FX_RADIUS / AUTHORED_FX_RADIUS * distanceScale
            ));
        }

        @Override
        public void updateFXObjectTick(IFXObject fxObject) {
            if (runtime != null && fxObject == runtime.root) {
                fxObject.updatePos(center.toVector3f());
            }
            // The relay lives in a virtual sky coordinate and has no loaded block; avoid BlockEffectExecutor's chunk-state auto-destruction.
        }

        private void destroy() {
            if (runtime != null) runtime.destroy(true);
            var cached = BlockEffectExecutor.CACHE.get(pos);
            if (cached != null) {
                cached.remove(this);
                if (cached.isEmpty()) BlockEffectExecutor.CACHE.remove(pos);
            }
        }
    }

    private static Quaternionf effectRotation(float yaw) {
        // Match the authored effect axis to relay local -Z before applying its yaw.
        return new Quaternionf().rotationY(yaw)
                .mul(new Quaternionf().rotationX((float) (-Math.PI / 2.0D)));
    }

    /** Releases GPU particle resources on mesh/resource refresh. */
    static void clearMesh() {
        if (particleBuffer != null) {
            particleBuffer.close();
            particleBuffer = null;
        }
        PARTICLE_RENDER_TYPES.clear();
    }

    private static boolean supportsGalaxyPass(ParticleEmitter emitter) {
        if (emitter.config.renderer.isUseGPUInstance()) return false;
        for (var entry : emitter.getParticles().entrySet()) {
            PhotonFXRenderPass pass = entry.getKey();
            if (pass.format != DefaultVertexFormat.BLOCK || pass.mode != VertexFormat.Mode.QUADS) return false;
            if (pass.rendererSetting.getMaterials().isEmpty()) return false;
            for (MaterialSetting setting : pass.rendererSetting.getMaterials()) {
                // Specialized Photon materials may use a different shader or atlas contract.
                if (setting.getMaterial() == null
                        || setting.getMaterial().getClass() != TextureMaterial.class) return false;
                TextureMaterial material = (TextureMaterial) setting.getMaterial();
                if (material.getHdr() == null || material.getHdrMode() == null
                        || material.getTexture() == null || material.getPixelArt().isEnable()) return false;
            }
        }
        return true;
    }

    private static RenderType createParticleRenderType(ResourceLocation texture) {
        RenderStateShard.ShaderStateShard shaderState = new RenderStateShard.ShaderStateShard(() -> {
            ShaderProgram shader = VeilRenderSystem.setShader(PARTICLE_SHADER);
            shader.setTexture("Sampler0", texture);
            return VeilRenderBridge.toShaderInstance(shader);
        });
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(shaderState)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setDepthTestState(GalaxyLogDepth.GREATER_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        return RenderType.create("deepspace_relay_particles", DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS, 131072, true, false, state);
    }

}
