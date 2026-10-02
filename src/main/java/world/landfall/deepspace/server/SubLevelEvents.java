package world.landfall.deepspace.server;

import com.mojang.logging.LogUtils;
import dev.rew1nd.sableschematicapi.blueprint.SableBlueprint;
import dev.rew1nd.sableschematicapi.blueprint.SableBlueprintExporter;
import dev.rew1nd.sableschematicapi.blueprint.SableBlueprintPlacer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.index.SableTags;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.Vec3i;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.util.Unit;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import dev.ryanhcode.sable.sublevel.SubLevel;
import world.landfall.deepspace.integration.InfiniteDimensionsIntegration;
import world.landfall.deepspace.network.SubLevelTransferProbePacket;
import world.landfall.deepspace.network.SubLevelTransferCompletePacket;
import world.landfall.deepspace.network.GalaxyArrivalPacket;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetExitPlacement;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.TerminaLandingCoordinates;
import world.landfall.deepspace.planet.WormholeArrivalPlacement;

import java.util.*;

@EventBusSubscriber(modid = Deepspace.MODID)
public class SubLevelEvents {

    private static final float PlanetTeleportOffset = 1.2345f;
    private static final ResourceKey<Biome> SPACE_BIOME =
            ResourceKey.create(Registries.BIOME, Deepspace.path("space"));
    private static final String VsieControlSeatMountClass = "com.kodu16.vsie.content.controlseat.entity.ControlSeatMountEntity";
    private static final List<PendingEntityRestore> PENDING_ENTITY_RESTORES = new ArrayList<>();
    private static final List<TransferPhysicsDiagnostic> TRANSFER_PHYSICS_DIAGNOSTICS = new ArrayList<>();
    private static final Map<UUID, Set<UUID>> CLIENT_READY_TRANSFERS = new HashMap<>();
    private static final Map<UUID, String> LAST_PLANET_ENTRY_DIAGNOSTIC = new HashMap<>();
    // Contact fixes the destination once, so a moving ship cannot leave a trail of terrain tickets.
    private static final Map<UUID, PendingPlanetEntry> PENDING_PLANET_ENTRIES = new HashMap<>();
    private record PendingPlanetEntry(UUID triggerId, Planet planet, Vec3 landing) {}
    private static final SubLevelTransferGuard TRANSFER_GUARD = new SubLevelTransferGuard();
    private static final Set<UUID> TEMPORARY_CHUNK_SYNC_PLAYERS = new HashSet<>();
    private static final Set<ServerSubLevelContainer> GALAXY_FORCE_LOAD_CONTAINERS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Returns whether a player is in the temporary ordinary-chunk sync window for a sub-level restore. */
    public static boolean isTemporaryChunkSyncEnabled(ServerPlayer player) {
        return player != null && TEMPORARY_CHUNK_SYNC_PLAYERS.contains(player.getUUID());
    }



    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        // Static contacts must not survive a world being closed and reopened in the same process.
        PENDING_PLANET_ENTRIES.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        var server = e.getServer();
        // Discard contacts whose source was destroyed or moved by a different transfer.
        PENDING_PLANET_ENTRIES.entrySet().removeIf(entry -> {
            ServerLevel source = server.getLevel(entry.getValue().planet().getGalaxy());
            ServerSubLevelContainer container = source == null ? null : ServerSubLevelContainer.getContainer(source);
            return container == null || container.getSubLevel(entry.getKey()) == null;
        });
        restorePendingEntities(server);
        sampleTransferPhysics(server);
        long gameTick = server.overworld().getGameTime();

