package world.landfall.deepspace.planet;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import world.landfall.deepspace.Deepspace;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persists the first player to enter each planet dimension and publishes it through planet sync. */
@EventBusSubscriber(modid = Deepspace.MODID)
public final class PlanetDiscoveries extends SavedData {
    private static final String DATA_NAME = "deepspace_planet_discoveries";
    private static final String DISCOVERERS_TAG = "Discoverers";
    private static final Factory<PlanetDiscoveries> FACTORY = new Factory<>(
            PlanetDiscoveries::new,
            PlanetDiscoveries::load
    );
    private final Map<ResourceLocation, String> discoverers = new LinkedHashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % 20 == 0) {
            recordCurrentPlanet(player);
        }
    }

    public static void recordCurrentPlanet(ServerPlayer player) {
        Planet planet = PlanetRegistry.getPlanetByDimension(player.level().dimension());
        if (planet == null || planet.isWormhole()) {
            return;
        }
        PlanetDiscoveries data = get(player);
        ResourceLocation dimension = planet.getDimension().location();
        if (data.discoverers.putIfAbsent(dimension, player.getGameProfile().getName()) == null) {
            data.setDirty();
            applyToRegistry(data);
            PlanetRegistry.syncToAllPlayers();
        }
    }

    public static void applyPersistedDiscoverers(ServerPlayer player) {
        applyPersistedDiscoverers(player.getServer());
    }

    /** Reapplies saved names after a data-pack refresh replaces planet instances. */
    public static void applyPersistedDiscoverers(MinecraftServer server) {
        applyToRegistry(get(server));
    }

    private static void applyToRegistry(PlanetDiscoveries data) {
        PlanetRegistry.getAllPlanets().forEach(planet -> planet.setDiscovererName(
                data.discoverers.getOrDefault(planet.getDimension().location(), "")
        ));
    }

    private static PlanetDiscoveries get(ServerPlayer player) {
        return get(player.getServer());
    }

    private static PlanetDiscoveries get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static PlanetDiscoveries load(CompoundTag tag, HolderLookup.Provider registries) {
        PlanetDiscoveries data = new PlanetDiscoveries();
        CompoundTag stored = tag.getCompound(DISCOVERERS_TAG);
        for (String key : stored.getAllKeys()) {
            ResourceLocation dimension = ResourceLocation.tryParse(key);
            if (dimension != null) {
                data.discoverers.put(dimension, stored.getString(key));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag stored = new CompoundTag();
        discoverers.forEach((dimension, name) -> stored.putString(dimension.toString(), name));
        tag.put(DISCOVERERS_TAG, stored);
        return tag;
    }
}
