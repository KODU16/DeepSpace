package world.landfall.deepspace.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.landfall.deepspace.render.NightSkyPlanetRenderer;

/** Prevents Infinity's random celestial pass while retaining its ordinary sky background. */
@Pseudo
@Mixin(targets = {
        "net.lerariemann.infinity.options.SkyRenderer",
        "net.codexarchonic.infinity.options.SkyRenderer"
}, remap = false)
public abstract class MixinInfinitySkyRenderer {
    @Inject(method = "renderAllCelestialBodies(Ljava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void deepspace$useManagedCelestialBodies(Runnable setupFog, CallbackInfo ci) {
        // Suppress random celestial bodies before planet metadata arrives, including restored dimension options.
        if (NightSkyPlanetRenderer.shouldSuppressInfinityCelestialBodies()) ci.cancel();
    }

    /** Version-specific moon entry points also stay blocked if Infinity changes its aggregate sky pass. */
    @Inject(method = {"renderMoon(I)V", "renderMoon(IF)V"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void deepspace$removeGeneratedMoon(CallbackInfo ci) {
        if (NightSkyPlanetRenderer.shouldSuppressInfinityCelestialBodies()) ci.cancel();
    }
}
