package world.landfall.deepspace.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Shadow;
import world.landfall.deepspace.server.SubLevelEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.landfall.deepspace.physics.GravityBoots;
import world.landfall.deepspace.physics.EntityGravityRegistry;

/**
 * Removes galaxy gravity without changing persistent NoGravity flags or a planet's native acceleration.
 */
@Mixin(value = Entity.class, remap = false)
public abstract class MixinEntityGravity {
    // Use the entity's native virtual acceleration rather than a previous NoGravity-dependent result.
    @Shadow
    protected abstract double getDefaultGravity();
    @Inject(method = "getGravity", at = @At("RETURN"), cancellable = true)
    private void deepspace$adjustEntityGravity(CallbackInfoReturnable<Double> callback) {
        Entity entity = (Entity) (Object) this;
        // Recheck the equipped switch and current structure bounds on every gravity calculation.
        if (entity instanceof net.minecraft.world.entity.player.Player player && GravityBoots.isGravityActive(player)) {
            // Preserve the short explicit freeze while a destination ship snapshot is restored.
            if (player instanceof ServerPlayer serverPlayer && player.isNoGravity()
                    && SubLevelEvents.isTemporaryChunkSyncEnabled(serverPlayer)) return;
            callback.setReturnValue(getDefaultGravity());
            return;
        }
        String dimensionId = entity.level().dimension().location().toString();
        callback.setReturnValue(EntityGravityRegistry.adjustGravity(dimensionId, callback.getReturnValue()));
    }
}
