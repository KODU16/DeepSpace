package world.landfall.deepspace.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.client.SubLevelTransferProbeState;

import java.util.UUID;

/** Starts a short client-side observation window for one Sable dimension transfer. */
public record SubLevelTransferProbePacket(
        UUID transferId,
        String destinationDimension,
        UUID expectedSubLevelId
) implements CustomPacketPayload {
    public static final Type<SubLevelTransferProbePacket> TYPE =
            new Type<>(Deepspace.path("sublevel_transfer_probe"));
    public static final StreamCodec<FriendlyByteBuf, SubLevelTransferProbePacket> STREAM_CODEC =
            StreamCodec.ofMember(SubLevelTransferProbePacket::encode, SubLevelTransferProbePacket::decode);

    private static void encode(SubLevelTransferProbePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.transferId);
        buffer.writeUtf(packet.destinationDimension);
        buffer.writeNullable(packet.expectedSubLevelId, (target, value) -> target.writeUUID(value));
    }

    private static SubLevelTransferProbePacket decode(FriendlyByteBuf buffer) {
        return new SubLevelTransferProbePacket(
                buffer.readUUID(),
                buffer.readUtf(),
                buffer.readNullable(target -> target.readUUID())
        );
    }

    public static void handle(SubLevelTransferProbePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> SubLevelTransferProbeState.begin(packet));
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
