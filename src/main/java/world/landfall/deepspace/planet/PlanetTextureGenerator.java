package world.landfall.deepspace.planet;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/** Builds surface maps from temporary generation chunks without loading world chunks. */
@EventBusSubscriber(modid = Deepspace.MODID)
public final class PlanetTextureGenerator {
    public static final int TEXTURE_WIDTH = PlanetTextureLayout.WIDTH;
    public static final int TEXTURE_HEIGHT = PlanetTextureLayout.HEIGHT;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FALLBACK_SURFACE_COLOR = 0x7F7F7F;
    private static final int MAP_MIN_X = -TEXTURE_WIDTH / 2;
    private static final int MAP_MIN_Z = -TEXTURE_HEIGHT / 2;
    private static final int MAP_CHUNK_MIN_X = SectionPos.blockToSectionCoord(MAP_MIN_X);
    private static final int MAP_CHUNK_MIN_Z = SectionPos.blockToSectionCoord(MAP_MIN_Z);
    static final int MAX_CHUNKS_PER_PLANET_PER_TICK = 10;
    private static final Map<MinecraftServer, SamplingQueue> SAMPLING_QUEUES = new IdentityHashMap<>();

    private PlanetTextureGenerator() {
    }

    @NotNull
    public static CompletableFuture<Void> sampleMissingPalettes(
            @NotNull MinecraftServer server,
            @NotNull Collection<Planet> planets,
            float fragmentation
    ) {
        return sampleMissingPalettes(server, planets, fragmentation, true);
    }

    /** The low-cost path prepares metadata only; it never loads terrain chunks. */
    @NotNull
    public static CompletableFuture<Void> sampleMissingPalettes(
            @NotNull MinecraftServer server,
            @NotNull Collection<Planet> planets,
            float fragmentation,
            boolean sampleChunks
    ) {
        prepareMetadata(server, planets, fragmentation);
        PlanetSurfaceMapCache.restore(server, planets);
        return sampleChunks
                ? enqueueSurfaceMaps(server, planets, fragmentation, false, null, PlanetTextureTier.FULL)
                : CompletableFuture.completedFuture(null);
    }

    /** Prewarms coarse maps for all destination planets, or resumes their persisted upgrade targets. */
    @NotNull
    public static CompletableFuture<Void> prewarmSurfaceMaps(
            @NotNull MinecraftServer server,
            @NotNull Collection<Planet> planets,
            float fragmentation
    ) {
        prepareMetadata(server, planets, fragmentation);
        PlanetSurfaceMapCache.restore(server, planets);
        return enqueueSurfaceMaps(server, planets, fragmentation, true, null, PlanetTextureTier.FULL);
    }

    /** The warp countdown starts all coarse jobs; the entrance only determines their submission order. */
    @NotNull
    public static CompletableFuture<Void> prewarmSurfaceMaps(
            @NotNull MinecraftServer server,
            @NotNull Collection<Planet> planets,
            float fragmentation,
            net.minecraft.world.phys.Vec3 entryPosition
    ) {
        prepareMetadata(server, planets, fragmentation);
        PlanetSurfaceMapCache.restore(server, planets);
        return enqueueSurfaceMaps(server, planets, fragmentation, true, entryPosition, PlanetTextureTier.FULL);
    }

    private static void prepareMetadata(MinecraftServer server, Collection<Planet> planets, float fragmentation) {
        long worldSeed = server.overworld().getSeed();
        for (Planet planet : planets) {
            long textureSeed = seedFromWorldAndPlanet(worldSeed, planet.getId());
            if (planet.getTexture().isPresent()) {
                planet.setGeneratedSurfaceColors(List.of(), fragmentation, textureSeed);
                planet.setGeneratedSurfaceMap(new short[0]);
                continue;
            }
            planet.setGeneratedSurfaceColors(planet.getGeneratedSurfaceColors(), fragmentation, textureSeed);
            ServerLevel level = server.getLevel(planet.getDimension());
            if (level != null) {
                planet.setGeneratedAtmosphereColor(skyColorFromBiomeSource(level));
            }
        }
    }

    private static CompletableFuture<Void> enqueueSurfaceMaps(
            MinecraftServer server,
            Collection<Planet> planets,
            float fragmentation,
            boolean highPriority
    ) {
        return enqueueSurfaceMaps(server, planets, fragmentation, highPriority, null, PlanetTextureTier.FULL);
    }

