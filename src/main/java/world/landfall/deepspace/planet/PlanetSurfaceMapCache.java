package world.landfall.deepspace.planet;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Persists each planet's best completed tier and resumable terrain sampling progress. */
public final class PlanetSurfaceMapCache extends SavedData {
    private static final String DATA_NAME = "deepspace_planet_surface_maps";
    private static final Factory<PlanetSurfaceMapCache> FACTORY = new Factory<>(
            PlanetSurfaceMapCache::new, PlanetSurfaceMapCache::load);
    private final Map<String, short[]> maps = new LinkedHashMap<>();
    private final Map<String, Progress> progress = new LinkedHashMap<>();
    private final Map<String, Metadata> metadata = new LinkedHashMap<>();

    /** Restore the highest completed tier before the first client synchronization. */
    public static void restore(MinecraftServer server, Collection<Planet> planets) {
        for (Planet planet : planets) {
            short[] pixels = find(server, planet);
            if (pixels != null && planet.getTexture().isEmpty()) {
                PlanetTextureTier current = planet.getGeneratedTextureTier();
                if (current == null || pixels.length >= current.pixels()) planet.setGeneratedSurfaceMap(pixels);
            }
            Metadata saved = get(server).metadata.get(cacheKey(planet));
            if (saved != null) {
                planet.setPlanetTypeBiome(saved.planetTypeBiome);
                planet.setSurfaceSamples(saved.biomes, saved.fluids, saved.blocks);
                planet.setSurfaceScanStatus(saved.complete
                        ? Planet.SurfaceScanStatus.COMPLETE
                        : Planet.SurfaceScanStatus.UNKNOWN);
            }
        }
    }

    @Nullable
    public static short[] find(MinecraftServer server, Planet planet) {
        short[] pixels = get(server).maps.get(cacheKey(planet));
        return pixels == null ? null : pixels.clone();
    }

    /** One slot per planet: a completed upgrade atomically replaces the lower-tier cache. */
    public static void store(MinecraftServer server, Planet planet, short[] pixels) {
        PlanetTextureTier tier = PlanetTextureTier.fromPixelCount(pixels.length);
        if (tier == null) return;
        PlanetSurfaceMapCache cache = get(server);
        String key = cacheKey(planet);
        short[] old = cache.maps.get(key);
        if (old == null || old.length <= pixels.length) cache.maps.put(key, pixels.clone());
        cache.metadata.put(key, Metadata.fromPlanet(planet));
        cache.progress.remove(key);
        cache.setDirty();
    }

    /** Persists type-only sampling for planets that keep a bundled surface texture. */
    static void storeMetadata(MinecraftServer server, Planet planet) {
        PlanetSurfaceMapCache cache = get(server);
        String key = cacheKey(planet);
        cache.metadata.put(key, Metadata.fromPlanet(planet));
        cache.progress.remove(key);
        cache.setDirty();
    }

    @Nullable
    static Progress findProgress(MinecraftServer server, Planet planet) {
        return get(server).progress.get(cacheKey(planet));
    }

    /** Sampling runs on the server thread; save serializes these buffers before asynchronous disk writes. */
    static void trackProgress(MinecraftServer server, Planet planet, Progress state) {
        PlanetSurfaceMapCache cache = get(server);
        cache.progress.put(cacheKey(planet), state);
        cache.setDirty();
    }

