package world.landfall.deepspace.mixin;

import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.landfall.deepspace.planet.GalaxyDimensions;

/** Prevents procedural galaxies from falling back to Sable's default downward acceleration. */
@Mixin(value = DimensionPhysicsData.class, remap = false)
public abstract class MixinSableDimensionGravity {
    @Inject(method = "getGravity(Lnet/minecraft/world/level/Level;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;",
            at = @At("HEAD"), cancellable = true)
    private static void deepspace$zeroGalaxyGravity(Level level, Vector3dc position, Vector3d destination,
                                                   CallbackInfoReturnable<Vector3d> callback) {
        // All three public overloads converge here, including pipeline initialization and seat compensation.
        if (GalaxyDimensions.isGalaxy(level.dimension())) callback.setReturnValue(destination.zero());
    }
}
