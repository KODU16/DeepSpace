package world.landfall.deepspace.mixin;

import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Treats an unsynchronized dynamic dimension as having no physics-staff locks. */
@Mixin(value = PhysicsStaffClientHandler.class, remap = false)
public abstract class MixinPhysicsStaffClientHandler {
    @Shadow @Final
    private Map<ResourceKey<Level>, List<UUID>> locks;

    @Inject(method = "getLocks", at = @At("HEAD"), cancellable = true)
    private void deepspace$emptyLocksBeforeDynamicDimensionSync(
            Level level,
            CallbackInfoReturnable<List<UUID>> callback
    ) {
        if (!locks.containsKey(level.dimension())) {
            callback.setReturnValue(List.of());
        }
    }

    @Inject(method = "isLocked", at = @At("HEAD"), cancellable = true)
    private void deepspace$unlockedBeforeDynamicDimensionSync(
            SubLevel subLevel,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (!locks.containsKey(subLevel.getLevel().dimension())) {
            callback.setReturnValue(false);
        }
    }
}
