package world.landfall.deepspace.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.landfall.deepspace.physics.EntityGravityRegistry;

/**
 * Removes galaxy gravity without changing persistent NoGravity flags or a planet's native acceleration.
 */
@Mixin(value = Entity.class, remap = false)
public abstract class MixinEntityGravity {
    @Inject(method = "getGravity", at = @At("RETURN"), cancellable = true)
    private void deepspace$adjustEntityGravity(CallbackInfoReturnable<Double> callback) {
        Entity entity = (Entity) (Object) this;
        String dimensionId = entity.level().dimension().location().toString();
        callback.setReturnValue(EntityGravityRegistry.adjustGravity(dimensionId, callback.getReturnValue()));
    }
}
