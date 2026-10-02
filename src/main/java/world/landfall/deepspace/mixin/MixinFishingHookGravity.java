package world.landfall.deepspace.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import world.landfall.deepspace.physics.EntityGravityRegistry;

/** Fishing hooks use a fixed acceleration instead of Entity.getGravity(). */
@Mixin(value = FishingHook.class, remap = false)
public abstract class MixinFishingHookGravity {
    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = -0.03D))
    private double deepspace$zeroGalaxyHookGravity(double acceleration) {
        FishingHook hook = (FishingHook) (Object) this;
        return EntityGravityRegistry.adjustGravity(hook.level().dimension().location().toString(), acceleration);
    }
}
