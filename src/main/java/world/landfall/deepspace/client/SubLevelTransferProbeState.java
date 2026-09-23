package world.landfall.deepspace.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
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

/** Reports sub-level readiness every tick so restore does not wait on sparse checkpoints. */
@EventBusSubscriber(modid = Deepspace.MODID, value = Dist.CLIENT)
public final class SubLevelTransferProbeState {
    private static final int MAX_PROBE_TICKS = 200;
    private static ActiveProbe activeProbe;

    private SubLevelTransferProbeState() {
    }

    public static void begin(SubLevelTransferProbePacket packet) {
        activeProbe = new ActiveProbe(packet.transferId(), packet.destinationDimension(), packet.expectedSubLevelId(), 0);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ActiveProbe probe = activeProbe;
        if (probe == null) {
            return;
        }
        int elapsed = probe.elapsedTicks();
        boolean ready = sendObservation(probe, elapsed);
        if (ready || elapsed >= MAX_PROBE_TICKS) {
            activeProbe = null;
        } else {
            activeProbe = new ActiveProbe(
                    probe.transferId(),
                    probe.destinationDimension(),
                    probe.expectedSubLevelId(),
                    elapsed + 1
            );
        }
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
        if (tracked == null && expectedPresent) {
            // Plot metadata is not enough; the client must already own hull chunks.
            tracked = container.getSubLevel(probe.expectedSubLevelId());
        }
        Entity vehicle = minecraft.player.getVehicle();
        PacketDistributor.sendToServer(new SubLevelTransferProbeReplyPacket(
                probe.transferId(),
                elapsedTicks,
                minecraft.level.dimension().location().toString(),
                minecraft.player.getX(),
                minecraft.player.getY(),
                minecraft.player.getZ(),
                expectedPresent,
                tracked == null ? null : tracked.getUniqueId(),
                vehicle == null ? null : vehicle.getUUID()
        ));
        return expectedPresent && tracked != null
                && probe.expectedSubLevelId().equals(tracked.getUniqueId());
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
            int elapsedTicks
    ) {
    }
}
