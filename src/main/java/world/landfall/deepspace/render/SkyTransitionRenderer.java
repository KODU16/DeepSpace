package world.landfall.deepspace.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
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
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import world.landfall.deepspace.client.SkyTransitionState;
import world.landfall.deepspace.integration.IrisIntegration;

public final class SkyTransitionRenderer {
    private static final RenderType NATIVE_RENDER_TYPE = createRenderType("deepspace_sky_transition", RenderStateShard.MAIN_TARGET);
    private static final RenderType IRIS_RENDER_TYPE = createRenderType("deepspace_sky_transition_iris", IrisIntegration.IRIS_TARGET);

    private SkyTransitionRenderer() {
    }

    private static RenderType createRenderType(String name, RenderStateShard.OutputStateShard output) {
        var state = RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                .setCullState(RenderStateShard.NO_CULL)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setOutputState(output)
                .createCompositeState(true);
        return RenderType.create(
                name,
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.TRIANGLES,
                1536,
                false,
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
        int argb = SkyTransitionState.sampleArgb();
        if ((argb >>> 24) == 0) {
            return;
        }

        float radius = Math.max(128.0F, Minecraft.getInstance().gameRenderer.getDepthFar() * 0.9F);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f pose = matrixStack.toPoseStack().last().pose();
        addCube(builder, pose, radius, argb);
        RenderType renderType = IrisIntegration.isShaderPackEnabled() ? IRIS_RENDER_TYPE : NATIVE_RENDER_TYPE;
        renderType.draw(builder.buildOrThrow());
    }

    /**
     * Draws a camera-centered far cube; existing terrain depth keeps the tint on the sky only.
     */
    private static void addCube(BufferBuilder builder, Matrix4f pose, float radius, int argb) {
        float n = -radius;
        float p = radius;
        addFace(builder, pose, argb, n, n, n, p, n, n, p, p, n, n, p, n);
        addFace(builder, pose, argb, p, n, p, n, n, p, n, p, p, p, p, p);
        addFace(builder, pose, argb, n, n, p, n, n, n, n, p, n, n, p, p);
        addFace(builder, pose, argb, p, n, n, p, n, p, p, p, p, p, p, n);
        addFace(builder, pose, argb, n, p, n, p, p, n, p, p, p, n, p, p);
        addFace(builder, pose, argb, n, n, p, p, n, p, p, n, n, n, n, n);
    }

    private static void addFace(
            BufferBuilder builder,
            Matrix4f pose,
            int argb,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz,
            float dx, float dy, float dz
    ) {
        addVertex(builder, pose, ax, ay, az, argb);
        addVertex(builder, pose, bx, by, bz, argb);
        addVertex(builder, pose, cx, cy, cz, argb);
        addVertex(builder, pose, ax, ay, az, argb);
        addVertex(builder, pose, cx, cy, cz, argb);
        addVertex(builder, pose, dx, dy, dz, argb);
    }

    private static void addVertex(BufferBuilder builder, Matrix4f pose, float x, float y, float z, int argb) {
        builder.addVertex(pose, x, y, z).setColor(argb);
    }
}
