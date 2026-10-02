package world.landfall.deepspace.planet;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/** Persists each debug galaxy's graph index and the galaxy from which it was summoned. */
public final class DachaGalaxyState extends SavedData {
    private static final Factory<DachaGalaxyState> FACTORY = new Factory<>(DachaGalaxyState::new, DachaGalaxyState::load, null);
    private final Map<Integer, Integer> origins = new HashMap<>();

    public static DachaGalaxyState resolve(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "deepspace_dacha_galaxies");
    }

    public Map<Integer, Integer> origins() { return Map.copyOf(origins); }

    public void add(int index, int sourceIndex) {
        origins.put(index, sourceIndex);
        setDirty();
    }

    private static DachaGalaxyState load(CompoundTag tag, HolderLookup.Provider registries) {
        DachaGalaxyState state = new DachaGalaxyState();
        CompoundTag entries = tag.getCompound("origins");
        for (String key : entries.getAllKeys()) {
            int index = Integer.parseInt(key);
            if (index < 0 || !entries.contains(key, Tag.TAG_INT)) throw new IllegalArgumentException("Invalid Dacha origin");
            int source = entries.getInt(key);
            if (source < -1) throw new IllegalArgumentException("Invalid Dacha source galaxy");
            state.origins.put(index, source);
        }
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag entries = new CompoundTag();
        origins.forEach((index, source) -> entries.putInt(index.toString(), source));
        tag.put("origins", entries);
        return tag;
    }
}
