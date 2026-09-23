package world.landfall.deepspace.server;

import dev.ryanhcode.sable.sublevel.plot.heat.HeatMapPropagationState;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import net.minecraft.core.BlockPos;
import world.landfall.deepspace.mixin.SubLevelHeatMapManagerAccessor;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Builds one stable Sable connectivity baseline after a cross-dimension template load. */
public final class SableHeatMapImport {
    private static final ThreadLocal<Transaction> ACTIVE = new ThreadLocal<>();

    private SableHeatMapImport() {
    }

    public static void begin(SubLevelHeatMapManager manager) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("A Sable heat-map import is already active on this thread");
        }
        ACTIVE.set(new Transaction(manager));
    }

    public static boolean record(SubLevelHeatMapManager manager, BlockPos position) {
        Transaction transaction = ACTIVE.get();
        if (transaction == null || transaction.manager != manager) {
            return false;
        }
        transaction.positions.add(position.immutable());
        return true;
    }

    public static CommitResult commit(SubLevelHeatMapManager manager) {
        Transaction transaction = requireActive(manager);
        ACTIVE.remove();

        Set<TopologyPosition> importedPositions = new HashSet<>(transaction.positions.size());
        for (BlockPos position : transaction.positions) {
            importedPositions.add(new TopologyPosition(position.getX(), position.getY(), position.getZ()));
        }
        Map<TopologyPosition, Short> heat = buildStableHeatMap(importedPositions);
        SubLevelHeatMapManagerAccessor accessor = (SubLevelHeatMapManagerAccessor) manager;
        accessor.deepspace$getSubLevelSplits().clear();
        accessor.deepspace$getFloodfill().clear();
        accessor.deepspace$getRemoved().clear();
        accessor.deepspace$getNewStarts().clear();
        accessor.deepspace$getSplitIndexMap().clear();
        if (!heat.isEmpty()) {
            accessor.deepspace$getSplitIndexMap().add(0);
        }
        heat.forEach((position, value) -> accessor.deepspace$setHeat(position.toBlockPos(), value));
        accessor.deepspace$setSolidCount(heat.size());
        accessor.deepspace$setInitialized(!heat.isEmpty());
        accessor.deepspace$setSplitComplete(true);
        accessor.deepspace$setState(HeatMapPropagationState.CLEARING);
        return new CommitResult(heat.size(), countComponents(heat));
    }

    public static void abort(SubLevelHeatMapManager manager) {
        Transaction transaction = ACTIVE.get();
        if (transaction != null && transaction.manager == manager) {
            ACTIVE.remove();
        }
    }

    static Map<TopologyPosition, Short> buildStableHeatMap(Set<TopologyPosition> positions) {
        Map<TopologyPosition, Short> heat = new HashMap<>(positions.size());
        ArrayDeque<TopologyPosition> queue = new ArrayDeque<>();
        for (TopologyPosition root : positions) {
            if (heat.putIfAbsent(root, (short) 1) != null) {
                continue;
            }
            queue.add(root);
            while (!queue.isEmpty()) {
                TopologyPosition current = queue.removeFirst();
                short nextHeat = (short) Math.min(Short.MAX_VALUE, heat.get(current) + 1);
                for (int[] direction : DIRECTIONS) {
                    TopologyPosition neighbor = current.offset(direction[0], direction[1], direction[2]);
                    if (positions.contains(neighbor) && heat.putIfAbsent(neighbor, nextHeat) == null) {
                        queue.addLast(neighbor);
                    }
                }
            }
        }
        return heat;
    }

    private static int countComponents(Map<TopologyPosition, Short> heat) {
        int components = 0;
        for (short value : heat.values()) {
            if (value == 1) {
                components++;
            }
        }
        return components;
    }

    private static Transaction requireActive(SubLevelHeatMapManager manager) {
        Transaction transaction = ACTIVE.get();
        if (transaction == null || transaction.manager != manager) {
            throw new IllegalStateException("No matching Sable heat-map import is active");
        }
        return transaction;
    }

    public record CommitResult(int solidBlocks, int components) {
    }

    record TopologyPosition(int x, int y, int z) {
        private TopologyPosition offset(int offsetX, int offsetY, int offsetZ) {
            return new TopologyPosition(x + offsetX, y + offsetY, z + offsetZ);
        }

        private BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }

    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private static final class Transaction {
        private final SubLevelHeatMapManager manager;
        private final Set<BlockPos> positions = new HashSet<>();

        private Transaction(SubLevelHeatMapManager manager) {
            this.manager = manager;
        }
    }
}
