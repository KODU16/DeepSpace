package world.landfall.deepspace.mixin;

import dev.ryanhcode.sable.sublevel.plot.heat.HeatMapPropagationState;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the minimum Sable heat-map state needed for an atomic template import. */
@Mixin(value = SubLevelHeatMapManager.class, remap = false)
public interface SubLevelHeatMapManagerAccessor {
    @Accessor("subLevelSplits")
    Long2IntOpenHashMap deepspace$getSubLevelSplits();

    @Accessor("floodfill")
    ObjectList<BlockPos> deepspace$getFloodfill();

    @Accessor("removed")
    ObjectList<BlockPos> deepspace$getRemoved();

    @Accessor("newStarts")
    ObjectList<BlockPos> deepspace$getNewStarts();

    @Accessor("splitIndexMap")
    IntArrayList deepspace$getSplitIndexMap();

    @Accessor("state")
    void deepspace$setState(HeatMapPropagationState state);

    @Accessor("initialized")
    void deepspace$setInitialized(boolean initialized);

    @Accessor("splitComplete")
    void deepspace$setSplitComplete(boolean splitComplete);

    @Accessor("solidCount")
    void deepspace$setSolidCount(int solidCount);

    @Invoker("heatMapSet")
    void deepspace$setHeat(BlockPos position, short heat);
}
