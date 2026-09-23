package world.landfall.deepspace.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.landfall.deepspace.client.PlanetSkyColor;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevelSkyColor {
    @Inject(
            method = "getSkyColor",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void deepspace$usePlanetAtmosphereSkyColor(
            Vec3 cameraPosition,
            float partialTick,
            CallbackInfoReturnable<Vec3> callback
    ) {
        var skyColor = PlanetSkyColor.current((ClientLevel) (Object) this, partialTick);
        if (skyColor != null) {
            // Iris samples this method too, so both render paths receive the same timed atmosphere color.
            callback.setReturnValue(skyColor);
        }
    }
}
