package world.landfall.deepspace.mixin;

import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.landfall.deepspace.integration.BslPlanetSkyUniforms;
import world.landfall.deepspace.integration.BslSolarUniforms;

/**
 * Adds the custom BSL point-sun uniforms to Iris program builders.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms", remap = false)
public abstract class MixinIrisCommonUniforms {
    @Inject(method = "addNonDynamicUniforms", at = @At("TAIL"), require = 0)
    private static void deepspace$registerBslSolarUniforms(
            UniformHolder uniforms,
            IdMap idMap,
            PackDirectives directives,
            FrameUpdateNotifier updateNotifier,
            CallbackInfo callback
    ) {
        BslSolarUniforms.register(uniforms);
        BslPlanetSkyUniforms.register(uniforms);
    }
}
