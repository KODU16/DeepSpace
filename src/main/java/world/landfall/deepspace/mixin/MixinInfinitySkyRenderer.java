package world.landfall.deepspace.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;

/** Prevents Infinity's random celestial pass while retaining its ordinary sky background. */
@Pseudo
@Mixin(targets = "net.lerariemann.infinity.options.SkyRenderer", remap = false)
public abstract class MixinInfinitySkyRenderer {
    @Inject(method = "renderAllCelestialBodies(Ljava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true)
    private void deepspace$useManagedCelestialBodies(Runnable setupFog, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().getNamespace().equals("infinity")) {
            return;
        }
        Planet planet = PlanetRegistry.getPlanetByDimension(level.dimension());
        if (planet != null) {
            ci.cancel();
        }
    }
}
