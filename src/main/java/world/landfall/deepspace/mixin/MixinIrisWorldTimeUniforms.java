package world.landfall.deepspace.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.landfall.deepspace.planet.RingWorldNoonTime;

/** Locks BSL time only for ring-world planet surfaces. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.WorldTimeUniforms", remap = false)
public abstract class MixinIrisWorldTimeUniforms {
    @Inject(method = "getWorldDayTime()I", at = @At("HEAD"), cancellable = true, require = 0)
    private static void deepspace$useRingWorldGalaxyNoon(CallbackInfoReturnable<Integer> callback) {
        var level = Minecraft.getInstance().level;
        if (level != null && RingWorldNoonTime.isNoonLockedSurface(level.dimension())) {
            callback.setReturnValue((int) RingWorldNoonTime.NOON_DAY_TIME);
        }
    }
}
