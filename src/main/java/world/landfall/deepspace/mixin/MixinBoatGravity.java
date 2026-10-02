package world.landfall.deepspace.mixin;

import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import world.landfall.deepspace.physics.EntityGravityRegistry;

/** Flowing-water boats replace normal gravity with a fixed downward acceleration. */
@Mixin(value = Boat.class, remap = false)
public abstract class MixinBoatGravity {
    @ModifyConstant(method = "floatBoat", constant = @Constant(doubleValue = -7.0E-4D))
    private double deepspace$zeroGalaxyBoatGravity(double acceleration) {
        Boat boat = (Boat) (Object) this;
        return EntityGravityRegistry.adjustGravity(boat.level().dimension().location().toString(), acceleration);
    }
}
