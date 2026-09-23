package world.landfall.deepspace.planet;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.integration.InfiniteDimensionsIntegration;
import world.landfall.deepspace.network.GalaxyArrivalPacket;
import world.landfall.deepspace.network.SkyTransitionPacket;
import world.landfall.deepspace.server.DestinationChunkPreload;
import world.landfall.deepspace.server.SeamlessTransitionSignal;
import world.landfall.deepspace.server.SubLevelEvents;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@EventBusSubscriber(modid = Deepspace.MODID)
public class PlanetTeleportHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int SPACE_DISTANCE_FROM_CEILING = 10;
    private static final Map<UUID, PreparedTransition> PREPARED_TRANSITIONS = new HashMap<>();
    private static final Map<UUID, SkySyncState> LAST_SKY_SYNC = new HashMap<>();
    // 只记录每个玩家最后一次规划的虫洞出口，避免在虫洞附近每 tick 重复写日志。
    private static final Map<UUID, String> LAST_WORMHOLE_EXIT_PLAN = new HashMap<>();

    @SubscribeEvent
    public static void serverPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        Planet currentPlanet = PlanetUtils.getPlayerPlanet(player);
        Vec3 transitionPosition = getTransitionPosition(player);
        if (currentPlanet != null) {
            handlePlanetExit(player, currentPlanet, transitionPosition);
            return;
        }

        if (PlanetRegistry.getGalaxyByDimension(player.level().dimension()) != null) {
            handleSpaceApproach(player, transitionPosition);
            return;
        }

        clearTransition(player);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.getEntity().getUUID();
        PREPARED_TRANSITIONS.remove(playerId);
        LAST_SKY_SYNC.remove(playerId);
        LAST_WORMHOLE_EXIT_PLAN.remove(playerId);
    }

    private static void handlePlanetExit(ServerPlayer player, Planet planet, Vec3 transitionPosition) {
        double boundaryY = planet.resolveAtmosphereExitHeight(player.level().getMaxBuildHeight());
        float progress = (float) DimensionTransitionMath.planetExitProgress(transitionPosition.y, boundaryY);
        PreparedTransition prepared = null;
        if (progress > 0.0F) {
            prepared = prepareTransition(
                    player,
                    planet.getGalaxy(),
                    () -> calculatePlanetExitLocation(player.position(), planet),
                    0x000000
            );
            syncSkyTransition(player, progress, prepared == null ? 0x000000 : prepared.skyColor);
        } else {
            clearTransition(player);
        }

        if (transitionPosition.y <= boundaryY
                || SubLevelEvents.findTrackedSubLevelInRidingGraph(player) != null) {
            return;
        }
        ServerLevel destination = player.getServer().getLevel(planet.getGalaxy());
        if (destination == null) {
            return;
        }
        Vec3 target = prepared == null ? calculatePlanetExitLocation(player.position(), planet) : prepared.landing;
        LOGGER.info("Teleporting player {} from {} to Deep Space", player.getDisplayName().getString(), planet.getName());
        // Suppress the vanilla receiving screen without starting the galaxy-arrival white overlay.
        SeamlessTransitionSignal.begin(player);
        player.teleportTo(destination, target.x, target.y, target.z, Set.of(), 0.0F, 0.0F);
        PREPARED_TRANSITIONS.remove(player.getUUID());
    }

    /** Retries a missing galaxy map only after arrival; warp and login remain the primary prewarm paths. */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ResourceKey<Level> galaxy = StarMapExploration.resolveGalaxy(player.level().dimension());
        if (galaxy != null) {
            // A planet dimension still belongs to its galaxy; keep the galaxy queue advancing while on the surface.
            PlanetRegistry.prewarmGeneratedTextures(player.getServer(), PlanetRegistry.getPlanetsForGalaxy(galaxy));
        }
    }

    private static void handleSpaceApproach(ServerPlayer player, Vec3 transitionPosition) {
        // Check every nearby body once per second, including the pilot's projected galaxy position.
        if (player.tickCount % 20 == 0) {
            for (Planet planet : PlanetRegistry.getPlanetsForGalaxy(player.level().dimension())) {
                if (planet.getTexture().isPresent() || planet.isHyperRelay()
                        || planet.getGeneratedTextureTier() == PlanetTextureTier.FULL) continue;
                PlanetTextureGenerator.requestTextureTier(player.getServer(), planet, PlanetTextureTier.FULL);
            }
        }
        Planet closestPlanet = findTouchedOrNearestPlanet(player, transitionPosition);
        if (closestPlanet == null) {
            clearTransition(player);
            return;
        }
        if (closestPlanet.isWormhole()) {
            // Hyper Relay transfers are structure-only; never teleport a standalone player.
            clearTransition(player);
            return;
        }
        if (SubLevelEvents.findTrackedSubLevelInRidingGraph(player) != null) {
            // The rider tick is the authoritative fallback when the global Sable
            // approach pass runs before the player's transformed position updates.
            if (SubLevelEvents.tryEnterPlanetFromRider(player.getServer(), player, closestPlanet)) {
                clearTransition(player);
            }
            return;
        }
        // Resolve a lazy Infinity chain link before selecting its destination level and arrival point.
        Planet destinationBody = closestPlanet.isWormhole()
                ? InfiniteDimensionsIntegration.resolveWormholeDestination(player.getServer(), closestPlanet)
                : closestPlanet;
        ServerLevel destination = player.getServer().getLevel(destinationBody.getDimension());
        if (destination == null) {
            clearTransition(player);
            return;
        }
        Planet pairedWormhole = closestPlanet.isWormhole()
                ? PlanetRegistry.getPairedWormhole(destinationBody.getDimension(), player.level().dimension())
                : null;
        if (closestPlanet.isWormhole() && pairedWormhole == null) {
            LOGGER.error(
                    "[DEEPSPACE-TRANSFER] phase=PLAYER_WORMHOLE_EXIT_REJECTED player={} sourceWormhole={} destination={} reason=missing_paired_wormhole",
                    player.getUUID(), closestPlanet.getId(), destinationBody.getDimension().location()
            );
            clearTransition(player);
            return;
        }

        Vec3 wormholeLanding = pairedWormhole == null
                ? null
                : calculatePlayerWormholeExit(player, transitionPosition, pairedWormhole, destinationBody.getDimension());

        PreparedTransition prepared = null;
        if (destinationBody.isRingWorldEdge()) {
            // 环世界保留正常接近和传送逻辑，但不改变当前天空颜色。
            clearTransition(player);
        } else {
            double diameter = planetDiameter(closestPlanet);
            double distanceToBounds = distanceToBounds(transitionPosition, closestPlanet);
            float progress = (float) DimensionTransitionMath.spaceApproachProgress(distanceToBounds, diameter);
            int planetSkyColor = destinationBody.getSkyColor();
            if (progress > 0.0F) {
                prepared = prepareTransition(
                        player,
                        destinationBody.getDimension(),
                        () -> wormholeLanding != null
                                ? wormholeLanding
                                : calculatePlanetEntryLocation(transitionPosition, destinationBody, destination, player.serverLevel()),
                        planetSkyColor
                );
                syncSkyTransition(
                        player,
                        progress,
                        prepared == null ? planetSkyColor : prepared.skyColor
                );
            } else {
                clearTransition(player);
            }
        }

        if (!closestPlanet.isPlayerTouching(player)) {
            return;
        }
        Vec3 target = prepared == null
                ? (wormholeLanding != null
                        ? wormholeLanding
                        : calculatePlanetEntryLocation(transitionPosition, destinationBody, destination, player.serverLevel()))
                : prepared.landing;
        if (closestPlanet.isWormhole()) {
            GalaxyArrivalPacket arrival = GalaxyArrivalPacket.forDimension(destinationBody.getDimension());
            if (arrival != null) {
                PacketDistributor.sendToPlayer(player, arrival);
            }
        }
        // The arrival packet above remains wormhole-only; this signal merely hides the loading screen.
        SeamlessTransitionSignal.begin(player);
        LOGGER.info(
                "[DEEPSPACE-PLANET-ENTRY] phase=PLAYER_TOUCH_TRANSFER player={} body={} source={} destination={} target={}",
                player.getUUID(),
                closestPlanet.getId(),
                player.level().dimension().location(),
                destination.dimension().location(),
                target
        );
        player.teleportTo(destination, target.x, target.y, target.z, Set.of(), 0.0F, 0.0F);
        if (!closestPlanet.isWormhole()) {
            player.forceAddEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 120, 1, false, true), null);
        }
        PREPARED_TRANSITIONS.remove(player.getUUID());
    }

    /** Prioritizes the actual touched ring edge instead of a neighboring edge with a nearer center. */
    private static Planet findTouchedOrNearestPlanet(ServerPlayer player, Vec3 transitionPosition) {
        return PlanetRegistry.getPlanetsForGalaxy(player.level().dimension()).stream()
                .filter(planet -> planet.intersectsModel(player.getBoundingBox()))
                .min((first, second) -> Double.compare(
                        transitionPosition.distanceTo(first.getCenter()),
                        transitionPosition.distanceTo(second.getCenter())
                ))
                .orElseGet(() -> PlanetUtils.getNearestPlanet(player.level().dimension(), transitionPosition));
    }

    private static PreparedTransition prepareTransition(
            ServerPlayer player,
            ResourceKey<Level> targetDimension,
            Supplier<Vec3> landingSupplier,
            int fallbackSkyColor
    ) {
        PreparedTransition existing = PREPARED_TRANSITIONS.get(player.getUUID());
        if (existing != null && existing.targetDimension.equals(targetDimension)) {
            return existing;
        }
        if (player.getServer().getLevel(targetDimension) == null) {
            return null;
        }

        // 接近阶段就开始预热目标区块，落地时不必再从零生成。
        Vec3 landing = landingSupplier.get();
        DestinationChunkPreload.request(player.getServer().getLevel(targetDimension), landing);
        PreparedTransition prepared = new PreparedTransition(targetDimension, landing, fallbackSkyColor);
        PREPARED_TRANSITIONS.put(player.getUUID(), prepared);
        return prepared;
    }

    private static void syncSkyTransition(ServerPlayer player, float progress, int targetColor) {
        float clamped = Math.max(0.0F, Math.min(1.0F, progress));
        int rgb = targetColor & 0xFFFFFF;
        SkySyncState previous = LAST_SKY_SYNC.get(player.getUUID());
        if (previous != null
                && previous.targetColor == rgb
                && Math.abs(previous.progress - clamped) < 0.015F) {
            return;
        }
        LAST_SKY_SYNC.put(player.getUUID(), new SkySyncState(clamped, rgb));
        PacketDistributor.sendToPlayer(player, new SkyTransitionPacket(clamped, rgb));
    }

    private static void clearTransition(ServerPlayer player) {
        PREPARED_TRANSITIONS.remove(player.getUUID());
        SkySyncState previous = LAST_SKY_SYNC.get(player.getUUID());
        if (previous == null || previous.progress != 0.0F) {
            syncSkyTransition(player, 0.0F, previous == null ? 0x000000 : previous.targetColor);
        }
    }

    private static Vec3 getTransitionPosition(ServerPlayer player) {
        SubLevel tracked = SubLevelEvents.findTrackedSubLevelInRidingGraph(player);
        if (tracked == null) {
            return player.position();
        }
        // Use the pilot's projected host-level position for transition visuals and the on-foot fallback.
        return Sable.HELPER.projectOutOfSubLevel(player.level(), player.position());
    }

    private static double distanceToBounds(Vec3 position, Planet planet) {
        Vec3 min = planet.getBoundingBoxMin();
        Vec3 max = planet.getBoundingBoxMax();
        double dx = Math.max(Math.max(min.x - position.x, 0.0), position.x - max.x);
        double dy = Math.max(Math.max(min.y - position.y, 0.0), position.y - max.y);
        double dz = Math.max(Math.max(min.z - position.z, 0.0), position.z - max.z);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double planetDiameter(Planet planet) {
        Vec3 size = planet.getBoundingBoxMax().subtract(planet.getBoundingBoxMin());
        return Math.max(size.x, Math.max(size.y, size.z));
    }

    private static Vec3 calculatePlanetEntryLocation(
            Vec3 observerPosition,
            Planet planet,
            ServerLevel destination,
            ServerLevel sourceSpace
    ) {
        Vec3 relativePos = planet.getCenter().subtract(observerPosition);
        double planetRadius = Math.max(1.0, Math.abs(planet.getBoundingBoxMin().x - planet.getCenter().x));
        double radialDistance = Math.max(1.0E-6, relativePos.length());
        double azimuth = Math.atan2(relativePos.z, relativePos.x);
        double theta = Math.acos(relativePos.y / radialDistance);
        double levelRadius = Math.abs(planet.getPhysicalMin().x - planet.getPhysicalMax().x) / 2.0;
        Vec3 levelCenter = new Vec3(
                (planet.getPhysicalMin().x + planet.getPhysicalMax().x) / 2.0,
                0.0,
                (planet.getPhysicalMin().y + planet.getPhysicalMax().y) / 2.0
        );

        double entryX;
        double entryZ;
        if (theta > Math.PI * 0.75) {
            entryX = relativePos.x / planetRadius;
            entryZ = -relativePos.z / planetRadius;
        } else if (theta < Math.PI * 0.25) {
            entryX = -relativePos.x / planetRadius;
            entryZ = relativePos.z / planetRadius;
        } else if (azimuth - Math.PI / 4 < -Math.PI || azimuth - Math.PI / 4 > Math.PI / 2) {
            entryX = relativePos.z / planetRadius;
            entryZ = -relativePos.y / planetRadius;
        } else if (azimuth - Math.PI / 4 < -Math.PI / 2) {
            entryX = -relativePos.x / planetRadius;
            entryZ = -relativePos.y / planetRadius;
        } else if (azimuth - Math.PI / 4 < 0) {
            entryX = -relativePos.z / planetRadius;
            entryZ = -relativePos.y / planetRadius;
        } else {
            entryX = relativePos.x / planetRadius;
            entryZ = -relativePos.y / planetRadius;
        }
        if (!sourceSpace.getWorldBorder().isWithinBounds(observerPosition)) {
            entryX = 0.0;
            entryZ = 0.0;
        }
        return new Vec3(
                entryX * levelRadius + levelCenter.x,
                planet.resolveAtmosphereEntryHeight(destination.getMaxBuildHeight()),
                entryZ * levelRadius + levelCenter.z
        );
    }

    /** Maps the surface position to a point just above the matching area of the space model. */
    private static Vec3 calculatePlanetExitLocation(Vec3 surfacePosition, Planet planet) {
        double xFraction = normalizedCoordinate(surfacePosition.x, planet.getPhysicalMin().x, planet.getPhysicalMax().x);
        double zFraction = normalizedCoordinate(surfacePosition.z, planet.getPhysicalMin().y, planet.getPhysicalMax().y);
        Vec3 min = planet.getBoundingBoxMin();
        Vec3 max = planet.getBoundingBoxMax();
        return new Vec3(
                min.x + xFraction * (max.x - min.x),
                max.y + SPACE_DISTANCE_FROM_CEILING,
                min.z + zFraction * (max.z - min.z)
        );
    }

    private static double normalizedCoordinate(double value, double min, double max) {
        if (max <= min) {
            return 0.5D;
        }
        return Math.clamp((value - min) / (max - min), 0.0D, 1.0D);
    }

    /** Places a walking player beside the paired portal using the player's complete current bounds. */
    private static Vec3 calculatePlayerWormholeExit(
            ServerPlayer player,
            Vec3 reference,
            Planet pairedWormhole,
            ResourceKey<Level> destinationGalaxy
    ) {
        var box = player.getBoundingBox();
        WormholeArrivalPlacement.Bounds relativeBounds = new WormholeArrivalPlacement.Bounds(
                box.minX - reference.x, box.minY - reference.y, box.minZ - reference.z,
                box.maxX - reference.x, box.maxY - reference.y, box.maxZ - reference.z
        );
        Vec3 min = pairedWormhole.getBoundingBoxMin();
        Vec3 max = pairedWormhole.getBoundingBoxMax();
        Vec3 galaxyCenter = PlanetRegistry.getSunForGalaxy(destinationGalaxy).getCenter();
        WormholeArrivalPlacement.Point target = WormholeArrivalPlacement.placeOutside(
                new WormholeArrivalPlacement.Bounds(min.x, min.y, min.z, max.x, max.y, max.z),
                relativeBounds,
                new WormholeArrivalPlacement.Point(galaxyCenter.x, galaxyCenter.y, galaxyCenter.z),
                WormholeArrivalPlacement.DEFAULT_CLEARANCE
        );
        // 只在实际出口位置发生变化时输出一次日志，避免同一玩家在虫洞停留期间每 tick 刷屏。
        String planKey = target.x() + "," + target.y() + "," + target.z();
        if (!planKey.equals(LAST_WORMHOLE_EXIT_PLAN.put(player.getUUID(), planKey))) {
            LOGGER.info(
                    "[DEEPSPACE-TRANSFER] phase=PLAYER_WORMHOLE_EXIT_PLANNED player={} destinationWormhole={} target=({},{},{}) relativeBounds={} clearance={}",
                    player.getUUID(), pairedWormhole.getId(), target.x(), target.y(), target.z(), relativeBounds,
                    WormholeArrivalPlacement.DEFAULT_CLEARANCE
            );
        }
        return new Vec3(target.x(), target.y(), target.z());
    }

    private static final class PreparedTransition {
        private final ResourceKey<Level> targetDimension;
        private final Vec3 landing;
        private final int skyColor;

        private PreparedTransition(ResourceKey<Level> targetDimension, Vec3 landing, int skyColor) {
            this.targetDimension = Objects.requireNonNull(targetDimension);
            this.landing = Objects.requireNonNull(landing);
            this.skyColor = skyColor;
        }
    }

    private record SkySyncState(float progress, int targetColor) {
    }
}
