package world.landfall.deepspace.mixin;

import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.landfall.deepspace.server.SableHeatMapImport;

/** Defers per-block heat-map mutation while Deep Space restores a complete template. */
@Mixin(value = SubLevelHeatMapManager.class, remap = false)
public abstract class MixinSubLevelHeatMapManager {
    @Inject(method = "onSolidAdded", at = @At("HEAD"), cancellable = true)
    private void deepspace$recordAtomicImport(BlockPos position, CallbackInfo callback) {
        if (SableHeatMapImport.record((SubLevelHeatMapManager) (Object) this, position)) {
            callback.cancel();
        }
    }
}
