package world.landfall.deepspace.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.planet.RingWorldDamage;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Renders the static, complete GeckoLib ring without entity-centering transforms. */
final class RingWorldGeoRenderer extends GeoObjectRenderer<RingWorldGeoRenderer.StaticRingWorld> {
    private static final ResourceLocation MODEL = Deepspace.path("geo/ring_world.geo.json");
    private static final ResourceLocation TEXTURE = Deepspace.path("textures/ring_world_gecko.png");
    private static final ResourceLocation UNUSED_ANIMATION = Deepspace.path("animations/ring_world.animation.json");
    private static final StaticRingWorld ANIMATABLE = new StaticRingWorld();
    private static final RingWorldGeoRenderer INSTANCE = new RingWorldGeoRenderer();
    private static final StaticRenderMesh[] MESHES = new StaticRenderMesh[1 << RingWorldDamage.SECTION_COUNT];
    private static final StaticRenderMesh[] GUI_INTERIOR_MESHES = new StaticRenderMesh[1 << RingWorldDamage.SECTION_COUNT];
    private int brokenSections;
    private boolean guiInteriorOnly;
    private int guiSectionIndex = -1;

    private RingWorldGeoRenderer() {
        super(new StaticRingWorldModel());
    }

    static void draw(
            PoseStack poseStack,
            RenderType renderType,
            int packedLight,
            int brokenSections
    ) {
        int sectionMask = brokenSections & (MESHES.length - 1);
        StaticRenderMesh mesh = MESHES[sectionMask];
        if (mesh == null) {
            // Each mask is uploaded once; later frames only update the model-view matrix.
            mesh = new StaticRenderMesh(
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    786432,
                    builder -> buildMesh(builder, renderType, packedLight, sectionMask)
            );
            MESHES[sectionMask] = mesh;
        }
        mesh.draw(renderType, poseStack);
    }

    /** Uses a separate GUI mesh without the outer radial wall; world meshes retain both sides. */
    static void drawGuiInterior(PoseStack poseStack, RenderType renderType, int packedLight, int brokenSections) {
        int sectionMask = brokenSections & (GUI_INTERIOR_MESHES.length - 1);
        StaticRenderMesh mesh = GUI_INTERIOR_MESHES[sectionMask];
        if (mesh == null) {
            mesh = new StaticRenderMesh(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 786432,
                    builder -> buildMesh(builder, renderType, packedLight, sectionMask, true));
            GUI_INTERIOR_MESHES[sectionMask] = mesh;
        }
        mesh.draw(renderType, poseStack);
    }

    static void clearMeshes() {
        for (int index = 0; index < MESHES.length; index++) {
            if (MESHES[index] != null) {
                MESHES[index].close();
                MESHES[index] = null;
            }
            if (GUI_INTERIOR_MESHES[index] != null) {
                GUI_INTERIOR_MESHES[index].close();
                GUI_INTERIOR_MESHES[index] = null;
            }
        }
    }

    private static void buildMesh(
            VertexConsumer buffer,
            RenderType renderType,
            int packedLight,
            int brokenSections
    ) {
        buildMesh(buffer, renderType, packedLight, brokenSections, false);
    }

    private static void buildMesh(
            VertexConsumer buffer,
            RenderType renderType,
            int packedLight,
            int brokenSections,
            boolean guiInteriorOnly
    ) {
        MultiBufferSource bufferSource = ignored -> buffer;
        INSTANCE.brokenSections = brokenSections;
        INSTANCE.guiInteriorOnly = guiInteriorOnly;
        try {
            INSTANCE.render(new PoseStack(), ANIMATABLE, bufferSource, renderType, buffer, packedLight, 0.0F);
        } finally {
            INSTANCE.brokenSections = 0;
            INSTANCE.guiInteriorOnly = false;
        }
    }

    @Override
    public void createVerticesOfQuad(GeoQuad quad, Matrix4f poseState, Vector3f normal,
                                     VertexConsumer buffer, int packedLight, int packedOverlay, int colour) {
        if (guiInteriorOnly && guiSectionIndex >= 0) {
            double minimumOutwardCoordinate = Double.POSITIVE_INFINITY;
            for (var vertex : quad.vertices()) {
                Vector4f position = poseState.transform(new Vector4f(vertex.position(), 1.0F));
                minimumOutwardCoordinate = Math.min(minimumOutwardCoordinate,
                        RingWorldRenderGeometry.guiOutwardCoordinate(guiSectionIndex,
                                position.x(), position.z()));
            }
            if (!RingWorldRenderGeometry.guiInteriorFrameFace(minimumOutwardCoordinate)) {
                return;
            }
        }
        super.createVerticesOfQuad(quad, poseState, normal, buffer, packedLight, packedOverlay, colour);
    }

    @Override
    public long getInstanceId(StaticRingWorld animatable) {
        return 0L;
    }

    @Override
    public void preRender(
            PoseStack poseStack,
            StaticRingWorld animatable,
            BakedGeoModel model,
            @Nullable MultiBufferSource bufferSource,
            @Nullable VertexConsumer buffer,
            boolean isReRender,
            float partialTick,
            int packedLight,
            int packedOverlay,
            int colour
    ) {
        // The authored model origin is already the stellar center, so omit GeoObjectRenderer's +0.5 translation.
    }

    @Override
    public void renderRecursively(
            PoseStack poseStack,
            StaticRingWorld animatable,
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
        int sectionIndex = sectionIndex(bone.getName());
        if (sectionIndex >= 0 && RingWorldDamage.isBroken(this.brokenSections, sectionIndex)) {
            // The matching compact section is replaced by the damage variant at the same transform.
            return;
        }
        if (isDamageVariantBone(bone.getName())) {
            // The authored damage tree sits beside the ring and is rendered only after explicit relocation.
            return;
        }
        int previousSectionIndex = guiSectionIndex;
        if (sectionIndex >= 0) guiSectionIndex = sectionIndex;
        try {
            super.renderRecursively(
                    poseStack, animatable, bone, renderType, bufferSource, buffer,
                    isReRender, partialTick, packedLight, packedOverlay, colour
            );
        } finally {
            guiSectionIndex = previousSectionIndex;
        }
    }

    /** Maps baked section roots to generation order Section1/Section4/Section3/Section2. */
    private static int sectionIndex(String boneName) {
        return switch (boneName) {
            case "Ring__World_Section1" -> 0;
            case "Ring__World_Section4" -> 1;
            case "Ring__World_Section3" -> 2;
            case "Ring__World_Section2" -> 3;
            default -> -1;
        };
    }

    private static boolean isDamageVariantBone(String boneName) {
        return boneName.equals("Ring__World_Section_damage")
                || boneName.equals("frame2")
                || boneName.equals("locater2")
                || boneName.equals("Ring_World_Section_damage");
    }

    private static final class StaticRingWorldModel extends GeoModel<StaticRingWorld> {
        @Override
        public ResourceLocation getModelResource(StaticRingWorld animatable) {
            return MODEL;
        }

        @Override
        public ResourceLocation getTextureResource(StaticRingWorld animatable) {
            return TEXTURE;
        }

        @Override
        public ResourceLocation getAnimationResource(StaticRingWorld animatable) {
            return UNUSED_ANIMATION;
        }
    }

    static final class StaticRingWorld implements GeoAnimatable {
        private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
            // The ring is structural geometry and has no animation controllers.
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
