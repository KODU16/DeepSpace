package world.landfall.deepspace.render;

import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.integration.IrisIntegration;
import world.landfall.deepspace.planet.PlanetRegistry;

/** Owns the reversed logarithmic depth state used only by the galaxy view. */
public final class GalaxyLogDepth {
    public static final RenderStateShard.DepthTestStateShard GEQUAL_DEPTH_TEST =
            new RenderStateShard.DepthTestStateShard("deepspace_log_depth_gequal", GL11.GL_GEQUAL);
    public static final RenderStateShard.DepthTestStateShard GREATER_DEPTH_TEST =
            new RenderStateShard.DepthTestStateShard("deepspace_log_depth_greater", GL11.GL_GREATER);
    private static final ResourceLocation ENTITY_CUTOUT_SHADER = Deepspace.path("galaxy_entity_cutout");
    private static boolean active;

    private GalaxyLogDepth() {
    }

    /** Clears the shared celestial depth attachment to the reversed-depth far value. */
    public static void begin() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || PlanetRegistry.getGalaxyByDimension(minecraft.level.dimension()) == null) {
            return;
        }
        IrisIntegration.beginGalaxyLogDepthPhase();
        clearCelestialDepth();
        active = true;
    }

    /** Releases celestial depth before normal world rendering resumes. */
    public static void end() {
        if (!active) {
            return;
        }
        clearCelestialDepth();
        RenderSystem.clearDepth(1.0D);
        IrisIntegration.endGalaxyLogDepthPhase();
        active = false;
    }

    /** Clears Iris' pipeline attachment rather than whichever framebuffer happened to be active. */
    private static void clearCelestialDepth() {
        boolean irisEnabled = IrisIntegration.isShaderPackEnabled();
        if (irisEnabled) {
            IrisIntegration.saveAndBindPipelineTarget();
        }
        try {
            RenderSystem.clearDepth(0.0D);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        } finally {
            if (irisEnabled) {
                IrisIntegration.restorePreviousFramebuffer();
            }
        }
    }

    /** Always writes the local logarithmic depth so Iris and native galaxy geometry remain comparable. */
    public static RenderStateShard.ShaderStateShard entityCutoutShader(ResourceLocation texture) {
        return new RenderStateShard.ShaderStateShard(() -> {
            ShaderProgram shader = VeilRenderSystem.setShader(ENTITY_CUTOUT_SHADER);
            shader.setTexture("Sampler0", texture);
            return VeilRenderBridge.toShaderInstance(shader);
        });
    }
}
