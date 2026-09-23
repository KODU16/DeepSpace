package world.landfall.deepspace.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.client.ClientSeamlessTransitionState;

/**
 * Marks the short packet-order window in which the vanilla loading screen is unnecessary.
 */
public record SeamlessTransitionPacket(boolean active) implements CustomPacketPayload {
    public static final Type<SeamlessTransitionPacket> TYPE =
            new Type<>(Deepspace.path("seamless_transition"));
    public static final StreamCodec<FriendlyByteBuf, SeamlessTransitionPacket> STREAM_CODEC =
            StreamCodec.ofMember(SeamlessTransitionPacket::encode, SeamlessTransitionPacket::decode);

    private static void encode(SeamlessTransitionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.active);
    }

    private static SeamlessTransitionPacket decode(FriendlyByteBuf buffer) {
        return new SeamlessTransitionPacket(buffer.readBoolean());
    }

    public static void handle(SeamlessTransitionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientSeamlessTransitionState.setActive(packet.active));
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
