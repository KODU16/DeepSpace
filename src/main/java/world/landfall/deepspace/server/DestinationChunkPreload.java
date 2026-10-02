package world.landfall.deepspace.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import world.landfall.deepspace.planet.GalaxyDimensions;

/** Requests only the arrival chunk and polls readiness without blocking the server thread. */
public final class DestinationChunkPreload {
    // Radius zero requests FULL only at the arrival chunk; normal player tickets own the view distance.
    private static final int PRELOAD_RADIUS = 0;

    private DestinationChunkPreload() {
    }

    /** Never call getChunk/getChunkFuture here: both can synchronously wait on the server thread. */
    public static boolean isReady(ServerLevel destination, Vec3 landing) {
        if (destination == null || landing == null) return false;
        if (GalaxyDimensions.isGalaxy(destination.dimension())) return true;
        ChunkPos chunk = new ChunkPos(BlockPos.containing(landing));
        return destination.getChunkSource().getChunkNow(chunk.x, chunk.z) != null;
    }

    public static void request(ServerLevel destination, Vec3 landing) {
        if (destination == null || landing == null || GalaxyDimensions.isGalaxy(destination.dimension())) {
            return;
        }
        BlockPos blockPos = BlockPos.containing(landing);
        destination.getChunkSource().addRegionTicket(
                TicketType.PORTAL,
                new ChunkPos(blockPos),
                PRELOAD_RADIUS,
                blockPos
        );
    }
}