    /** Version two separates dimensions and invalidates obsolete type and material metadata. */
    private static String cacheKey(Planet planet) {
        return planet.getId() + "#" + planet.getDimension().location() + "#atlas3x2_v2#"
                + planet.getTextureGenerationDetail().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static PlanetSurfaceMapCache get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static PlanetSurfaceMapCache load(CompoundTag tag, HolderLookup.Provider registries) {
        PlanetSurfaceMapCache cache = new PlanetSurfaceMapCache();
        CompoundTag stored = tag.getCompound("Maps");
        for (String id : stored.getAllKeys()) {
            short[] pixels = unpack(stored.getIntArray(id));
            if (PlanetTextureTier.fromPixelCount(pixels.length) != null) cache.maps.put(id, pixels);
        }
        CompoundTag pending = tag.getCompound("Progress");
        for (String id : pending.getAllKeys()) {
            short[] completed = cache.maps.get(id);
            if (completed != null && completed.length == PlanetTextureTier.FULL.pixels()) continue;
            Progress state = Progress.read(pending.getCompound(id));
            if (state != null) cache.progress.put(id, state);
        }
        CompoundTag savedMetadata = tag.getCompound("Metadata");
        for (String id : savedMetadata.getAllKeys()) {
            Metadata value = Metadata.read(savedMetadata.getCompound(id));
            if (value != null) cache.metadata.put(id, value);
        }
        return cache;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag stored = new CompoundTag();
        CompoundTag tiers = new CompoundTag();
        maps.forEach((id, pixels) -> {
            stored.putIntArray(id, pack(pixels));
            // Explicit completed-tier marker; old saves can still infer it from their map length.
            tiers.putString(id, PlanetTextureTier.fromPixelCount(pixels.length).name());
        });
        tag.put("Maps", stored);
        tag.put("CompletedTiers", tiers);
        CompoundTag pending = new CompoundTag();
        progress.forEach((id, state) -> pending.put(id, state.write()));
        tag.put("Progress", pending);
        CompoundTag savedMetadata = new CompoundTag();
        metadata.forEach((id, value) -> savedMetadata.put(id, value.write()));
        tag.put("Metadata", savedMetadata);
        return tag;
    }

    private static int[] pack(short[] pixels) {
        int[] packed = new int[(pixels.length + 1) / 2];
        for (int index = 0; index < pixels.length; index += 2) {
            int low = pixels[index] & 0xFFFF;
            int high = index + 1 < pixels.length ? (pixels[index + 1] & 0xFFFF) << 16 : 0;
            packed[index / 2] = low | high;
        }
        return packed;
    }

    private static short[] unpack(int[] packed) {
        if (PlanetTextureTier.fromPixelCount(packed.length * 2) == null) return new short[0];
        short[] pixels = new short[packed.length * 2];
        for (int index = 0; index < packed.length; index++) {
            pixels[index * 2] = (short) packed[index];
            pixels[index * 2 + 1] = (short) (packed[index] >>> 16);
        }
        return pixels;
    }

    /** Completed chunk bitmap prevents resampling after a normal save/exit or an autosave recovery. */
    static final class Progress {
        final PlanetTextureTier tier;
        PlanetTextureTier requestedTier;
        final int[] colors;
        final short[] heights;
        final BitSet sampled;
        final Map<String, Integer> biomes = new TreeMap<>();
        final Map<String, Integer> coarseBiomeVotes = new TreeMap<>();
        final Map<String, Integer> fluids = new TreeMap<>();
        final Map<String, Integer> blocks = new TreeMap<>();
        final Map<Integer, Integer> colorCounts = new TreeMap<>();

        Progress(PlanetTextureTier tier) {
            this(tier, tier, new int[tier.pixels()], new short[tier.pixels()], new BitSet(tier.chunks()));
        }

        private Progress(PlanetTextureTier tier, PlanetTextureTier requestedTier, int[] colors,
                         short[] heights, BitSet sampled) {
            this.tier = tier;
            this.requestedTier = requestedTier;
            this.colors = colors;
            this.heights = heights;
            this.sampled = sampled;
        }

        private CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Tier", tier.name());
            tag.putString("RequestedTier", requestedTier.name());
            tag.putIntArray("Colors", colors.clone());
            tag.putIntArray("Heights", pack(heights));
            tag.putLongArray("SampledChunks", sampled.toLongArray());
            tag.put("Biomes", writeCounts(biomes));
            tag.put("CoarseBiomeVotes", writeCounts(coarseBiomeVotes));
            tag.put("Fluids", writeCounts(fluids));
            tag.put("Blocks", writeCounts(blocks));
            CompoundTag palette = new CompoundTag();
            colorCounts.forEach((color, count) -> palette.putInt(Integer.toString(color), count));
            tag.put("ColorCounts", palette);
            return tag;
        }

        @Nullable
        private static Progress read(CompoundTag tag) {
            try {
                PlanetTextureTier tier = PlanetTextureTier.valueOf(tag.getString("Tier"));
                PlanetTextureTier requested = PlanetTextureTier.valueOf(tag.getString("RequestedTier"));
                int[] colors = tag.getIntArray("Colors");
                short[] heights = unpack(tag.getIntArray("Heights"));
                BitSet sampled = BitSet.valueOf(tag.getLongArray("SampledChunks"));
                if (colors.length != tier.pixels() || heights.length != tier.pixels()
                        || sampled.length() > tier.chunks() || requested.ordinal() < tier.ordinal()) return null;
                Progress state = new Progress(tier, requested, colors, heights, sampled);
                readCounts(tag.getCompound("Biomes"), state.biomes);
                readCounts(tag.getCompound("CoarseBiomeVotes"), state.coarseBiomeVotes);
                readCounts(tag.getCompound("Fluids"), state.fluids);
                readCounts(tag.getCompound("Blocks"), state.blocks);
                CompoundTag palette = tag.getCompound("ColorCounts");
                for (String key : palette.getAllKeys()) state.colorCounts.put(Integer.parseInt(key), palette.getInt(key));
                return state;
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }

        private static CompoundTag writeCounts(Map<String, Integer> counts) {
            CompoundTag tag = new CompoundTag();
            counts.forEach(tag::putInt);
            return tag;
        }

        private static void readCounts(CompoundTag tag, Map<String, Integer> counts) {
            for (String key : tag.getAllKeys()) counts.put(key, tag.getInt(key));
        }
    }

    /** Metadata is persisted beside the map because clients need it after a server restart. */
    private record Metadata(
            String planetTypeBiome,
            java.util.List<Planet.SurfaceSample> biomes,
            java.util.List<Planet.SurfaceSample> fluids,
            java.util.List<Planet.SurfaceSample> blocks,
            boolean complete
    ) {
        private static Metadata fromPlanet(Planet planet) {
            return new Metadata(
                    planet.getPlanetTypeBiome(),
                    planet.getSampledBiomes(),
                    planet.getSampledFluids(),
                    planet.getSampledBlocks(),
                    planet.getSurfaceScanStatus() == Planet.SurfaceScanStatus.COMPLETE
            );
        }

        private CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putString("PlanetTypeBiome", planetTypeBiome);
            tag.put("Biomes", writeSamples(biomes));
            tag.put("Fluids", writeSamples(fluids));
            tag.put("Blocks", writeSamples(blocks));
            tag.putBoolean("Complete", complete);
            return tag;
        }

        @Nullable
        private static Metadata read(CompoundTag tag) {
            try {
                return new Metadata(
                        tag.getString("PlanetTypeBiome"),
                        readSamples(tag.getCompound("Biomes")),
                        readSamples(tag.getCompound("Fluids")),
                        readSamples(tag.getCompound("Blocks")),
                        tag.getBoolean("Complete")
                );
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }

        private static CompoundTag writeSamples(java.util.List<Planet.SurfaceSample> samples) {
            CompoundTag tag = new CompoundTag();
            samples.forEach(sample -> tag.putInt(sample.id(), sample.count()));
            return tag;
        }

        private static java.util.List<Planet.SurfaceSample> readSamples(CompoundTag tag) {
            return tag.getAllKeys().stream()
                    .map(id -> new Planet.SurfaceSample(id, tag.getInt(id)))
                    .toList();
        }
    }
}
