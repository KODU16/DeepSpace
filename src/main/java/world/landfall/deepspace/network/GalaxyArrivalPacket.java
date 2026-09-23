package world.landfall.deepspace.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.client.GalaxyArrivalState;
import world.landfall.deepspace.planet.Galaxy;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.StarIdentity;

import java.util.List;

/** Carries the authoritative destination summary used by the wormhole arrival overlay. */
public record GalaxyArrivalPacket(String galaxyName, List<StarSummary> stars, int planetCount)
        implements CustomPacketPayload {
    public static final Type<GalaxyArrivalPacket> TYPE = new Type<>(Deepspace.path("galaxy_arrival"));
    public static final StreamCodec<FriendlyByteBuf, GalaxyArrivalPacket> STREAM_CODEC = StreamCodec.ofMember(
            GalaxyArrivalPacket::encode,
            GalaxyArrivalPacket::decode
    );

    public GalaxyArrivalPacket {
        stars = List.copyOf(stars);
    }

    @Nullable
    public static GalaxyArrivalPacket forDimension(ResourceKey<Level> dimension) {
        Galaxy galaxy = PlanetRegistry.getGalaxyByDimension(dimension);
        if (galaxy == null) {
            return null;
        }
        List<StarSummary> stars = galaxy.suns().stream()
                .map(sun -> new StarSummary(
                        sun.getName(),
                        StarIdentity.spectralSize(
                                sun.getSpectralClass(),
                                sun.getModelRadius() / StarIdentity.BASE_STAR_RADIUS
                        )
                ))
                .toList();
        int planetCount = (int) PlanetRegistry.getPlanetsForGalaxy(dimension).stream()
                .filter(planet -> !planet.isWormhole())
                .count();
        return new GalaxyArrivalPacket(galaxy.name(), stars, planetCount);
    }

    private static void encode(GalaxyArrivalPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.galaxyName);
        buffer.writeCollection(packet.stars, (target, star) -> {
            target.writeUtf(star.name);
            target.writeUtf(star.spectralSize);
        });
        buffer.writeVarInt(packet.planetCount);
    }

    private static GalaxyArrivalPacket decode(FriendlyByteBuf buffer) {
        String galaxyName = buffer.readUtf();
        List<StarSummary> stars = buffer.readList(source ->
                new StarSummary(source.readUtf(), source.readUtf()));
        return new GalaxyArrivalPacket(galaxyName, stars, buffer.readVarInt());
    }

    public static void handle(GalaxyArrivalPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> GalaxyArrivalState.begin(packet));
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record StarSummary(String name, String spectralSize) {
    }
}
