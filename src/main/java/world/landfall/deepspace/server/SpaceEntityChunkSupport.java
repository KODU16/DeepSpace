package world.landfall.deepspace.server;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.planet.GalaxyDimensions;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Keeps nearby ordinary space entities ticking without restoring player view-distance terrain loading. */
@EventBusSubscriber(modid = Deepspace.MODID)
public final class SpaceEntityChunkSupport {
    // Level 31 ticks the occupied chunk; surrounding empty chunks supply vanilla's readiness dependencies.
    private static final int TICKET_RADIUS = 2;
    private static final double ACTIVE_DISTANCE_SQUARED = 64.0D * 64.0D;
    private static final TicketType<ChunkPos> ENTITY_SUPPORT = TicketType.create(
            "deepspace_entity_support", Comparator.comparingLong(ChunkPos::toLong), 40);
    private static final Map<ServerLevel, Set<Entity>> ENTITIES = new HashMap<>();
    private static final Map<ServerLevel, Set<ChunkPos>> TICKETS = new HashMap<>();

    private SpaceEntityChunkSupport() { }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && GalaxyDimensions.isGalaxy(level.dimension())) {
            // Retain newly added entities even before an initially hidden section enters the vanilla tick list.
            ENTITIES.computeIfAbsent(level, ignored -> new HashSet<>()).add(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Set<Entity> entities = ENTITIES.get(level);
            if (entities != null) entities.remove(event.getEntity());
        }
    }

    public static boolean isSupportedChunk(ServerLevel level, int x, int z) {
        Set<ChunkPos> chunks = TICKETS.get(level);
        return chunks != null && chunks.contains(new ChunkPos(x, z));
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (!GalaxyDimensions.isGalaxy(level.dimension())) continue;
            Set<ChunkPos> desired = new HashSet<>();
            // A player's own chunk also loads any existing saved entities at their arrival point.
            level.players().forEach(player -> desired.add(player.chunkPosition()));
            Set<Entity> entities = ENTITIES.get(level);
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (entities != null) {
                // Later event cancellation must not retain entities that were never admitted to the level.
                entities.removeIf(entity -> entity.isRemoved() || !entity.isAddedToLevel() || entity.level() != level);
                for (Entity entity : entities) {
                    ChunkPos chunk = entity.chunkPosition();
                    // Sable already owns plot chunks and their storage; never duplicate those tickets here.
                    if (container != null && container.inBounds(chunk)) continue;
                    if (level.players().stream().anyMatch(player -> player.distanceToSqr(entity) <= ACTIVE_DISTANCE_SQUARED)) {
                        desired.add(chunk);
                    }
                }
            }
            if (container != null) desired.removeIf(container::inBounds);
            Set<ChunkPos> previous = TICKETS.getOrDefault(level, Set.of());
            for (ChunkPos chunk : previous) {
                if (!desired.contains(chunk)) level.getChunkSource().removeRegionTicket(ENTITY_SUPPORT, chunk, TICKET_RADIUS, chunk);
            }
            for (ChunkPos chunk : desired) {
                // Acquire moved/spawned entities immediately; refresh stationary tickets before their timeout.
                if (!previous.contains(chunk) || level.getGameTime() % 10L == 0L) {
                    level.getChunkSource().addRegionTicket(ENTITY_SUPPORT, chunk, TICKET_RADIUS, chunk);
                }
            }
            TICKETS.put(level, desired);
        }
    }

    @SubscribeEvent
    public static void unloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            // Dynamic-dimension removal must release strong references before the entire server stops.
            ENTITIES.remove(level);
            TICKETS.remove(level);
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        // No references or tickets survive an integrated-server world switch.
        ENTITIES.clear();
        TICKETS.clear();
    }
}