    private static CompletableFuture<Void> enqueueSurfaceMaps(
            MinecraftServer server,
            Collection<Planet> planets,
            float fragmentation,
            boolean highPriority,
            net.minecraft.world.phys.Vec3 entryPosition,
            PlanetTextureTier tier
    ) {
        List<CompletableFuture<Void>> completions = new ArrayList<>();
        List<Planet> orderedPlanets = new ArrayList<>(planets);
        if (entryPosition != null) {
            orderedPlanets.sort(java.util.Comparator.comparingDouble(
                    planet -> planet.getCenter().distanceToSqr(entryPosition)));
        }
        for (Planet planet : orderedPlanets) {
            if (planet.isHyperRelay()) {
                continue;
            }
            PlanetTextureTier current = planet.getGeneratedTextureTier();
            if (current == PlanetTextureTier.FULL) continue;
            PlanetSurfaceMapCache.Progress progress = PlanetSurfaceMapCache.findProgress(server, planet);
            PlanetTextureTier requested = progress != null && progress.requestedTier.ordinal() > tier.ordinal()
                    ? progress.requestedTier : tier;
            // A low-tier cache is display data, not proof that the requested upgrade is finished.
            if (current != null && current.ordinal() >= requested.ordinal() && progress == null) continue;
            short[] cached = PlanetSurfaceMapCache.find(server, planet);
            if (cached != null && (current == null || cached.length > current.pixels())) {
                planet.setGeneratedSurfaceMap(cached);
                current = planet.getGeneratedTextureTier();
                PlanetRegistry.syncPlanetToAllPlayers(planet.getId());
            }
            if (current == PlanetTextureTier.FULL
                    || (current != null && current.ordinal() >= requested.ordinal() && progress == null)) continue;
            // Every procedural planet advances toward the final tier regardless of distance.
            PlanetTextureTier next = progress != null ? progress.tier : requested;
            completions.add(enqueueJob(server, planet, fragmentation, highPriority, next, requested));
        }
        return CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new));
    }

    /** Upgrade only the nearby planet; repeated requests coalesce with its current job. */
    public static void requestTextureTier(MinecraftServer server, Planet planet, PlanetTextureTier tier) {
        enqueueSurfaceMaps(server, List.of(planet), planet.getGeneratedTextureFragmentation(), true, null, tier);
    }

    private static CompletableFuture<Void> enqueueJob(
            MinecraftServer server,
            Planet planet,
            float fragmentation,
            boolean highPriority,
            PlanetTextureTier tier,
            PlanetTextureTier requested
    ) {
        synchronized (SAMPLING_QUEUES) {
            SamplingQueue queue = SAMPLING_QUEUES.computeIfAbsent(server, ignored -> new SamplingQueue());
            SurfaceMapJob existing = queue.byPlanet.get(planet.getId());
            if (existing != null) {
                // Finish the visible coarse map first, then run only the highest requested upgrade.
                if (requested.ordinal() > existing.progress.requestedTier.ordinal()) {
                    existing.progress.requestedTier = requested;
                    PlanetSurfaceMapCache.trackProgress(server, planet, existing.progress);
                }
                if (highPriority && queue.jobs.remove(existing)) {
                    queue.jobs.addFirst(existing);
                }
                return existing.completion;
            }
            SurfaceMapJob job = new SurfaceMapJob(server, planet, fragmentation, tier, requested);
            if (job.tier != PlanetTextureTier.COARSE) {
                planet.setSurfaceScanStatus(Planet.SurfaceScanStatus.SCANNING);
                PlanetRegistry.syncPlanetToAllPlayers(planet.getId());
            }
            // Expose queued detail and completion counts so slow custom dimensions are diagnosable.
            LOGGER.info("Queued {} {} surface-map sampling for {} ({}/{} chunks complete, target={})",
                    job.tier, planet.getTextureGenerationDetail(), planet.getId(), job.completedChunks,
                    job.tier.chunks(), job.progress.requestedTier);
            queue.byPlanet.put(planet.getId(), job);
            if (highPriority) {
                queue.jobs.addFirst(job);
            } else {
                queue.jobs.addLast(job);
            }
            return job.completion;
        }
    }

    /** Advances every pending planet without exceeding its per-tick chunk budget. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        List<SurfaceMapJob> jobs;
        synchronized (SAMPLING_QUEUES) {
            SamplingQueue queue = SAMPLING_QUEUES.get(event.getServer());
            jobs = queue == null ? List.of() : List.copyOf(queue.jobs);
        }
        // Advance only one planet per tick; other eligible planets remain queued to avoid concurrent stalls.
        for (SurfaceMapJob job : jobs) {
            if (!job.isDimensionReady()) {
                continue;
            }
            try {
                for (int sampledChunks = 0; sampledChunks < MAX_CHUNKS_PER_PLANET_PER_TICK; sampledChunks++) {
                    if (job.processOneChunk()) {
                        removeJob(event.getServer(), job);
                        if (job.progress.requestedTier.ordinal() > job.tier.ordinal()) {
                            requestTextureTier(event.getServer(), job.planet, job.progress.requestedTier);
                        }
                        job.completion.complete(null);
                        break;
                    }
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Surface-map sampling failed for planet {}", job.planet.getId(), exception);
                job.releaseTemporaryData();
                removeJob(event.getServer(), job);
                job.completion.completeExceptionally(exception);
            }
            break;
        }
    }

    private static void removeJob(MinecraftServer server, SurfaceMapJob job) {
        synchronized (SAMPLING_QUEUES) {
            SamplingQueue queue = SAMPLING_QUEUES.get(server);
            if (queue == null) {
                return;
            }
            queue.jobs.remove(job);
            queue.byPlanet.remove(job.planet.getId(), job);
            if (queue.jobs.isEmpty()) {
                SAMPLING_QUEUES.remove(server);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SamplingQueue queue;
        synchronized (SAMPLING_QUEUES) {
            queue = SAMPLING_QUEUES.remove(event.getServer());
        }
        if (queue != null) {
            queue.jobs.forEach(job -> {
                LOGGER.info("Stopped surface-map sampling for {} after {}/{} chunks",
                        job.planet.getId(), job.completedChunks, job.tier.chunks());
                job.releaseTemporaryData();
                job.completion.cancel(false);
            });
        }
    }

    private static int skyColorFromBiomeSource(ServerLevel level) {
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int count = 0;
        for (var holder : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
            int color = holder.value().getSkyColor();
            red += color >>> 16 & 0xFF;
            green += color >>> 8 & 0xFF;
            blue += color & 0xFF;
            count++;
        }
        return count == 0 ? 0 : ((int) (red / count) << 16)
                | ((int) (green / count) << 8)
                | (int) (blue / count);
    }

    /** Decodes and bilinearly resizes a cached RGB565 terrain map for a client texture. */
    public static int[] generateMapPixels(short @NotNull [] source, int width, int height) {
        PlanetTextureTier tier = PlanetTextureTier.fromPixelCount(source.length);
        if (tier == null || width <= 0 || height <= 0) {
            return new int[0];
        }
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            double sourceY = height == 1 ? 0.0 : y * (tier.height() - 1.0) / (height - 1.0);
            int y0 = (int) sourceY;
            int y1 = Math.min(tier.height() - 1, y0 + 1);
            double fy = sourceY - y0;
            for (int x = 0; x < width; x++) {
                double sourceX = width == 1 ? 0.0 : x * (tier.width() - 1.0) / (width - 1.0);
                int x0 = (int) sourceX;
                int x1 = Math.min(tier.width() - 1, x0 + 1);
                double fx = sourceX - x0;
                int top = blendRgb(decodeRgb565(source[y0 * tier.width() + x0]),
                        decodeRgb565(source[y0 * tier.width() + x1]), fx);
                int bottom = blendRgb(decodeRgb565(source[y1 * tier.width() + x0]),
                        decodeRgb565(source[y1 * tier.width() + x1]), fx);
                pixels[y * width + x] = 0xFF000000 | blendRgb(top, bottom, fy);
            }
        }
        return pixels;
    }

    public static int[] generatePixels(long textureSeed, @NotNull List<Planet.SurfaceColor> sourceColors,
                                       float fragmentation) {
        return generatePixels(textureSeed, sourceColors, fragmentation, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    /** Generates the legacy palette texture only while no persisted terrain map exists. */
    public static int[] generatePixels(long textureSeed, @NotNull List<Planet.SurfaceColor> sourceColors,
                                       float fragmentation, int width, int height) {
        List<Planet.SurfaceColor> colors = sourceColors.isEmpty()
                ? List.of(new Planet.SurfaceColor(FALLBACK_SURFACE_COLOR, 1))
                : sourceColors;
        int[] rgb = colors.stream().mapToInt(Planet.SurfaceColor::rgb).toArray();
        int[] weights = colors.stream().mapToInt(Planet.SurfaceColor::weight).toArray();
        return PlanetTextureLayout.generatePixels(textureSeed, rgb, weights, fragmentation, width, height);
    }

    public static long seedFromWorldAndPlanet(long worldSeed, @NotNull String planetId) {
        return PlanetTextureLayout.seedFromWorldAndPlanet(worldSeed, planetId);
    }

    private static short encodeRgb565(int rgb) {
        return (short) (((rgb >>> 19) & 0x1F) << 11
                | ((rgb >>> 10) & 0x3F) << 5
                | ((rgb >>> 3) & 0x1F));
    }

    private static int decodeRgb565(short packed) {
        int value = packed & 0xFFFF;
        int red = value >>> 11 & 0x1F;
        int green = value >>> 5 & 0x3F;
        int blue = value & 0x1F;
        return ((red << 3) | (red >>> 2)) << 16
                | ((green << 2) | (green >>> 4)) << 8
                | ((blue << 3) | (blue >>> 2));
    }

    private static int blendRgb(int first, int second, double amount) {
        double inverse = 1.0 - amount;
        int red = (int) Math.round((first >>> 16 & 0xFF) * inverse + (second >>> 16 & 0xFF) * amount);
        int green = (int) Math.round((first >>> 8 & 0xFF) * inverse + (second >>> 8 & 0xFF) * amount);
        int blue = (int) Math.round((first & 0xFF) * inverse + (second & 0xFF) * amount);
        return red << 16 | green << 8 | blue;
    }

    private static int colorDistanceSquared(int first, int second) {
        int red = (first >>> 16 & 0xFF) - (second >>> 16 & 0xFF);
        int green = (first >>> 8 & 0xFF) - (second >>> 8 & 0xFF);
        int blue = (first & 0xFF) - (second & 0xFF);
        return red * red + green * green + blue * blue;
    }

    private static List<Planet.SurfaceColor> surfaceColors(Map<Integer, Integer> colorCounts) {
        return colorCounts.entrySet().stream()
                .map(entry -> new Planet.SurfaceColor(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator.comparingInt(Planet.SurfaceColor::weight).reversed()
                        .thenComparingInt(Planet.SurfaceColor::rgb))
                .limit(4)
                .toList();
    }

    private static List<Planet.SurfaceSample> sortedSamples(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .map(entry -> new Planet.SurfaceSample(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator.comparingInt(Planet.SurfaceSample::count).reversed()
                        .thenComparing(Planet.SurfaceSample::id))
                .toList();
    }

    private static final class SamplingQueue {
        private final Deque<SurfaceMapJob> jobs = new ArrayDeque<>();
        private final Map<String, SurfaceMapJob> byPlanet = new LinkedHashMap<>();
    }

    /** Persists completed atlas tiers and checkpoints only sampled chunks, never live chunk objects. */
    private static final class SurfaceMapJob {
        private final MinecraftServer server;
        private final Planet planet;
        private final float fragmentation;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final PlanetTextureTier tier;
        private final PlanetSurfaceMapCache.Progress progress;
        private int[] colors;
        // Minecraft generation heights fit in signed shorts, halving each pending job's height buffer.
        private short[] heights;
        private final Map<String, Integer> biomeCounts;
        private final Map<String, Integer> coarseBiomeVotes;
        private final Map<String, Integer> fluidCounts;
        private final Map<String, Integer> blockCounts;
        private final Map<Integer, Integer> colorCounts;
        private final BitSet typeVoteSamples;
        private final Deque<PendingChunk> pendingChunks = new ArrayDeque<>();
        // Adjacent detailed samples share six terrain chunks; retain only the current 3x3 window.
        private final Map<ChunkPos, CompletableFuture<ChunkAccess>> featureTerrain = new LinkedHashMap<>();
        private int nextChunkToSubmit;
        private int completedChunks;
        private int nextPreviewTick;
        private final boolean bundledTexture;

        private SurfaceMapJob(MinecraftServer server, Planet planet, float fragmentation,
                              PlanetTextureTier tier, PlanetTextureTier requested) {
            this.server = server;
            this.planet = planet;
            this.fragmentation = fragmentation;
            PlanetSurfaceMapCache.Progress saved = PlanetSurfaceMapCache.findProgress(server, planet);
            this.progress = saved != null ? saved : new PlanetSurfaceMapCache.Progress(tier);
            this.tier = progress.tier;
            if (requested.ordinal() > progress.requestedTier.ordinal()) progress.requestedTier = requested;
            // Resume the same compact buffers and skip chunks that were already sampled before exit.
            this.colors = progress.colors;
            this.heights = progress.heights;
            this.biomeCounts = progress.biomes;
            this.coarseBiomeVotes = progress.coarseBiomeVotes;
            this.fluidCounts = progress.fluids;
            this.blockCounts = progress.blocks;
            this.colorCounts = progress.colorCounts;
            this.typeVoteSamples = typeVoteSamples(seedFromWorldAndPlanet(
                    server.overworld().getSeed(), planet.getId()));
            this.completedChunks = progress.sampled.cardinality();
            planet.setSurfaceScanProgress(completedChunks, PlanetTextureTier.FULL.chunks());
            this.bundledTexture = planet.getTexture().isPresent();
            PlanetSurfaceMapCache.trackProgress(server, planet, progress);
        }

        /** Defers a requested dynamic planet until its server level has finished registration. */
        private boolean isDimensionReady() {
            return server.getLevel(planet.getDimension()) != null;
        }

        private boolean processOneChunk() {
            ServerLevel level = server.getLevel(planet.getDimension());
            if (level == null) {
                return false;
            }
            PendingChunk finished = pendingChunks.stream()
                    .filter(pending -> pending.future().isDone())
                    .findFirst()
                    .orElse(null);
            if (finished != null) {
                pendingChunks.remove(finished);
                // The future is known to be complete, so this join cannot block the server thread.
                ChunkAccess chunk = finished.future().join();
                sampleChunk(level, chunk, finished.sampleIndex(), finished.chunkX(), finished.chunkZ());
                completedChunks++;
                progress.sampled.set(finished.sampleIndex());
                planet.setSurfaceScanProgress(completedChunks, PlanetTextureTier.FULL.chunks());
                PlanetSurfaceMapCache.trackProgress(server, planet, progress);
                PlanetRegistry.syncPlanetToAllPlayers(planet.getId());
                if (planet.getTextureGenerationDetail() == Planet.TextureGenerationDetail.FEATURES
                        && completedChunks < tier.chunks()
                        && planet.getGeneratedTextureTier() == null
                        && server.getTickCount() >= nextPreviewTick) {
                    publishColorPreview(level);
                }
            }
            // Submitted-but-unfinished chunks are intentionally regenerated after restart.
            nextChunkToSubmit = progress.sampled.nextClearBit(nextChunkToSubmit);
            if (nextChunkToSubmit < tier.chunks()
                    && pendingChunks.size() < maxPendingChunks()) {
                int chunkOffsetX = tier.chunkOffsetX(nextChunkToSubmit);
                int chunkOffsetZ = tier.chunkOffsetZ(nextChunkToSubmit);
                int chunkX = MAP_CHUNK_MIN_X + chunkOffsetX;
                int chunkZ = MAP_CHUNK_MIN_Z + chunkOffsetZ;
                // Generate only untracked temporary chunks; detailed planets add biome features in a local 3x3 window.
                pendingChunks.addLast(new PendingChunk(
                        nextChunkToSubmit,
                        chunkX,
                        chunkZ,
                        generateSurfaceChunk(level, chunkX, chunkZ, planet.getTextureGenerationDetail())
                ));
                nextChunkToSubmit++;
            }
            if (completedChunks == tier.chunks() && pendingChunks.isEmpty()) {
                commit(level);
                return true;
            }
            return false;
        }

        /** Shows actual sampled terrain colors while slow feature generation is still incomplete. */
        private void publishColorPreview(ServerLevel level) {
            if (colorCounts.isEmpty() || planet.getGeneratedTextureTier() != null) {
                return;
            }
            planet.setGeneratedSurfaceColors(surfaceColors(colorCounts), fragmentation,
                    seedFromWorldAndPlanet(server.overworld().getSeed(), planet.getId()));
            planet.setGeneratedAtmosphereColor(skyColorFromBiomeSource(level));
            // Keep any completed lower-tier map visible while its replacement is still sampling.
            nextPreviewTick = server.getTickCount() + 200;
            server.execute(() -> PlanetRegistry.syncPlanetToAllPlayers(planet.getId()));
            LOGGER.info("Published sampled-color preview for {} after {}/{} feature chunks",
                    planet.getId(), completedChunks, tier.chunks());
        }

        /** Keeps feature-complete 3x3 windows below the ten-chunk per-planet memory budget. */
        private int maxPendingChunks() {
            return planet.getTextureGenerationDetail() == Planet.TextureGenerationDetail.FEATURES
                    ? 1
                    : MAX_CHUNKS_PER_PLANET_PER_TICK;
        }

        /**
         * Runs an isolated terrain pipeline without requesting a ServerChunkCache chunk.
         */
        private CompletableFuture<ChunkAccess> generateSurfaceChunk(
                ServerLevel level,
                int chunkX,
                int chunkZ,
                Planet.TextureGenerationDetail detail
        ) {
            if (detail == Planet.TextureGenerationDetail.FEATURES) {
                return generateFeatureChunk(level, chunkX, chunkZ);
            }
            return generateSurfaceChunk(level, chunkX, chunkZ);
        }

        /** Generates base terrain only for the standard low-memory surface-texture path. */
        private static CompletableFuture<ChunkAccess> generateSurfaceChunk(ServerLevel level, int chunkX, int chunkZ) {
            var chunkSource = level.getChunkSource();
            ChunkGenerator generator = chunkSource.getGenerator();
            var randomState = chunkSource.randomState();
            Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
            ProtoChunk chunk = new ProtoChunk(
                    new ChunkPos(chunkX, chunkZ),
                    UpgradeData.EMPTY,
                    level,
                    biomes,
                    null
            );

            if (generator instanceof FlatLevelSource) {
                // Flat generators fill the temporary chunk directly and do not need surface rules.
                return generator.createBiomes(randomState, Blender.empty(), null, chunk)
                        .thenCompose(ignored -> generator.fillFromNoise(Blender.empty(), randomState, null, chunk))
                        .thenApply(ignored -> {
                            chunk.setPersistedStatus(ChunkStatus.SURFACE);
                            return (ChunkAccess) chunk;
                        });
            }
            if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
                throw new IllegalStateException("Unsupported surface texture generator "
                        + generator.getClass().getName() + " in " + level.dimension().location());
            }
            // Keep all generator work off the server thread; no stage waits with join().
            return CompletableFuture.supplyAsync(() -> {
                // Pre-install noise without structures so no sampler call can request a real world chunk.
                installStructureFreeNoiseChunk(chunk, noiseGenerator, randomState);
                chunk.fillBiomesFromNoise(generator.getBiomeSource(), randomState.sampler());
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                return chunk;
            }).thenCompose(ready -> noiseGenerator.fillFromNoise(Blender.empty(), randomState, null, ready))
                    .thenApply(ignored -> {
                        chunk.setPersistedStatus(ChunkStatus.NOISE);
                        BiomeManager biomeManager = new BiomeManager(
                                (noiseX, noiseY, noiseZ) -> generator.getBiomeSource()
                                        .getNoiseBiome(noiseX, noiseY, noiseZ, randomState.sampler()),
                                BiomeManager.obfuscateSeed(level.getSeed())
                        );
                        noiseGenerator.buildSurface(
                                chunk,
                                new WorldGenerationContext(generator, level),
                                randomState,
                                null,
                                biomeManager,
                                biomes,
                                Blender.empty()
                        );
                        chunk.setPersistedStatus(ChunkStatus.SURFACE);
                        return (ChunkAccess) chunk;
                    });
        }

        /**
         * Decorates one central temporary chunk with a 3x3 temporary neighborhood.
         * The neighborhood supplies feature reads and writes without entering the world's chunk cache.
         */
        private CompletableFuture<ChunkAccess> generateFeatureChunk(ServerLevel level, int chunkX, int chunkZ) {
            // Evict the previous fringe before generating new chunks to keep the memory bound unchanged.
            featureTerrain.keySet().removeIf(pos -> Math.abs(pos.x - chunkX) > 1 || Math.abs(pos.z - chunkZ) > 1);
            List<CompletableFuture<ChunkAccess>> terrain = new ArrayList<>(9);
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    ChunkPos pos = new ChunkPos(chunkX + offsetX, chunkZ + offsetZ);
                    terrain.add(featureTerrain.computeIfAbsent(pos,
                            key -> generateSurfaceChunk(level, key.x, key.z)));
                }
            }
            return CompletableFuture.allOf(terrain.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                Map<ChunkPos, ChunkAccess> chunks = new LinkedHashMap<>();
                int index = 0;
                for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    for (int offsetX = -1; offsetX <= 1; offsetX++) {
                        ChunkAccess chunk = terrain.get(index++).join();
                        chunks.put(chunk.getPos(), chunk);
                    }
                }
                ChunkAccess center = chunks.get(new ChunkPos(chunkX, chunkZ));
                decorateTemporaryChunk(level, center, chunks);
                return center;
            });
        }

        /** Invokes vanilla biome decoration against a bounded fake generation region. */
        private static void decorateTemporaryChunk(
                ServerLevel level,
                ChunkAccess center,
                Map<ChunkPos, ChunkAccess> chunks
        ) {
            ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
            StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(
                    center.getPos().x,
                    center.getPos().z,
                    1,
                    (x, z) -> new TemporaryGenerationChunkHolder(chunks.get(new ChunkPos(x, z)))
            );
            WorldGenRegion region = new WorldGenRegion(level, cache, step, center);
            // Feature placement must update the final heightmaps, including new tree canopies.
            if (center instanceof ProtoChunk protoChunk) {
                protoChunk.setPersistedStatus(ChunkStatus.FEATURES);
            }
            Heightmap.primeHeightmaps(center, java.util.EnumSet.of(
                    Heightmap.Types.MOTION_BLOCKING,
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    Heightmap.Types.OCEAN_FLOOR,
                    Heightmap.Types.WORLD_SURFACE
            ));
            level.getChunkSource().getGenerator().applyBiomeDecoration(
                    region,
                    center,
                    level.structureManager().forWorldGenRegion(region)
            );
        }

        private record PendingChunk(int sampleIndex, int chunkX, int chunkZ, CompletableFuture<ChunkAccess> future) {
        }

        /** Makes only the nine temporary chunks visible to a WorldGenRegion. */
        private static final class TemporaryGenerationChunkHolder extends GenerationChunkHolder {
            private final ChunkAccess chunk;

            private TemporaryGenerationChunkHolder(ChunkAccess chunk) {
                super(chunk.getPos());
                this.chunk = chunk;
            }

            @Override
            public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
                return chunk;
            }

            @Override
            public ChunkStatus getPersistedStatus() {
                return chunk.getPersistedStatus();
            }

            @Override
            public int getTicketLevel() {
                return 0;
            }

            @Override
            public int getQueueLevel() {
                return 0;
            }
        }

        /**
         * Supplies an empty terrain-adjustment marker, so temporary sampling never queries structures.
         */
        private static void installStructureFreeNoiseChunk(
                ProtoChunk chunk,
                NoiseBasedChunkGenerator generator,
                net.minecraft.world.level.levelgen.RandomState randomState
        ) {
            NoiseGeneratorSettings settings = generator.generatorSettings().value();
            chunk.getOrCreateNoiseChunk(ignored -> NoiseChunk.forChunk(
                    ignored,
                    randomState,
                    EmptyBeardifier.INSTANCE,
                    settings,
                    samplingFluidPicker(settings),
                    Blender.empty()
            ));
        }

        /** Mirrors the generator's default aquifer fluid selection for the temporary noise chunk. */
        private static Aquifer.FluidPicker samplingFluidPicker(NoiseGeneratorSettings settings) {
            Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
            Aquifer.FluidStatus defaultFluid = new Aquifer.FluidStatus(
                    settings.seaLevel(),
                    settings.defaultFluid()
            );
            return (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : defaultFluid;
        }

        /** Replaces structure beardification with zero contribution for isolated texture sampling. */
        private enum EmptyBeardifier implements DensityFunctions.BeardifierOrMarker {
            INSTANCE;

            @Override
            public double compute(DensityFunction.FunctionContext context) {
                return 0.0;
            }

            @Override
            public void fillArray(double[] values, DensityFunction.ContextProvider contextProvider) {
                Arrays.fill(values, 0.0);
            }

            @Override
            public double minValue() {
                return 0.0;
            }

            @Override
            public double maxValue() {
                return 0.0;
            }
        }

        private void sampleChunk(ServerLevel level, ChunkAccess chunk, int sampleIndex, int chunkX, int chunkZ) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight() - 1;
            // The first six FULL samples also provide the lightweight planet-type vote.
            boolean typeVoteSample = tier == PlanetTextureTier.FULL
                    ? typeVoteSamples.get(sampleIndex)
                    : sampleIndex < PlanetTextureTier.COARSE.chunks();
            Map<String, Integer> chunkBiomeCounts = typeVoteSample
                    ? new LinkedHashMap<>() : Map.of();
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int worldX = (chunkX << 4) + localX;
                    int worldZ = (chunkZ << 4) + localZ;
                    int height = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, localX, localZ);
                    int surfaceY = findSurfaceY(chunk, pos, worldX, worldZ, Math.min(maxY, height + 1), minY);
                    int pixel = tier.pixelIndex(sampleIndex, localX, localZ);
                    heights[pixel] = (short) surfaceY;
                    if (surfaceY < minY) {
                        colors[pixel] = FALLBACK_SURFACE_COLOR;
                        continue;
                    }
                    pos.set(worldX, surfaceY, worldZ);
                    BlockState state = chunk.getBlockState(pos);
                    BlockState colorState = correctedSurfaceState(chunk, state, pos);
                    // Reuse the current chunk so 256 biome reads do not re-query the level chunk source.
                    var biomeHolder = chunk.getNoiseBiome(
                            QuartPos.fromBlock(worldX),
                            QuartPos.fromBlock(surfaceY),
                            QuartPos.fromBlock(worldZ)
                    );
                    Biome biome = biomeHolder.value();
                    biomeHolder.unwrapKey().ifPresent(key -> {
                        String biomeId = key.location().toString();
                        biomeCounts.merge(biomeId, 1, Integer::sum);
                        if (typeVoteSample) {
                            chunkBiomeCounts.merge(PlanetBiomeType.classify(biomeId), 1, Integer::sum);
                        }
                    });
                    if (!state.getFluidState().isEmpty()) {
                        fluidCounts.merge(BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString(),
                                1, Integer::sum);
                    } else if (!colorState.isAir()) {
                        blockCounts.merge(BuiltInRegistries.BLOCK.getKey(colorState.getBlock()).toString(),
                                1, Integer::sum);
                    }
                    MapColor mapColor = colorState.getMapColor(chunk, pos);
                    int rgb = tintedMapColor(mapColor, colorState, biome, worldX, worldZ);
                    colors[pixel] = rgb;
                    colorCounts.merge(rgb, 1, Integer::sum);
                }
            }
            if (!chunkBiomeCounts.isEmpty()) {
                String chunkBiome = selectTiedMaximum(
                        chunkBiomeCounts,
                        seedFromWorldAndPlanet(server.overworld().getSeed(), planet.getId()) ^ sampleIndex
                );
                coarseBiomeVotes.merge(chunkBiome, 1, Integer::sum);
                planet.setPlanetTypeBiome(selectTiedMaximum(
                        coarseBiomeVotes,
                        seedFromWorldAndPlanet(server.overworld().getSeed(), planet.getId())
                ));
            }
        }

        private void commit(ServerLevel level) {
            // A restored full cache must never be overwritten by a still-finishing coarse job.
            PlanetTextureTier current = planet.getGeneratedTextureTier();
            if (current != null && current.ordinal() > tier.ordinal()) {
                releaseTemporaryData();
                return;
            }
            short[] encoded = new short[tier.pixels()];
            for (int z = 0; z < tier.height(); z++) {
                for (int x = 0; x < tier.width(); x++) {
                    int index = z * tier.width() + x;
                    int north = (z == 0 || (tier != PlanetTextureTier.FULL && z % tier.faceSize() == 0) ? z : z - 1) * tier.width() + x;
                    int delta = heights[index] - heights[north];
                    double shade = delta > 0 ? 1.06 : delta < 0 ? 0.94 : 1.0;
                    encoded[index] = encodeRgb565(scaleRgb(colors[index], shade));
                }
            }
            // Bundled textures keep their authored pixels; scanning only contributes metadata.
            if (!bundledTexture) {
                planet.setGeneratedSurfaceMap(encoded);
                planet.setGeneratedSurfaceColors(surfaceColors(colorCounts), fragmentation,
                        seedFromWorldAndPlanet(server.overworld().getSeed(), planet.getId()));
                planet.setGeneratedAtmosphereColor(skyColorFromBiomeSource(level));
            }
            if (tier == PlanetTextureTier.COARSE && !coarseBiomeVotes.isEmpty()) {
                planet.setPlanetTypeBiome(selectTiedMaximum(
                        coarseBiomeVotes,
                        seedFromWorldAndPlanet(server.overworld().getSeed(), planet.getId())
                ));
            }
            if (tier == PlanetTextureTier.FULL) {
                planet.setSurfaceSamples(
                        sortedSamples(biomeCounts),
                        sortedSamples(fluidCounts),
                        sortedSamples(blockCounts)
                );
                planet.setSurfaceScanStatus(Planet.SurfaceScanStatus.COMPLETE);
                planet.setSurfaceScanProgress(PlanetTextureTier.FULL.chunks(), PlanetTextureTier.FULL.chunks());
            } else if (progress.requestedTier.ordinal() > tier.ordinal()) {
                planet.setSurfaceScanStatus(Planet.SurfaceScanStatus.SCANNING);
            } else {
                planet.setSurfaceScanStatus(Planet.SurfaceScanStatus.UNKNOWN);
            }
            // Publish the new completed tier and remove its obsolete lower-tier cache and progress.
            if (bundledTexture) {
                PlanetSurfaceMapCache.storeMetadata(server, planet);
            } else {
                PlanetSurfaceMapCache.store(server, planet, encoded);
            }
            // Publish this completed map immediately; other planets may still be sampling for many ticks.
            server.execute(() -> PlanetRegistry.syncPlanetToAllPlayers(planet.getId()));
            LOGGER.info("Completed {} {}x{} surface map for {} from {} chunks",
                    tier, tier.width(), tier.height(), planet.getId(), tier.chunks());
            releaseTemporaryData();
        }

        /** Releases per-job sampling buffers as soon as a map is persisted or aborted. */
        private void releaseTemporaryData() {
            pendingChunks.forEach(pending -> pending.future().cancel(false));
            pendingChunks.clear();
            featureTerrain.values().forEach(future -> future.cancel(false));
            featureTerrain.clear();
            colors = null;
            heights = null;
            // Progress owns the sampled metadata until completion; keep it intact across save/exit.
        }
    }

    private static int findSurfaceY(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                    int x, int z, int startY, int minY) {
        for (int y = startY; y >= minY; y--) {
            pos.set(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            if (!state.isAir() && state.getMapColor(chunk, pos) != MapColor.NONE) {
                return y;
            }
        }
        return minY - 1;
    }

    /** Selects a maximum-count ID and uses the stable planet seed only to break exact ties. */
    private static String selectTiedMaximum(Map<String, Integer> counts, long seed) {
        int maximum = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> tied = counts.entrySet().stream()
                .filter(entry -> entry.getValue() == maximum)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        return tied.get(new Random(seed).nextInt(tied.size()));
    }

    /** Selects one deterministic random chunk from each of the six atlas faces. */
    private static BitSet typeVoteSamples(long seed) {
        BitSet samples = new BitSet(PlanetTextureTier.FULL.chunks());
        Random random = new Random(seed ^ 0x6A09E667F3BCC909L);
        int faceWidth = PlanetTextureTier.FULL.faceSize() / 16;
        int atlasWidth = faceWidth * 3;
        for (int faceZ = 0; faceZ < 2; faceZ++) {
            for (int faceX = 0; faceX < 3; faceX++) {
                int localX = random.nextInt(faceWidth);
                int localZ = random.nextInt(faceWidth);
                samples.set((faceZ * faceWidth + localZ) * atlasWidth
                        + faceX * faceWidth + localX);
            }
        }
        return samples;
    }

    /** Mirrors the vanilla map correction for exposed fluid blocks. */
    private static BlockState correctedSurfaceState(ChunkAccess chunk, BlockState state, BlockPos pos) {
        if (!state.getFluidState().isEmpty() && !state.isFaceSturdy(chunk, pos, Direction.UP)) {
            return state.getFluidState().createLegacyBlock();
        }
        return state;
    }

    private static int tintedMapColor(MapColor mapColor, BlockState state, Biome biome, int x, int z) {
        if (mapColor == MapColor.WATER) {
            return biome.getWaterColor() & 0xFFFFFF;
        }
        if (mapColor == MapColor.GRASS) {
            return biome.getGrassColor(x, z) & 0xFFFFFF;
        }
        if (mapColor == MapColor.PLANT || state.is(BlockTags.LEAVES)) {
            return biome.getFoliageColor() & 0xFFFFFF;
        }
        int rgb = mapColor.col & 0xFFFFFF;
        return rgb == 0 ? FALLBACK_SURFACE_COLOR : rgb;
    }

    private static int scaleRgb(int rgb, double scale) {
        int red = Math.min(255, (int) Math.round((rgb >>> 16 & 0xFF) * scale));
        int green = Math.min(255, (int) Math.round((rgb >>> 8 & 0xFF) * scale));
        int blue = Math.min(255, (int) Math.round((rgb & 0xFF) * scale));
        return red << 16 | green << 8 | blue;
    }
}








