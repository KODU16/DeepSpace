package world.landfall.deepspace.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.planet.RingWorldDamage;
import world.landfall.deepspace.planet.RingWorldDimensions;


/** Relocates the compact model's side-authored damage tree onto each broken ring section. */
final class BrokenRingWorldGeoRenderer extends GeoObjectRenderer<BrokenRingWorldGeoRenderer.StaticBrokenRing> {
    private static final ResourceLocation IRREPARABLE_MODEL = Deepspace.path("geo/ring_world_irreparable.geo.json");
    private static final ResourceLocation REPAIRABLE_MODEL = Deepspace.path("geo/ring_world_repairable.geo.json");
    private static final ResourceLocation IRREPARABLE_TEXTURE = Deepspace.path("textures/ring_world_irreparable.png");
    private static final ResourceLocation REPAIRABLE_TEXTURE = Deepspace.path("textures/ring_world_reparable.png");
    private static final ResourceLocation UNUSED_ANIMATION = Deepspace.path("animations/ring_world.animation.json");
    private static final StaticBrokenRing ANIMATABLE = new StaticBrokenRing();
    private static final BrokenRingWorldGeoRenderer IRREPARABLE_INSTANCE = new BrokenRingWorldGeoRenderer(false);
    private static final BrokenRingWorldGeoRenderer REPAIRABLE_INSTANCE = new BrokenRingWorldGeoRenderer(true);
    private static StaticRenderMesh irreparableMesh;
    private static StaticRenderMesh repairableMesh;
    private final boolean repairable;

    // GeckoLib mirrors the authored X=40 center to baked X=-40; use that point as
    // the mesh origin so the large damage model is submitted with smaller coordinates.
    private static final float LOCAL_ORIGIN_X_PIXELS = -40.0F;
    private static final float LOCAL_ORIGIN_Z_PIXELS = 0.0F;

    private BrokenRingWorldGeoRenderer(boolean repairable) {
        super(new StaticBrokenRingModel(repairable));
        this.repairable = repairable;
    }

    static void drawSections(
            PoseStack poseStack,
            RenderType renderType,
            RenderType repairableRenderType,
            int packedLight,
            int brokenSections,
            int repairableSections
    ) {
        for (int index = 0; index < RingWorldDamage.SECTION_COUNT; index++) {
            if (!RingWorldDamage.isBroken(brokenSections, index)) {
                continue;
            }
            RingWorldDimensions.SectionTransform transform = RingWorldDimensions.damageSectionTransform(index);
            poseStack.pushPose();
            BrokenRingWorldGeoRenderer renderer = RingWorldDamage.isBroken(repairableSections, index)
                    ? REPAIRABLE_INSTANCE : IRREPARABLE_INSTANCE;
            RenderType selectedRenderType = renderer.repairable ? repairableRenderType : renderType;
            // New standalone damage models are authored around the local Z=0 section center.
            poseStack.translate(transform.xPixels() / 16.0D, 0.0D, transform.zPixels() / 16.0D);
            poseStack.mulPose(Axis.YP.rotationDegrees((float) transform.yawDegrees()));
            // Undo the build-time recentering after the section rotation to preserve placement.
            poseStack.translate(LOCAL_ORIGIN_X_PIXELS / 16.0F, 0.0F, LOCAL_ORIGIN_Z_PIXELS / 16.0F);
            mesh(renderer, selectedRenderType, packedLight).draw(selectedRenderType, poseStack);
            poseStack.popPose();
        }
    }

    static void clearMeshes() {
        if (irreparableMesh != null) {
            irreparableMesh.close();
            irreparableMesh = null;
        }
        if (repairableMesh != null) {
            repairableMesh.close();
            repairableMesh = null;
        }
    }

    private static StaticRenderMesh mesh(
            BrokenRingWorldGeoRenderer renderer,
            RenderType renderType,
            int packedLight
    ) {
        StaticRenderMesh cached = renderer.repairable ? repairableMesh : irreparableMesh;
        if (cached != null) {
            return cached;
        }
        // Damage variants share one local-space VBO across all relocated sections.
        cached = new StaticRenderMesh(
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                262144,
                builder -> buildMesh(renderer, builder, renderType, packedLight)
        );
        if (renderer.repairable) {
            repairableMesh = cached;
        } else {
            irreparableMesh = cached;
        }
        return cached;
    }

    private static void buildMesh(
            BrokenRingWorldGeoRenderer renderer,
            VertexConsumer buffer,
            RenderType renderType,
            int packedLight
    ) {
        MultiBufferSource bufferSource = ignored -> buffer;
        PoseStack localPose = new PoseStack();
        // Keep the uploaded vertex coordinates close to the section origin; drawSections
        // applies the exact inverse translation after rotating each relocated section.
        localPose.translate(-LOCAL_ORIGIN_X_PIXELS / 16.0F, 0.0F, -LOCAL_ORIGIN_Z_PIXELS / 16.0F);
        renderer.render(localPose, ANIMATABLE, bufferSource, renderType,
                buffer, packedLight, 0.0F);
    }

    @Override
    public void renderRecursively(
            PoseStack poseStack,
            StaticBrokenRing animatable,
            GeoBone bone,
            RenderType renderType,
            MultiBufferSource bufferSource,
            VertexConsumer buffer,
            boolean isReRender,
            float partialTick,
            int packedLight,
            int packedOverlay,
            int colour
    ) {
        if (!isDamageVariantBone(bone.getName(), this.repairable)) {
            // Healthy section trees are owned exclusively by RingWorldGeoRenderer.
            return;
        }
        super.renderRecursively(
                poseStack,
                animatable,
                bone,
                renderType,
                bufferSource,
                buffer,
                isReRender,
                partialTick,
                packedLight,
                packedOverlay,
                colour
        );
    }

    private static boolean isDamageVariantBone(String boneName, boolean repairable) {
        if (repairable) {
            return boneName.equals("Ring__World_damage_repairable")
                    || boneName.equals("frame2")
                    || boneName.equals("locater2")
                    || boneName.equals("Ring_World_Section_damage");
        }
        return boneName.equals("Ring__World_damage_irreparable") || boneName.equals("box");
    }

    @Override
    public long getInstanceId(StaticBrokenRing animatable) {
        return 0L;
    }

    @Override
    public void preRender(
            PoseStack poseStack,
            StaticBrokenRing animatable,
            BakedGeoModel model,
            @Nullable MultiBufferSource bufferSource,
            @Nullable VertexConsumer buffer,
            boolean isReRender,
            float partialTick,
            int packedLight,
            int packedOverlay,
            int colour
    ) {
        // drawSections supplies the complete recentering transform; omit GeoObjectRenderer's offset.
    }

    private static final class StaticBrokenRingModel extends GeoModel<StaticBrokenRing> {
        private final boolean repairable;

        private StaticBrokenRingModel(boolean repairable) {
            this.repairable = repairable;
        }

        @Override
        public ResourceLocation getModelResource(StaticBrokenRing animatable) {
            return repairable ? REPAIRABLE_MODEL : IRREPARABLE_MODEL;
        }

        @Override
        public ResourceLocation getTextureResource(StaticBrokenRing animatable) {
            return repairable ? REPAIRABLE_TEXTURE : IRREPARABLE_TEXTURE;
        }

        @Override
        public ResourceLocation getAnimationResource(StaticBrokenRing animatable) {
            return UNUSED_ANIMATION;
        }
    }

    static final class StaticBrokenRing implements GeoAnimatable {
        private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
            // Broken ring geometry is static and therefore has no animation controllers.
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
