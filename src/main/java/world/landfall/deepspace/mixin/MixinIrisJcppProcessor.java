package world.landfall.deepspace.mixin;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.landfall.deepspace.render.BslSolarShaderPatcher;
import world.landfall.deepspace.render.GalaxyLogDepthShaderPatcher;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Patches fully expanded Iris GLSL after shader options have been evaluated.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor", remap = false)
public abstract class MixinIrisJcppProcessor {
    private static final Logger DEEPSPACE$LOGGER = LogUtils.getLogger();
    private static final String DEEPSPACE$DIAGNOSTIC_MARKER = "[DEEPSPACE-BSL-DIAG]";
    private static final AtomicBoolean DEEPSPACE$HOOK_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean DEEPSPACE$PATCH_LOGGED = new AtomicBoolean();

    @Inject(
            method = "glslPreprocessSource(Ljava/lang/String;Ljava/lang/Iterable;)Ljava/lang/String;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private static void deepspace$patchBslPreprocessedSource(
            String source,
            Iterable<?> environmentDefines,
            CallbackInfoReturnable<String> callback
    ) {
        if (DEEPSPACE$HOOK_LOGGED.compareAndSet(false, true)) {
            DEEPSPACE$LOGGER.info(
                    "{} iris_glsl_preprocessor_hook_active",
                    DEEPSPACE$DIAGNOSTIC_MARKER
            );
        }

        String processedSource = callback.getReturnValue();
        String bslPatchedSource = BslSolarShaderPatcher.patchFragmentSource(processedSource);
        // Only Iris programs exposing renderStage can safely participate in the custom-sky depth phase.
        String patchedSource = GalaxyLogDepthShaderPatcher.patchFragmentSource(bslPatchedSource);
        if (processedSource != null
                && bslPatchedSource != null
                && !processedSource.equals(bslPatchedSource)
                && DEEPSPACE$PATCH_LOGGED.compareAndSet(false, true)) {
            DEEPSPACE$LOGGER.info(
                    "{} bsl_shader_source_patch_applied {}",
                    DEEPSPACE$DIAGNOSTIC_MARKER,
                    BslSolarShaderPatcher.compactDiagnostics()
            );
        }
        callback.setReturnValue(patchedSource);
    }
}
