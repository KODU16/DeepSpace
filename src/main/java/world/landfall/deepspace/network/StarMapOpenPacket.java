package world.landfall.deepspace.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.client.StarMapScreen;
import world.landfall.deepspace.planet.StarMapExploration;

import java.util.ArrayList;
import java.util.List;

/** Sends only the player's exploration state; synchronized planet data supplies the graph topology. */
public record StarMapOpenPacket(List<ResourceLocation> exploredGalaxies, ResourceLocation currentGalaxy)
        implements CustomPacketPayload {
    public static final Type<StarMapOpenPacket> TYPE = new Type<>(Deepspace.path("star_map_open"));
    public static final StreamCodec<FriendlyByteBuf, StarMapOpenPacket> STREAM_CODEC =
            StreamCodec.ofMember(StarMapOpenPacket::encode, StarMapOpenPacket::decode);

    public StarMapOpenPacket {
        exploredGalaxies = List.copyOf(exploredGalaxies);
    }

    public static StarMapOpenPacket forPlayer(ServerPlayer player) {
        var current = StarMapExploration.resolveGalaxy(player.level().dimension());
        return new StarMapOpenPacket(
                new ArrayList<>(StarMapExploration.getExploredGalaxies(player)),
                current == null ? null : current.location()
        );
    }

    private static void encode(StarMapOpenPacket packet, FriendlyByteBuf buffer) {
        buffer.writeCollection(packet.exploredGalaxies, FriendlyByteBuf::writeResourceLocation);
        buffer.writeNullable(packet.currentGalaxy, FriendlyByteBuf::writeResourceLocation);
    }

    private static StarMapOpenPacket decode(FriendlyByteBuf buffer) {
        return new StarMapOpenPacket(
                buffer.readList(FriendlyByteBuf::readResourceLocation),
                buffer.readNullable(FriendlyByteBuf::readResourceLocation)
        );
    }

    public static void handle(StarMapOpenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> StarMapScreen.open(packet.exploredGalaxies, packet.currentGalaxy));
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
