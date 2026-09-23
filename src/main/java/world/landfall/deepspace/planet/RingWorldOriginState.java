package world.landfall.deepspace.planet;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import world.landfall.deepspace.Config;

/** Locks the global ring-world-origin option when a save is first started. */
public final class RingWorldOriginState extends SavedData {
    private static final String DATA_NAME = "deepspace_ring_world_origin";
    private static final String INITIALIZED_TAG = "Initialized";
    private static final String ENABLED_TAG = "Enabled";
    private static final Factory<RingWorldOriginState> FACTORY = new Factory<>(
            RingWorldOriginState::new,
            RingWorldOriginState::load
    );

    private boolean initialized;
    private boolean enabled;

    public static RingWorldOriginState resolve(MinecraftServer server) {
        RingWorldOriginState state = server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        if (!state.initialized) {
            // A missing state file is accepted as new only before the world has accumulated play time.
            state.enabled = Config.RING_WORLD_ORIGIN.get() && server.overworld().getGameTime() <= 1L;
            state.initialized = true;
            state.setDirty();
        }
        return state;
    }

    public boolean enabled() {
        return enabled;
    }

    private static RingWorldOriginState load(CompoundTag tag, HolderLookup.Provider registries) {
        RingWorldOriginState state = new RingWorldOriginState();
        state.initialized = tag.getBoolean(INITIALIZED_TAG);
        state.enabled = tag.getBoolean(ENABLED_TAG);
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean(INITIALIZED_TAG, initialized);
        tag.putBoolean(ENABLED_TAG, enabled);
        return tag;
    }
}
