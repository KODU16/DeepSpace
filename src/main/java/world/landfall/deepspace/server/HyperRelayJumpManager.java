package world.landfall.deepspace.server;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.network.HyperRelayJumpStatusPacket;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Two-phase hyper-relay jump.
 *
 * <p>Unlike the old contact teleport, a jump is started explicitly (hyperspace engine redstone pulse
 * or the control-seat K key). The countdown stores only the route. At its execution tick the current
 * Sable snapshot is captured, players move first to load the destination, the snapshot is rebuilt,
 * seats are restored, and only then is the source removed. Any failed rebuild keeps the source and
 * returns the players to it.
 */
@EventBusSubscriber(modid = Deepspace.MODID)
public final class HyperRelayJumpManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Maximum distance, in blocks, between the sub-level bounding box and a relay before K/redstone is ignored. */
    public static final double ACTIVATION_DISTANCE = 200.0D;
    public static final int JUMP_DELAY_TICKS = 5 * 20;

    private static final Map<UUID, PendingJump> PENDING_JUMPS = new HashMap<>();
    private static final Map<UUID, PendingStart> PENDING_STARTS = new HashMap<>();

    private HyperRelayJumpManager() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        processPendingStarts(server);
        Iterator<Map.Entry<UUID, PendingJump>> iterator = PENDING_JUMPS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingJump> entry = iterator.next();
            PendingJump pending = entry.getValue();
            long remaining = pending.executeAtTick() - now;
            if (remaining > 0) {
                // Feedback loop: one heartbeat per second so a reproduced report shows the countdown.
                if (remaining % 20 == 0 && !pending.countdownLogged().contains(remaining / 20)) {
                    pending.countdownLogged().add(remaining / 20);
                    LOGGER.info("[DEEPSPACE-JUMP] id={} phase=COUNTDOWN remainingTicks={} destination={}",
                            entry.getKey(), remaining, pending.plan().destinationContainer().getLevel().dimension().location());
                }
                continue;
            }
            iterator.remove();
            PendingJumpPlan plan = pending.plan();
            // Resolve the live craft pose and landing geometry at execution, as planetary transfers do.
            SubLevel trigger = plan.trigger();
            boolean sourceReady = plan.sourceContainer().getSubLevel(trigger.getUniqueId()) == trigger;
            SubLevelEvents.HyperRelayJumpPlan current = sourceReady
                    ? SubLevelEvents.planWormholeJump(server, plan.relay(), trigger,
                            plan.sourceContainer().getLevel().dimension())
                    : null;
            boolean sameStructure = current != null
                    && current.connected().stream().map(SubLevel::getUniqueId).collect(java.util.stream.Collectors.toSet())
                    .equals(plan.connected().stream().map(SubLevel::getUniqueId)
                            .collect(java.util.stream.Collectors.toSet()));
            boolean executed = sameStructure && SubLevelEvents.WarpSubLevels(
                    plan.connected(),
                    plan.sourceContainer(),
                    current.destinationContainer(),
                    current.center(),
                    current.destination(),
                    now,
                    SubLevelEvents.TransferMotionPolicy.STOP_AT_DESTINATION
            );
            if (!executed) {
                SubLevelEvents.releaseTransfer(plan.connected());
            }
            LOGGER.info("[DEEPSPACE-JUMP] id={} phase={} destination={}",
                    entry.getKey(), executed ? "EXECUTED" : "FAILED",
                    plan.destinationContainer().getLevel().dimension().location());
        }
    }

    private static void processPendingStarts(MinecraftServer server) {
        Iterator<Map.Entry<UUID, PendingStart>> iterator = PENDING_STARTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingStart> entry = iterator.next();
            PendingStart pending = entry.getValue();
            ServerLevel galaxyLevel = server.getLevel(pending.galaxy());
            ServerSubLevelContainer source = galaxyLevel == null ? null : ServerSubLevelContainer.getContainer(galaxyLevel);
            // Readiness drives preparation; a removed or transferred source cancels the accepted request.
            StartResult result = source == null
                    || source.getSubLevel(pending.subLevel().getUniqueId()) != pending.subLevel()
                    ? StartResult.REJECTED
                    : tryStartJump(server, galaxyLevel, pending.subLevel(), pending.relay());
            if (result != StartResult.RETRY_LATER) {
                iterator.remove();
                notifyPilots(server, pending.subLevel());
            }
        }
    }

    /** Keeps the seated pilot's button state and countdown in sync. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.serverLevel() == null || player.tickCount % 10 != 0) {
            return;
        }
        SubLevel subLevel = SubLevelEvents.findTrackedSubLevelInRidingGraph(player);
        if (subLevel == null) {
            return;
        }
        ResourceKey<Level> galaxy = player.serverLevel().dimension();
        if (PlanetRegistry.getGalaxyByDimension(galaxy) == null) {
            return;
        }
        sendStatus(player, subLevel);
    }

    /** Sends server-confirmed preparation and countdown state immediately to the pilot. */
    public static void sendStatus(ServerPlayer player, SubLevel subLevel) {
        double nearest = nearestRelayDistance(player.serverLevel().dimension(), subLevel);
        boolean inRange = nearest < ACTIVATION_DISTANCE;
        int countdown = countdownFor(subLevel);
        PacketDistributor.sendToPlayer(player, new HyperRelayJumpStatusPacket(inRange, nearest, countdown));
    }

    private static void notifyPilots(MinecraftServer server, SubLevel subLevel) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (SubLevelEvents.findTrackedSubLevelInRidingGraph(player) == subLevel) {
                sendStatus(player, subLevel);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // A subsequent world must not inherit an unresolved request from this server.
        PENDING_STARTS.clear();
        PENDING_JUMPS.clear();
    }

    private static int countdownFor(SubLevel subLevel) {
        long now = -1;
        UUID targetId = subLevel.getUniqueId();
        for (PendingJump pending : PENDING_JUMPS.values()) {
            if (pending.plan().connected().stream().anyMatch(s -> s.getUniqueId().equals(targetId))) {
                if (now < 0) {
                    // Resolve the current tick lazily; PENDING_JUMPS only mutates on the server thread.
                    now = pending.plan().sourceContainer().getLevel().getServer().overworld().getGameTime();
                }
                return (int) Math.max(0, pending.executeAtTick() - now);
            }
        }
        return PENDING_STARTS.containsKey(targetId) ? -1 : 0;
    }

    /** Attempts to schedule a jump for the sub-level containing {@code blockPos} in the given galaxy level. */
    public static boolean tryStartJump(MinecraftServer server, ServerLevel galaxyLevel, SubLevel subLevel) {
        UUID subLevelId = subLevel.getUniqueId();
        if (PENDING_STARTS.containsKey(subLevelId) || isJumpScheduled(subLevel)) {
            // Duplicate button presses acknowledge the active request instead of starting another route.
            notifyPilots(server, subLevel);
            return false;
        }

        Planet relay = findNearestRelayInRange(galaxyLevel.dimension(), subLevel);
        if (relay != null && PlanetRegistry.getGalaxyByDimension(galaxyLevel.dimension()) != null) {
            // Acknowledge the route before lazy world generation; the client shows preparation immediately.
            PENDING_STARTS.put(subLevelId, new PendingStart(galaxyLevel.dimension(), subLevel, relay));
            notifyPilots(server, subLevel);
        }
        StartResult result = tryStartJump(server, galaxyLevel, subLevel, relay);
        if (result == StartResult.RETRY_LATER) {
            LOGGER.info("[DEEPSPACE-JUMP] phase=PENDING_START subLevel={} dimension={}", subLevelId, galaxyLevel.dimension().location());
        } else {
            PENDING_STARTS.remove(subLevelId);
        }
        notifyPilots(server, subLevel);
        return result == StartResult.STARTED;
    }

    private static StartResult tryStartJump(
            MinecraftServer server,
            ServerLevel galaxyLevel,
            SubLevel subLevel,
            @Nullable Planet relay
    ) {
        ResourceKey<Level> galaxy = galaxyLevel.dimension();
        if (PlanetRegistry.getGalaxyByDimension(galaxy) == null) {
            LOGGER.info("[DEEPSPACE-JUMP] phase=REJECTED reason=not_a_galaxy dimension={}", galaxy.location());
            return StartResult.REJECTED;
        }
        if (isJumpScheduled(subLevel)) {
            LOGGER.info("[DEEPSPACE-JUMP] phase=REJECTED reason=jump_already_scheduled subLevel={}", subLevel.getUniqueId());
            return StartResult.REJECTED;
        }
        if (relay == null || !relay.getGalaxy().equals(galaxy)
                || distanceToRelay(subLevel, relay) >= ACTIVATION_DISTANCE) {
            LOGGER.info("[DEEPSPACE-JUMP] phase=REJECTED reason=no_relay_in_range dimension={} distance={}",
                    galaxy.location(), nearestRelayDistance(galaxy, subLevel));
            return StartResult.REJECTED;
        }
        long gameTick = server.overworld().getGameTime();
        SubLevelEvents.HyperRelayJumpPlan plan = SubLevelEvents.planWormholeJump(server, relay, subLevel, galaxy);
        if (plan == null) {
            if (!PENDING_STARTS.containsKey(subLevel.getUniqueId())) {
                LOGGER.info("[DEEPSPACE-JUMP] phase=DEFERRED reason=plan_pending relay={}", relay.getId());
            }
            return StartResult.RETRY_LATER;
        }
        ServerSubLevelContainer sourceContainer = ServerSubLevelContainer.getContainer(galaxyLevel);
        if (sourceContainer == null) {
            LOGGER.info("[DEEPSPACE-JUMP] phase=REJECTED reason=no_source_container dimension={}", galaxy.location());
            return StartResult.REJECTED;
        }
        Collection<SubLevel> connected = plan.connected();
        if (!SubLevelEvents.claimTransfer(connected, gameTick)) {
            LOGGER.info("[DEEPSPACE-JUMP] phase=REJECTED reason=transfer_guard subLevel={}", subLevel.getUniqueId());
            return StartResult.REJECTED;
        }
        UUID jumpId = UUID.randomUUID();
        PendingJumpPlan pendingPlan = new PendingJumpPlan(
                subLevel,
                connected,
                sourceContainer,
                plan.destinationContainer(),
                relay
        );
        PENDING_JUMPS.put(jumpId, new PendingJump(pendingPlan, gameTick + JUMP_DELAY_TICKS));
        ResourceKey<Level> destinationGalaxy = pendingPlan.destinationContainer().getLevel().dimension();
        // Use the five-second countdown to sample the destination before the transfer completes.
        PlanetRegistry.prewarmGeneratedTextures(
                server,
                PlanetRegistry.getPlanetsForGalaxy(destinationGalaxy),
                relay.getWarpTarget().orElse(null)
        );
        LOGGER.info(
                "[DEEPSPACE-JUMP] id={} phase=SCHEDULED relay={} source={} destination={} delayTicks={} distance={}",
                jumpId,
                relay.getId(),
                galaxy.location(),
                pendingPlan.destinationContainer().getLevel().dimension().location(),
                JUMP_DELAY_TICKS,
                distanceToRelay(subLevel, relay)
        );
        return StartResult.STARTED;
    }

    /** Covers lazy destination preparation and the countdown for every connected ship member. */
    public static boolean isPreparingJump(SubLevel subLevel) {
        if (isJumpScheduled(subLevel)) return true;
        UUID targetId = subLevel.getUniqueId();
        return PENDING_STARTS.values().stream().anyMatch(pending ->
                SubLevelHelper.getConnectedChain(pending.subLevel()).stream()
                        .anyMatch(member -> member.getUniqueId().equals(targetId)));
    }

    private static boolean isJumpScheduled(SubLevel subLevel) {
        UUID subLevelId = subLevel.getUniqueId();
        for (PendingJump pending : PENDING_JUMPS.values()) {
            if (pending.plan().connected().stream().anyMatch(candidate -> candidate.getUniqueId().equals(subLevelId))) {
                return true;
            }
        }
        return false;
    }

    /** Returns the nearest relay within range, or null when every relay is farther than {@link #ACTIVATION_DISTANCE}. */
    @Nullable
    public static Planet findNearestRelayInRange(ResourceKey<Level> galaxy, SubLevel subLevel) {
        Planet nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Planet planet : PlanetRegistry.getPlanetsForGalaxy(galaxy)) {
            if (!planet.isWormhole()) {
                continue;
            }
            double distance = distanceToRelay(subLevel, planet);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = planet;
            }
        }
        return nearestDistance < ACTIVATION_DISTANCE ? nearest : null;
    }

    /** Distance to the nearest relay, or {@code Double.POSITIVE_INFINITY} when there is no relay. */
    public static double nearestRelayDistance(ResourceKey<Level> galaxy, SubLevel subLevel) {
        double nearest = Double.POSITIVE_INFINITY;
        for (Planet planet : PlanetRegistry.getPlanetsForGalaxy(galaxy)) {
            if (!planet.isWormhole()) {
                continue;
            }
            nearest = Math.min(nearest, distanceToRelay(subLevel, planet));
        }
        return nearest;
    }

    /** Minimum distance between the sub-level's world-space bounding box and a relay's model bounds. */
    public static double distanceToRelay(SubLevel subLevel, Planet relay) {
        return distanceBetweenBounds(subLevelBounds(subLevel), relay.getModelBounds());
    }

    static AABB subLevelBounds(SubLevel subLevel) {
        // The true world bounds include hull rotation and any offset between geometry and center of mass.
        var bounds = subLevel.boundingBox();
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    static double distanceBetweenBounds(AABB first, AABB second) {
        double dx = Math.max(0.0, Math.max(first.minX - second.maxX, second.minX - first.maxX));
        double dy = Math.max(0.0, Math.max(first.minY - second.maxY, second.minY - first.maxY));
        double dz = Math.max(0.0, Math.max(first.minZ - second.maxZ, second.minZ - first.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static final class PendingJump {
        private final PendingJumpPlan plan;
        private final long executeAtTick;
        private final java.util.Set<Long> countdownLogged = new java.util.HashSet<>();

        private PendingJump(PendingJumpPlan plan, long executeAtTick) {
            this.plan = plan;
            this.executeAtTick = executeAtTick;
        }

        private PendingJumpPlan plan() {
            return plan;
        }

        private long executeAtTick() {
            return executeAtTick;
        }

        private java.util.Set<Long> countdownLogged() {
            return countdownLogged;
        }
    }

    /** Defers snapshot capture until the exact execution tick. */
    private record PendingJumpPlan(
            SubLevel trigger,
            Collection<SubLevel> connected,
            ServerSubLevelContainer sourceContainer,
            ServerSubLevelContainer destinationContainer,
            Planet relay
    ) {}

    private enum StartResult {
        STARTED,
        RETRY_LATER,
        REJECTED
    }

    private record PendingStart(
            ResourceKey<Level> galaxy,
            SubLevel subLevel,
            Planet relay
    ) {}
}