        // Every registered galaxy owns its own planet and wormhole approach checks.
        PlanetRegistry.getAllGalaxies().forEach(galaxy -> {
            ServerLevel galaxyLevel = server.getLevel(galaxy.dimension());
            if (galaxyLevel == null) {
                return;
            }
            ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(galaxyLevel);
            if (container != null) {
                keepGalaxySubLevelsLoaded(container);
                processGalaxyApproaches(server, galaxy.dimension(), container, gameTick);
            }
        });
        processPlanetExits(server, gameTick);
    }

    /** Keeps every Sable sub-level in a galaxy loaded while the galaxy skips normal chunk tickets. */
    private static void keepGalaxySubLevelsLoaded(ServerSubLevelContainer container) {
        if (!GALAXY_FORCE_LOAD_CONTAINERS.add(container)) {
            return;
        }
        container.addObserver(new SubLevelObserver() {
            @Override
            public void onSubLevelAdded(SubLevel subLevel) {
                normalizeGalaxySubLevelBiome(subLevel);
                forceLoadGalaxySubLevel(container, subLevel);
            }

            @Override
            public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
                if (subLevel instanceof ServerSubLevel serverSubLevel) {
                    container.removeForceLoadTicket(
                            serverSubLevel,
                            SubLevelLoadingTicketType.COMMAND_FORCED,
                            Unit.INSTANCE
                    );
                }
            }
        });
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            normalizeGalaxySubLevelBiome(subLevel);
            forceLoadGalaxySubLevel(container, subLevel);
        }
    }

    /** Sable initializes new plots as plains, including plots rebuilt during a transfer. */
    private static void normalizeGalaxySubLevelBiome(SubLevel subLevel) {
        if (subLevel instanceof ServerSubLevel serverSubLevel) {
            ServerLevelPlot plot = serverSubLevel.getPlot();
            plot.setBiome(SPACE_BIOME);
        }
    }

    private static void forceLoadGalaxySubLevel(ServerSubLevelContainer container, SubLevel subLevel) {
        if (subLevel instanceof ServerSubLevel serverSubLevel) {
            container.addForceLoadTicket(serverSubLevel, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        }
    }

    private static void processGalaxyApproaches(
            MinecraftServer server,
            ResourceKey<Level> galaxyDimension,
            ServerSubLevelContainer sourceContainer,
            long gameTick
    ) {
        List<Planet> bodies = PlanetRegistry.getPlanetsForGalaxy(galaxyDimension);
        for (SubLevel subLevel : new ArrayList<>(sourceContainer.getAllSubLevels())) {
            if (subLevel == null) {
                continue;
            }
            PendingPlanetEntry pending = PENDING_PLANET_ENTRIES.get(subLevel.getUniqueId());
            if (pending != null) {
                // Finish an accepted contact even if the ship has crossed the model while worldgen runs.
                enterPlanet(server, sourceContainer, subLevel, pending.planet(), gameTick);
                continue;
            }
            for (Planet planet : bodies) {
                // Wormholes only start jumps explicitly, so their model is never a tick-time collision target.
                if (planet.isWormhole()) {
                    continue;
                }
                boolean riderContact = trackedPlayerTouchesPlanet(sourceContainer.getLevel(), subLevel, planet);
                if (riderContact) {
                    enterPlanet(server, sourceContainer, subLevel, planet, gameTick);
                    break;
                }
            }
        }
    }

    /** Transfers a connected Sable structure when any member touches a planetary model. */
    private static void enterPlanet(
            MinecraftServer server,
            ServerSubLevelContainer sourceContainer,
            SubLevel trigger,
            Planet planet,
            long gameTick
    ) {
        PendingPlanetEntry pending = PENDING_PLANET_ENTRIES.get(trigger.getUniqueId());
        if (pending != null) {
            // All connected members must retain the contact trigger's coordinate frame.
            planet = pending.planet();
            trigger = sourceContainer.getSubLevel(pending.triggerId());
            if (trigger == null) {
                PendingPlanetEntry abandoned = pending;
                PENDING_PLANET_ENTRIES.values().removeIf(entry -> entry == abandoned);
                return;
            }
        }
        ServerLevel destinationLevel = server.getLevel(planet.getDimension());
        ServerSubLevelContainer destinationContainer = destinationLevel == null
                ? null
                : ServerSubLevelContainer.getContainer(destinationLevel);
        if (destinationContainer == null) {
            logPlanetEntryState(
                    trigger,
                    planet,
                    "DESTINATION_UNAVAILABLE",
                    "destination={} loaded={} container={}",
                    planet.getDimension().location(),
                    destinationLevel != null,
                    false
            );
            return;
        }

        Collection<SubLevel> connected = SubLevelHelper.getConnectedChain(trigger);
        if (connected.stream().anyMatch(member -> TRANSFER_GUARD.isProtected(member.getUniqueId(), gameTick))) return;
        if (pending == null) {
            Vector3d target = calculatePlanetEntryPosition(trigger, planet, destinationLevel);
            pending = new PendingPlanetEntry(trigger.getUniqueId(), planet, new Vec3(target.x, target.y, target.z));
            for (SubLevel member : connected) PENDING_PLANET_ENTRIES.put(member.getUniqueId(), pending);
        }
        Vector3d sourcePosition = new Vector3d(trigger.logicalPose().position());
        Vec3 landing = pending.landing();
        Vector3d destinationPosition = new Vector3d(landing.x, landing.y, landing.z);
        // Poll the actual pilot landing chunks, which can differ from the ship center across a chunk edge.
        if (!planetEntryChunksReady(sourceContainer, connected, destinationLevel, sourcePosition, destinationPosition)) {
            logPlanetEntryState(trigger, planet, "LANDING_CHUNK_PENDING", "destination={} target={}",
                    destinationLevel.dimension().location(), destinationPosition);
            return;
        }
        if (!claimTransfer(connected, gameTick)) return;
        PendingPlanetEntry accepted = pending;
        logPlanetEntryState(
                trigger,
                planet,
                "CONTACT",
                "source={} destination={} connected={} sourcePosition={} target={}",
                sourceContainer.getLevel().dimension().location(),
                destinationLevel.dimension().location(),
                connected.size(),
                sourcePosition,
                destinationPosition
        );
        boolean transferred = WarpSubLevels(
                connected,
                sourceContainer,
                destinationContainer,
                sourcePosition,
                destinationPosition,
                gameTick,
                TransferMotionPolicy.STOP_AT_DESTINATION
        );
        if (transferred) {
            // Keep the accepted contact retryable if any participant or reconstruction failed.
            PENDING_PLANET_ENTRIES.values().removeIf(entry -> entry == accepted);
        } else {
            releaseTransfer(connected);
            logPlanetEntryState(
                    trigger,
                    planet,
                    "PREPARE_FAILED",
                    "source={} destination={}",
                    sourceContainer.getLevel().dimension().location(),
                    destinationLevel.dimension().location()
            );
        }
    }

    /**
     * Starts a planet-entry transfer directly from the rider tick when the player
     * is mounted on a tracked sublevel.
     */
    public static boolean tryEnterPlanetFromRider(
            MinecraftServer server,
            ServerPlayer player,
            Planet planet
    ) {
        if (server == null || player == null || planet == null
                || !player.level().dimension().equals(planet.getGalaxy())) {
            return false;
        }
        SubLevel tracked = findTrackedSubLevelInRidingGraph(player);
        if (tracked == null) {
            return false;
        }
        ServerSubLevelContainer sourceContainer = ServerSubLevelContainer.getContainer(player.serverLevel());
        if (sourceContainer == null || !trackedPlayerTouchesPlanet(player.serverLevel(), tracked, planet)) {
            return false;
        }
        enterPlanet(server, sourceContainer, tracked, planet, server.overworld().getGameTime());
        return true;
    }

    /** Logs only state changes for one structure, preventing contact checks from flooding the server log. */
    private static void logPlanetEntryState(
            SubLevel subLevel,
            Planet planet,
            String phase,
            String details,
            Object... arguments
    ) {
        String state = planet.getId() + ':' + phase;
        if (state.equals(LAST_PLANET_ENTRY_DIAGNOSTIC.put(subLevel.getUniqueId(), state))) {
            return;
        }
        Object[] values = new Object[arguments.length + 3];
        values[0] = phase;
        values[1] = planet.getId();
        values[2] = subLevel.getUniqueId();
        System.arraycopy(arguments, 0, values, 3, arguments.length);
        LOGGER.info(
                "[DEEPSPACE-PLANET-ENTRY] phase={} planet={} subLevel={} " + details,
                values
        );
    }

    /** Resolves a hyper relay's destination level and computes the safe arrival pose next to its paired relay. */
    static HyperRelayJumpPlan planWormholeJump(
            MinecraftServer server,
            Planet relay,
            SubLevel subLevel,
            ResourceKey<Level> sourceGalaxy
    ) {
        Planet destinationBody = InfiniteDimensionsIntegration.resolveWormholeDestination(server, relay);
        ServerLevel destinationLevel = server.getLevel(destinationBody.getDimension());
        if (destinationLevel == null) {
            LOGGER.info(
                    "[DEEPSPACE-TRANSFER] phase=WORMHOLE_EXIT_PENDING sourceWormhole={} destination={} reason=destination_level_not_loaded",
                    relay.getId(), destinationBody.getDimension().location()
            );
            return null;
        }
        ServerSubLevelContainer destinationContainer = ServerSubLevelContainer.getContainer(destinationLevel);
        if (destinationContainer == null) {
            return null;
        }
        Planet pairedWormhole = PlanetRegistry.getPairedWormhole(destinationBody.getDimension(), sourceGalaxy);
        if (pairedWormhole == null) {
            LOGGER.error(
                    "[DEEPSPACE-TRANSFER] phase=WORMHOLE_EXIT_REJECTED sourceWormhole={} source={} destination={} reason=missing_paired_wormhole",
                    relay.getId(), sourceGalaxy.location(), destinationBody.getDimension().location()
            );
            return null;
        }
        Collection<SubLevel> connected = SubLevelHelper.getConnectedChain(subLevel);
        Vector3d center = subLevel.logicalPose().position();
        WormholeArrivalPlacement.Bounds relativeBounds = connectedChainRelativeBounds(connected, center);
        WormholeArrivalPlacement.Point safeExit = WormholeArrivalPlacement.placeOutside(
                planetBounds(pairedWormhole),
                relativeBounds,
                point(PlanetRegistry.getSunForGalaxy(destinationBody.getDimension()).getCenter()),
                WormholeArrivalPlacement.DEFAULT_CLEARANCE
        );
        Vector3d destination = new Vector3d(safeExit.x(), safeExit.y(), safeExit.z());
        LOGGER.info(
                "[DEEPSPACE-TRANSFER] phase=WORMHOLE_EXIT_PLANNED sourceWormhole={} destinationWormhole={} target={} relativeBounds={} clearance={}",
                relay.getId(), pairedWormhole.getId(), destination, relativeBounds,
                WormholeArrivalPlacement.DEFAULT_CLEARANCE
        );
        return new HyperRelayJumpPlan(
                relay,
                pairedWormhole,
                destinationBody,
                destinationContainer,
                connected,
                center,
                destination
        );
    }

    /** The resolved target of a hyper-relay jump. */
    record HyperRelayJumpPlan(
            Planet relay,
            Planet pairedWormhole,
            Planet destinationBody,
            ServerSubLevelContainer destinationContainer,
            Collection<SubLevel> connected,
            Vector3d center,
            Vector3d destination
    ) {}

    private static void processPlanetExits(MinecraftServer server, long gameTick) {
        for (Planet planet : PlanetRegistry.getAllPlanets()) {
            // A wormhole points to a galaxy dimension; it is never a planetary surface.
            if (planet.isWormhole()) {
                continue;
            }
            ServerLevel sourceLevel = server.getLevel(planet.getDimension());
            ServerLevel destinationLevel = server.getLevel(planet.getGalaxy());
            ServerSubLevelContainer sourceContainer = sourceLevel == null
                    ? null
                    : ServerSubLevelContainer.getContainer(sourceLevel);
            ServerSubLevelContainer destinationContainer = destinationLevel == null
                    ? null
                    : ServerSubLevelContainer.getContainer(destinationLevel);
            if (sourceContainer == null || destinationContainer == null) {
                continue;
            }

            double exitHeight = planet.resolveAtmosphereExitHeight(sourceLevel.getMaxBuildHeight());
            for (SubLevel subLevel : new ArrayList<>(sourceContainer.getAllSubLevels())) {
                if (subLevel == null) {
                    continue;
                }
                // Planet-to-space transfers always move the one independent Sable sublevel.
                Collection<SubLevel> transferSubLevels = List.of(subLevel);
                if (!subLevelReachedAtmosphereExit(sourceLevel, subLevel, exitHeight)) {
                    continue;
                }
                if (!claimTransfer(transferSubLevels, gameTick)) {
                    continue;
                }
                Vector3d sourcePosition = subLevel.logicalPose().position();
                Vec3 previousPosition = new Vec3(sourcePosition.x, sourcePosition.y, sourcePosition.z);
                // Clear the entire departing hull, including ordinary planets as well as elongated ring edges.
                Vec3 exitPosition = calculateSafePlanetExitPosition(
                        transferSubLevels, sourcePosition, previousPosition, planet, sourceLevel);
                LOGGER.info(
                        "Teleporting sublevel from {} to host galaxy {} at {}",
                        planet.getName(),
                        planet.getGalaxy().location(),
                        exitPosition
                );
                boolean transferred = WarpSubLevels(
                        transferSubLevels,
                        sourceContainer,
                        destinationContainer,
                        sourcePosition,
                        new Vector3d(exitPosition.x, exitPosition.y, exitPosition.z),
                        gameTick,
                        TransferMotionPolicy.STOP_AT_DESTINATION
                );
                // A failed preparation must not permanently block the next attempt to leave this planet.
                if (!transferred) {
                    releaseTransfer(transferSubLevels);
                }
            }
        }
    }

    static boolean claimTransfer(Collection<SubLevel> subLevels, long gameTick) {
        return TRANSFER_GUARD.tryClaim(
                subLevels.stream().map(SubLevel::getUniqueId).toList(),
                gameTick
        );
    }

    static void releaseTransfer(Collection<SubLevel> subLevels) {
        TRANSFER_GUARD.release(subLevels.stream().map(SubLevel::getUniqueId).toList());
    }

    /**
     * Detects the exit from either the craft pose or a rider projected into the host dimension.
     * The rider path is required because Sable's logical pose can lag the pilot during ascent.
     */
    private static boolean subLevelReachedAtmosphereExit(
            ServerLevel sourceLevel,
            SubLevel subLevel,
            double exitHeight
    ) {
        // The center of mass need not coincide with the center of the hull bounds.
        if (subLevel.boundingBox().maxY() > exitHeight) {
            return true;
        }
        for (ServerPlayer player : sourceLevel.players()) {
            if (findTrackedSubLevelInRidingGraph(player) != subLevel) {
                continue;
            }
            Vec3 projected = Sable.HELPER.projectOutOfSubLevel(sourceLevel, player.position());
            if (projected.y + player.getBbHeight() * 0.5 > exitHeight) {
                LOGGER.info(
                        "[DEEPSPACE-PLANET-EXIT] phase=RIDER_CONTACT player={} subLevel={} projectedY={} exitHeight={}",
                        player.getUUID(),
                        subLevel.getUniqueId(),
                        projected.y,
                        exitHeight
                );
                return true;
            }
        }
        return false;
    }

    /** Finds Sable tracking on the player or any vehicle in the active riding chain. */
    public static SubLevel findTrackedSubLevelInRidingGraph(Entity entity) {
        for (Entity cursor = entity; cursor != null; cursor = cursor.getVehicle()) {
            SubLevel tracked = Sable.HELPER.getTrackingSubLevel(cursor);
            if (tracked != null) {
                return tracked;
            }
        }
        return null;
    }

    /** Keeps pilots in the source dimension until their exact destination chunks are FULL. */
    private static boolean planetEntryChunksReady(
            ServerSubLevelContainer source, Collection<SubLevel> connected, ServerLevel destination,
            Vector3d center, Vector3d target
    ) {
        Vector3d delta = new Vector3d(target).sub(center);
        boolean ready = true;
        boolean hasPlayer = false;
        Set<UUID> visited = new HashSet<>();
        for (Set<Entity> entities : collectTransferEntities(connected, source).values()) {
            for (Entity entity : entities) {
                if (!(entity instanceof ServerPlayer player) || !visited.add(player.getUUID())) continue;
                hasPlayer = true;
                Vec3 projected = Sable.HELPER.projectOutOfSubLevel(source.getLevel(), player.position());
                Vec3 landing = projected.add(delta.x, delta.y, delta.z);
                DestinationChunkPreload.request(destination, landing);
                ready &= DestinationChunkPreload.isReady(destination, landing);
            }
        }
        if (!hasPlayer) {
            Vec3 landing = new Vec3(target.x, target.y, target.z);
            DestinationChunkPreload.request(destination, landing);
            ready = DestinationChunkPreload.isReady(destination, landing);
        }
        return ready;
    }

    /** Uses the pilot's projected host position as a fallback for rider-only contact detection. */
    private static boolean trackedPlayerTouchesPlanet(
            ServerLevel galaxyLevel,
            SubLevel subLevel,
            Planet planet
    ) {
        for (ServerPlayer player : galaxyLevel.players()) {
            if (findTrackedSubLevelInRidingGraph(player) != subLevel) {
                continue;
            }
            Vec3 projected = Sable.HELPER.projectOutOfSubLevel(galaxyLevel, player.position());
            AABB playerBounds = AABB.ofSize(
                    projected,
                    player.getBbWidth(),
                    player.getBbHeight(),
                    player.getBbWidth()
            );
            if (planet.intersectsModel(playerBounds)) {
                return true;
            }
        }
        return false;
    }

    /** Measures every connected body relative to the trigger pose used by the transfer copier. */
    private static WormholeArrivalPlacement.Bounds connectedChainRelativeBounds(
            Collection<SubLevel> connected,
            Vector3d reference
    ) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (SubLevel member : connected) {
            // Use actual world extrema rather than assuming the mass center is the hull center.
            var bounds = member.boundingBox();
            minX = Math.min(minX, bounds.minX() - reference.x);
            minY = Math.min(minY, bounds.minY() - reference.y);
            minZ = Math.min(minZ, bounds.minZ() - reference.z);
            maxX = Math.max(maxX, bounds.maxX() - reference.x);
            maxY = Math.max(maxY, bounds.maxY() - reference.y);
            maxZ = Math.max(maxZ, bounds.maxZ() - reference.z);
        }
        return new WormholeArrivalPlacement.Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static WormholeArrivalPlacement.Bounds planetBounds(Planet planet) {
        Vec3 min = planet.getBoundingBoxMin();
        Vec3 max = planet.getBoundingBoxMax();
        return new WormholeArrivalPlacement.Bounds(min.x, min.y, min.z, max.x, max.y, max.z);
    }

    private static WormholeArrivalPlacement.Point point(Vec3 value) {
        return new WormholeArrivalPlacement.Point(value.x, value.y, value.z);
    }

    /** 把船体放在离境面之下，避免落地当帧又被送回太空。 */
    private static double safeAtmosphereEntryY(SubLevel subLevel, Planet planet, ServerLevel destinationLevel) {
        double entryY = planet.resolveAtmosphereEntryHeight(destinationLevel.getMaxBuildHeight());
        double exitY = planet.resolveAtmosphereExitHeight(destinationLevel.getMaxBuildHeight());
        // Keep every connected hull below the exit boundary, including offset mass centers and taller companions.
        double topOffset = Math.max(0.0D, connectedChainRelativeBounds(
                SubLevelHelper.getConnectedChain(subLevel), subLevel.logicalPose().position()).maxY());
        return Math.min(entryY, exitY - topOffset - 2.0D);
    }

    private static Vector3d calculatePlanetEntryPosition(
            SubLevel subLevel,
            Planet planet,
            ServerLevel destinationLevel
    ) {
        if (planet.isRingWorldEdge()) {
            return calculateRingWorldEntryPosition(subLevel, planet, destinationLevel);
        }
        Vector3d position = subLevel.logicalPose().position();
        Vec3 distance = planet.getCenter()
                .subtract(new Vec3(position.x, position.y, position.z))
                .multiply(planet.blockScale(), planet.blockScale(), planet.blockScale());
        double entryY = safeAtmosphereEntryY(subLevel, planet, destinationLevel);
        boolean sideways = Math.abs(distance.x) > Math.abs(distance.y) || Math.abs(distance.z) > Math.abs(distance.y);
        Vector3d landing;
        if (!sideways) {
            landing = distance.y > 0
                    ? new Vector3d(-distance.x, entryY, distance.z)
                    : new Vector3d(distance.x, entryY, -distance.z);
        } else if (Math.abs(distance.x) > Math.abs(distance.z)) {
            landing = distance.x > 0
                    ? new Vector3d(-distance.z, entryY, distance.y)
                    : new Vector3d(distance.z, entryY, distance.y);
        } else {
            landing = distance.z > 0
                    ? new Vector3d(distance.x, entryY, distance.y)
                    : new Vector3d(-distance.x, entryY, distance.y);
        }
        if (destinationLevel.dimension().equals(Level.END)) {
            // Keep the ship above the central island instead of dropping it into the End's empty ring.
            Vec3 safe = TerminaLandingCoordinates.avoidEmptyInnerRing(new Vec3(landing.x, landing.y, landing.z));
            return new Vector3d(safe.x, safe.y, safe.z);
        }
        return landing;
    }

    /** Maps the ring's long and vertical surface axes into the destination world's horizontal plane. */
    private static Vector3d calculateRingWorldEntryPosition(
            SubLevel subLevel,
            Planet planet,
            ServerLevel destinationLevel
    ) {
        Vector3d position = subLevel.logicalPose().position();
        Vec3 center = planet.getCenter();
        Vec3 size = planet.getBoundingBoxMax().subtract(planet.getBoundingBoxMin());
        boolean longX = size.x >= size.z;
        double halfLong = Math.max(size.x, size.z) * 0.5D;
        double halfHeight = size.y * 0.5D;
        double longFraction = Math.clamp(
                (longX ? position.x - center.x : position.z - center.z) / halfLong,
                -1.0D,
                1.0D
        );
        double verticalFraction = Math.clamp(
                (position.y - center.y) / halfHeight,
                -1.0D,
                1.0D
        );
        Vec2 physicalMin = planet.getPhysicalMin();
        Vec2 physicalMax = planet.getPhysicalMax();
        double targetX = (physicalMin.x + physicalMax.x) * 0.5D
                + longFraction * (physicalMax.x - physicalMin.x) * 0.5D;
        double targetZ = (physicalMin.y + physicalMax.y) * 0.5D
                + verticalFraction * (physicalMax.y - physicalMin.y) * 0.5D;
        return new Vector3d(
                targetX,
                safeAtmosphereEntryY(subLevel, planet, destinationLevel),
                targetZ
        );
    }

    private static Vec3 calculateExitPosition(Vec3 previousPosition, Planet planet, Level level) {
//        int offset = (int) Math.floor(level.getDayTimeFraction() * 4);
//
//
        var sunPos = Objects.requireNonNull(PlanetRegistry.getSunForPlanet(planet)).getCenter();
        var planetPos = planet.getCenter();

        var angleBetween = Math.atan2(
                sunPos.subtract(planetPos).x,
                sunPos.subtract(planetPos).z
        ) + Math.PI;
        angleBetween = (angleBetween) / (Math.PI * 2);
//        var timeFactor = (float) (level.dayTime() % ServerLevel.TICKS_PER_DAY) / ServerLevel.TICKS_PER_DAY;
        var timeFactor = level.getTimeOfDay(0);
        int offset = (int) Math.floor(
                (
                        angleBetween +
                        timeFactor
                ) * 4
        ) % 4;

        var exitPos = planetPos;
        var scaledPos = calculateWorldToPlanetScale(
                new Vec2((float) previousPosition.x, (float) previousPosition.z),
                planet
        );
        var planetRadius = planet.getBoundingBoxMax().x / 2 - planet.getBoundingBoxMin().x / 2;
        switch (offset) {
            case 0 ->
                    exitPos = exitPos.add(
                            scaledPos.x * PlanetTeleportOffset,
                            scaledPos.y,
                            planetRadius * PlanetTeleportOffset
                    );
            case 1 ->
                    exitPos = exitPos.add(
                            -planetRadius * PlanetTeleportOffset,
                            scaledPos.y,
                            scaledPos.x * PlanetTeleportOffset
                    );

            case 2 ->
                    exitPos = exitPos.add(
                            -scaledPos.x * PlanetTeleportOffset,
                            scaledPos.y,
                            -planetRadius * PlanetTeleportOffset
                    );

            case 3 ->
                    exitPos = exitPos.add(
                            planetRadius * PlanetTeleportOffset,
                            scaledPos.y,
                            -scaledPos.x * PlanetTeleportOffset
                    );

        }

        LOGGER.info("Planet teleport offset: {}, Planet scaled position: {} {}", offset, scaledPos.x, scaledPos.y);
        LOGGER.info("Time factor: {}, angle between: {}", timeFactor, angleBetween);

        return exitPos;
    }

    /** Preserves mapped height and tangent coordinates while leaving three hull lengths beyond the model. */
    private static Vec3 calculateSafePlanetExitPosition(
            Collection<SubLevel> connected,
            Vector3d sourcePosition,
            Vec3 previousPosition,
            Planet planet,
            Level level
    ) {
        Vec3 desired = calculateExitPosition(previousPosition, planet, level);
        Vec3 starCenter = Objects.requireNonNull(PlanetRegistry.getSunForPlanet(planet)).getCenter();
        Vec3 outward = planet.isRingWorldEdge()
                ? planet.getCenter().subtract(starCenter)
                : desired.subtract(planet.getCenter());
        WormholeArrivalPlacement.Point safe = PlanetExitPlacement.place(
                planetBounds(planet),
                connectedChainRelativeBounds(connected, sourcePosition),
                point(desired),
                point(outward)
        );
        LOGGER.info("[DEEPSPACE-PLANET-EXIT] phase=SAFE_EXIT_PLANNED planet={} mapped={} target={} relativeHull={}",
                planet.getId(), desired, safe, connectedChainRelativeBounds(connected, sourcePosition));
        return new Vec3(safe.x(), safe.y(), safe.z());
    }

    /**
     * Keeps the entry pose below the same ceiling threshold used to leave a planet.
     */
    private static Vec2 calculateWorldToPlanetScale(Vec2 insidePlanet, Planet planet) {
        var scale = planet.blockScale();
        var planetSize = planet.getBoundingBoxMax().x - planet.getBoundingBoxMin().x;
        return new Vec2(
                (float) (insidePlanet.x / scale),
                (float) (insidePlanet.y / scale)
        );
    }

    /** Share the exact participant selection between readiness checks and snapshot transfer. */
    private static Map<UUID, Set<Entity>> collectTransferEntities(
            Collection<SubLevel> compoundSubLevel, ServerSubLevelContainer sourceContainer
    ) {
        HashMap<UUID, Set<Entity>> visitedEntities = new HashMap<>();

        for(SubLevel subLevel : compoundSubLevel) {
            // Sable supplies world bounds, including rotation and the offset from the center of mass.
            var bounds = subLevel.boundingBox();
            AABB box = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ());
            // Match Dimensional Sable's exact structure bounds and retain every player in the plot.
            List<Entity> candidates = sourceContainer.getLevel().getEntities((Entity)null, box);
            Set<Entity> movingEntities = expandRidingGraph(candidates);
            for (ServerPlayer player : sourceContainer.getLevel().players()) {
                // A rider may be stored in plot coordinates; compare everyone in the same world frame.
                Vec3 projected = Sable.HELPER.projectOutOfSubLevel(sourceContainer.getLevel(), player.position());
                AABB playerBounds = player.getBoundingBox().move(projected.subtract(player.position()));
                if (box.contains(projected) || box.intersects(playerBounds)) {
                    movingEntities.add(player);
                    movingEntities.addAll(expandRidingGraph(List.of(player)));
                }
            }
            visitedEntities.put(subLevel.getUniqueId(), movingEntities);
        }
        // Tracking is authoritative for players standing on a Sable body near an imprecise AABB edge.
        Set<UUID> movingIds = compoundSubLevel.stream().map(SubLevel::getUniqueId).collect(java.util.stream.Collectors.toSet());
        for (ServerPlayer player : sourceContainer.getLevel().players()) {
            // Seat-only tracking must include every passenger, even outside an approximate hull edge.
            SubLevel tracked = findTrackedSubLevelInRidingGraph(player);
            if (tracked != null && movingIds.contains(tracked.getUniqueId())) {
                visitedEntities.computeIfAbsent(tracked.getUniqueId(), ignored -> new HashSet<>()).add(player);
                visitedEntities.get(tracked.getUniqueId()).addAll(expandRidingGraph(List.of(player)));
            }
        }

        return visitedEntities;
    }

    /** Captures an immutable Photomancy snapshot without moving players or deleting sources. */
    static PreparedJump prepareJump(
            Collection<SubLevel> compoundSubLevel,
            ServerSubLevelContainer sourceContainer,
            ServerSubLevelContainer destinationContainer,
            Vector3d center,
            Vector3d position,
            long gameTick,
            TransferMotionPolicy motionPolicy
    ) {
        UUID transferId = UUID.randomUUID();
        boolean galaxyToGalaxy = PlanetRegistry.getGalaxyByDimension(sourceContainer.getLevel().dimension()) != null
                && PlanetRegistry.getGalaxyByDimension(destinationContainer.getLevel().dimension()) != null;
        GalaxyArrivalPacket galaxyArrival = motionPolicy == TransferMotionPolicy.STOP_AT_DESTINATION && galaxyToGalaxy
                ? GalaxyArrivalPacket.forDimension(destinationContainer.getLevel().dimension())
                : null;
        LOGGER.info(
                "[DEEPSPACE-TRANSFER] id={} phase=BEGIN source={} destination={} subLevels={} center={} target={} motionPolicy={}",
                transferId,
                sourceContainer.getLevel().dimension().location(),
                destinationContainer.getLevel().dimension().location(),
                compoundSubLevel.stream().map(SubLevel::getUniqueId).toList(),
                center,
                position,
                motionPolicy
        );
        Map<UUID, Set<Entity>> visitedEntities = collectTransferEntities(compoundSubLevel, sourceContainer);

        SableBlueprint snapshot;
        try {
            snapshot = SableBlueprintExporter.exportSelected(
                    sourceContainer.getLevel(),
                    new Vec3(center.x, center.y, center.z),
                    compoundSubLevel.stream().map(SubLevel::getUniqueId).toList()
            );
            snapshot = removeLivePlayersFromSnapshot(snapshot);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Cancelled Sable transfer from {} to {} because the Photomancy snapshot failed",
                    sourceContainer.getLevel().dimension().location(),
                    destinationContainer.getLevel().dimension().location(),
                    exception
            );
            return null;
        }
        if (snapshot.isEmpty() || snapshot.subLevels().size() != compoundSubLevel.size()) {
            LOGGER.error(
                    "Cancelled Sable transfer: Photomancy captured {} of {} connected sublevels",
                    snapshot.subLevels().size(),
                    compoundSubLevel.size()
            );
            return null;
        }
        LOGGER.info(
                "[DEEPSPACE-TRANSFER] id={} phase=SNAPSHOT_CAPTURED subLevels={} blocks={} blockEntities={} entities={}",
                transferId,
                snapshot.subLevels().size(),
                snapshot.blockCount(),
                snapshot.blockEntityCount(),
                snapshot.entityCount()
        );

        Map<UUID, UUID> trackedSubLevels = retainPlayerKeys(
                captureTrackedSubLevels(visitedEntities, compoundSubLevel),
                visitedEntities
        );
        Map<UUID, UUID> passengerVehicles = retainPlayerKeys(
                capturePassengerVehicles(visitedEntities),
                visitedEntities
        );

        return new PreparedJump(
                transferId,
                compoundSubLevel,
                sourceContainer,
                destinationContainer,
                center,
                position,
                galaxyArrival,
                snapshot,
                gameTick,
                motionPolicy,
                visitedEntities,
                trackedSubLevels,
                passengerVehicles
        );
    }

    /** Moves players first, rebuilds the current snapshot, then restores seats and removes sources. */
    static boolean executeJump(PreparedJump jump) {
        UUID transferId = jump.transferId();
        Collection<SubLevel> compoundSubLevel = jump.compoundSubLevel();
        ServerSubLevelContainer sourceContainer = jump.sourceContainer();
        ServerSubLevelContainer destinationContainer = jump.destinationContainer();
        Vector3d center = jump.center();
        Vector3d position = jump.position();
        Map<UUID, Set<Entity>> visitedEntities = jump.visitedEntities();
        Map<UUID, UUID> trackedSubLevels = jump.trackedSubLevels();
        Map<UUID, UUID> passengerVehicles = jump.passengerVehicles();
        Map<UUID, Boolean> protectedPlayerGravity = new HashMap<>();
        List<PlayerTransferState> movedPlayers = movePlayersBeforeSnapshotPlacement(jump, protectedPlayerGravity);
        if (movedPlayers == null) {
            return false;
        }

        SableBlueprintPlacer.Result placement;
        try {
            Vector3d placementOrigin = new Vector3d(jump.snapshot().origin())
                    .add(new Vector3d(position).sub(center));
            placement = SableBlueprintPlacer.place(
                    destinationContainer.getLevel(),
                    jump.snapshot(),
                    new Vec3(placementOrigin.x, placementOrigin.y, placementOrigin.z)
            );
            placement.diagnostics().logSummary(LOGGER, "[DEEPSPACE-TRANSFER] id=" + transferId);
        } catch (RuntimeException exception) {
            LOGGER.error("Photomancy failed to rebuild Sable transfer {}", transferId, exception);
            rollbackMovedPlayers(jump, movedPlayers);
            return false;
        }

        Map<UUID, ServerLevelPlot> subLevelPlots = resolvePlacedSubLevels(
                destinationContainer,
                placement.subLevelUuidMap()
        );
        if (placementHasIntegrityFailure(placement, compoundSubLevel.size(), subLevelPlots.size())) {
            removePlacedSubLevels(destinationContainer, subLevelPlots.values(), transferId);
            rollbackMovedPlayers(jump, movedPlayers);
            return false;
        }

        HashMap<UUID, SubLevelReplacement> oldToNew = buildReplacementMap(
                compoundSubLevel,
                subLevelPlots,
                jump.snapshot()
        );
        TRANSFER_GUARD.protect(placement.subLevelUuidMap().values(), jump.gameTick());
        Map<UUID, PassengerVehicleTarget> replacementVehicleTargets = captureReplacementVehicleTargets(
                visitedEntities,
                passengerVehicles,
                oldToNew
        );
        SubLevelPhysicsSystem physics = SubLevelPhysicsSystem.get(destinationContainer.getLevel());
        subLevelPlots.forEach((sourceId, plot) -> {
            ServerSubLevel copy = plot.getSubLevel();
            // Photomancy keeps blocks in the far plot grid; world pose and local AABB must stay hull-sized.
            clampReplacementPlotBounds(plot, jump.snapshot(), sourceId);
            applyReplacementWorldPose(physics, findSourceSubLevel(compoundSubLevel, sourceId), copy, jump);
            applySubLevelMotion(physics, copy, jump.motionPolicy(), transferId);
        });
        // Rebuild seats after the replacement pose is the destination world transform.
        replacementVehicleTargets.forEach((playerId, target) -> prepareVsieMount(
                destinationContainer.getLevel(), target, destinationContainer.getLevel().getEntity(playerId)));

        Map<UUID, UUID> replacementTracking = remapTrackedSubLevels(trackedSubLevels, subLevelPlots);
        // Authorize ordinary chunk synchronization before the client probe is sent.
        movedPlayers.forEach(state -> TEMPORARY_CHUNK_SYNC_PLAYERS.add(state.playerId()));
        notifyTransferredPlayers(destinationContainer, movedPlayers, replacementTracking, transferId, center);

        // Force the replacement plot through Sable's full-sync path before the riding barrier can open.
        subLevelPlots.values().forEach(plot -> forceLoadGalaxySubLevel(destinationContainer, plot.getSubLevel()));
        // The source remains authoritative until the complete destination snapshot exists.
        for(SubLevel subLevel : compoundSubLevel) {
            sourceContainer.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
        }
        // Removed source UUIDs no longer own an active transfer lock.
        releaseTransfer(compoundSubLevel);
        LOGGER.info("[DEEPSPACE-TRANSFER] id={} phase=SOURCE_REMOVED", transferId);

        restoreTrackedSubLevels(destinationContainer, replacementTracking);
        // Let Sable's normal server tick perform plot tracking without blocking this transfer.
        Map<UUID, Vec3> destinationPlayerPositions = movedPlayers.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        PlayerTransferState::playerId,
                        PlayerTransferState::destinationPosition
                )
        );
        // Riding waits for destination mass, not the client probe; the source plot is already gone.
        PENDING_ENTITY_RESTORES.add(new PendingEntityRestore(
                transferId,
                destinationContainer.getLevel().dimension(),
                Map.copyOf(replacementTracking),
                Set.copyOf(placement.subLevelUuidMap().values()),
                Map.copyOf(passengerVehicles),
                Map.copyOf(replacementVehicleTargets),
                Map.copyOf(protectedPlayerGravity),
                destinationPlayerPositions,
                0,
                0
        ));
        LOGGER.info("[DEEPSPACE-TRANSFER] id={} phase=SNAPSHOT_RELEASED", transferId);
        return true;
    }

    /** Atomic convenience used by planet entry/exit paths. */
    static boolean WarpSubLevels(
            Collection<SubLevel> compoundSubLevel,
            ServerSubLevelContainer sourceContainer,
            ServerSubLevelContainer destinationContainer,
            Vector3d center,
            Vector3d position,
            long gameTick,
            TransferMotionPolicy motionPolicy
    ) {
        PreparedJump prepared = prepareJump(
                compoundSubLevel,
                sourceContainer,
                destinationContainer,
                center,
                position,
                gameTick,
                motionPolicy
        );
        if (prepared != null) {
            return executeJump(prepared);
        }
        return false;
    }

    /** All intermediate state shared between {@link #prepareJump} and {@link #executeJump}. */
    record PreparedJump(
            UUID transferId,
            Collection<SubLevel> compoundSubLevel,
            ServerSubLevelContainer sourceContainer,
            ServerSubLevelContainer destinationContainer,
            Vector3d center,
            Vector3d position,
            GalaxyArrivalPacket galaxyArrival,
            SableBlueprint snapshot,
            long gameTick,
            TransferMotionPolicy motionPolicy,
            Map<UUID, Set<Entity>> visitedEntities,
            Map<UUID, UUID> trackedSubLevels,
            Map<UUID, UUID> passengerVehicles
    ) {}

    private static Map<UUID, UUID> retainPlayerKeys(
            Map<UUID, UUID> values,
            Map<UUID, Set<Entity>> visitedEntities
    ) {
        Set<UUID> playerIds = new HashSet<>();
        visitedEntities.values().forEach(entities -> entities.stream()
                .filter(ServerPlayer.class::isInstance)
                .forEach(player -> playerIds.add(player.getUUID())));
        Map<UUID, UUID> retained = new HashMap<>();
        values.forEach((entityId, value) -> {
            if (playerIds.contains(entityId)) {
                retained.put(entityId, value);
            }
        });
        return Map.copyOf(retained);
    }

    /** Players move as their live server entities, never as nested blueprint passenger NBT. */
    private static SableBlueprint removeLivePlayersFromSnapshot(SableBlueprint snapshot) {
        CompoundTag encoded = snapshot.save();
        ListTag subLevels = encoded.getList("sub_levels", Tag.TAG_COMPOUND);
        int removed = 0;
        for (int i = 0; i < subLevels.size(); i++) {
            ListTag entities = subLevels.getCompound(i).getList("entities", Tag.TAG_COMPOUND);
            for (int entityIndex = 0; entityIndex < entities.size(); entityIndex++) {
                CompoundTag wrapper = entities.getCompound(entityIndex);
                CompoundTag entity = wrapper.contains("entity", Tag.TAG_COMPOUND)
                        ? wrapper.getCompound("entity")
                        : wrapper;
                removed += removePlayerPassengers(entity);
            }
        }
        if (removed > 0) {
            LOGGER.info("[DEEPSPACE-TRANSFER] phase=SNAPSHOT_PLAYERS_EXCLUDED count={}", removed);
        }
        return SableBlueprint.load(encoded);
    }

    private static int removePlayerPassengers(CompoundTag entity) {
        if (!entity.contains("Passengers", Tag.TAG_LIST)) {
            return 0;
        }
        ListTag passengers = entity.getList("Passengers", Tag.TAG_COMPOUND);
        ListTag retained = new ListTag();
        int removed = 0;
        for (int i = 0; i < passengers.size(); i++) {
            CompoundTag passenger = passengers.getCompound(i);
            if (passenger.getString("id").equals("minecraft:player")) {
                removed++;
                continue;
            }
            removed += removePlayerPassengers(passenger);
            retained.add(passenger);
        }
        entity.put("Passengers", retained);
        return removed;
    }

    /** Capture every participant before detaching anyone, then move the complete player group. */
    private static List<PlayerTransferState> movePlayersBeforeSnapshotPlacement(
            PreparedJump jump,
            Map<UUID, Boolean> protectedPlayerGravity
    ) {
        Map<UUID, ServerPlayer> players = new LinkedHashMap<>();
        List<PlayerTransferState> planned = new ArrayList<>();
        Vector3d delta = new Vector3d(jump.position()).sub(jump.center());
        for (Set<Entity> entities : jump.visitedEntities().values()) {
            for (Entity entity : entities) {
                if (!(entity instanceof ServerPlayer player) || players.putIfAbsent(player.getUUID(), player) != null) continue;
                Vec3 sourcePosition = Sable.HELPER.projectOutOfSubLevel(
                        jump.sourceContainer().getLevel(), player.position());
                // Apply exactly the same translation as the ship snapshot to preserve everyone's relative position.
                planned.add(new PlayerTransferState(player.getUUID(), sourcePosition,
                        sourcePosition.add(delta.x, delta.y, delta.z), player.getYRot(), player.getXRot(),
                        player.isNoGravity(), player.getVehicle() == null ? null : player.getVehicle().getUUID()));
            }
        }
        boolean allChunksReady = true;
        for (PlayerTransferState state : planned) {
            // Recheck the snapshot's complete roster before moving anyone; pilots can occupy different chunks.
            DestinationChunkPreload.request(jump.destinationContainer().getLevel(), state.destinationPosition());
            allChunksReady &= DestinationChunkPreload.isReady(jump.destinationContainer().getLevel(), state.destinationPosition());
        }
        if (!allChunksReady) return null;
        LOGGER.info("[DEEPSPACE-TRANSFER] id={} phase=PLAYER_GROUP_READY players={}",
                jump.transferId(), planned.stream().map(PlayerTransferState::playerId).toList());

        List<PlayerTransferState> moved = new ArrayList<>();
        for (PlayerTransferState state : planned) {
            ServerPlayer player = players.get(state.playerId());
            Vec3 destinationPosition = state.destinationPosition();
            protectedPlayerGravity.put(player.getUUID(), state.wasNoGravity());
            // Include the current player in rollback even if dismounting or teleporting itself fails.
            moved.add(state);
            boolean teleported;
            try {
                player.setNoGravity(true);
                player.unRide();
                if (jump.galaxyArrival() != null) PacketDistributor.sendToPlayer(player, jump.galaxyArrival());
                SeamlessTransitionSignal.begin(player);
                teleported = player.teleportTo(jump.destinationContainer().getLevel(), destinationPosition.x,
                        destinationPosition.y, destinationPosition.z, Set.of(), state.yaw(), state.pitch());
            } catch (RuntimeException exception) {
                LOGGER.error("[DEEPSPACE-TRANSFER] id={} phase=PLAYER_PRELOAD_FAILED player={}",
                        jump.transferId(), player.getUUID(), exception);
                teleported = false;
            }
            if (!teleported) {
                LOGGER.error("[DEEPSPACE-TRANSFER] id={} phase=PLAYER_GROUP_ROLLBACK failedPlayer={}",
                        jump.transferId(), player.getUUID());
                rollbackMovedPlayers(jump, moved);
                return null;
            }
            LOGGER.info("[DEEPSPACE-TRANSFER] id={} phase=PLAYER_PRELOADED player={} destinationPosition={}",
                    jump.transferId(), player.getUUID(), destinationPosition);
        }
        return List.copyOf(moved);
    }

    /** Returns already moved players to the intact source structure when reconstruction fails. */
    private static void rollbackMovedPlayers(PreparedJump jump, Collection<PlayerTransferState> movedPlayers) {
        for (PlayerTransferState state : movedPlayers) {
            ServerPlayer player = jump.sourceContainer().getLevel().getServer().getPlayerList().getPlayer(state.playerId());
            if (player == null) {
                continue;
            }
            player.unRide();
            player.teleportTo(
                    jump.sourceContainer().getLevel(),
                    state.sourcePosition().x,
                    state.sourcePosition().y,
                    state.sourcePosition().z,
                    Set.of(),
                    state.yaw(),
                    state.pitch()
            );
            player.setNoGravity(state.wasNoGravity());
            Entity oldVehicle = state.vehicleId() == null
                    ? null
                    : jump.sourceContainer().getLevel().getEntity(state.vehicleId());
            if (oldVehicle != null && !oldVehicle.isRemoved()) {
                player.startRiding(oldVehicle, true);
            }
        }
        LOGGER.warn("[DEEPSPACE-TRANSFER] id={} phase=PLAYER_ROLLBACK count={}", jump.transferId(), movedPlayers.size());
    }

    private static Map<UUID, ServerLevelPlot> resolvePlacedSubLevels(
            ServerSubLevelContainer destinationContainer,
            Map<UUID, UUID> replacements
    ) {
        Map<UUID, ServerLevelPlot> plots = new HashMap<>();
        replacements.forEach((oldId, newId) -> {
            SubLevel replacement = destinationContainer.getSubLevel(newId);
            if (replacement instanceof ServerSubLevel serverSubLevel) {
                plots.put(oldId, serverSubLevel.getPlot());
            }
        });
        return plots;
    }

    private static boolean placementHasIntegrityFailure(
            SableBlueprintPlacer.Result placement,
            int expectedSubLevels,
            int resolvedSubLevels
    ) {
        boolean failed = placement.placedSubLevels() != expectedSubLevels
                || resolvedSubLevels != expectedSubLevels
                || !placement.diagnostics().isEmpty();
        if (failed) {
            LOGGER.error(
                    "Photomancy reconstruction rejected: placed={} resolved={} expected={} diagnostics={}",
                    placement.placedSubLevels(),
                    resolvedSubLevels,
                    expectedSubLevels,
                    placement.diagnostics().summaryText()
            );
        }
        return failed;
    }

    private static void removePlacedSubLevels(
            ServerSubLevelContainer destinationContainer,
            Collection<ServerLevelPlot> plots,
            UUID transferId
    ) {
        for (ServerLevelPlot plot : plots) {
            try {
                destinationContainer.removeSubLevel(plot.getSubLevel(), SubLevelRemovalReason.REMOVED);
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to remove incomplete Photomancy sublevel for transfer {}", transferId, exception);
            }
        }
    }

    private static HashMap<UUID, SubLevelReplacement> buildReplacementMap(
            Collection<SubLevel> sources,
            Map<UUID, ServerLevelPlot> replacementPlots,
            SableBlueprint snapshot
    ) {
        HashMap<UUID, SubLevelReplacement> replacements = new HashMap<>();
        for (SubLevel source : sources) {
            ServerLevelPlot destinationPlot = replacementPlots.get(source.getUniqueId());
            if (!(source instanceof ServerSubLevel serverSource) || destinationPlot == null) {
                continue;
            }
            // Photomancy centers local block bounds in the destination plot, independently of dimension minY.
            var entry = snapshot.subLevels().stream()
                    .filter(data -> data.sourceUuid().equals(source.getUniqueId())).findFirst().orElseThrow();
            var bounds = entry.localBounds();
            var sourceBounds = serverSource.getPlot().getBoundingBox();
            Vec3i start = new BlockPos(sourceBounds.minX(), sourceBounds.minY(), sourceBounds.minZ());
            Vec3i end = destinationPlot.getCenterBlock().offset(
                    -(bounds.minX() + bounds.maxX()) / 2,
                    -(bounds.minY() + bounds.maxY()) / 2,
                    -(bounds.minZ() + bounds.maxZ()) / 2);
            replacements.put(
                    source.getUniqueId(),
                    new SubLevelReplacement(destinationPlot.getSubLevel().getUniqueId(), end.subtract(start))
            );
        }
        return replacements;
    }

    private static void notifyTransferredPlayers(
            ServerSubLevelContainer destinationContainer,
            Collection<PlayerTransferState> movedPlayers,
            Map<UUID, UUID> replacementTracking,
            UUID transferId,
            Vector3d sourceCenter
    ) {
        for (PlayerTransferState state : movedPlayers) {
            Entity entity = destinationContainer.getLevel().getEntity(state.playerId());
            if (!(entity instanceof ServerPlayer player)) {
                continue;
            }
            UUID expectedSubLevel = replacementTracking.get(state.playerId());
            Vec3 destination = state.destinationPosition();
            // teleportTo already sent the authoritative player position before reconstruction.
            // Repeating it here invokes NeoForge's synchronous Entity.setPosRaw -> getChunk path.
            PacketDistributor.sendToPlayer(player, new SubLevelTransferProbePacket(
                    transferId,
                    destinationContainer.getLevel().dimension().location().toString(),
                    expectedSubLevel,
                    destination.x, destination.y, destination.z,
                    sourceCenter.x, sourceCenter.y, sourceCenter.z
            ));
            LOGGER.info(
                    "[DEEPSPACE-TRANSFER] id={} phase=PLAYER_STRUCTURE_READY player={} expectedSubLevel={}",
                    transferId,
                    state.playerId(),
                    expectedSubLevel
            );
        }
    }

    private static SubLevel findSourceSubLevel(Collection<SubLevel> sources, UUID sourceId) {
        for (SubLevel source : sources) {
            if (source.getUniqueId().equals(sourceId)) {
                return source;
            }
        }
        return null;
    }

    /** Shrinks the destination plot AABB to the snapshot hull instead of the far plot allocation. */
    private static void clampReplacementPlotBounds(ServerLevelPlot plot, SableBlueprint snapshot, UUID sourceId) {
        var entry = snapshot.subLevels().stream()
                .filter(data -> data.sourceUuid().equals(sourceId))
                .findFirst()
                .orElse(null);
        var bounds = entry == null ? null : entry.localBounds();
        if (bounds == null || bounds.volume() <= 0) {
            plot.updateBoundingBox();
            return;
        }
        BlockPos center = plot.getCenterBlock();
        int originX = center.getX() - (bounds.minX() + bounds.maxX()) / 2;
        int originY = center.getY() - (bounds.minY() + bounds.maxY()) / 2;
        int originZ = center.getZ() - (bounds.minZ() + bounds.maxZ()) / 2;
        plot.setBoundingBox(new BoundingBox3i(
                originX + bounds.minX(),
                originY + bounds.minY(),
                originZ + bounds.minZ(),
                originX + bounds.maxX(),
                originY + bounds.maxY(),
                originZ + bounds.maxZ()
        ));
    }

    /** Teleports the replacement body to the destination world pose used by the jump. */
    private static void applyReplacementWorldPose(
            SubLevelPhysicsSystem physics,
            SubLevel source,
            ServerSubLevel copy,
            PreparedJump jump
    ) {
        Vector3d destination = source != null
                ? new Vector3d(source.logicalPose().position()).sub(jump.center()).add(jump.position())
                : new Vector3d(jump.position());
        physics.getPipeline().teleport(
                copy,
                destination,
                source != null ? source.logicalPose().orientation() : copy.logicalPose().orientation()
        );
        copy.logicalPose().position().set(destination);
        if (source != null) {
            copy.logicalPose().orientation().set(source.logicalPose().orientation());
        }
        copy.updateLastPose();
        copy.updateBoundingBox();
        copy.forceUpdateGlobalBounds();
        LOGGER.info(
                "[DEEPSPACE-TRANSFER] id={} phase=REPLACEMENT_POSE_ALIGNED subLevel={} pose={} plotAABB={} worldAABB={}",
                jump.transferId(),
                copy.getUniqueId(),
                copy.logicalPose().position(),
                copy.getPlot().getBoundingBox(),
                copy.boundingBox()
        );
    }

    /** True when every replacement plot exists and already has a valid mass tracker. */
    private static boolean destinationHullReady(ServerSubLevelContainer container, PendingEntityRestore pending) {
        if (container == null || pending.protectedSubLevels().isEmpty()) {
            return false;
        }
        for (UUID subLevelId : pending.protectedSubLevels()) {
            SubLevel subLevel = container.getSubLevel(subLevelId);
            if (!(subLevel instanceof ServerSubLevel serverSubLevel)
                    || serverSubLevel.getMassTracker() == null
                    || serverSubLevel.getMassTracker().isInvalid()) {
                return false;
            }
        }
        return true;
    }

    /** Clears both velocity components after every cross-dimension transfer. */
    private static void applySubLevelMotion(
            SubLevelPhysicsSystem physics,
            ServerSubLevel copy,
            TransferMotionPolicy motionPolicy,
            UUID transferId
    ) {
        // A replacement rigid body always starts stationary, regardless of the source dimension or transfer route.
        physics.getPipeline().resetVelocity(copy);
        if (physics.getPaused()) {
            physics.setPaused(false);
        }
        // A zero-velocity replacement may otherwise remain asleep after reconstruction.
        physics.getPipeline().wakeUp(copy);

        RigidBodyHandle destinationHandle = RigidBodyHandle.of(copy);
        LOGGER.info(
                "[DEEPSPACE-TRANSFER] id={} phase=MOTION_APPLIED subLevel={} policy={} paused={} handleValid={} linear={} angular={}",
                transferId,
                copy.getUniqueId(),
                motionPolicy,
                physics.getPaused(),
                destinationHandle.isValid(),
                destinationHandle.getLinearVelocity(new Vector3d()),
                destinationHandle.getAngularVelocity(new Vector3d())
        );
    }

    /**
     * Includes seats and their riders even when one member falls just outside the sublevel bounds.
     */
    private static Set<Entity> expandRidingGraph(Collection<Entity> candidates) {
        Set<Entity> expanded = new HashSet<>(candidates);
        ArrayDeque<Entity> queue = new ArrayDeque<>(candidates);
        while (!queue.isEmpty()) {
            Entity entity = queue.removeFirst();
            Entity vehicle = entity.getVehicle();
            if (vehicle != null && expanded.add(vehicle)) {
                queue.addLast(vehicle);
            }
            for (Entity passenger : entity.getPassengers()) {
                if (expanded.add(passenger)) {
                    queue.addLast(passenger);
                }
            }
        }
        return expanded;
    }

    /**
     * Captures riding links before teleporting any vehicle, since unRide also ejects its passengers.
     */
    private static Map<UUID, UUID> capturePassengerVehicles(Map<UUID, Set<Entity>> visitedEntities) {
        Map<UUID, UUID> passengerVehicles = new HashMap<>();
        visitedEntities.values().forEach(entities -> entities.forEach(entity -> {
            Entity vehicle = entity.getVehicle();
            // A VSIE control-seat mount may be recreated from its block entity instead of
            // appearing in the Sable entity snapshot, so capture the riding link directly.
            if (vehicle != null) {
                passengerVehicles.put(entity.getUUID(), vehicle.getUUID());
            }
        }));
        return passengerVehicles;
    }

    /** Captures where block-owned vehicles will be recreated in the destination plot. */
    private static Map<UUID, PassengerVehicleTarget> captureReplacementVehicleTargets(
            Map<UUID, Set<Entity>> visitedEntities,
            Map<UUID, UUID> passengerVehicles,
            Map<UUID, SubLevelReplacement> oldToNew
    ) {
        Map<UUID, PassengerVehicleTarget> targets = new HashMap<>();
        for (Map.Entry<UUID, UUID> passengerEntry : passengerVehicles.entrySet()) {
            UUID vehicleId = passengerEntry.getValue();
            for (Map.Entry<UUID, Set<Entity>> subLevelEntry : visitedEntities.entrySet()) {
                Entity vehicle = subLevelEntry.getValue().stream()
                        .filter(entity -> entity.getUUID().equals(vehicleId))
                        .findFirst()
                        .orElse(null);
                if (vehicle == null) {
                    // Seat mounts are sometimes omitted from the snapshot; recover the
                    // original mount through the captured passenger's live riding link.
                    vehicle = subLevelEntry.getValue().stream()
                            .filter(entity -> entity.getUUID().equals(passengerEntry.getKey()))
                            .map(Entity::getVehicle)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);
                }
                BlockPos oldBoundPos = readVsieControlSeatBoundPos(vehicle);
                if (vehicle == null || (!vehicle.getType().is(SableTags.DESTROY_WITH_SUB_LEVEL) && oldBoundPos == null)) {
                    continue;
                }
                SubLevelReplacement replacement = oldToNew.get(subLevelEntry.getKey());
                if (replacement != null) {
                    Vec3i offset = replacement.offset();
                    targets.put(passengerEntry.getKey(), new PassengerVehicleTarget(
                            vehicle.getType(),
                            vehicle.position().add(offset.getX(), offset.getY(), offset.getZ()),
                            oldBoundPos == null ? null : oldBoundPos.offset(offset),
                            replacement.subLevelId(),
                            new SeatMountHandoff()
                    ));
                }
                break;
            }
        }
        return targets;
    }

    /**
     * Restores the entity graph after cross-dimension clones have been added to the destination level.
     */
    private static boolean restorePassengerVehicles(
            ServerLevel destinationLevel,
            Map<UUID, UUID> passengerVehicles,
            Map<UUID, PassengerVehicleTarget> replacementTargets,
            Map<UUID, Vec3> destinationPlayerPositions,
            boolean clientReady,
            UUID transferId
    ) {
        boolean complete = true;
        for (Map.Entry<UUID, UUID> entry : passengerVehicles.entrySet()) {
            UUID passengerId = entry.getKey();
            UUID vehicleId = entry.getValue();
            Entity passenger = destinationLevel.getEntity(passengerId);
            PassengerVehicleTarget replacementTarget = replacementTargets.get(passengerId);
            // VSIE owns mount identity. Never search for, adopt, or rewrite a nearby mount.
            boolean vsieSeat = replacementTarget != null && replacementTarget.boundBlockPos() != null;
            Entity vehicle = vsieSeat
                    ? prepareVsieMount(destinationLevel, replacementTarget, passenger)
                    : destinationLevel.getEntity(vehicleId);
            if (!vsieSeat && vehicle == null && replacementTarget != null) {
                vehicle = findReplacementVehicle(destinationLevel, replacementTarget);
            }
            if (passenger != null && vehicle != null && !passenger.isRemoved() && !vehicle.isRemoved()) {
                // The seat binding is authoritative in VSIE and must remain unchanged.
                SubLevel replacementSubLevel = null;
                if (replacementTarget != null) {
                    ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(destinationLevel);
                    replacementSubLevel = container == null
                            ? null
                            : container.getSubLevel(replacementTarget.replacementSubLevelId());
                }
                rebindTransferredEntity(passenger, replacementSubLevel);
                rebindTransferredEntity(vehicle, replacementSubLevel);
                // Let VSIE position a mounted player; do not teleport a seat passenger to the seat coordinates.
                boolean restored = restoreVsiePassenger(vehicle, passenger);
                if (restored && passenger instanceof ServerPlayer player && clientReady) {
                    // The client must know the replacement entities before it receives either packet.
                    player.connection.send(new ClientboundTeleportEntityPacket(player));
                    player.connection.send(new ClientboundSetPassengersPacket(vehicle));
                    player.setDeltaMovement(Vec3.ZERO);
                    player.fallDistance = 0.0F;
                    EntitySubLevelUtil.setOldPosNoMovement(player);
                }
                if (restored) {
                    // Keep the transfer pending until VSIE accepts the restored seat relationship.
                    restored = notifyVsiePassengerRestore(
                            destinationLevel,
                            replacementTarget,
                            vehicle,
                            passenger
                    );
                }
                if (restored && !vehicle.getUUID().equals(vehicleId)) {
                    LOGGER.info(
                        "[DEEPSPACE-TRANSFER] id={} phase=REPLACEMENT_VEHICLE_RESOLVED passenger={} oldVehicle={} newVehicle={}",
                            transferId,
                            passengerId,
                            vehicleId,
                            vehicle.getUUID()
                    );
                }
                complete &= restored;
            } else {
                complete = false;
            }
        }
        return complete;
    }

    /** Restores the riding link without moving the passenger to a stale seat coordinate. */
    private static boolean restoreVsiePassenger(Entity vehicle, Entity passenger) {
        if (vehicle == null || passenger == null || vehicle.isRemoved() || passenger.isRemoved()) {
            return false;
        }
        if (passenger.getVehicle() == vehicle) {
            return true;
        }
        if (passenger.getVehicle() != null) {
            passenger.stopRiding();
        }
        if (!passenger.startRiding(vehicle, true)) {
            return false;
        }
        return passenger.getVehicle() == vehicle;
    }

    /** Rebinds a recreated seat and rider to the destination plot before the next Sable transform tick. */
    private static void rebindTransferredEntity(Entity entity, SubLevel replacementSubLevel) {
        if (!(entity instanceof EntityMovementExtension extension) || replacementSubLevel == null) {
            return;
        }
        extension.sable$setTrackingSubLevel(replacementSubLevel);
        extension.sable$setLastTrackingSubLevelID(replacementSubLevel.getUniqueId());
        EntitySubLevelUtil.setOldPosNoMovement(entity);
    }

    /** Resolves a freshly spawned helper vehicle without relying on its intentionally new UUID. */
    private static Entity findReplacementVehicle(ServerLevel level, PassengerVehicleTarget target) {
        if (target.boundBlockPos() != null) {
            return resolveVsieMount(level, target);
        }
        AABB search = AABB.ofSize(target.destinationPosition(), 2.0D, 2.0D, 2.0D);
        return level.getEntities((Entity) null, search, entity ->
                        entity.getType() == target.entityType() && !entity.isRemoved())
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.position().distanceToSqr(target.destinationPosition())))
                .orElse(null);
    }

    /** Matches the recreated VSIE mount by its seat block and replacement sublevel, never by stale UUID. */
    private static boolean matchesReplacementVehicle(Entity entity, PassengerVehicleTarget target) {
        if (entity == null || entity.isRemoved() || entity.getType() != target.entityType()) {
            return false;
        }
        BlockPos boundPos = readVsieControlSeatBoundPos(entity);
        if (boundPos == null) {
            return target.boundBlockPos() == null
                    && entity.position().distanceToSqr(target.destinationPosition()) <= 4.0D;
        }
        SubLevel containing = Sable.HELPER.getContaining(entity.level(), boundPos);
        return boundPos.equals(target.boundBlockPos())
                && containing != null && target.replacementSubLevelId().equals(containing.getUniqueId());
    }

    /** Opens the riding barrier after the destination hull arrives, without waiting for seat-driven tracking. */
    public static void markClientTransferReady(
            UUID transferId,
            UUID playerId,
            boolean expectedSubLevelPresent,
            UUID trackedSubLevelId,
            String clientDimension,
            double clientX,
            double clientY,
            double clientZ,
            int observationTick
    ) {
        PendingEntityRestore pending = PENDING_ENTITY_RESTORES.stream()
                .filter(candidate -> candidate.transferId().equals(transferId))
                .findFirst()
                .orElse(null);
        if (pending == null) return;
        UUID expectedSubLevelId = pending.trackedSubLevels().get(playerId);
        Vec3 destination = pending.destinationPlayerPositions().get(playerId);
        if (expectedSubLevelId == null || destination == null) return;
        double dx = clientX - destination.x;
        double dy = clientY - destination.y;
        double dz = clientZ - destination.z;
        // A seated client can drift while dismounted; the exact replacement mount will place it correctly.
        boolean seatedTransfer = pending.passengerVehicles().containsKey(playerId);
        boolean positionReady = pending.dimension().location().toString().equals(clientDimension)
                && (seatedTransfer || dx * dx + dy * dy + dz * dz <= 32.0D * 32.0D);
        boolean ready = SubLevelTransferReadiness.canRestoreRiding(
                expectedSubLevelPresent,
                expectedSubLevelId,
                trackedSubLevelId
        ) && positionReady;
        if (!positionReady && !seatedTransfer && observationTick % 20 == 0) {
            ServerLevel level = pending.dimension() == null ? null : net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().getLevel(pending.dimension());
            Entity entity = level == null ? null : level.getEntity(playerId);
            if (entity instanceof ServerPlayer player) {
                // An entity teleport packet does not move the local player; resend player position explicitly.
                player.connection.teleport(destination.x, destination.y, destination.z,
                        player.getYRot(), player.getXRot());
            }
        }
        if (ready) {
            boolean newlyReady = CLIENT_READY_TRANSFERS
                    .computeIfAbsent(transferId, ignored -> new HashSet<>())
                    .add(playerId);
            if (newlyReady) {
                LOGGER.info(
                        "[DEEPSPACE-TRANSFER] id={} phase=CLIENT_BARRIER_OPEN player={} expectedSubLevel={} trackedSubLevel={}",
                        transferId,
                        playerId,
                        expectedSubLevelId,
                        trackedSubLevelId
                );
            }
        } else if (observationTick % 20 == 0) {
            // Summarize a closed barrier once per second; the client continues probing every tick.
            LOGGER.info(
                    "[DEEPSPACE-TRANSFER] id={} phase=CLIENT_BARRIER_WAIT player={} expectedPresent={} "
                            + "expectedSubLevel={} trackedSubLevel={}",
                    transferId,
                    playerId,
                    expectedSubLevelPresent,
                    expectedSubLevelId,
                    trackedSubLevelId
            );
        }
    }

    private static boolean isClientReady(UUID transferId, UUID playerId) {
        return CLIENT_READY_TRANSFERS.getOrDefault(transferId, Set.of()).contains(playerId);
    }

    /**
     * Records players and other entities glued to a moving Sable sublevel.
     */
    private static Map<UUID, UUID> captureTrackedSubLevels(
            Map<UUID, Set<Entity>> visitedEntities,
            Collection<SubLevel> movingSubLevels
    ) {
        Set<UUID> movingIds = new HashSet<>();
        movingSubLevels.forEach(subLevel -> movingIds.add(subLevel.getUniqueId()));

        Map<UUID, UUID> trackedSubLevels = new HashMap<>();
        visitedEntities.forEach((ownerId, entities) -> entities.forEach(entity -> {
            SubLevel tracked = findTrackedSubLevelInRidingGraph(entity);
            if (tracked != null && movingIds.contains(tracked.getUniqueId())) {
                trackedSubLevels.put(entity.getUUID(), tracked.getUniqueId());
            } else if (entity instanceof ServerPlayer && movingIds.contains(ownerId)) {
                // Hull passengers without prior tracking still need a destination hull for the client barrier.
                trackedSubLevels.putIfAbsent(entity.getUUID(), ownerId);
            }
        }));
        return trackedSubLevels;
    }

    /**
     * Converts tracked old sublevel IDs to the newly allocated destination IDs.
     */
    private static Map<UUID, UUID> remapTrackedSubLevels(
            Map<UUID, UUID> trackedSubLevels,
            Map<UUID, ServerLevelPlot> replacementPlots
    ) {
        Map<UUID, UUID> replacements = new HashMap<>();
        trackedSubLevels.forEach((entityId, oldSubLevelId) -> {
            ServerLevelPlot replacement = replacementPlots.get(oldSubLevelId);
            if (replacement != null) {
                replacements.put(entityId, replacement.getSubLevel().getUniqueId());
            }
        });
        return replacements;
    }

    /**
     * Reattaches server-side Sable tracking so transferred entities keep the new structure loaded and collidable.
     */
    private static boolean restoreTrackedSubLevels(
            ServerSubLevelContainer destinationContainer,
            Map<UUID, UUID> trackedSubLevels
    ) {
        boolean complete = true;
        for (Map.Entry<UUID, UUID> entry : trackedSubLevels.entrySet()) {
            UUID entityId = entry.getKey();
            UUID subLevelId = entry.getValue();
            Entity entity = destinationContainer.getLevel().getEntity(entityId);
            SubLevel subLevel = destinationContainer.getSubLevel(subLevelId);
            if (entity == null || subLevel == null) {
                complete = false;
                LOGGER.info(
                        "[DEEPSPACE-TRANSFER] phase=RESTORE_TRACKING_FAIL entity={} entityPresent={} subLevel={} subLevelPresent={}",
                        entityId,
                        entity != null,
                        subLevelId,
                        subLevel != null
                );
                continue;
            }

            // Sable applies this interface to every Entity; a failed cast indicates a broken Sable runtime.
            EntityMovementExtension extension = (EntityMovementExtension) entity;
            extension.sable$setTrackingSubLevel(subLevel);
            extension.sable$setLastTrackingSubLevelID(subLevelId);
            EntitySubLevelUtil.setOldPosNoMovement(entity);
        }
        return complete;
    }

    /**
     * Runs after the destination level tick so entity clones and client tracking are fully established.
     */
    private static void restorePendingEntities(MinecraftServer server) {
        ListIterator<PendingEntityRestore> iterator = PENDING_ENTITY_RESTORES.listIterator();
        while (iterator.hasNext()) {
            PendingEntityRestore pending = iterator.next();
            ServerLevel level = server.getLevel(pending.dimension());
            boolean complete = false;
            // Every moved player participates in the handshake, not only seat passengers.
            boolean clientsReady = pending.protectedPlayerGravity().keySet().stream()
                    .allMatch(playerId -> isClientReady(pending.transferId(), playerId));
            int restoreAttempts = pending.restoreAttempts();
            // Never clamp this clock: 401 would disable retries and emit a heartbeat every tick.
            int pollTicks = pending.elapsedTicks() + 1;
            holdPendingPlayers(level, pending);
            // Resolve on each server tick; the client readiness reply opens the riding barrier.
            if (level != null) {
                ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
                if (container != null) {
                    // The placement ticket and galaxy observer already retain the replacement plot.
                    restoreAttempts++;
                    boolean trackingRestored = restoreTrackedSubLevels(container, pending.trackedSubLevels());
                    // Mount only after the local player has reached the destination and loaded the replacement hull.
                    boolean mayRestoreRiding = clientsReady && destinationHullReady(container, pending);
                    boolean ridingRestored = mayRestoreRiding && restorePassengerVehicles(
                            level,
                            pending.passengerVehicles(),
                            pending.replacementVehicleTargets(),
                            pending.destinationPlayerPositions(),
                            clientsReady,
                            pending.transferId()
                    );
                    boolean playersRestored = mayRestoreRiding && restoreNonPassengerPlayers(level, pending);
                    complete = clientsReady && trackingRestored && ridingRestored && playersRestored;
                }
            }
            // Periodic heartbeat so a reproduced report shows when the destination sub-level
            // disappears and what its physics state was, without spamming every tick.
            if (level != null && pollTicks % 50 == 1) {
                ServerSubLevelContainer heartbeatContainer = ServerSubLevelContainer.getContainer(level);
                for (Map.Entry<UUID, UUID> entry : pending.trackedSubLevels().entrySet()) {
                    SubLevel heartbeatSubLevel = heartbeatContainer == null
                            ? null
                            : heartbeatContainer.getSubLevel(entry.getValue());
                    LOGGER.info(
                            "[DEEPSPACE-TRANSFER] id={} phase=RESTORE_HEARTBEAT pollTicks={} clientsReady={} subLevel={} subLevelPresent={} mass={} massInvalid={}",
                            pending.transferId(),
                            pollTicks,
                            clientsReady,
                            entry.getValue(),
                            heartbeatSubLevel != null,
                            heartbeatSubLevel instanceof ServerSubLevel serverSubLevel ? serverSubLevel.getMassTracker().getMass() : -1.0,
                            heartbeatSubLevel instanceof ServerSubLevel serverSubLevel && serverSubLevel.getMassTracker().isInvalid()
                    );
                }
            }
            if (complete) {
                LOGGER.info(
                        "[DEEPSPACE-TRANSFER] id={} phase=RESTORE_COMPLETE pollTicks={} resolutionAttempts={}",
                        pending.transferId(),
                        pollTicks,
                        restoreAttempts
                );
                // A valid handle at restoration does not prove the native body keeps the destination pose.
                pending.protectedSubLevels().forEach(subLevelId ->
                        TRANSFER_PHYSICS_DIAGNOSTICS.add(new TransferPhysicsDiagnostic(
                                pending.transferId(), pending.dimension(), subLevelId,
                                Set.copyOf(pending.passengerVehicles().keySet()), 0)));
                restorePlayerGravity(level, pending);
                // Notify each client only after its replacement riding relationship is authoritative.
                pending.protectedPlayerGravity().keySet().forEach(playerId -> {
                    Entity entity = level.getEntity(playerId);
                    if (entity instanceof ServerPlayer player) {
                        Entity vehicle = player.getVehicle();
                        UUID mountId = vehicle != null
                                && VsieControlSeatMountClass.equals(vehicle.getClass().getName())
                                ? vehicle.getUUID() : null;
                        PacketDistributor.sendToPlayer(player, new SubLevelTransferCompletePacket(
                                pending.transferId(), pending.dimension().location().toString(), mountId));
                    }
                });
                pending.protectedPlayerGravity().keySet().forEach(TEMPORARY_CHUNK_SYNC_PLAYERS::remove);
                TRANSFER_GUARD.release(pending.protectedSubLevels());
                CLIENT_READY_TRANSFERS.remove(pending.transferId());
                iterator.remove();
            } else {
                // Keep the transfer lock until seats and foot positions are restored on a valid hull.
                TRANSFER_GUARD.protect(pending.protectedSubLevels(), server.overworld().getGameTime());
                iterator.set(new PendingEntityRestore(
                        pending.transferId(),
                        pending.dimension(),
                        pending.trackedSubLevels(),
                        pending.protectedSubLevels(),
                        pending.passengerVehicles(),
                        pending.replacementVehicleTargets(),
                        pending.protectedPlayerGravity(),
                        pending.destinationPlayerPositions(),
                        pollTicks,
                        restoreAttempts
                ));
            }
        }
    }

    /** Compares Sable's Java pose with the native body through a possible later dismount. */
    private static void sampleTransferPhysics(MinecraftServer server) {
        ListIterator<TransferPhysicsDiagnostic> iterator = TRANSFER_PHYSICS_DIAGNOSTICS.listIterator();
        while (iterator.hasNext()) {
            TransferPhysicsDiagnostic diagnostic = iterator.next();
            int tick = diagnostic.elapsedTicks() + 1;
            if (tick % 20 == 0) {
                ServerLevel level = server.getLevel(diagnostic.dimension());
                ServerSubLevelContainer container = level == null ? null : ServerSubLevelContainer.getContainer(level);
                SubLevel found = container == null ? null : container.getSubLevel(diagnostic.subLevelId());
                if (found instanceof ServerSubLevel subLevel) {
                    // Diagnostics must never interrupt a live transfer if a native handle becomes stale.
                    try {
                        SubLevelPhysicsSystem physics = SubLevelPhysicsSystem.get(level);
                        RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
                        Pose3d nativePose = handle.isValid()
                                ? physics.getPipeline().readPose(subLevel, new Pose3d()) : null;
                        List<String> riders = diagnostic.playerIds().stream().map(playerId -> {
                            Entity player = level.getEntity(playerId);
                            Entity vehicle = player == null ? null : player.getVehicle();
                            return playerId + ":" + (vehicle == null ? "none" : vehicle.getUUID().toString());
                        }).toList();
                        List<String> seatControls = diagnostic.playerIds().stream().map(playerId -> {
                            Entity player = level.getEntity(playerId);
                            return playerId + ":" + describeVsieSeatInput(level, player == null ? null : player.getVehicle());
                        }).toList();
                        LOGGER.info("[DEEPSPACE-TRANSFER-DIAG] id={} side=server tick={} dimension={} subLevel={} logical={} last={} native={} linear={} angular={} inertia={} paused={} handleValid={} riders={} seatControls={}",
                                diagnostic.transferId(), tick, diagnostic.dimension(), diagnostic.subLevelId(),
                                subLevel.logicalPose().position(), subLevel.lastPose().position(),
                                nativePose == null ? null : nativePose.position(),
                                handle.isValid() ? physics.getPipeline().getLinearVelocity(subLevel, new Vector3d()) : null,
                                handle.isValid() ? handle.getAngularVelocity(new Vector3d()) : null,
                                subLevel.getMassTracker() == null ? null : subLevel.getMassTracker().getInertiaTensor(),
                                physics.getPaused(), handle.isValid(), riders, seatControls);
                    } catch (RuntimeException exception) {
                        LOGGER.warn("[DEEPSPACE-TRANSFER-DIAG] id={} side=server tick={} nativeReadFailed={}",
                                diagnostic.transferId(), tick, exception.toString());
                    }
                } else {
                    LOGGER.info("[DEEPSPACE-TRANSFER-DIAG] id={} side=server tick={} dimension={} subLevel={} present=false",
                            diagnostic.transferId(), tick, diagnostic.dimension(), diagnostic.subLevelId());
                }
            }
            if (tick >= 400) {
                iterator.remove();
            } else {
                iterator.set(new TransferPhysicsDiagnostic(diagnostic.transferId(), diagnostic.dimension(),
                        diagnostic.subLevelId(), diagnostic.playerIds(), tick));
            }
        }
    }

    /** Separates an idle seat input path from an inactive Sable rigid body in transfer logs. */
    private static String describeVsieSeatInput(ServerLevel level, Entity vehicle) {
        BlockPos seatPos = readVsieControlSeatBoundPos(vehicle);
        if (seatPos == null) {
            return "no_seat";
        }
        Object seat = level.getBlockEntity(seatPos);
        if (seat == null) {
            return "seat_block_missing";
        }
        try {
            Object data = seat.getClass().getMethod("getServerData").invoke(seat);
            Class<?> dataClass = data.getClass();
            Object throttle = dataClass.getMethod("getThrottle").invoke(data);
            Object force = dataClass.getMethod("getForce").invoke(data);
            Object torque = dataClass.getMethod("getTorque").invoke(data);
            Object appliedForce = dataClass.getMethod("getFinalforce").invoke(data);
            Object appliedTorque = dataClass.getMethod("getFinaltorque").invoke(data);
            Object forceStrength = dataClass.getField("thruster_force_strength").get(data);
            Object torqueStrength = dataClass.getField("thruster_torque_strength").get(data);
            return "throttle=" + throttle + ",inputForce=" + force + ",inputTorque=" + torque
                    + ",appliedForce=" + appliedForce + ",appliedTorque=" + appliedTorque
                    + ",forceStrength=" + forceStrength + ",torqueStrength=" + torqueStrength;
        } catch (ReflectiveOperationException exception) {
            return "unavailable:" + exception.getClass().getSimpleName();
        }
    }

    private record TransferPhysicsDiagnostic(UUID transferId, ResourceKey<Level> dimension, UUID subLevelId,
                                             Set<UUID> playerIds, int elapsedTicks) {
    }

    /** Holds pilots at their safe physical entry point until the client can interpret plot coordinates. */
    private static void holdPendingPlayers(ServerLevel level, PendingEntityRestore pending) {
        if (level == null) {
            return;
        }
        pending.protectedPlayerGravity().keySet().forEach(playerId -> {
            Entity entity = level.getEntity(playerId);
            if (entity instanceof ServerPlayer player && player.getVehicle() == null) {
                player.setDeltaMovement(Vec3.ZERO);
                player.fallDistance = 0.0F;
            }
        });
    }

    /** Confirms that non-seat players remain at their transferred relative positions before release. */
    private static boolean restoreNonPassengerPlayers(ServerLevel level, PendingEntityRestore pending) {
        if (level == null) {
            return false;
        }
        boolean complete = true;
        for (UUID playerId : pending.protectedPlayerGravity().keySet()) {
            Entity entity = level.getEntity(playerId);
            if (!(entity instanceof ServerPlayer player)) {
                complete = false;
                continue;
            }
            if (pending.passengerVehicles().containsKey(playerId)) {
                continue;
            }
            // A recreated seat may already have restored riding even when the captured passenger map
            // did not contain this player; never treat that valid riding state as a failed foot transfer.
            if (player.getVehicle() != null) {
                continue;
            }
            Vec3 destination = pending.destinationPlayerPositions().get(playerId);
            if (destination == null) {
                complete = false;
                continue;
            }
            // Unmounted Sable players may also use plot coordinates; compare physical positions before correction.
            Vec3 projected = Sable.HELPER.projectOutOfSubLevel(level, player.position());
            if (projected.distanceToSqr(destination) > 0.25D) {
                player.teleportTo(level, destination.x, destination.y, destination.z, Set.of(), player.getYRot(), player.getXRot());
                complete = false;
            }
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
        }
        return complete;
    }

    private static void restorePlayerGravity(ServerLevel level, PendingEntityRestore pending) {
        if (level == null) {
            return;
        }
        pending.protectedPlayerGravity().forEach((playerId, wasNoGravity) -> {
            Entity entity = level.getEntity(playerId);
            if (entity instanceof ServerPlayer player) {
                player.setNoGravity(wasNoGravity);
                player.fallDistance = 0.0F;
            }
        });
    }

    /** Lists the exact missing server-side relationship when a transfer probe reaches its deadline. */
    private static String describeUnresolvedEntityState(ServerLevel level, PendingEntityRestore pending) {
        if (level == null) {
            return "destination-level-missing";
        }
        ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
        if (container == null) {
            return "destination-container-missing";
        }
        List<String> unresolved = new ArrayList<>();
        pending.trackedSubLevels().forEach((entityId, subLevelId) -> {
            Entity entity = level.getEntity(entityId);
            SubLevel subLevel = container.getSubLevel(subLevelId);
            if (entity == null || subLevel == null || Sable.HELPER.getTrackingSubLevel(entity) != subLevel) {
                unresolved.add("tracking[entity=" + entityId + ",entityPresent=" + (entity != null)
                        + ",subLevel=" + subLevelId + ",subLevelPresent=" + (subLevel != null)
                        + ",actual=" + (entity == null || Sable.HELPER.getTrackingSubLevel(entity) == null
                        ? null : Sable.HELPER.getTrackingSubLevel(entity).getUniqueId()) + "]");
            }
        });
        pending.passengerVehicles().forEach((passengerId, vehicleId) -> {
            Entity passenger = level.getEntity(passengerId);
            Entity vehicle = level.getEntity(vehicleId);
            PassengerVehicleTarget target = pending.replacementVehicleTargets().get(passengerId);
            Entity actual = passenger == null ? null : passenger.getVehicle();
            boolean ridingReplacement = target != null && matchesReplacementVehicle(actual, target);
            if (passenger == null || (vehicle == null && !ridingReplacement)
                    || (vehicle != null && actual != vehicle && !ridingReplacement)) {
                unresolved.add("riding[passenger=" + passengerId + ",passengerPresent=" + (passenger != null)
                        + ",vehicle=" + vehicleId + ",vehiclePresent=" + (vehicle != null)
                        + ",actual=" + (actual == null ? null : actual.getUUID()) + "]");
            }
        });
        return unresolved.toString();
    }

    private static BlockPos readVsieControlSeatBoundPos(Entity entity) {
        if (entity == null || !VsieControlSeatMountClass.equals(entity.getClass().getName())) {
            return null;
        }
        try {
            return (BlockPos) entity.getClass().getMethod("getBoundBlockPos").invoke(entity);
        } catch (ReflectiveOperationException exception) {
            LOGGER.warn("Failed to read a VSIE control-seat mount binding during dimension transfer", exception);
            return null;
        }
    }

    /** Receives the authoritative UUID and runtime ID directly from the rebuilt VSIE block entity. */
    private static Entity prepareVsieMount(ServerLevel level, PassengerVehicleTarget target, Entity passenger) {
        if (target == null || target.boundBlockPos() == null
                || !(passenger instanceof net.minecraft.world.entity.player.Player player)
                || player.level() != level) {
            return null;
        }
        Entity ready = resolveVsieMount(level, target);
        if (ready != null) {
            return ready;
        }
        Object seat = level.getBlockEntity(target.boundBlockPos());
        if (seat == null) {
            return null;
        }
        try {
            java.util.function.BiConsumer<UUID, Integer> onReady = (uuid, id) -> {
                Entity mount = level.getEntity(id);
                // Check both IDs, the exact seat block, and the destination structure before accepting a receipt.
                if (mount != null && uuid.equals(mount.getUUID()) && matchesReplacementVehicle(mount, target)) {
                    target.mountHandoff().entityUuid = uuid;
                    target.mountHandoff().entityId = id;
                    LOGGER.info("[DEEPSPACE-TRANSFER] phase=VSIE_MOUNT_READY passenger={} mount={} entityId={} seatPos={}",
                            player.getUUID(), uuid, id, target.boundBlockPos());
                }
            };
            seat.getClass().getMethod("prepareExternalPassengerRestore",
                    net.minecraft.world.entity.player.Player.class, java.util.function.BiConsumer.class)
                    .invoke(seat, player, onReady);
        } catch (ReflectiveOperationException exception) {
            if (!target.mountHandoff().errorLogged) {
                target.mountHandoff().errorLogged = true;
                LOGGER.error("VSIE exact mount handoff failed at {}; both mods must support the ID callback",
                        target.boundBlockPos(), exception);
            }
        }
        return resolveVsieMount(level, target);
    }

    /** Resolves only the entity explicitly supplied by VSIE, without enumerating world entities. */
    private static Entity resolveVsieMount(ServerLevel level, PassengerVehicleTarget target) {
        SeatMountHandoff receipt = target.mountHandoff();
        Entity mount = receipt.entityUuid == null ? null : level.getEntity(receipt.entityId);
        return mount != null && receipt.entityUuid.equals(mount.getUUID()) && matchesReplacementVehicle(mount, target)
                ? mount : null;
    }

    /**
     * Confirms the restored rider through the destination seat block first.
     * VSIE owns the seat state there; the mount hook remains a compatibility fallback.
     */
    private static boolean notifyVsiePassengerRestore(
            ServerLevel destinationLevel,
            PassengerVehicleTarget target,
            Entity vehicle,
            Entity passenger
    ) {
        if (vehicle == null || passenger == null || !VsieControlSeatMountClass.equals(vehicle.getClass().getName())) {
            return true;
        }
        if (!(passenger instanceof net.minecraft.world.entity.player.Player player)) {
            return false;
        }
        try {
            Object blockEntity = target == null || target.boundBlockPos() == null || destinationLevel == null
                    ? null
                    : destinationLevel.getBlockEntity(target.boundBlockPos());
            if (blockEntity != null) {
                Object confirmed = blockEntity.getClass()
                        .getMethod(
                                "confirmExternalPassengerRestore",
                                net.minecraft.world.entity.player.Player.class,
                                vehicle.getClass()
                        )
                        .invoke(blockEntity, player, vehicle);
                return logVsiePassengerRestoreResult(
                        confirmed,
                        player,
                        vehicle,
                        target,
                        blockEntity.getClass().getName()
                );
            }

            // Older VSIE builds only expose the mount-level forwarding hook.
            Object confirmed = vehicle.getClass()
                    .getMethod("confirmExternalPassengerRestore", Entity.class)
                    .invoke(vehicle, passenger);
            return logVsiePassengerRestoreResult(
                    confirmed,
                    player,
                    vehicle,
                    target,
                    vehicle.getClass().getName()
            );
        } catch (ReflectiveOperationException exception) {
            LOGGER.warn(
                    "[DEEPSPACE-TRANSFER] VSIE passenger restore confirmation failed passenger={} vehicle={} seatPos={}",
                    passenger.getUUID(),
                    vehicle.getUUID(),
                    target == null ? null : target.boundBlockPos(),
                    exception
            );
            return false;
        }
    }

    private static boolean logVsiePassengerRestoreResult(
            Object confirmed,
            net.minecraft.world.entity.player.Player player,
            Entity vehicle,
            PassengerVehicleTarget target,
            String confirmationOwner
    ) {
            if (confirmed instanceof Boolean success) {
                if (!success) {
                    LOGGER.warn(
                            "[DEEPSPACE-TRANSFER] VSIE passenger restore rejected passenger={} vehicle={} "
                                    + "seatPos={} owner={} passengerVehicle={} sameLevel={} boundPos={}",
                            player.getUUID(),
                            vehicle.getUUID(),
                            target == null ? null : target.boundBlockPos(),
                            confirmationOwner,
                            player.getVehicle() == null ? null : player.getVehicle().getUUID(),
                            player.level() == vehicle.level(),
                            readVsieControlSeatBoundPos(vehicle)
                    );
                }
                return success;
            }
            return false;
    }

    private record PendingEntityRestore(
            UUID transferId,
            ResourceKey<Level> dimension,
            Map<UUID, UUID> trackedSubLevels,
            Set<UUID> protectedSubLevels,
            Map<UUID, UUID> passengerVehicles,
            Map<UUID, PassengerVehicleTarget> replacementVehicleTargets,
            Map<UUID, Boolean> protectedPlayerGravity,
            Map<UUID, Vec3> destinationPlayerPositions,
            int elapsedTicks,
            int restoreAttempts
    ) {}

    private record PassengerVehicleTarget(
            EntityType<?> entityType,
            Vec3 destinationPosition,
            BlockPos boundBlockPos,
            UUID replacementSubLevelId,
            SeatMountHandoff mountHandoff
    ) {}

    /** One transfer owns one exact VSIE mount receipt; it is discarded with that transfer. */
    private static final class SeatMountHandoff {
        UUID entityUuid;
        int entityId = -1;
        boolean errorLogged;
    }

    /** Local replacement mapping used instead of the removed companion mod type. */
    private record SubLevelReplacement(UUID subLevelId, Vec3i offset) {}

    private record PlayerTransferState(
            UUID playerId,
            Vec3 sourcePosition,
            Vec3 destinationPosition,
            float yaw,
            float pitch,
            boolean wasNoGravity,
            UUID vehicleId
    ) {}

    enum TransferMotionPolicy {
        STOP_AT_DESTINATION
    }
}
