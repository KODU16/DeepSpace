package world.landfall.deepspace.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.landfall.deepspace.client.ClientSeamlessTransitionState;

/** Avoids Xaero's synchronous map flush from blocking a prepared dimension transfer. */
@Pseudo
@Mixin(targets = "xaero.map.MapProcessor", remap = false)
public abstract class MixinXaeroMapProcessor {
    @Inject(method = "onWorldUnload", at = @At("HEAD"), cancellable = true, remap = false)
    private void deepspace$skipSynchronousWorldUnload(CallbackInfo callback) {
        if (ClientSeamlessTransitionState.isActive()) {
            callback.cancel();
        }
    }
}
