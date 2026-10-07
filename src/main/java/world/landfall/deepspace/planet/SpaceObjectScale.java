package world.landfall.deepspace.planet;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import world.landfall.deepspace.Config;
import world.landfall.deepspace.Deepspace;

/** Keeps canonical planet definitions separate from the two spatial multipliers locked into each save. */
@EventBusSubscriber(modid = Deepspace.MODID)
public final class SpaceObjectScale extends SavedData {
    private static final Factory<SpaceObjectScale> FACTORY = new Factory<>(SpaceObjectScale::new, SpaceObjectScale::load);
    // Publish both values atomically because integrated server and client share this registry.
    private static volatile Multipliers active = new Multipliers(1.0D, 1.0D);
    private record Multipliers(double size, double distance) { }
    private static boolean newSave;
    private boolean initialized;
    private double size = 1.0D;
    private double distance = 1.0D;

    public static double size() { return active.size(); }
    public static double distance() { return active.distance(); }

    /** Only the server's persisted values control geometry, including on remote clients. */
    public static void synchronize(double size, double distance) {
        active = new Multipliers(validate(size), validate(distance));
    }

    private static double validate(double value) {
        return Double.isFinite(value) ? Math.clamp(value, 0.1D, 100.0D) : 1.0D;
    }

    public static Vec3 position(Vec3 canonical) {
        return canonical.scale(distance());
    }

    /** Ring sections expand around the unchanged host-star center instead of changing system distance. */
    public static Vec3 planetCenter(Planet planet) {
        Vec3 canonical = planet.getUnscaledCenter();
        if (!planet.isRingWorldEdge()) return position(canonical);
        if (size() == 1.0D) return canonical;
        Galaxy galaxy = PlanetRegistry.getGalaxyByDimension(planet.getGalaxy());
        Vec3 origin = galaxy == null ? Vec3.ZERO : galaxy.sun().getUnscaledCenter();
        return origin.add(canonical.subtract(origin).scale(size()));
    }

    /** Relays retain authored extents; other bodies resize around their independently transformed centers. */
    public static Vec3 planetBound(Planet planet, boolean upper) {
        Vec3 canonicalCenter = planet.getUnscaledCenter();
        Vec3 endpoint = upper ? planet.getUnscaledBoundingBoxMax() : planet.getUnscaledBoundingBoxMin();
        // Default and legacy saves retain exact authored endpoints without registry lookups.
        if (size() == 1.0D && distance() == 1.0D) return endpoint;
        double extentScale = planet.isHyperRelay() ? 1.0D : size();
        return planetCenter(planet).add(endpoint.subtract(canonicalCenter).scale(extentScale));
    }

    public static boolean isRingGalaxy(Galaxy galaxy) {
        return galaxy.brokenRingSections() != 0 || PlanetRegistry.getPlanetsForGalaxy(galaxy.dimension())
                .stream().anyMatch(Planet::isRingWorldEdge);
    }

    /** Only the ring's host star ignores distance scaling; companion stars use ordinary coordinates. */
    public static Vec3 starCenter(Sun star) {
        if (distance() == 1.0D) return star.getUnscaledCenter();
        boolean ringHost = PlanetRegistry.getAllGalaxies().stream()
                .anyMatch(galaxy -> galaxy.sun().equals(star) && isRingGalaxy(galaxy));
        return ringHost ? star.getUnscaledCenter() : position(star.getUnscaledCenter());
    }

    public static Vec3 starBound(Sun star, boolean upper) {
        Vec3 endpoint = upper ? star.getUnscaledBoundingBoxMax() : star.getUnscaledBoundingBoxMin();
        if (size() == 1.0D && distance() == 1.0D) return endpoint;
        return starCenter(star).add(endpoint.subtract(star.getUnscaledCenter()).scale(size()));
    }

    /** Ring arrivals follow the ring's new radius so larger rings do not swallow the landing point. */
    public static Vec3 arrival(Vec3 canonical, Galaxy galaxy) {
        if (galaxy == null || !isRingGalaxy(galaxy)) return position(canonical);
        Vec3 origin = galaxy.sun().getUnscaledCenter();
        return origin.add(canonical.subtract(origin).scale(size()));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeStart(ServerAboutToStartEvent event) {
        // Read the spawn initialization flag before level creation, including zero-tick legacy saves.
        newSave = !event.getServer().getWorldData().overworldData().isInitialized();
        synchronize(1.0D, 1.0D);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void levelLoaded(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(Level.OVERWORLD)) return;
        SpaceObjectScale state = level.getDataStorage().computeIfAbsent(FACTORY, "deepspace_space_object_scale");
        if (!state.initialized) {
            state.size = newSave ? Config.SPACE_OBJECT_SIZE_SCALE.get() : 1.0D;
            state.distance = newSave ? Config.SPACE_OBJECT_DISTANCE_SCALE.get() : 1.0D;
            state.initialized = true;
            state.setDirty();
        }
        synchronize(state.size, state.distance);
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        newSave = false;
        synchronize(1.0D, 1.0D);
    }

    private static SpaceObjectScale load(CompoundTag tag, HolderLookup.Provider registries) {
        SpaceObjectScale state = new SpaceObjectScale();
        state.initialized = tag.getBoolean("Initialized");
        state.size = tag.contains("Size") ? validate(tag.getDouble("Size")) : 1.0D;
        state.distance = tag.contains("Distance") ? validate(tag.getDouble("Distance")) : 1.0D;
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("Initialized", initialized);
        tag.putDouble("Size", size);
        tag.putDouble("Distance", distance);
        return tag;
    }
}
