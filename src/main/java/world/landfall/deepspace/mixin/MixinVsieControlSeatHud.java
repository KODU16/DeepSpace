package world.landfall.deepspace.mixin;

import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.landfall.deepspace.client.GalaxyArrivalState;

/** Hides VSIE's optional control-seat HUD while the destination card is visible. */
@Pseudo
@Mixin(targets = "com.kodu16.vsie.content.controlseat.client.HUD.ControlSeatWorldHudRenderer", remap = false)
public abstract class MixinVsieControlSeatHud {
    @Inject(method = "onRenderLevel", at = @At("HEAD"), cancellable = true, remap = false)
    private static void deepspace$hideDuringGalaxyArrival(RenderLevelStageEvent event, CallbackInfo callback) {
        if (GalaxyArrivalState.isCardVisible()) {
            callback.cancel();
        }
    }
}
