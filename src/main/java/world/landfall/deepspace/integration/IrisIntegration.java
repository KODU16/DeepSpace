package world.landfall.deepspace.integration;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class IrisIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int GL_DRAW_FRAMEBUFFER = 36009;
    private static final int GL_READ_FRAMEBUFFER = 36008;
    private static final int GL_DRAW_FRAMEBUFFER_BINDING = 36006;
    private static final int GL_READ_FRAMEBUFFER_BINDING = 36010;
    private static final Deque<FramebufferBinding> FRAMEBUFFER_BINDINGS = new ArrayDeque<>();
    private static final AtomicLong TARGET_BINDS = new AtomicLong();
    private static final AtomicLong TARGET_RESTORES = new AtomicLong();
    private static final AtomicLong TARGET_RESTORE_UNDERFLOWS = new AtomicLong();
    private static Class<?> irisInstanceClass;
    private static Object irisInstance;
    private static boolean hasFailedPipeline;
    private static boolean hasFailedGalaxyDepthPhase;
    private static Object previousGalaxyDepthPhase;
    private static boolean galaxyDepthPhaseActive;

    /**
     * Binds the active Iris framebuffer while a compatible RenderType is drawing.
     */
    public static final RenderStateShard.OutputStateShard IRIS_TARGET =
            new RenderStateShard.OutputStateShard(
                    "deepspace_iris_target",
                    IrisIntegration::saveAndBindPipelineTarget,
                    IrisIntegration::restorePreviousFramebuffer
            );

    static {
        try {
            irisInstanceClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisInstance = irisInstanceClass.getDeclaredMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            irisInstanceClass = null;
            irisInstance = null;
        }
        hasFailedPipeline = false;
    }

    private IrisIntegration() {
    }

    /**
     * Reports whether the public Iris API was linked successfully.
     */
    public static boolean isIrisAvailable() {
        return irisInstanceClass != null && irisInstance != null;
    }

    public static boolean isShaderPackEnabled() {
        if (!isIrisAvailable()) {
            return false;
        }
        try {
            return (Boolean) irisInstanceClass.getDeclaredMethod("isShaderPackInUse").invoke(irisInstance);
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    /**
     * Returns the active shader pack's celestial path so custom suns share its light direction.
     */
    public static float getSunPathRotation() {
        if (!isShaderPackEnabled()) {
            return 0.0f;
        }
        try {
            Number rotation = (Number) irisInstanceClass.getDeclaredMethod("getSunPathRotation").invoke(irisInstance);
            return rotation.floatValue();
        } catch (ReflectiveOperationException | LinkageError e) {
            return 0.0f;
        }
    }

    /**
     * Reads Iris's internal pack name only for BSL compatibility diagnostics.
     */
    public static Optional<String> getCurrentShaderPackName() {
        if (!isShaderPackEnabled()) {
            return Optional.empty();
        }
        try {
            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            return Optional.ofNullable((String) irisClass.getDeclaredMethod("getCurrentPackName").invoke(null));
        } catch (ReflectiveOperationException | LinkageError | ClassCastException ignored) {
            return Optional.empty();
        }
    }

    /** Identifies BSL packs without coupling DeepSpace to Iris implementation classes. */
    public static boolean isBslShaderPack() {
        return getCurrentShaderPackName()
                .map(name -> name.toLowerCase(Locale.ROOT).contains("bsl"))
                .orElse(false);
    }

    public static void bindPipeline() {
        if (!isShaderPackEnabled()) {
            return;
        }
        try {
            Class iris = Class.forName("net.irisshaders.iris.Iris");
            var pipelineManager = iris.getDeclaredMethod("getPipelineManager").invoke(null);
            Class<?> pipelineManagerClass = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
            Optional<?> pipeline = (Optional<?>) pipelineManagerClass.getDeclaredMethod("getPipeline").invoke(pipelineManager);
            Class<?> renderingPipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            if (pipeline.isPresent() && renderingPipelineClass.isInstance(pipeline.get())) {
                renderingPipelineClass.getDeclaredMethod("bindDefault").invoke(pipeline.get());
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            if (!hasFailedPipeline) {
                LOGGER.warn("Unable to bind the Iris framebuffer; this Iris version may be unsupported", e);
                hasFailedPipeline = true;
            }
        }
    }

    /** Marks DeepSpace celestial draws as Iris custom-sky fragments for logarithmic depth output. */
    public static void beginGalaxyLogDepthPhase() {
        if (!isShaderPackEnabled() || galaxyDepthPhaseActive) {
            return;
        }
        try {
            Object pipeline = getRenderingPipeline();
            if (pipeline == null) {
                return;
            }
            Class<?> phaseClass = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPhase");
            previousGalaxyDepthPhase = pipeline.getClass().getMethod("getPhase").invoke(pipeline);
            Object customSky = phaseClass.getField("CUSTOM_SKY").get(null);
            pipeline.getClass().getMethod("setPhase", phaseClass).invoke(pipeline, customSky);
            notifyIrisPhaseChange();
            galaxyDepthPhaseActive = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            if (!hasFailedGalaxyDepthPhase) {
                LOGGER.warn("Unable to activate Iris galaxy logarithmic depth", e);
                hasFailedGalaxyDepthPhase = true;
            }
        }
    }

    /** Restores the Iris phase used before DeepSpace entered the galaxy pass. */
    public static void endGalaxyLogDepthPhase() {
        if (!galaxyDepthPhaseActive) {
            return;
        }
        try {
            Object pipeline = getRenderingPipeline();
            if (pipeline != null && previousGalaxyDepthPhase != null) {
                Class<?> phaseClass = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPhase");
                pipeline.getClass().getMethod("setPhase", phaseClass)
                        .invoke(pipeline, previousGalaxyDepthPhase);
                notifyIrisPhaseChange();
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            if (!hasFailedGalaxyDepthPhase) {
                LOGGER.warn("Unable to restore the Iris phase after galaxy logarithmic depth", e);
                hasFailedGalaxyDepthPhase = true;
            }
        } finally {
            previousGalaxyDepthPhase = null;
            galaxyDepthPhaseActive = false;
        }
    }

    /** Reports whether Iris accepted the custom-sky phase used by galaxy logarithmic depth. */
    public static boolean isGalaxyLogDepthPhaseActive() {
        return galaxyDepthPhaseActive;
    }

    private static Object getRenderingPipeline() throws ReflectiveOperationException {
        Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
        Object pipelineManager = irisClass.getDeclaredMethod("getPipelineManager").invoke(null);
        return pipelineManager.getClass().getMethod("getPipelineNullable").invoke(pipelineManager);
    }

    private static void notifyIrisPhaseChange() throws ReflectiveOperationException {
        Class.forName("net.irisshaders.iris.layer.GbufferPrograms")
                .getMethod("runPhaseChangeNotifier")
                .invoke(null);
    }

    /**
     * Saves both framebuffer bindings before a DeepSpace RenderType enters the Iris target.
     */
    public static void saveAndBindPipelineTarget() {
        FRAMEBUFFER_BINDINGS.push(new FramebufferBinding(
                GlStateManager._getInteger(GL_DRAW_FRAMEBUFFER_BINDING),
                GlStateManager._getInteger(GL_READ_FRAMEBUFFER_BINDING)
        ));
        TARGET_BINDS.incrementAndGet();
        bindPipeline();
    }

    /**
     * Restores the exact target used by Iris before the nested DeepSpace draw.
     */
    public static void restorePreviousFramebuffer() {
        FramebufferBinding binding = FRAMEBUFFER_BINDINGS.poll();
        if (binding == null) {
            TARGET_RESTORE_UNDERFLOWS.incrementAndGet();
            LOGGER.warn("DeepSpace Iris framebuffer restore had no matching saved binding");
            return;
        }
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, binding.draw());
        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, binding.read());
        TARGET_RESTORES.incrementAndGet();
    }

    public static List<String> framebufferDiagnosticLines() {
        return List.of(
                "render.irisFramebuffer=binds:" + TARGET_BINDS.get()
                        + ",restores:" + TARGET_RESTORES.get()
                        + ",underflows:" + TARGET_RESTORE_UNDERFLOWS.get()
                        + ",pending:" + FRAMEBUFFER_BINDINGS.size()
        );
    }

    private record FramebufferBinding(int draw, int read) {
    }
}
