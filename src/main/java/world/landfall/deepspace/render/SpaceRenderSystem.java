package world.landfall.deepspace.render;

import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.platform.VeilEventPlatform;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Objects;

public class SpaceRenderSystem {

    public static final VeilRenderLevelStageEvent.Stage BACKGROUND_STAGE = VeilRenderLevelStageEvent.Stage.AFTER_SKY;
    private static final Collection<Renderer> renderers = new LinkedList<>();
    public static void init() {

        // Draw the distant star field before all local celestial bodies.
        SpaceSkyboxRenderer.init();
        // Apply the surface-to-space tint before custom suns so the sun remains full brightness.
        registerRenderer(SkyTransitionRenderer::render, BACKGROUND_STAGE);
        // Galaxy bodies share one reversed logarithmic depth attachment.
        registerRenderer((stage, levelRenderer, bufferSource, matrixStack, frustumMatrix, projectionMatrix,
                          renderTick, partialTicks, camera, frustum) -> GalaxyLogDepth.begin(), BACKGROUND_STAGE);
        PlanetRenderer.init();
        // Draw ring geometry before all later celestial bodies.
        RingWorldSkyRenderer.init();
        RingWorldRenderer.init();
        NightSkyPlanetRenderer.init();
        PlanetDecorationsRenderer.init();
        // The host star is the final celestial pass, so its opaque surface covers the opposite surface ring.
        SunRenderer.init();
        registerRenderer((stage, levelRenderer, bufferSource, matrixStack, frustumMatrix, projectionMatrix,
                          renderTick, partialTicks, camera, frustum) ->
                        HyperRelayGeoRenderer.renderGalaxyParticles(camera, frustumMatrix, projectionMatrix),
                BACKGROUND_STAGE);
        registerRenderer((stage, levelRenderer, bufferSource, matrixStack, frustumMatrix, projectionMatrix,
                          renderTick, partialTicks, camera, frustum) -> GalaxyLogDepth.end(), BACKGROUND_STAGE);
        // Celestial bodies share depth with each other, then release it before terrain and every entity pass.
        registerRenderer((stage, levelRenderer, bufferSource, matrixStack, frustumMatrix, projectionMatrix,
                          renderTick, partialTicks, camera, frustum) ->
                        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX),
                BACKGROUND_STAGE);

        VeilEventPlatform.INSTANCE.preVeilPostProcessing((location, pipeline, ctx) -> {

            pipeline.getUniform("Time").setFloat(0.0f);
            pipeline.getUniform("SunLocation").setVector(new float[] {0, 0, 0});
        });
        VeilEventPlatform.INSTANCE.onVeilRenderLevelStage(
                (stage,
                 levelRenderer,
                 bufferSource,
                 matrixStack,
                 frustumMatrix,
                 projectionMatrix,
                 renderTick,
                 partialTicks,
                 camera,
                 frustum
                ) -> {
                    if (stage.equals(BACKGROUND_STAGE)) {

                        var postManager = VeilRenderSystem.renderer().getPostProcessingManager();
//                        postManager.runPipeline(postManager.getPipeline(Deepspace.path("bloom")));

                    }
                    for (Renderer x : renderers) {
                        if (stage.equals(x.stage) || x.stage == null)
                            x.e.onRenderLevelStage(stage, levelRenderer, bufferSource, matrixStack, frustumMatrix, projectionMatrix, renderTick, partialTicks, camera, frustum);
                    }
                });
    }
    public static void registerRenderer(@NotNull VeilRenderLevelStageEvent e, @Nullable VeilRenderLevelStageEvent.Stage stage) { // null stage if the renderer wants access to all of them
        renderers.add(new Renderer(Objects.requireNonNull(e), stage));
    }

    private record Renderer(VeilRenderLevelStageEvent e, VeilRenderLevelStageEvent.Stage stage) {}
}
