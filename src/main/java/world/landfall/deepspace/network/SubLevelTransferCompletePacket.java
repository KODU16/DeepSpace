package world.landfall.deepspace.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.client.ClientSeatInputRecovery;
import world.landfall.deepspace.client.SubLevelTransferProbeState;

import java.util.UUID;

/** Confirms the exact replacement seat after server-side transfer restoration completes. */
public record SubLevelTransferCompletePacket(
        UUID transferId,
        String destinationDimension,
        UUID mountId
) implements CustomPacketPayload {
    public static final Type<SubLevelTransferCompletePacket> TYPE =
            new Type<>(Deepspace.path("sublevel_transfer_complete"));
    public static final StreamCodec<FriendlyByteBuf, SubLevelTransferCompletePacket> STREAM_CODEC =
            StreamCodec.ofMember(SubLevelTransferCompletePacket::encode, SubLevelTransferCompletePacket::decode);

    private static void encode(SubLevelTransferCompletePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.transferId);
        buffer.writeUtf(packet.destinationDimension);
        buffer.writeNullable(packet.mountId, (target, value) -> target.writeUUID(value));
    }

    private static SubLevelTransferCompletePacket decode(FriendlyByteBuf buffer) {
        return new SubLevelTransferCompletePacket(
                buffer.readUUID(), buffer.readUtf(), buffer.readNullable(target -> target.readUUID()));
    }

    public static void handle(SubLevelTransferCompletePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            SubLevelTransferProbeState.complete(packet.transferId());
            ClientSeatInputRecovery.completeTransfer(packet);
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
