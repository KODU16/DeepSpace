package world.landfall.deepspace.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.landfall.deepspace.Config;

/** Prevents the overlapping book-portal feature while retaining Infinite Dimensions' runtime registry API. */
@Mixin(value = NetherPortalBlock.class, priority = 2000)
public abstract class MixinDisableInfinityBookPortals {
    @Inject(
            method = "entityInside(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            cancellable = true,
            order = 0,
            remap = false
    )
    private void deepspace$disableInfinityBookPortals(
            BlockState state,
            Level level,
            BlockPos pos,
            Entity entity,
            CallbackInfo callback
    ) {
        if (!ModList.get().isLoaded("infinity")
                || !Config.DISABLE_INFINITY_BOOK_PORTALS.get()
                || !(entity instanceof ItemEntity itemEntity)) {
            return;
        }
        if (itemEntity.getItem().is(Items.WRITABLE_BOOK) || itemEntity.getItem().is(Items.WRITTEN_BOOK)) {
            callback.cancel();
        }
    }
}
