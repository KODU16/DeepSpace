package world.landfall.deepspace.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.client.HyperRelayJumpClientState;

/** Server -> client jump status used to render the control-seat hyper-relay button and countdown. */
public record HyperRelayJumpStatusPacket(boolean inRange, double distance, int countdownTicks)
        implements CustomPacketPayload {
    public static final Type<HyperRelayJumpStatusPacket> TYPE =
            new Type<>(Deepspace.path("hyper_relay_jump_status"));
    public static final StreamCodec<FriendlyByteBuf, HyperRelayJumpStatusPacket> STREAM_CODEC =
            StreamCodec.ofMember(HyperRelayJumpStatusPacket::encode, HyperRelayJumpStatusPacket::decode);

    private static void encode(HyperRelayJumpStatusPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.inRange);
        buffer.writeDouble(packet.distance);
        buffer.writeVarInt(packet.countdownTicks);
    }

    private static HyperRelayJumpStatusPacket decode(FriendlyByteBuf buffer) {
        return new HyperRelayJumpStatusPacket(
                buffer.readBoolean(),
                buffer.readDouble(),
                buffer.readVarInt()
        );
    }

    public static void handle(HyperRelayJumpStatusPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> HyperRelayJumpClientState.acceptStatus(packet));
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
