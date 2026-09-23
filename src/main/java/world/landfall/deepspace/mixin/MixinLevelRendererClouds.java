package world.landfall.deepspace.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.landfall.deepspace.client.PlanetCloudPolicy;

/** Suppresses vanilla cloud geometry in vacuum with or without shaders. */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRendererClouds {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, remap = false)
    private void deepspace$disableGalaxyClouds(CallbackInfo callback) {
        if (PlanetCloudPolicy.suppressClouds()) {
            callback.cancel();
        }
    }
}
