package world.landfall.deepspace.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.landfall.deepspace.client.ClientSeamlessTransitionState;

/**
 * Catches alternate loading-screen paths while the explicit transition gate is active.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftTransitionScreen {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true, remap = false)
    private void deepspace$skipPreparedReceivingScreen(Screen screen, CallbackInfo ci) {
        if (ClientSeamlessTransitionState.isActive() && screen instanceof ReceivingLevelScreen) {
            ci.cancel();
        }
    }
}
