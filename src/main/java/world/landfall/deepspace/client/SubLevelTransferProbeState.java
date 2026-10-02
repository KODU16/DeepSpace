package world.landfall.deepspace.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.network.SubLevelTransferProbePacket;
import world.landfall.deepspace.network.SubLevelTransferProbeReplyPacket;

import java.util.UUID;
import org.joml.Vector3d;
import org.slf4j.Logger;

/** Reports sub-level readiness until the server acknowledges the completed transfer. */
@EventBusSubscriber(modid = Deepspace.MODID, value = Dist.CLIENT)
public final class SubLevelTransferProbeState {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ActiveProbe activeProbe;
    private static StaleSourceGuard staleSourceGuard;
    private static Pose3d destinationAnchor;
    private static int rejectedSnapshots;
    private static int acceptedSnapshots;
    private static int lateRejectedSnapshots;
    private static int clockRebases;
    private static Vector3d lastAcceptedPosition;

    private SubLevelTransferProbeState() {
    }

    public static void begin(SubLevelTransferProbePacket packet) {
        ClientSeatInputRecovery.beginTransfer(packet.transferId());
        staleSourceGuard = null;
        destinationAnchor = null;
        rejectedSnapshots = 0;
        acceptedSnapshots = 0;
        lateRejectedSnapshots = 0;
        clockRebases = 0;
        lastAcceptedPosition = null;
        activeProbe = new ActiveProbe(packet.transferId(), packet.destinationDimension(), packet.expectedSubLevelId(),
                packet.destinationX(), packet.destinationY(), packet.destinationZ(),
                packet.sourceX(), packet.sourceY(), packet.sourceZ(), 0, false);
        LOGGER.info("[DEEPSPACE-TRANSFER] id={} phase=CLIENT_PROBE_BEGIN dimension={} subLevel={}",
                packet.transferId(), packet.destinationDimension(), packet.expectedSubLevelId());
        // Repair as soon as the handshake arrives, before the next client physics tick.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null
                && packet.destinationDimension().equals(minecraft.level.dimension().location().toString())) {
            ClientSubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
            SubLevel expected = container == null || packet.expectedSubLevelId() == null
                    ? null : container.getSubLevel(packet.expectedSubLevelId());
            if (expected instanceof ClientSubLevel clientSubLevel) {
                repairTransferredPose(activeProbe, clientSubLevel, 0);
            }
        }
    }

    /** Rejects a source-galaxy UDP pose before Sable can attach it to a reused plot coordinate. */
    public static boolean rejectStaleSnapshot(ClientSubLevel subLevel, Pose3dc pose) {
        ActiveProbe probe = activeProbe;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || subLevel == null || pose == null) {
            return false;
        }
        String dimension = minecraft.level.dimension().location().toString();
        if (probe == null) {
            StaleSourceGuard guard = staleSourceGuard;
            if (guard == null || guard.expectedSubLevelId() == null
                    || !guard.destinationDimension().equals(dimension)
                    || !guard.expectedSubLevelId().equals(subLevel.getUniqueId())) {
                return false;
            }
            // After the handshake, reject only old-galaxy poses so the ship may move freely.
            boolean oldSource = distanceToSourceSqr(pose, guard) <= 512.0D * 512.0D
                    && distanceToAcceptedSqr(pose, guard) > 512.0D * 512.0D;
            if (oldSource && ++lateRejectedSnapshots == 1) {
                LOGGER.warn("[DEEPSPACE-TRANSFER] id={} phase=LATE_SOURCE_SNAPSHOT_REJECTED subLevel={}",
                        guard.transferId(), subLevel.getUniqueId());
            }
            if (!oldSource) {
                staleSourceGuard = guard.withAccepted(pose);
            }
            return oldSource;
        }
        if (probe.expectedSubLevelId() == null
                || !probe.destinationDimension().equals(dimension)
                || !probe.expectedSubLevelId().equals(subLevel.getUniqueId())) {
            return false;
        }
        boolean stale = distanceToDestinationSqr(pose, probe) > 128.0D * 128.0D;
        if (stale) {
            if (++rejectedSnapshots == 1) {
                LOGGER.warn("[DEEPSPACE-TRANSFER] id={} phase=STALE_SNAPSHOT_REJECTED subLevel={} firstSnapshot={}",
                        probe.transferId(), subLevel.getUniqueId(), pose.position());
            }
        } else {
            acceptedSnapshots++;
            lastAcceptedPosition = new Vector3d(pose.position());
        }
        return stale;
    }

    /** Limits clock recovery to a validated snapshot of the transfer's destination structure. */
    public static boolean isExpectedDestinationSnapshot(ClientSubLevel subLevel, Pose3dc pose) {
        ActiveProbe probe = activeProbe;
        StaleSourceGuard guard = staleSourceGuard;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || subLevel == null || pose == null) {
            return false;
        }
        String dimension = minecraft.level.dimension().location().toString();
        if (probe != null) {
            return probe.expectedSubLevelId() != null
                    && probe.destinationDimension().equals(dimension)
                    && probe.expectedSubLevelId().equals(subLevel.getUniqueId())
                    && distanceToDestinationSqr(pose, probe) <= 128.0D * 128.0D;
        }
        // Late source-clock packets can arrive after the riding handshake closes.
        return guard != null && guard.destinationDimension().equals(dimension)
                && guard.expectedSubLevelId().equals(subLevel.getUniqueId());
    }

    /** Records a clock correction so future logs distinguish interpolation loss from stopped physics. */
    public static void recordClockRebase(ClientSubLevel subLevel, int snapshotTick, double oldPointer) {
        if (++clockRebases == 1) {
            LOGGER.warn("[DEEPSPACE-TRANSFER] id={} phase=CLIENT_CLOCK_REBASED subLevel={} snapshotTick={} oldPointer={}",
                    activeProbe != null ? activeProbe.transferId()
                            : staleSourceGuard == null ? null : staleSourceGuard.transferId(),
                    subLevel.getUniqueId(), snapshotTick, oldPointer);
        }
    }

    /** Ends observation only when the server confirms that riding restoration finished. */
    public static void complete(UUID transferId) {
        if (activeProbe != null && activeProbe.transferId().equals(transferId)) {
            ActiveProbe probe = activeProbe;
            LOGGER.info("[DEEPSPACE-TRANSFER] id={} phase=CLIENT_SNAPSHOT_SUMMARY accepted={} rejected={} clockRebases={}",
                    transferId, acceptedSnapshots, rejectedSnapshots, clockRebases);
            // Keep the transfer identity after riding restoration to reject old-plot UDP poses.
            staleSourceGuard = new StaleSourceGuard(transferId, probe.destinationDimension(),
                    probe.expectedSubLevelId(), probe.sourceX(), probe.sourceY(), probe.sourceZ(),
                    lastAcceptedPosition);
            activeProbe = null;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ActiveProbe probe = activeProbe;
        if (probe == null) {
            return;
        }
        int elapsed = probe.elapsedTicks();
        // One ready observation is enough; the server's completion packet closes this handshake.
        boolean ready = probe.ready() || sendObservation(probe, elapsed);
        activeProbe = new ActiveProbe(
                probe.transferId(),
                probe.destinationDimension(),
                probe.expectedSubLevelId(),
                probe.destinationX(), probe.destinationY(), probe.destinationZ(),
                probe.sourceX(), probe.sourceY(), probe.sourceZ(),
                elapsed + 1,
                ready
        );
    }

    private static boolean sendObservation(ActiveProbe probe, int elapsedTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return false;
        }
        SubLevel tracked = findTrackedSubLevel(minecraft.player);
        ClientSubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        boolean expectedPresent = probe.expectedSubLevelId() != null
                && container != null
                && hasLoadedChunks(container, probe.expectedSubLevelId());
        SubLevel expected = container == null || probe.expectedSubLevelId() == null
                ? null : container.getSubLevel(probe.expectedSubLevelId());
        if (expected instanceof ClientSubLevel clientSubLevel
                && probe.destinationDimension().equals(minecraft.level.dimension().location().toString())
                && !probe.ready()) {
            repairTransferredPose(probe, clientSubLevel, elapsedTicks);
        }
        boolean expectedPoseReady = expected != null
                && distanceToDestinationSqr(expected.logicalPose(), probe) <= 128.0D * 128.0D
                && distanceToDestinationSqr(expected.lastPose(), probe) <= 128.0D * 128.0D
                && (!(expected instanceof ClientSubLevel clientSubLevel)
                        || distanceToDestinationSqr(clientSubLevel.getInterpolator().getInterpolatedPose(), probe)
                                <= 128.0D * 128.0D);
        // A repaired local pose alone is insufficient; the destination must send a real snapshot.
        // The barrier requires retained snapshots; received packets alone can be discarded by Sable's clock.
        boolean poseSettled = expectedPoseReady && acceptedSnapshots > 0
                && expected instanceof ClientSubLevel clientSubLevel
                && !clientSubLevel.getInterpolator().buffer.isEmpty();
        Entity vehicle = minecraft.player.getVehicle();
        if (elapsedTicks % 20 == 0) {
            LOGGER.info("[DEEPSPACE-TRANSFER-DIAG] id={} side=client tick={} dimension={} player={} oldPosition=({},{},{}) vehicle={} tracked={} hull={} poseReady={} logical={} last={} snapshotBuffer={} seatInputBinding={}",
                    probe.transferId(), elapsedTicks, minecraft.level.dimension().location(),
                    minecraft.player.position(), minecraft.player.xOld, minecraft.player.yOld, minecraft.player.zOld,
                    vehicle == null ? null : vehicle.getUUID(),
                    tracked == null ? null : tracked.getUniqueId(), expectedPresent, expectedPoseReady,
                    expected == null ? null : expected.logicalPose().position(),
                    expected == null ? null : expected.lastPose().position(),
                    expected instanceof ClientSubLevel clientSubLevel
                            ? clientSubLevel.getInterpolator().buffer.size() : -1,
                    ClientSeatInputRecovery.bindingState());
        }
        PacketDistributor.sendToServer(new SubLevelTransferProbeReplyPacket(
                probe.transferId(),
                elapsedTicks,
                minecraft.level.dimension().location().toString(),
                minecraft.player.getX(),
                minecraft.player.getY(),
                minecraft.player.getZ(),
                expectedPresent,
                poseSettled,
                tracked == null ? null : tracked.getUniqueId(),
                vehicle == null ? null : vehicle.getUUID()
        ));
        // A tracked plot alone is insufficient when the local player still has source-galaxy coordinates.
        double dx = minecraft.player.getX() - probe.destinationX();
        double dy = minecraft.player.getY() - probe.destinationY();
        double dz = minecraft.player.getZ() - probe.destinationZ();
        return expectedPresent && poseSettled && tracked != null
                && probe.expectedSubLevelId().equals(tracked.getUniqueId())
                && probe.destinationDimension().equals(minecraft.level.dimension().location().toString())
                && dx * dx + dy * dy + dz * dz <= 32.0D * 32.0D;
    }

    /** Discards source-galaxy snapshots that Sable can apply to the replacement plot during dimension change. */
    private static void repairTransferredPose(ActiveProbe probe, ClientSubLevel subLevel, int elapsedTicks) {
        if (destinationAnchor == null) {
            if (distanceToDestinationSqr(subLevel.lastPose(), probe) <= 128.0D * 128.0D) {
                destinationAnchor = new Pose3d(subLevel.lastPose());
            } else if (distanceToDestinationSqr(subLevel.logicalPose(), probe) <= 128.0D * 128.0D) {
                destinationAnchor = new Pose3d(subLevel.logicalPose());
            }
        }
        if (destinationAnchor == null) {
            return;
        }
        boolean stalePose = distanceToDestinationSqr(subLevel.logicalPose(), probe) > 128.0D * 128.0D
                || distanceToDestinationSqr(subLevel.getInterpolator().getInterpolatedPose(), probe) > 128.0D * 128.0D;
        int removedSnapshots;
        synchronized (subLevel.getInterpolator().buffer) {
            // Preserve valid destination snapshots; clearing them leaves interpolation without motion updates.
            int previousSize = subLevel.getInterpolator().buffer.size();
            subLevel.getInterpolator().buffer.removeIf(
                    snapshot -> distanceToDestinationSqr(snapshot.pose(), probe) > 128.0D * 128.0D);
            removedSnapshots = previousSize - subLevel.getInterpolator().buffer.size();
        }
        if (!stalePose && removedSnapshots == 0) {
            return;
        }
        if (!stalePose) {
            LOGGER.warn("[DEEPSPACE-TRANSFER] id={} phase=STALE_SNAPSHOTS_REMOVED tick={} subLevel={} removed={} retained={}",
                    probe.transferId(), elapsedTicks, subLevel.getUniqueId(), removedSnapshots,
                    subLevel.getInterpolator().buffer.size());
            return;
        }
        subLevel.getInterpolator().setFirstPoses(destinationAnchor, destinationAnchor);
        if (subLevel.getInterpolator().getInterpolatedPose() instanceof Pose3d runningPose) {
            runningPose.set(destinationAnchor);
        }
        subLevel.logicalPose().set(destinationAnchor);
        subLevel.updateLastPose();
        subLevel.forceUpdateBounds();
        LOGGER.warn("[DEEPSPACE-TRANSFER] id={} phase=STALE_CLIENT_POSE_DISCARDED tick={} subLevel={} restoredPose={} removedSnapshots={} retainedSnapshots={}",
                probe.transferId(), elapsedTicks, subLevel.getUniqueId(), destinationAnchor.position(),
                removedSnapshots, subLevel.getInterpolator().buffer.size());
    }

    private static double distanceToDestinationSqr(dev.ryanhcode.sable.companion.math.Pose3dc pose, ActiveProbe probe) {
        double dx = pose.position().x() - probe.destinationX();
        double dy = pose.position().y() - probe.destinationY();
        double dz = pose.position().z() - probe.destinationZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceToAcceptedSqr(Pose3dc pose, StaleSourceGuard guard) {
        double dx = pose.position().x() - guard.lastAcceptedPosition().x;
        double dy = pose.position().y() - guard.lastAcceptedPosition().y;
        double dz = pose.position().z() - guard.lastAcceptedPosition().z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceToSourceSqr(Pose3dc pose, StaleSourceGuard guard) {
        double dx = pose.position().x() - guard.sourceX();
        double dy = pose.position().y() - guard.sourceY();
        double dz = pose.position().z() - guard.sourceZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Requires real plot data before opening the transfer barrier. */
    private static boolean hasLoadedChunks(ClientSubLevelContainer container, UUID subLevelId) {
        SubLevel subLevel = container.getSubLevel(subLevelId);
        return subLevel instanceof ClientSubLevel clientSubLevel
                && clientSubLevel.getPlot() != null
                && !clientSubLevel.getPlot().getLoadedChunks().isEmpty();
    }

    private static SubLevel findTrackedSubLevel(Entity entity) {
        for (Entity cursor = entity; cursor != null; cursor = cursor.getVehicle()) {
            SubLevel tracked = Sable.HELPER.getTrackingSubLevel(cursor);
            if (tracked != null) {
                return tracked;
            }
        }
        return null;
    }

    private record ActiveProbe(
            UUID transferId,
            String destinationDimension,
            UUID expectedSubLevelId,
            double destinationX,
            double destinationY,
            double destinationZ,
            double sourceX,
            double sourceY,
            double sourceZ,
            int elapsedTicks,
            boolean ready
    ) {
    }

    private record StaleSourceGuard(
            UUID transferId,
            String destinationDimension,
            UUID expectedSubLevelId,
            double sourceX,
            double sourceY,
            double sourceZ,
            Vector3d lastAcceptedPosition
    ) {
        private StaleSourceGuard withAccepted(Pose3dc pose) {
            return new StaleSourceGuard(transferId, destinationDimension, expectedSubLevelId,
                    sourceX, sourceY, sourceZ, new Vector3d(pose.position()));
        }
    }
}
