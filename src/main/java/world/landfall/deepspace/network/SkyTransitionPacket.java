package world.landfall.deepspace.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.client.SkyTransitionState;

/** Sends either a planet-sky tint or the textured galaxy skybox transition. */
public record SkyTransitionPacket(float progress, int targetColor, boolean skybox) implements CustomPacketPayload {
    public static final Type<SkyTransitionPacket> TYPE = new Type<>(Deepspace.path("sky_transition"));
    public static final StreamCodec<FriendlyByteBuf, SkyTransitionPacket> STREAM_CODEC = StreamCodec.ofMember(
            SkyTransitionPacket::encode,
            SkyTransitionPacket::decode
    );

    private static void encode(SkyTransitionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeFloat(packet.progress);
        buffer.writeInt(packet.targetColor);
        buffer.writeBoolean(packet.skybox);
    }

    private static SkyTransitionPacket decode(FriendlyByteBuf buffer) {
        return new SkyTransitionPacket(buffer.readFloat(), buffer.readInt(), buffer.readBoolean());
    }

    public static void handle(SkyTransitionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> SkyTransitionState.setTarget(packet.progress, packet.targetColor, packet.skybox));
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
