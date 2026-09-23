package world.landfall.deepspace.planet;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import world.landfall.deepspace.Deepspace;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.Set;

/** Persists galaxy discoveries once per save so every player sees the same explored graph. */
@EventBusSubscriber(modid = Deepspace.MODID)
public final class StarMapExploration extends SavedData {
    private static final String EXPLORED_GALAXIES_TAG = "DeepspaceExploredGalaxies";
    private static final String DATA_NAME = "deepspace_star_map";
    private static final Factory<StarMapExploration> FACTORY = new Factory<>(
            StarMapExploration::new,
            StarMapExploration::load
    );
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Set<ResourceLocation> exploredGalaxies = new LinkedHashSet<>();

    public StarMapExploration() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % 20 == 0) {
            recordCurrentGalaxy(player);
        }
    }

    public static void recordCurrentGalaxy(ServerPlayer player) {
        ResourceKey<Level> galaxy = resolveGalaxy(player.level().dimension());
        if (galaxy == null) {
            return;
        }
        StarMapExploration data = get(player);
        if (data.exploredGalaxies.add(galaxy.location())) {
            data.setDirty();
            LOGGER.info(
                    "[DEEPSPACE-STAR-MAP] phase=GALAXY_DISCOVERED player={} galaxy={} exploredCount={}",
                    player.getUUID(), galaxy.location(), data.exploredGalaxies.size()
            );
        }
    }

    public static Set<ResourceLocation> getExploredGalaxies(ServerPlayer player) {
        return Set.copyOf(get(player).exploredGalaxies);
    }

    private static StarMapExploration get(ServerPlayer player) {
        // The overworld data storage makes discoveries shared by all players and bound to this save.
        return player.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static StarMapExploration load(CompoundTag tag, HolderLookup.Provider registries) {
        StarMapExploration data = new StarMapExploration();
        ListTag stored = tag.getList(EXPLORED_GALAXIES_TAG, Tag.TAG_STRING);
        for (int index = 0; index < stored.size(); index++) {
            ResourceLocation id = ResourceLocation.tryParse(stored.getString(index));
            if (id != null) {
                data.exploredGalaxies.add(id);
            }
        }
        return data;
    }

    public static ResourceKey<Level> resolveGalaxy(ResourceKey<Level> dimension) {
        Galaxy galaxy = PlanetRegistry.getGalaxyByDimension(dimension);
        if (galaxy != null) {
            return galaxy.dimension();
        }
        Planet planet = PlanetRegistry.getPlanetByDimension(dimension);
        return planet == null ? null : planet.getGalaxy();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag values = new ListTag();
        exploredGalaxies.forEach(id -> values.add(StringTag.valueOf(id.toString())));
        tag.put(EXPLORED_GALAXIES_TAG, values);
        return tag;
    }
}
