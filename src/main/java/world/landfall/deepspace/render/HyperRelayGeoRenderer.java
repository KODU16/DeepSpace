package world.landfall.deepspace.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.integration.IrisIntegration;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;

/** Draws a 100-block relay with world-up Y and its positive Z axis facing the galaxy center. */
final class HyperRelayGeoRenderer extends GeoObjectRenderer<HyperRelayGeoRenderer.StaticHyperRelay> {
    private static final ResourceLocation MODEL = Deepspace.path("geo/hyper_relay.geo.json");
    private static final ResourceLocation TEXTURE = Deepspace.path("textures/hyper_relay.png");
    private static final ResourceLocation UNUSED_ANIMATION = Deepspace.path("animations/hyper_relay.animation.json");
    // Exact unrotated atlas-model bounds measured from the imported Bedrock cubes, in model pixels.
    private static final float MODEL_MAX_EXTENT_PIXELS = 113.66192F;
    private static final float MODEL_CENTER_X_PIXELS = 0.0F;
    private static final float MODEL_CENTER_Y_PIXELS = 0.0F;
    private static final float MODEL_CENTER_Z_PIXELS = -1.016465F;
    private static final StaticHyperRelay ANIMATABLE = new StaticHyperRelay();
    private static final HyperRelayGeoRenderer INSTANCE = new HyperRelayGeoRenderer();
    private static final RenderType RENDER_TYPE = createRenderType();
    private static StaticRenderMesh mesh;

    private HyperRelayGeoRenderer() {
        super(new StaticHyperRelayModel());
    }

    static void draw(PoseStack poseStack, Planet relay, Vec3 cameraPosition, Matrix4fc projectionMatrix) {
        // Keep the longest authored extent at 100 blocks, including relays restored from old saves.
        float scale = 100.0F * 16.0F / MODEL_MAX_EXTENT_PIXELS;
        Vec3 center = relay.getCenter().subtract(cameraPosition);
        double distance = center.length();
        double safeDistance = Math.max(16.0D, Minecraft.getInstance().gameRenderer.getDepthFar() * 0.45D);
        float distanceScale = distance > safeDistance ? (float) (safeDistance / distance) : 1.0F;
        if (distanceScale < 1.0F) {
            center = center.scale(distanceScale);
        }

        poseStack.pushPose();
        poseStack.translate(center.x, center.y, center.z);
        // The primary star anchors the galaxy; ignore height differences to preserve world-up Y.
        var galaxy = PlanetRegistry.getGalaxyByDimension(relay.getGalaxy());
        Vec3 galaxyCenter = galaxy == null ? Vec3.ZERO : galaxy.sun().getCenter();
        Vec3 inward = galaxyCenter.subtract(relay.getCenter());
        float yaw = inward.x * inward.x + inward.z * inward.z < 1.0E-8
                ? 0.0F : (float) Math.atan2(inward.x, inward.z);
        poseStack.mulPose(new Quaternionf().rotationY(yaw));
        poseStack.scale(scale * distanceScale, scale * distanceScale, scale * distanceScale);
        poseStack.translate(
                -MODEL_CENTER_X_PIXELS / 16.0F,
                -MODEL_CENTER_Y_PIXELS / 16.0F,
                -MODEL_CENTER_Z_PIXELS / 16.0F
        );
        // Use the same explicit sky-stage matrix submission as ring-world geometry.
        mesh().draw(RENDER_TYPE, poseStack, projectionMatrix);
        poseStack.popPose();
    }

    static void clearMesh() {
        if (mesh != null) {
            mesh.close();
            mesh = null;
        }
    }

    private static StaticRenderMesh mesh() {
        if (mesh == null) {
            mesh = new StaticRenderMesh(
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    786432,
                    HyperRelayGeoRenderer::buildMesh
            );
        }
        return mesh;
    }

    private static void buildMesh(VertexConsumer buffer) {
        MultiBufferSource bufferSource = ignored -> buffer;
        INSTANCE.render(new PoseStack(), ANIMATABLE, bufferSource, RENDER_TYPE, buffer, 0x00F000F0, 0.0F);
    }

    private static RenderType createRenderType() {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(GalaxyLogDepth.entityCutoutShader(TEXTURE))
                .setTextureState(new RenderStateShard.TextureStateShard(TEXTURE, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                // The imported relay contains mirrored, sub-pixel-thin cubes whose authored winding is not uniform.
                .setCullState(RenderStateShard.NO_CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setDepthTestState(GalaxyLogDepth.GEQUAL_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                // Use the same target as stars and ring worlds so their depth ordering remains physical under Iris.
                .setOutputState(IrisIntegration.IRIS_TARGET)
                .createCompositeState(true);
        return RenderType.create(
                "deepspace_hyper_relay",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                786432,
                true,
                false,
                state
        );
    }

    @Override
    public long getInstanceId(StaticHyperRelay animatable) {
        return 0L;
    }

    @Override
    public void preRender(
            PoseStack poseStack,
            StaticHyperRelay animatable,
            BakedGeoModel model,
            @Nullable MultiBufferSource bufferSource,
            @Nullable VertexConsumer buffer,
            boolean isReRender,
            float partialTick,
            int packedLight,
            int packedOverlay,
            int colour
    ) {
        // The caller centers the authored bounds explicitly; omit GeoObjectRenderer's object translation.
    }

    private static final class StaticHyperRelayModel extends GeoModel<StaticHyperRelay> {
        @Override
        public ResourceLocation getModelResource(StaticHyperRelay animatable) {
            return MODEL;
        }

        @Override
        public ResourceLocation getTextureResource(StaticHyperRelay animatable) {
            return TEXTURE;
        }

        @Override
        public ResourceLocation getAnimationResource(StaticHyperRelay animatable) {
            return UNUSED_ANIMATION;
        }
    }

    static final class StaticHyperRelay implements GeoAnimatable {
        private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
            // The relay is static and has no animation controllers.
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() {
            return this.cache;
        }

        @Override
        public double getTick(Object object) {
            return 0.0D;
        }
    }
}
