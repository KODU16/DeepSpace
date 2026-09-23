package world.landfall.deepspace.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import world.landfall.deepspace.client.ClientSeamlessTransitionState;

/**
 * Prevents handleRespawn from installing a loading screen during prepared transfers.
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListenerTransitionScreen {
    @WrapOperation(
            method = "handleRespawn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V",
                    remap = false
            ),
            remap = false
    )
    private void deepspace$skipPreparedReceivingScreen(
            Minecraft minecraft,
            Screen screen,
            Operation<Void> original
    ) {
        if (ClientSeamlessTransitionState.isActive() && screen instanceof ReceivingLevelScreen) {
            return;
        }
        original.call(minecraft, screen);
    }
}
