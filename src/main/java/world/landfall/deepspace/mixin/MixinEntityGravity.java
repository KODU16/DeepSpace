package world.landfall.deepspace.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.landfall.deepspace.physics.EntityGravityRegistry;

/**
 * Applies dimension-specific multipliers to every vanilla entity's native gravity.
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
