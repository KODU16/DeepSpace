package world.landfall.deepspace.mixin;

import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.RingWorldNoonTime;

/** Locks Iris/BSL celestial light vectors to the zenith only in ring-world galaxies. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CelestialUniforms", remap = false)
public abstract class MixinIrisCelestialUniforms {
    @Inject(
            method = "getCelestialPosition(F)Lorg/joml/Vector4f;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void deepspace$lockRingWorldViewPosition(
            float distance,
            CallbackInfoReturnable<Vector4f> callback
    ) {
        if (!deepspace$isRingWorldGalaxySurface()) {
            return;
        }
        Vector4f position = new Vector4f(0.0F, distance, 0.0F, 0.0F);
        new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()).transform(position);
        callback.setReturnValue(position);
    }

    @Inject(
            method = "getCelestialPositionInWorldSpace(F)Lorg/joml/Vector4f;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void deepspace$lockRingWorldWorldPosition(
            float distance,
            CallbackInfoReturnable<Vector4f> callback
    ) {
        if (deepspace$isRingWorldGalaxySurface()) {
            callback.setReturnValue(new Vector4f(0.0F, distance, 0.0F, 0.0F));
        }
    }

    private static boolean deepspace$isRingWorldGalaxySurface() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        return PlanetRegistry.getPlanetByDimension(level.dimension()) != null
                && RingWorldNoonTime.isNoonLockedSurface(level.dimension());
    }
}
