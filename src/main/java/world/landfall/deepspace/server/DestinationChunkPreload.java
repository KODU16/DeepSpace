package world.landfall.deepspace.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import world.landfall.deepspace.planet.GalaxyDimensions;

/** 传送前预热目标星球区块，避免落地后主线程堵在 getChunk。 */
public final class DestinationChunkPreload {
    private static final int PRELOAD_RADIUS = 6;

    private DestinationChunkPreload() {
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
