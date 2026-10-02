package world.landfall.deepspace.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.landfall.deepspace.client.hud.HelmetHudState;

/** Makes the optional VSIE renderer respect the same helmet switch as DeepSpace's own renderer. */
@Pseudo
@Mixin(targets = "com.kodu16.vsie.integration.deepspace.DeepSpaceHudRenderer", remap = false)
public abstract class MixinVsieHelmetHudToggle {
    @Inject(method = "canRender", at = @At("RETURN"), cancellable = true)
    private static void deepspace$applyHelmetHudToggle(CallbackInfoReturnable<Boolean> callback) {
        // VSIE's non-helmet HUD retains its own controls; wearing a HUD helmet follows the J preference.
        if (HelmetHudState.hasHudHelmet(Minecraft.getInstance().player)) {
            callback.setReturnValue(callback.getReturnValue() && HelmetHudState.isEnabled());
        }
    }
}
