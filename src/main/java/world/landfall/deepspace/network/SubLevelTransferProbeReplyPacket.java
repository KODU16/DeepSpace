package world.landfall.deepspace.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.server.SubLevelEvents;

import java.util.UUID;

/** Returns client-observed dimension and Sable state to the server transfer log. */
public record SubLevelTransferProbeReplyPacket(
        UUID transferId,
        int observationTick,
        String dimension,
        double x,
        double y,
        double z,
        boolean expectedSubLevelPresent,
        boolean expectedPoseReady,
        UUID trackedSubLevelId,
        UUID vehicleId
) implements CustomPacketPayload {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Type<SubLevelTransferProbeReplyPacket> TYPE =
            new Type<>(Deepspace.path("sublevel_transfer_probe_reply"));
    public static final StreamCodec<FriendlyByteBuf, SubLevelTransferProbeReplyPacket> STREAM_CODEC =
            StreamCodec.ofMember(SubLevelTransferProbeReplyPacket::encode, SubLevelTransferProbeReplyPacket::decode);

    private static void encode(SubLevelTransferProbeReplyPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.transferId);
        buffer.writeVarInt(packet.observationTick);
        buffer.writeUtf(packet.dimension);
        buffer.writeDouble(packet.x);
        buffer.writeDouble(packet.y);
        buffer.writeDouble(packet.z);
        buffer.writeBoolean(packet.expectedSubLevelPresent);
        buffer.writeBoolean(packet.expectedPoseReady);
        buffer.writeNullable(packet.trackedSubLevelId, (target, value) -> target.writeUUID(value));
        buffer.writeNullable(packet.vehicleId, (target, value) -> target.writeUUID(value));
    }

    private static SubLevelTransferProbeReplyPacket decode(FriendlyByteBuf buffer) {
        return new SubLevelTransferProbeReplyPacket(
                buffer.readUUID(),
                buffer.readVarInt(),
                buffer.readUtf(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readNullable(target -> target.readUUID()),
                buffer.readNullable(target -> target.readUUID())
        );
    }

    public static void handle(SubLevelTransferProbeReplyPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            SubLevelEvents.markClientTransferReady(
                    packet.transferId,
                    player.getUUID(),
                    packet.expectedSubLevelPresent && packet.expectedPoseReady,
                    packet.trackedSubLevelId,
                    packet.dimension, packet.x, packet.y, packet.z, packet.observationTick
            );
            // Preserve periodic evidence without writing a log entry for every handshake packet.
            if (packet.observationTick % 20 == 0) {
                LOGGER.info(
                        "[DEEPSPACE-TRANSFER] id={} phase=CLIENT_OBSERVATION player={} tick={} dimension={} "
                                + "position=({},{},{}) expectedSubLevelPresent={} expectedPoseReady={} trackedSubLevel={} vehicle={}",
                        packet.transferId,
                        player.getUUID(),
                        packet.observationTick,
                        packet.dimension,
                        packet.x,
                        packet.y,
                        packet.z,
                        packet.expectedSubLevelPresent,
                        packet.expectedPoseReady,
                        packet.trackedSubLevelId,
                        packet.vehicleId
                );
            }
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
