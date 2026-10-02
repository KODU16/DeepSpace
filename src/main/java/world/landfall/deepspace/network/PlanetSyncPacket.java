package world.landfall.deepspace.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.Galaxy;
import world.landfall.deepspace.render.PlanetDecorationsRenderer;
import world.landfall.deepspace.render.PlanetRenderer;
import world.landfall.deepspace.render.NightSkyPlanetRenderer;
import world.landfall.deepspace.render.RingWorldRenderer;
import world.landfall.deepspace.render.SunRenderer;
import world.landfall.deepspace.render.HyperRelayGeoRenderer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Network packet for synchronizing planet data from server to client.
 */
public record PlanetSyncPacket(List<Planet> planets, List<Galaxy> galaxies, String changedPlanetId,
                               boolean textureChanged)
        implements CustomPacketPayload {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static final Type<PlanetSyncPacket> TYPE = new Type<>(
        Deepspace.path("planet_sync")
    );
    
    public static final StreamCodec<FriendlyByteBuf, PlanetSyncPacket> STREAM_CODEC = StreamCodec.ofMember(
        PlanetSyncPacket::encode,
        PlanetSyncPacket::decode
    );
    
    /**
     * Creates a new planet sync packet with the given planets.
     *
     * @param planets The planets to sync
     */
    public PlanetSyncPacket(@NotNull Collection<Planet> planets, @NotNull Collection<Galaxy> galaxies) {
        this(new ArrayList<>(Objects.requireNonNull(planets, "Planets cannot be null")),
                new ArrayList<>(Objects.requireNonNull(galaxies, "Galaxies cannot be null")), null, true);
    }

    public PlanetSyncPacket(@NotNull Planet planet) {
        this(List.of(Objects.requireNonNull(planet, "Planet cannot be null")), List.of(), planet.getId(), true);
    }
    
    /**
     * Creates a planet sync packet with all currently registered planets.
     *
     * @return A new sync packet
     */
    @NotNull
    public static PlanetSyncPacket createSyncPacket() {
        return new PlanetSyncPacket(PlanetRegistry.getAllPlanets(), PlanetRegistry.getAllGalaxies());
    }

    /** Creates the minimal packet used when one generated texture has completed. */
    public static PlanetSyncPacket createPlanetUpdatePacket(@NotNull Planet planet) {
        return new PlanetSyncPacket(planet);
    }

    /** Scan progress updates carry data but leave the current GPU textures intact. */
    public static PlanetSyncPacket createProgressUpdatePacket(@NotNull Planet planet) {
        return new PlanetSyncPacket(List.of(Objects.requireNonNull(planet, "Planet cannot be null")),
                List.of(), planet.getId(), false);
    }
    
    /**
     * Encodes the packet data to the network buffer.
     *
     * @param packet The packet to encode
     * @param buffer The buffer to write to
     */
    public static void encode(@NotNull PlanetSyncPacket packet, @NotNull FriendlyByteBuf buffer) {
        Objects.requireNonNull(packet, "Packet cannot be null");
        Objects.requireNonNull(buffer, "Buffer cannot be null");
        
        buffer.writeInt(packet.planets.size());
        for (Planet planet : packet.planets) {
            planet.toNetwork(buffer);
        }
        buffer.writeCollection(packet.galaxies, (target, galaxy) -> galaxy.toNetwork(target));
        buffer.writeBoolean(packet.changedPlanetId != null);
        if (packet.changedPlanetId != null) {
            buffer.writeUtf(packet.changedPlanetId);
        }
        buffer.writeBoolean(packet.textureChanged);
    }
    
    /**
     * Decodes packet data from the network buffer.
     *
     * @param buffer The buffer to read from
     * @return The decoded packet
     */
    @NotNull
    public static PlanetSyncPacket decode(@NotNull FriendlyByteBuf buffer) {
        Objects.requireNonNull(buffer, "Buffer cannot be null");
        
        int planetCount = buffer.readInt();
        List<Planet> planets = new ArrayList<>(planetCount);
        
        for (int i = 0; i < planetCount; i++) {
            planets.add(Planet.fromNetwork(buffer));
        }
        List<Galaxy> galaxies = buffer.readList(Galaxy::fromNetwork);
        String changedPlanetId = buffer.readBoolean() ? buffer.readUtf() : null;
        boolean textureChanged = buffer.readBoolean();
        return new PlanetSyncPacket(planets, galaxies, changedPlanetId, textureChanged);
    }
    
    /**
     * Handles the packet on the client side.
     *
     * @param context The payload context
     */
    public static void handle(@NotNull PlanetSyncPacket packet, @NotNull IPayloadContext context) {
        Objects.requireNonNull(packet, "Packet cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");
        
        context.enqueueWork(() -> {
            // The integrated server shares this registry; replacing its planets detaches active sampling jobs.
            // Local packets only refresh rendering so completed maps remain on the authoritative objects.
            if (packet.changedPlanetId != null) {
                if (Minecraft.getInstance().getSingleplayerServer() == null && !packet.planets.isEmpty()) {
                    PlanetRegistry.replacePlanet(packet.planets.getFirst());
                }
                if (packet.textureChanged) {
                    PlanetRenderer.refreshPlanet(packet.changedPlanetId);
                    // Ring textures are cached separately from ordinary planet meshes.
                    if (!packet.planets.isEmpty() && packet.planets.getFirst().isRingWorldEdge()) {
                        RingWorldRenderer.invalidateSurfaceTexture(packet.changedPlanetId);
                    }
                }
            } else if (Minecraft.getInstance().getSingleplayerServer() == null) {
                PlanetRegistry.clear();
                HyperRelayGeoRenderer.clearEffects();
                for (Galaxy galaxy : packet.galaxies) {
                    PlanetRegistry.registerGalaxy(galaxy);
                }
                for (Planet planet : packet.planets) {
                    PlanetRegistry.registerPlanet(planet);
                }
            }
            LOGGER.info("Synchronized {} planets from server", packet.planets.size());
            LOGGER.info("Synchronized {} galaxies from server", packet.galaxies.size());
            if (packet.changedPlanetId == null) {
                PlanetRenderer.refreshMeshes();
                NightSkyPlanetRenderer.refreshMeshes();
                PlanetDecorationsRenderer.refreshMeshes();
                SunRenderer.refreshMeshes();
                RingWorldRenderer.refreshMeshes();
            }
        });
    }
    
    @Override
    @NotNull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
} 
