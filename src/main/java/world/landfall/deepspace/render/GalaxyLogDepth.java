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
    private static final ResourceLocation SOLAR_ENTITY_CUTOUT_SHADER = Deepspace.path("galaxy_solar_entity_cutout");
    private static boolean active;
    private static float geometryScale = 1.0F;

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

    /** Tracks camera compression only; model size is not part of this factor. */
    static float setGeometryScale(float scale) {
        float previous = geometryScale;
        geometryScale = scale;
        return previous;
    }

    /** Restores physical clip W without changing projected size or logarithmic depth. */
    static void applyGeometryScale(ShaderProgram shader) {
        shader.getUniform("GeometryDepthScale").setFloat(geometryScale);
    }

    /** Always writes the local logarithmic depth so Iris and native galaxy geometry remain comparable. */
    public static RenderStateShard.ShaderStateShard entityCutoutShader(ResourceLocation texture) {
        return new RenderStateShard.ShaderStateShard(() -> {
            ShaderProgram shader = VeilRenderSystem.setShader(ENTITY_CUTOUT_SHADER);
            shader.setTexture("Sampler0", texture);
            applyGeometryScale(shader);
            return VeilRenderBridge.toShaderInstance(shader);
        });
    }

    /** Uses the same log-depth cutout pass while exposing a local solar direction to Gecko models. */
    public static RenderStateShard.ShaderStateShard solarEntityCutoutShader(ResourceLocation texture) {
        return new RenderStateShard.ShaderStateShard(() -> {
            ShaderProgram shader = VeilRenderSystem.setShader(SOLAR_ENTITY_CUTOUT_SHADER);
            shader.setTexture("Sampler0", texture);
            applyGeometryScale(shader);
            return VeilRenderBridge.toShaderInstance(shader);
        });
    }
}
