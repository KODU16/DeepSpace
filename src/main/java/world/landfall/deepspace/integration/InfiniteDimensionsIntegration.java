package world.landfall.deepspace.integration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.Config;
import world.landfall.deepspace.planet.Galaxy;
import world.landfall.deepspace.planet.ParadiseRating;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.RingWorldDimensions;
import world.landfall.deepspace.planet.RingWorldDamage;
import world.landfall.deepspace.planet.RingWorldOriginState;
import world.landfall.deepspace.planet.RingWorldNoonTime;
import world.landfall.deepspace.planet.Sun;
import world.landfall.deepspace.planet.StarIdentity;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Optional bridge that creates a second Deep Space galaxy through Infinite Dimensions' runtime world loader.
 */
@EventBusSubscriber(modid = Deepspace.MODID)
public final class InfiniteDimensionsIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String INFINITY_MOD_ID = "infinity";
    private static final ResourceKey<Level> PRIMARY_SPACE =
            ResourceKey.create(Registries.DIMENSION, Deepspace.path("space"));
    private static final ResourceLocation WORMHOLE_TEXTURE = Deepspace.path("textures/wormhole.png");
    private static final int WORMHOLE_SIZE = 100;
    private static final int MAX_CANDIDATES_PER_PLANET = 16;
    // 任意星系生成时有 1% 概率成为环世界星系（只生成恒星与四条环世界棱）。
    private static final double RING_WORLD_CHANCE = 0.01;
    private static final double RING_WORLD_STAR_SIZE = 4.0;
    private static final double RING_WORLD_FIXED_STAR_RADIUS =
            InfiniteStarSystemLayout.BASE_STAR_RADIUS * RING_WORLD_STAR_SIZE;
    // 固定环世界尺寸：生成、进入盒和客户端完整模型共用 Gecko 模型推导值。
    private static final int RING_WORLD_SECTION_COUNT = RingWorldDimensions.SECTION_COUNT;
    // 无限星系中带大气行星的占比；其余行星按无大气黑空处理。
    private static final float ATMOSPHERE_PLANET_FRACTION = 0.8F;
    private static final int OVERWORLD_SKY_COLOR = 0x78A7FF;
    private static final int AIRLESS_SKY_COLOR = 0;
    private static final Set<String> VANILLA_BOSS_ENTITY_IDS = Set.of(
            "minecraft:ender_dragon",
            "minecraft:wither"
    );
    private static final long RETRY_INTERVAL = 20L;
    private static final String LEGACY_CHAIN_DEPTH_FILE = "deepspace_infinite_chain.dat";
    private static final String GENERATED_GRAPH_FILE = "deepspace_infinite_graph.dat";
    private static final String FORCED_RING_WORLDS_FILE = "deepspace_forced_ring_worlds.dat";
    private static final String FORCED_RING_WORLD_DAMAGE_FILE = "deepspace_forced_ring_world_broken_counts.dat";

    private static MinecraftServer activeServer;
    private static boolean initialized;
    private static long nextRetryTick;
    private static final Map<ResourceKey<Level>, Integer> CHAIN_INDICES = new java.util.HashMap<>();
    private static final Map<ResourceKey<Level>, Integer> DIMENSION_INDICES = new java.util.HashMap<>();
    private static final Set<Integer> GENERATED_INDICES = new HashSet<>();
    private static final Set<Integer> FORCED_RING_WORLD_INDICES = new HashSet<>();
    private static final Map<Integer, Integer> FORCED_RING_WORLD_BROKEN_COUNTS = new java.util.HashMap<>();
    private static final Map<Class<?>, Boolean> GENERATED_BOSS_CLASSES = new java.util.HashMap<>();
    private static final Map<ResourceLocation, Boolean> GENERATED_BOSS_ENTITY_TYPES = new java.util.HashMap<>();
    private static Set<ResourceKey<Level>> pendingTextureLevels = Set.of();
    private static final Set<ResourceLocation> PENDING_REJECTED_LEVEL_STORAGE = new HashSet<>();

    private InfiniteDimensionsIntegration() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        RingWorldOriginState originState = RingWorldOriginState.resolve(event.getServer());
        if (!ModList.get().isLoaded(INFINITY_MOD_ID)) {
            if (originState.enabled()) {
                LOGGER.error("Ring-world origin is enabled for this save, but Infinite Dimensions is unavailable");
            }
            return;
        }
        if (!Config.INFINITE_DIMENSIONS_WORMHOLES.get() && !originState.enabled()) {
            return;
        }
        activeServer = event.getServer();
        tryInitialize(activeServer);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (activeServer == null || event.getServer() != activeServer) {
            return;
        }
        if (initialized) {
            if (!pendingTextureLevels.isEmpty()
                    && pendingTextureLevels.stream().allMatch(key -> activeServer.getLevel(key) != null)) {
                // Warp and first login choose when to prewarm; this only waits for dimension registration.
                pendingTextureLevels = Set.of();
            }
            return;
        }
        long gameTime = activeServer.overworld().getGameTime();
        if (gameTime >= nextRetryTick) {
            nextRetryTick = gameTime + RETRY_INTERVAL;
            tryInitialize(activeServer);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        if (event.getServer() == activeServer) {
            for (ResourceLocation id : Set.copyOf(PENDING_REJECTED_LEVEL_STORAGE)) {
                try {
                    deleteRejectedDimensionStorage(event.getServer(), id);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Could not delete rejected dimension storage {} after shutdown", id, exception);
                }
            }
            PENDING_REJECTED_LEVEL_STORAGE.clear();
            activeServer = null;
            initialized = false;
            nextRetryTick = 0L;
            CHAIN_INDICES.clear();
            DIMENSION_INDICES.clear();
            GENERATED_INDICES.clear();
            FORCED_RING_WORLD_INDICES.clear();
            FORCED_RING_WORLD_BROKEN_COUNTS.clear();
            GENERATED_BOSS_CLASSES.clear();
            GENERATED_BOSS_ENTITY_TYPES.clear();
            pendingTextureLevels = Set.of();
        }
    }

    private static void tryInitialize(MinecraftServer server) {
        try {
            boolean graphEnabled = Config.INFINITE_DIMENSIONS_WORMHOLES.get();
            Set<Integer> storedIndices = graphEnabled
                    ? new HashSet<>(readGeneratedIndices(server))
                    : new HashSet<>();
            FORCED_RING_WORLD_INDICES.clear();
            FORCED_RING_WORLD_BROKEN_COUNTS.clear();
            if (graphEnabled) {
                FORCED_RING_WORLD_INDICES.addAll(readIntegerSet(server, FORCED_RING_WORLDS_FILE));
                FORCED_RING_WORLD_BROKEN_COUNTS.putAll(readIntegerMap(server, FORCED_RING_WORLD_DAMAGE_FILE));
            }
            storedIndices.addAll(FORCED_RING_WORLD_INDICES);
            Set<ResourceKey<Level>> textureLevels = new HashSet<>();
            if (RingWorldOriginState.resolve(server).enabled()) {
                textureLevels.addAll(initializePrimaryRingWorldOrigin(server));
            }
            Galaxy primary = PlanetRegistry.getGalaxyByDimension(PRIMARY_SPACE);
            if (primary == null) {
                throw new IllegalStateException("Primary Deep Space galaxy is unavailable");
            }
            primary = withSafePrimaryArrival(primary, server.overworld().getSeed());
            PlanetRegistry.registerGalaxy(primary);
            CHAIN_INDICES.put(PRIMARY_SPACE, -1);
            DIMENSION_INDICES.put(PRIMARY_SPACE, -1);
            for (int index : storedIndices.stream().sorted().toList()) {
                boolean forcedRingWorld = FORCED_RING_WORLD_INDICES.contains(index);
                Integer brokenCount = forcedRingWorld
                        ? FORCED_RING_WORLD_BROKEN_COUNTS.getOrDefault(index, 0)
                        : null;
                GeneratedSystem system = generateSystem(server, index, forcedRingWorld, brokenCount);
                registerSystem(server, system, textureLevels);
                GENERATED_INDICES.add(index);
            }
            if (graphEnabled) {
                registerPrimaryGraphWormholes(primary, server.overworld().getSeed());
            }
            refreshResolvedWormholes();
            if (graphEnabled) {
                writeGeneratedIndices(server, GENERATED_INDICES);
            }
            initialized = true;
            pendingTextureLevels = Set.copyOf(textureLevels);
            PlanetRegistry.syncToAllPlayers();
            LOGGER.info(
                    "Initialized Infinite Dimensions integration with {} generated galaxies, graphEnabled={}, ringWorldOrigin={}",
                    GENERATED_INDICES.size(), graphEnabled, RingWorldOriginState.resolve(server).enabled()
            );
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("Infinite Dimensions galaxy is not ready; retrying after invocation/runtime setup: {}",
                    exception.getMessage());
        }
    }

    private static GeneratedSystem generateSystem(MinecraftServer server, int chainIndex)
            throws ReflectiveOperationException {
        return generateSystem(server, chainIndex, false);
    }

    /**
     * Generates one galaxy system. When {@code forceRingWorld} is set or the deterministic
     * 1% roll succeeds, the galaxy contains only its stars plus four ring world edges;
     * otherwise the normal orbiting-planet and wormhole graph is produced.
     */
    private static GeneratedSystem generateSystem(MinecraftServer server, int chainIndex, boolean forceRingWorld)
            throws ReflectiveOperationException {
        return generateSystem(server, chainIndex, forceRingWorld, forceRingWorld ? 0 : null);
    }

    /** Uses a separate deterministic stream so damage selection cannot perturb names or dimensions. */
    private static GeneratedSystem generateSystem(
            MinecraftServer server,
            int chainIndex,
            boolean forceRingWorld,
            Integer forcedBrokenCount
    ) throws ReflectiveOperationException {
        long worldSeed = server.overworld().getSeed();
        long chainSalt = mix64(chainIndex * 0x632BE59BD9B4E019L);
        Random random = new Random(mix64(worldSeed ^ 0x49A3E77D9B5C21A1L ^ chainSalt));
        // A separate stream keeps dimension salts independent from names and layout choices.
        Random dimensionRandom = new Random(mix64(worldSeed ^ 0x7C61D4A92E58B30FL ^ chainSalt));
        Random damageRandom = new Random(mix64(worldSeed ^ 0x42524F4B454E5249L ^ chainSalt));
        // Always consume the classification roll so forced and natural systems keep the same stable identity.
        boolean naturalRingWorld = random.nextDouble() < RING_WORLD_CHANCE;
        boolean ringWorld = forceRingWorld || naturalRingWorld;
        String galaxyName = PronounceableNameGenerator.generate(
                random,
                InfiniteGalaxyLayout.NAME_MIN_LENGTH,
                InfiniteGalaxyLayout.NAME_MAX_LENGTH
        );
        String galaxyId = "infinite_" + chainIndex + "_" + galaxyName.toLowerCase(Locale.ROOT);
        ResourceKey<Level> galaxyDimension = ResourceKey.create(
                Registries.DIMENSION,
                Deepspace.path("galaxy_" + chainIndex + "_" + galaxyName.toLowerCase(Locale.ROOT))
        );

        List<Sun> generatedStars = generateStars(random, galaxyName);
        if (ringWorld) {
            int selectedBrokenSections = forcedBrokenCount == null
                    ? RingWorldDamage.naturalMask(damageRandom)
                    : RingWorldDamage.maskWithCount(damageRandom, forcedBrokenCount);
            int brokenSections = RingWorldDamage.activeMask(selectedBrokenSections);
            int repairableBrokenSections = RingWorldDamage.repairableMask(damageRandom, brokenSections);
            return generateRingWorldSystem(
                    server,
                    chainIndex,
                    galaxyId,
                    galaxyName,
                    galaxyDimension,
                    random,
                    dimensionRandom,
                    generatedStars,
                    brokenSections,
                    repairableBrokenSections
            );
        }
        Sun primaryStar = generatedStars.getFirst();
        int requestedPlanetCount = InfiniteGalaxyLayout.randomPlanetCount(random);
        List<Planet> generatedPlanets = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        double[] nextOrbitRadii = generatedStars.stream()
                .mapToDouble(star -> starRadius(star) + 900.0 + random.nextDouble() * 600.0)
                .toArray();
        double systemOuterRadius = generatedStars.stream()
                .mapToDouble(star -> horizontalDistance(star.getCenter(), new Vec3(0.0, 200.0, 0.0)) + starRadius(star))
                .max()
                .orElse(1_000.0);
        for (int planetIndex = 0; planetIndex < requestedPlanetCount; planetIndex++) {
            GeneratedPlanetWorld world = generatePlanetWorld(
                    server,
                    random,
                    dimensionRandom,
                    galaxyName,
                    galaxyDimension,
                    usedNames
            );
            double planetScale = InfiniteGalaxyLayout.randomPlanetScale(random);
            double halfExtent = InfiniteGalaxyLayout.BASE_PLANET_RADIUS * planetScale;
            int hostIndex = random.nextInt(generatedStars.size());
            Vec3 center = placePlanetNearHost(
                    random,
                    generatedStars.get(hostIndex),
                    halfExtent,
                    nextOrbitRadii[hostIndex],
                    generatedStars,
                    generatedPlanets
            );
            nextOrbitRadii[hostIndex] = horizontalDistance(center, generatedStars.get(hostIndex).getCenter())
                    + halfExtent * Math.sqrt(3.0) + 700.0 + random.nextDouble() * 700.0;
            String id = galaxyId + "_" + world.name().toLowerCase(Locale.ROOT);
            Planet generatedPlanet = new Planet(
                    id,
                    world.name(),
                    world.dimension(),
                    center.subtract(halfExtent, halfExtent, halfExtent),
                    center.add(halfExtent, halfExtent, halfExtent),
                    world.decorations(),
                    "A procedurally generated world in the " + galaxyName + " galaxy",
                    new Vec2(-1000.0F, -1000.0F),
                    new Vec2(1000.0F, 1000.0F),
                    List.of(),
                    galaxyDimension,
                    null,
                    world.atmosphereEntryHeight(),
                    world.atmosphereExitHeight()
            );
            generatedPlanet.setParadiseProfile(world.paradiseProfile());
            generatedPlanets.add(generatedPlanet);
            systemOuterRadius = Math.max(
                    systemOuterRadius,
                    horizontalDistance(center, new Vec3(0.0, 200.0, 0.0)) + halfExtent * Math.sqrt(3.0)
            );
        }

        Vec3 generatedArrival = orbitPoint(
                new Vec3(0.0, 200.0, 0.0),
                systemOuterRadius + 600.0,
                random.nextDouble() * Math.PI * 2.0
        );
        Galaxy galaxy = new Galaxy(
                galaxyId,
                galaxyName,
                galaxyDimension,
                generatedArrival,
                primaryStar,
                generatedStars
        );

        List<Planet> allBodies = new ArrayList<>(generatedPlanets);
        Vec3 eclipticCenter = new Vec3(0.0, 200.0, 0.0);
        for (int neighborIndex : InfiniteGalaxyLayout.wormholeGraphNeighbors(
                chainIndex,
                worldSeed,
                Config.INFINITE_WORMHOLE_DENSITY.get()
        )) {
            double radius = InfiniteGalaxyLayout.wormholeOrbitRadius(
                    systemOuterRadius,
                    1_200.0 + random.nextDouble() * 1_200.0,
                    WORMHOLE_SIZE
            );
            double angle = InfiniteGalaxyLayout.wormholeGraphAngle(chainIndex, neighborIndex, worldSeed);
            ResourceKey<Level> targetDimension = neighborIndex == -1
                    ? PRIMARY_SPACE
                    : galaxyDimensionKey(worldSeed, neighborIndex);
            Galaxy targetGalaxy = PlanetRegistry.getGalaxyByDimension(targetDimension);
            String targetName = targetGalaxy == null
                    ? nextGalaxyName(worldSeed, neighborIndex)
                    : targetGalaxy.name();
            Vec3 targetArrival = targetGalaxy == null ? Vec3.ZERO : targetGalaxy.arrival();
            allBodies.add(createWormhole(
                    galaxyId + "_wormhole_" + graphIndexSlug(neighborIndex),
                    targetName + " Hyper Relay",
                    galaxyDimension,
                    targetDimension,
                    orbitPoint(eclipticCenter, radius, angle),
                    targetArrival
            ));
        }
        return new GeneratedSystem(chainIndex, galaxy, allBodies, requestedPlanetCount);
    }

    /**
     * Generates one planet surface world through the Infinite Dimensions candidate pipeline:
     * dimension preview, layout normalization, biome naming and sky normalization, boss removal,
     * and finally world registration. The caller decides where the resulting body is placed.
     */
    private static GeneratedPlanetWorld generatePlanetWorld(
            MinecraftServer server,
            Random random,
            Random dimensionRandom,
            String galaxyName,
            ResourceKey<Level> galaxyDimension,
            Set<String> usedNames
    ) throws ReflectiveOperationException {
        return generatePlanetWorld(
                server, random, dimensionRandom, galaxyName, galaxyDimension, usedNames, null, false
        );
    }

    /** Generates a candidate with an optional exact two-biome checkerboard source. */
    private static GeneratedPlanetWorld generatePlanetWorld(
            MinecraftServer server,
            Random random,
            Random dimensionRandom,
            String galaxyName,
            ResourceKey<Level> galaxyDimension,
            Set<String> usedNames,
            RingOriginBiomes prescribedBiomes,
            boolean fixedNoon
    ) throws ReflectiveOperationException {
        int attempts = 0;
        Map<ResourceLocation, RejectedCandidate> rejectedCandidates = new java.util.LinkedHashMap<>();
        while (attempts++ < MAX_CANDIDATES_PER_PLANET) {
            String planetName = uniqueName(random, usedNames);
            long dimensionSeed = InfiniteGalaxyLayout.randomDimensionSeed(dimensionRandom);
            ResourceLocation dimensionId = ResourceLocation.fromNamespaceAndPath(
                    INFINITY_MOD_ID,
                    "generated_" + dimensionSeed
            );
            InfinityBiomeGenerationRules.GenerationPlan biomePlan = prescribedBiomes == null
                    ? InfinityBiomeGenerationRules.randomPlan(dimensionSeed)
                    : InfinityBiomeGenerationRules.prescribedPlan(
                            prescribedBiomes.firstBiome(), prescribedBiomes.secondBiome()
                    );
            DimensionProfile profile = inspectDimension(server, dimensionId, biomePlan);
            if (!profile.previewGenerated()
                    && (profile.minY() != server.overworld().getMinBuildHeight()
                    || profile.height() != server.overworld().getHeight())) {
                // Keep rejected data until a complete replacement set exists, so retries cannot shrink the galaxy.
                LOGGER.info(
                        "[DEEPSPACE-INFINITE] phase=CANDIDATE_REJECTED_REGENERATING dimension={} reason=vertical_layout minY={} height={}",
                        dimensionId,
                        profile.minY(),
                        profile.height()
                );
                rejectedCandidates.put(dimensionId, new RejectedCandidate(false));
                continue;
            }
            // Inspect native ceilings before Overworld dimension-type normalization erases that flag.
            if (profile.hasCeiling()) {
                rejectedCandidates.put(dimensionId, new RejectedCandidate(profile.previewGenerated()));
                LOGGER.info("[DEEPSPACE-INFINITE] phase=CANDIDATE_REJECTED_REGENERATING dimension={} reason=terrain_roof", dimensionId);
                continue;
            }
            profile = normalizeGeneratedDimensionType(server, dimensionId, profile, fixedNoon);
            if (profile.hasCeiling()
                    || !InfiniteGalaxyLayout.matchesSectionCount(profile.height(), server.overworld().getHeight())) {
                LOGGER.info(
                        "[DEEPSPACE-INFINITE] phase=CANDIDATE_REJECTED_REGENERATING dimension={} reason=ceiling_or_sections ceiling={} height={}",
                        dimensionId,
                        profile.hasCeiling(),
                        profile.height()
                );
                rejectedCandidates.put(dimensionId, new RejectedCandidate(profile.previewGenerated()));
                continue;
            }
            rejectedCandidates.remove(dimensionId);
            // Refine new terrain before naming or loading; restored dimensions keep their existing generator.
            try {
                if (!InfinityTerrainRefinement.apply(server, generatedPackRoot(server, dimensionId), biomePlan,
                        profile.generatedDefinition())) {
                    rejectedCandidates.put(dimensionId, new RejectedCandidate(profile.previewGenerated()));
                    LOGGER.info("[DEEPSPACE-INFINITE] phase=CANDIDATE_REJECTED_REGENERATING dimension={} reason=high_flat_plateau", dimensionId);
                    continue;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not refine generated terrain for " + dimensionId, exception);
            }
            nameGeneratedBiomes(server, dimensionId, dimensionSeed, biomePlan);
            // 大气星球占比固定为 80%；大气颜色完全随机，天空颜色直接取大气颜色，
            // 不再读取 Infinite 生成群系的天空色。其余 20% 为无大气黑空。
            boolean hasAtmosphere = random.nextDouble() < ATMOSPHERE_PLANET_FRACTION;
            int atmosphereColor = hasAtmosphere ? randomAtmosphereColor(random) : AIRLESS_SKY_COLOR;
            normalizeGeneratedBiomeVisuals(server, dimensionId, atmosphereColor);
            removeFixedShapeDecorations(server, dimensionId);
            removeBossSpawns(server, dimensionId);
            sanitizeGeneratedDimensionOptions(server, dimensionId);
            Planet.ParadiseProfile paradiseProfile = inspectParadiseProfile(
                    server, dimensionId, biomePlan, hasAtmosphere, atmosphereColor
            );
            addInfinityDimension(server, dimensionId, profile.generatedDefinition());
            ServerLevel generatedLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
            if (generatedLevel != null && (generatedLevel.getMinBuildHeight() != server.overworld().getMinBuildHeight()
                    || generatedLevel.getSectionsCount() != server.overworld().getSectionsCount())) {
                throw new IllegalStateException(
                        "Generated Infinite Dimensions level did not adopt the overworld section layout: " + dimensionId
                );
            }
            LOGGER.info(
                    "[DEEPSPACE-INFINITE] phase={} dimension={} minY={} height={} sections={}",
                    generatedLevel == null ? "WORLD_ADD_QUEUED" : "VERTICAL_LAYOUT_LOCKED",
                    dimensionId,
                    generatedLevel == null ? server.overworld().getMinBuildHeight() : generatedLevel.getMinBuildHeight(),
                    generatedLevel == null ? server.overworld().getHeight() : generatedLevel.getHeight(),
                    generatedLevel == null ? server.overworld().getSectionsCount() : generatedLevel.getSectionsCount()
            );
            if (generatedLevel != null) {
                disableInfinityShader(server, dimensionId, generatedLevel);
            }
            InfiniteGalaxyLayout.AtmosphereHeights atmosphereHeights = InfiniteGalaxyLayout.atmosphereHeights(
                    server.overworld().getMinBuildHeight(),
                    server.overworld().getMaxBuildHeight()
            );
            cleanupRejectedCandidates(server, rejectedCandidates);
            List<Planet.PlanetDecoration> decorations = hasAtmosphere
                    ? List.of(new Planet.PlanetDecoration(
                            Planet.PlanetDecoration.ATMOSPHERE,
                            1.1F,
                            0xFF000000 | atmosphereColor))
                    : List.of();
            return new GeneratedPlanetWorld(
                    planetName,
                    ResourceKey.create(Registries.DIMENSION, dimensionId),
                    decorations,
                    atmosphereHeights.entryHeight(),
                    atmosphereHeights.exitHeight(),
                    paradiseProfile
            );
        }
        throw new IllegalStateException(
                "Could not find enough ceiling-free Infinite Dimensions candidates with Sable-compatible height"
        );
    }

    /** Builds the primary system as one fixed star and four ring edges, with the overworld on edge zero. */
    private static Set<ResourceKey<Level>> initializePrimaryRingWorldOrigin(MinecraftServer server)
            throws ReflectiveOperationException {
        Galaxy configuredPrimary = PlanetRegistry.getGalaxyByDimension(PRIMARY_SPACE);
        if (configuredPrimary == null) {
            throw new IllegalStateException("Primary Deep Space galaxy is unavailable for ring-world origin");
        }
        Planet configuredOverworld = PlanetRegistry.getPlanetByDimension(Level.OVERWORLD);
        List<ResourceLocation> overworldTextures = configuredOverworld == null
                ? List.of()
                : configuredOverworld.getTextures();
        String saveName = server.getWorldData().getLevelName();
        Sun primaryStar = fixedRingWorldSun(configuredPrimary.sun()).withName(saveName);
        Vec3 starCenter = primaryStar.getCenter();
        InfiniteGalaxyLayout.AtmosphereHeights overworldHeights = InfiniteGalaxyLayout.atmosphereHeights(
                server.overworld().getMinBuildHeight(),
                server.overworld().getMaxBuildHeight()
        );
        long worldSeed = server.overworld().getSeed();
        // The configured primary origin keeps its Overworld section healthy; other sections honor the config count.
        Random damageRandom = new Random(mix64(worldSeed ^ 0x42524F4B454E5249L));
        int brokenSections = RingWorldDamage.activeMask(
                RingWorldDamage.primaryMaskWithCount(damageRandom, Config.RING_WORLD_BROKEN_SECTION_COUNT.get())
        );
        int repairableBrokenSections = RingWorldDamage.repairableMask(damageRandom, brokenSections);

        List<Planet> edges = new ArrayList<>(4);
        Vec3[] overworldBounds = ringWorldEdgeBounds(0, starCenter);
        // The Overworld always occupies healthy section zero; only sections one through three may break.
        Planet overworldEdge = new Planet(
                "overworld",
                "Overworld",
                Level.OVERWORLD,
                overworldBounds[0],
                overworldBounds[1],
                List.of(),
                "The origin segment of the primary ring world",
                new Vec2(-1000.0F, -1000.0F),
                new Vec2(1000.0F, 1000.0F),
                overworldTextures,
                PRIMARY_SPACE,
                null,
                overworldHeights.entryHeight(),
                overworldHeights.exitHeight(),
                true
        );
        overworldEdge.setParadiseProfile(primaryRingParadiseProfile());
        edges.add(overworldEdge);

        RingOriginBiomes[] compositions = {
                new RingOriginBiomes(
                        "Sparse Jungle / Deep Ocean",
                        "minecraft:sparse_jungle",
                        "minecraft:deep_ocean",
                        Deepspace.path("textures/ring_origin_sparse_jungle.png")
                ),
                new RingOriginBiomes(
                        "Flower Forest / Warm Ocean",
                        "minecraft:flower_forest",
                        "minecraft:warm_ocean",
                        Deepspace.path("textures/ring_origin_flower_forest.png")
                ),
                new RingOriginBiomes(
                        "Plains / River",
                        "minecraft:plains",
                        "minecraft:river",
                        Deepspace.path("textures/ring_origin_plains_river.png")
                )
        };
        Random names = new Random(mix64(worldSeed ^ 0x52494E474F524947L));
        Random dimensions = new Random(mix64(worldSeed ^ 0x5345474D454E5453L));
        Set<String> usedNames = new HashSet<>();
        Set<ResourceKey<Level>> textureLevels = new HashSet<>();
        // The primary ring replaces the ordinary Overworld planet, so discard its earlier samples and resample edge 0 too.
        textureLevels.add(Level.OVERWORLD);
        for (int index = 1; index < 4; index++) {
            if (RingWorldDamage.isBroken(brokenSections, index)) {
                // Broken sections are structural wreckage only: no dimension, planet or collision volume exists.
                continue;
            }
            RingOriginBiomes composition = compositions[index - 1];
            GeneratedPlanetWorld generated = generatePlanetWorld(
                    server,
                    names,
                    dimensions,
                    "Overworld",
                    PRIMARY_SPACE,
                    usedNames,
                    composition,
                    true
            );
            Vec3[] bounds = ringWorldEdgeBounds(index, starCenter);
            Planet generatedEdge = new Planet(
                    "landfall_ring_" + index,
                    composition.name(),
                    generated.dimension(),
                    bounds[0],
                    bounds[1],
                    List.of(),
                    "A primary ring-world segment composed equally of "
                            + composition.firstBiome() + " and " + composition.secondBiome(),
                    new Vec2(-1000.0F, -1000.0F),
                    new Vec2(1000.0F, 1000.0F),
                    List.of(composition.texture()),
                    PRIMARY_SPACE,
                    null,
                    generated.atmosphereEntryHeight(),
                    generated.atmosphereExitHeight(),
                    true
            );
            // These authored vanilla biome pairs share the Overworld's hospitable environment.
            generatedEdge.setParadiseProfile(primaryRingParadiseProfile());
            edges.add(generatedEdge);
            textureLevels.add(generated.dimension());
        }

        double arrivalRadius = RingWorldDimensions.OUTER_RADIUS + 1_200.0D;
        Galaxy ringPrimary = new Galaxy(
                configuredPrimary.id(),
                "Overworld",
                PRIMARY_SPACE,
                new Vec3(0.0, 200.0, arrivalRadius),
                primaryStar,
                List.of(primaryStar),
                brokenSections,
                repairableBrokenSections
        );
        validateRingWorldGeometry(primaryStar, edges, brokenSections, "primary");
        PlanetRegistry.replacePrimaryGalaxyBodies(ringPrimary, edges);
        LOGGER.info(
                "[DEEPSPACE-INFINITE] phase=RING_ORIGIN_READY star={} edges={} generatedDimensions={}",
                primaryStar.getStage(), edges.size(), textureLevels.stream().map(ResourceKey::location).toList()
        );
        return textureLevels;
    }

    /**
     * Builds a ring world galaxy from fixed display constants. Each edge maps to one generated
     * planet world and uses the edge-sized bounding box, so contact entry behaves like a regular planet.
     */
    private static GeneratedSystem generateRingWorldSystem(
            MinecraftServer server,
            int chainIndex,
            String galaxyId,
            String galaxyName,
            ResourceKey<Level> galaxyDimension,
            Random random,
            Random dimensionRandom,
            List<Sun> generatedStars,
            int brokenSections,
            int repairableBrokenSections
    ) throws ReflectiveOperationException {
        Sun primaryStar = fixedRingWorldSun(generatedStars.getFirst());
        // 环世界只保留一颗固定主恒星，避免随机副恒星破坏各环世界的统一布局。
        List<Sun> ringStars = List.of(primaryStar);
        Vec3 starCenter = primaryStar.getCenter();
        Set<String> usedNames = new HashSet<>();
        List<Planet> edges = new ArrayList<>(4);
        for (int index = 0; index < 4; index++) {
            if (RingWorldDamage.isBroken(brokenSections, index)) {
                // Broken sections deliberately omit their world, bounding box and dynamic surface texture.
                continue;
            }
            GeneratedPlanetWorld world = generatePlanetWorld(
                    server,
                    random,
                    dimensionRandom,
                    galaxyName,
                    galaxyDimension,
                    usedNames,
                    null,
                    true
            );
            Vec3[] bounds = ringWorldEdgeBounds(index, starCenter);
            Vec3 min = bounds[0];
            Vec3 max = bounds[1];
            Planet generatedEdge = new Planet(
                    galaxyId + "_ring_" + index,
                    world.name(),
                    world.dimension(),
                    min,
                    max,
                    List.of(), // 环世界棱暂时只保留地表，不继承生成维度的大气装饰。
                    "One of four ring world segments around " + galaxyName + ", hosting " + world.name(),
                    new Vec2(-1000.0F, -1000.0F),
                    new Vec2(1000.0F, 1000.0F),
                    List.of(),
                    galaxyDimension,
                    null,
                    world.atmosphereEntryHeight(),
                    world.atmosphereExitHeight(),
                    true
            );
            generatedEdge.setParadiseProfile(world.paradiseProfile());
            edges.add(generatedEdge);
            LOGGER.info(
                    "[DEEPSPACE-INFINITE] phase=RING_EDGE_GENERATED galaxy={} edge={} planet={} dimension={} min={} max={}",
                    galaxyName,
                    index,
                    world.name(),
                    world.dimension().location(),
                    min,
                    max
            );
        }
        double systemOuterRadius = RingWorldDimensions.OUTER_RADIUS + 600.0D;
        Vec3 generatedArrival = orbitPoint(
                starCenter,
                systemOuterRadius + 600.0,
                random.nextDouble() * Math.PI * 2.0
        );
        Galaxy galaxy = new Galaxy(
                galaxyId,
                galaxyName,
                galaxyDimension,
                generatedArrival,
                primaryStar,
                ringStars,
                brokenSections,
                repairableBrokenSections
        );
        validateRingWorldGeometry(primaryStar, edges, brokenSections, galaxyId);
        return new GeneratedSystem(chainIndex, galaxy, edges, edges.size());
    }

    /** Returns the four planet centers implied by the exact rotated Gecko section envelopes. */
    private static double[][] ringWorldEdgeCenters(Vec3 starCenter) {
        double[][] centers = new double[RING_WORLD_SECTION_COUNT][2];
        for (int index = 0; index < centers.length; index++) {
            RingWorldDimensions.Bounds bounds = RingWorldDimensions.sectionBounds(index);
            centers[index][0] = starCenter.x + bounds.centerX();
            centers[index][1] = starCenter.z + bounds.centerZ();
        }
        return centers;
    }

    /** Returns the exact AABB enclosing one fixed, rotated Gecko ring section. */
    private static Vec3[] ringWorldEdgeBounds(int index, Vec3 starCenter) {
        RingWorldDimensions.Bounds bounds = RingWorldDimensions.sectionBounds(index);
        Vec3 min = new Vec3(
                starCenter.x + bounds.minX(),
                starCenter.y + bounds.minY(),
                starCenter.z + bounds.minZ()
        );
        Vec3 max = new Vec3(
                starCenter.x + bounds.maxX(),
                starCenter.y + bounds.maxY(),
                starCenter.z + bounds.maxZ()
        );
        return new Vec3[]{min, max};
    }

    private static Sun fixedRingWorldSun(Sun sun) {
        // 环世界统一为单星系统，主恒星中心固定在星系 X/Z 原点和 y=200 黄道面。
        Vec3 center = new Vec3(0.0, 200.0, 0.0);
        Vec3 halfSize = new Vec3(
                RING_WORLD_FIXED_STAR_RADIUS,
                RING_WORLD_FIXED_STAR_RADIUS,
                RING_WORLD_FIXED_STAR_RADIUS
        );
        // 环世界中心恒星固定半径，但保留生成恒星的随机光谱类型和颜色。
        return new Sun(
                center.subtract(halfSize),
                center.add(halfSize),
                RING_WORLD_FIXED_STAR_RADIUS * 1.5,
                sun.getName(),
                sun.getStage(),
                sun.getColor()
        );
    }

    /** Rejects any ring system whose star size or edge centers drift from the shared fixed layout. */
    private static void validateRingWorldGeometry(
            Sun star,
            List<Planet> edges,
            int brokenSections,
            String systemId
    ) {
        Vec3 center = star.getCenter();
        if (center.distanceToSqr(new Vec3(0.0D, 200.0D, 0.0D)) > 1.0E-8D
                || Math.abs(star.getModelRadius() - RING_WORLD_FIXED_STAR_RADIUS) > 1.0E-8D
                || edges.size() != RING_WORLD_SECTION_COUNT - RingWorldDamage.count(brokenSections)) {
            throw new IllegalStateException("Invalid fixed ring-world star or edge count for " + systemId);
        }
        double[][] expectedCenters = ringWorldEdgeCenters(center);
        int healthyEdgeIndex = 0;
        for (int index = 0; index < expectedCenters.length; index++) {
            if (RingWorldDamage.isBroken(brokenSections, index)) {
                continue;
            }
            Planet edge = edges.get(healthyEdgeIndex++);
            Vec3 actual = edge.getCenter();
            Vec3[] expectedBounds = ringWorldEdgeBounds(index, center);
            if (Math.abs(actual.x - expectedCenters[index][0]) > 1.0E-8D
                    || Math.abs(actual.y - center.y) > 1.0E-8D
                    || Math.abs(actual.z - expectedCenters[index][1]) > 1.0E-8D
                    || edge.getBoundingBoxMin().distanceToSqr(expectedBounds[0]) > 1.0E-8D
                    || edge.getBoundingBoxMax().distanceToSqr(expectedBounds[1]) > 1.0E-8D) {
                throw new IllegalStateException("Ring-world section " + index + " bounds drifted in " + systemId);
            }
        }
    }

    /**
     * Debug command hook: creates a random ring world galaxy at a fresh chain index, registers
     * a hyper relay (wormhole) in the primary galaxy pointing at it, and a paired relay back.
     */
    public static Galaxy summonRingWorldAtPrimary(MinecraftServer server) {
        return summonRingWorldAtPrimary(server, 0);
    }

    /** Debug variant with an exact number of randomly positioned broken sections. */
    public static Galaxy summonRingWorldAtPrimary(MinecraftServer server, int brokenSectionCount) {
        if (brokenSectionCount < 0 || brokenSectionCount >= RING_WORLD_SECTION_COUNT) {
            throw new IllegalArgumentException("Broken section count must be in [0, 3]");
        }
        if (activeServer == null || server != activeServer) {
            throw new IllegalStateException("Infinite Dimensions bridge is not ready");
        }
        Galaxy primary = PlanetRegistry.getGalaxyByDimension(PRIMARY_SPACE);
        if (primary == null) {
            throw new IllegalStateException("Primary Deep Space galaxy is unavailable");
        }
        Random random = new Random();
        int index;
        do {
            index = random.nextInt(2_000_000);
        } while (GENERATED_INDICES.contains(index));
        try {
            Set<ResourceKey<Level>> textureLevels = new HashSet<>(pendingTextureLevels);
            GeneratedSystem system = generateSystem(server, index, true, brokenSectionCount);
            registerSystem(server, system, textureLevels);
            GENERATED_INDICES.add(index);
            FORCED_RING_WORLD_INDICES.add(index);
            FORCED_RING_WORLD_BROKEN_COUNTS.put(index, brokenSectionCount);
            writeGeneratedIndices(server, GENERATED_INDICES);
            writeIntegerSet(server, FORCED_RING_WORLDS_FILE, FORCED_RING_WORLD_INDICES);
            writeIntegerMap(server, FORCED_RING_WORLD_DAMAGE_FILE, FORCED_RING_WORLD_BROKEN_COUNTS);
            pendingTextureLevels = Set.copyOf(textureLevels);

            Galaxy ringGalaxy = system.galaxy;
            double relayRadius = outerRadius(primary, primary.dimension())
                    + 1_200.0 + random.nextDouble() * 1_200.0;
            Vec3 relayPosition = orbitPoint(
                    primary.sun().getCenter(),
                    relayRadius,
                    random.nextDouble() * Math.PI * 2.0
            );
            PlanetRegistry.registerPlanet(createWormhole(
                    primary.id() + "_wormhole_ring_" + system.chainIndex,
                    ringGalaxy.name() + " Hyper Relay",
                    primary.dimension(),
                    ringGalaxy.dimension(),
                    relayPosition,
                    ringGalaxy.arrival()
            ));
            Vec3 returnPosition = orbitPoint(
                    ringGalaxy.sun().getCenter(),
                    outerRadius(ringGalaxy, ringGalaxy.dimension()) + 800.0 + random.nextDouble() * 800.0,
                    random.nextDouble() * Math.PI * 2.0
            );
            PlanetRegistry.registerPlanet(createWormhole(
                    ringGalaxy.id() + "_wormhole_primary",
                    primary.name() + " Hyper Relay",
                    ringGalaxy.dimension(),
                    primary.dimension(),
                    returnPosition,
                    primary.arrival()
            ));
            refreshResolvedWormholes();
            PlanetRegistry.syncToAllPlayers();
            LOGGER.info(
                    "[DEEPSPACE-INFINITE] phase=RING_WORLD_SUMMONED galaxy={} dimension={} relay={}",
                    ringGalaxy.name(),
                    ringGalaxy.dimension().location(),
                    primary.id() + "_wormhole_ring_" + system.chainIndex
            );
            return ringGalaxy;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Could not summon ring world galaxy", exception);
        }
    }

    /** Materializes one graph neighbor only when any wormhole leading to it is actually reached. */
    public static Planet resolveWormholeDestination(MinecraftServer server, Planet wormhole) {
        if (!initialized || !wormhole.isWormhole() || server != activeServer) {
            return wormhole;
        }
        Galaxy existingTarget = PlanetRegistry.getGalaxyByDimension(wormhole.getDimension());
        if (existingTarget != null) {
            return replaceWormholeMetadataIfNeeded(wormhole, existingTarget);
        }
        Integer sourceIndex = CHAIN_INDICES.get(wormhole.getGalaxy());
        Integer targetIndex = resolveTargetGraphIndex(server, sourceIndex, wormhole.getDimension());
        if (sourceIndex == null || targetIndex == null || targetIndex < 0) {
            return wormhole;
        }
        try {
            GeneratedSystem next = generateSystem(server, targetIndex);
            Set<ResourceKey<Level>> textureLevels = new HashSet<>(pendingTextureLevels);
            registerSystem(server, next, textureLevels);
            GENERATED_INDICES.add(next.chainIndex);
            writeGeneratedIndices(server, GENERATED_INDICES);
            refreshResolvedWormholes();
            pendingTextureLevels = Set.copyOf(textureLevels);
            PlanetRegistry.syncToAllPlayers();
            LOGGER.info(
                    "Extended Infinite Dimensions wormhole graph from index {} to {} ({}) with {} planets",
                    sourceIndex, targetIndex, next.galaxy.name(), next.randomPlanetCount
            );
            Planet resolved = PlanetRegistry.getPlanet(wormhole.getId());
            return resolved == null ? wormhole : resolved;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("Could not extend Infinite Dimensions wormhole chain from {}", wormhole.getId(), exception);
            return wormhole;
        }
    }

    private static void registerSystem(
            MinecraftServer server,
            GeneratedSystem system,
            Set<ResourceKey<Level>> textureLevels
    ) throws ReflectiveOperationException {
        registerGalaxyDimension(server, system.galaxy.dimension());
        PlanetRegistry.registerGalaxy(system.galaxy);
        CHAIN_INDICES.put(system.galaxy.dimension(), system.chainIndex);
        DIMENSION_INDICES.put(system.galaxy.dimension(), system.chainIndex);
        InfiniteGalaxyLayout.wormholeGraphNeighbors(
                system.chainIndex,
                server.overworld().getSeed(),
                Config.INFINITE_WORMHOLE_DENSITY.get()
        ).forEach(neighbor -> DIMENSION_INDICES.put(
                neighbor == -1 ? PRIMARY_SPACE : galaxyDimensionKey(server.overworld().getSeed(), neighbor),
                neighbor
        ));
        system.planets.forEach(planet -> {
            PlanetRegistry.registerPlanet(planet);
            if (!planet.isWormhole()) {
                textureLevels.add(planet.getDimension());
            }
        });
        // Dynamic dimensions are created after login, so publish Simulated's empty-or-persisted lock state now.
        server.getPlayerList().getPlayers().forEach(PhysicsStaffServerHandler::sendAllData);
    }

    /** Adds every configured graph edge incident to the primary galaxy. */
    private static void registerPrimaryGraphWormholes(Galaxy source, long worldSeed) {
        Random random = new Random(mix64(worldSeed ^ 0x2A6E6F586C14F781L));
        for (int neighborIndex : InfiniteGalaxyLayout.wormholeGraphNeighbors(
                -1,
                worldSeed,
                Config.INFINITE_WORMHOLE_DENSITY.get()
        )) {
            ResourceKey<Level> targetDimension = galaxyDimensionKey(worldSeed, neighborIndex);
            DIMENSION_INDICES.put(targetDimension, neighborIndex);
            Galaxy target = PlanetRegistry.getGalaxyByDimension(targetDimension);
            double radius = InfiniteGalaxyLayout.wormholeOrbitRadius(
                    outerRadius(source, source.dimension()),
                    1_200.0 + random.nextDouble() * 1_200.0,
                    WORMHOLE_SIZE
            );
            PlanetRegistry.registerPlanet(createWormhole(
                    source.id() + "_wormhole_" + graphIndexSlug(neighborIndex),
                    (target == null ? nextGalaxyName(worldSeed, neighborIndex) : target.name()) + " Hyper Relay",
                    source.dimension(),
                    targetDimension,
                    orbitPoint(source.sun().getCenter(), radius,
                            InfiniteGalaxyLayout.wormholeGraphAngle(-1, neighborIndex, worldSeed)),
                    target == null ? Vec3.ZERO : target.arrival()
            ));
        }
    }

    /** Uses the same between-orbits arrival rule for a return trip to the primary galaxy. */
    private static Galaxy withSafePrimaryArrival(Galaxy primary, long worldSeed) {
        List<Planet> planets = PlanetRegistry.getPlanetsForGalaxy(primary.dimension()).stream()
                .filter(planet -> !planet.isWormhole())
                .toList();
        Vec3 arrival = interplanetaryArrival(
                new Random(mix64(worldSeed ^ 0x5CD7284E38A916B3L)),
                primary.sun(),
                planets
        );
        return new Galaxy(
                primary.id(),
                primary.name(),
                primary.dimension(),
                arrival,
                primary.sun(),
                primary.suns(),
                primary.brokenRingSections(),
                primary.repairableBrokenSections()
        );
    }

    /** Resolves display and arrival metadata without changing either endpoint's physical graph bearing. */
    private static Planet replaceWormholeMetadataIfNeeded(Planet placeholder, Galaxy target) {
        if (placeholder.getWarpTarget().filter(target.arrival()::equals).isPresent()
                && placeholder.getName().equals(target.name() + " Hyper Relay")) {
            return placeholder;
        }
        Planet resolved = createWormhole(
                placeholder.getId(),
                target.name() + " Hyper Relay",
                placeholder.getGalaxy(),
                target.dimension(),
                placeholder.getCenter(),
                target.arrival()
        );
        PlanetRegistry.registerPlanet(resolved);
        return resolved;
    }

    private static void refreshResolvedWormholes() {
        for (Planet wormhole : PlanetRegistry.getAllPlanets().stream().filter(Planet::isWormhole).toList()) {
            Galaxy target = PlanetRegistry.getGalaxyByDimension(wormhole.getDimension());
            if (target != null) {
                replaceWormholeMetadataIfNeeded(wormhole, target);
            }
        }
    }

    private static Integer resolveTargetGraphIndex(
            MinecraftServer server,
            Integer sourceIndex,
            ResourceKey<Level> targetDimension
    ) {
        Integer known = DIMENSION_INDICES.get(targetDimension);
        if (known != null || sourceIndex == null) {
            return known;
        }
        for (int candidate : InfiniteGalaxyLayout.wormholeGraphNeighbors(
                sourceIndex,
                server.overworld().getSeed(),
                Config.INFINITE_WORMHOLE_DENSITY.get()
        )) {
            ResourceKey<Level> candidateDimension = candidate == -1
                    ? PRIMARY_SPACE
                    : galaxyDimensionKey(server.overworld().getSeed(), candidate);
            DIMENSION_INDICES.put(candidateDimension, candidate);
            if (candidateDimension.equals(targetDimension)) {
                return candidate;
            }
        }
        return null;
    }

    private static String graphIndexSlug(int graphIndex) {
        return graphIndex < 0 ? "primary" : Integer.toString(graphIndex);
    }

    private static String nextGalaxyName(long worldSeed, int chainIndex) {
        long chainSalt = mix64(chainIndex * 0x632BE59BD9B4E019L);
        Random random = new Random(mix64(worldSeed ^ 0x49A3E77D9B5C21A1L ^ chainSalt));
        // Match generateSystem's classification roll before deriving the dimension-bearing name.
        random.nextDouble();
        return PronounceableNameGenerator.generate(
                random,
                InfiniteGalaxyLayout.NAME_MIN_LENGTH,
                InfiniteGalaxyLayout.NAME_MAX_LENGTH
        );
    }

    private static ResourceKey<Level> galaxyDimensionKey(long worldSeed, int chainIndex) {
        return ResourceKey.create(
                Registries.DIMENSION,
                Deepspace.path("galaxy_" + chainIndex + "_" + nextGalaxyName(worldSeed, chainIndex).toLowerCase(Locale.ROOT))
        );
    }

    private static Set<Integer> readGeneratedIndices(MinecraftServer server) {
        Path graphPath = graphStatePath(server, GENERATED_GRAPH_FILE);
        if (Files.exists(graphPath)) {
            try {
                Set<Integer> indices = new HashSet<>();
                for (String line : Files.readAllLines(graphPath, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        indices.add(Math.max(0, Integer.parseInt(line.trim())));
                    }
                }
                if (!indices.isEmpty()) {
                    return indices;
                }
            } catch (IOException | NumberFormatException exception) {
                throw new IllegalStateException("Could not read Infinite Dimensions graph state " + graphPath, exception);
            }
        }
        Path legacyPath = graphStatePath(server, LEGACY_CHAIN_DEPTH_FILE);
        int legacyDepth = 0;
        try {
            if (Files.exists(legacyPath)) {
                legacyDepth = Math.max(0, Integer.parseInt(Files.readString(legacyPath, StandardCharsets.UTF_8).trim()));
            }
        } catch (IOException | NumberFormatException exception) {
            throw new IllegalStateException("Could not migrate Infinite Dimensions chain state " + legacyPath, exception);
        }
        Set<Integer> migrated = new HashSet<>();
        for (int index = 0; index <= legacyDepth; index++) {
            migrated.add(index);
        }
        return migrated;
    }

    /** Persists the generated vertex set because graph exploration can branch and return through cycles. */
    private static void writeGeneratedIndices(MinecraftServer server, Set<Integer> indices) {
        writeIntegerSet(server, GENERATED_GRAPH_FILE, indices);
    }

    private static Set<Integer> readIntegerSet(MinecraftServer server, String fileName) {
        Path path = graphStatePath(server, fileName);
        if (!Files.exists(path)) {
            return Set.of();
        }
        try {
            Set<Integer> values = new HashSet<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    values.add(Math.max(0, Integer.parseInt(line.trim())));
                }
            }
            return values;
        } catch (IOException | NumberFormatException exception) {
            throw new IllegalStateException("Could not read Infinite Dimensions integer state " + path, exception);
        }
    }

    /** Stores graph state sets as newline-delimited integers for deterministic startup restore. */
    private static void writeIntegerSet(MinecraftServer server, String fileName, Set<Integer> indices) {
        Path path = graphStatePath(server, fileName);
        try {
            String serialized = String.join("\n", indices.stream().sorted().map(String::valueOf).toList());
            Files.writeString(path, serialized, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not persist Infinite Dimensions integer state " + path, exception);
        }
    }

    /** Reads newline-delimited index=count pairs used to restore forced ring-world damage. */
    private static Map<Integer, Integer> readIntegerMap(MinecraftServer server, String fileName) {
        Path path = graphStatePath(server, fileName);
        if (!Files.exists(path)) {
            return Map.of();
        }
        try {
            Map<Integer, Integer> values = new java.util.HashMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.trim().split("=", 2);
                if (fields.length != 2) {
                    throw new IllegalArgumentException("Expected index=count");
                }
                int index = Math.max(0, Integer.parseInt(fields[0]));
                int count = Integer.parseInt(fields[1]);
                if (count < 0 || count >= RING_WORLD_SECTION_COUNT) {
                    throw new IllegalArgumentException("Broken section count must be in [0, 3]");
                }
                values.put(index, count);
            }
            return values;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not read Infinite Dimensions integer map " + path, exception);
        }
    }

    /** Stores forced debug damage counts without changing the legacy forced-ring index file. */
    private static void writeIntegerMap(MinecraftServer server, String fileName, Map<Integer, Integer> values) {
        Path path = graphStatePath(server, fileName);
        try {
            String serialized = String.join("\n", values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toList());
            Files.writeString(path, serialized, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not persist Infinite Dimensions integer map " + path, exception);
        }
    }

    private static Path graphStatePath(MinecraftServer server, String fileName) {
        Path datapacks = server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize();
        Path worldRoot = datapacks.getParent();
        if (worldRoot == null) {
            throw new IllegalStateException("World root is unavailable for Infinite Dimensions chain state");
        }
        return worldRoot.resolve(fileName).normalize();
    }

    private static String uniqueName(Random random, Set<String> usedNames) {
        String name;
        do {
            name = PronounceableNameGenerator.generate(
                    random,
                    InfiniteGalaxyLayout.NAME_MIN_LENGTH,
                    InfiniteGalaxyLayout.NAME_MAX_LENGTH
            );
        } while (!usedNames.add(name.toLowerCase(Locale.ROOT)));
        return name;
    }

    private static Planet createWormhole(
            String id,
            String name,
            ResourceKey<Level> sourceGalaxy,
            ResourceKey<Level> targetGalaxy,
            Vec3 center,
            Vec3 target
    ) {
        double halfExtent = WORMHOLE_SIZE / 2.0;
        return new Planet(
                id,
                name,
                targetGalaxy,
                center.subtract(halfExtent, halfExtent, halfExtent),
                center.add(halfExtent, halfExtent, halfExtent),
                List.of(),
                "A stable passage between galaxies",
                new Vec2(-50.0F, -50.0F),
                new Vec2(50.0F, 50.0F),
                List.of(WORMHOLE_TEXTURE),
                sourceGalaxy,
                target
        );
    }

    private static Vec3 orbitPoint(Vec3 center, double radius, double angle) {
        return center.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
    }

    /** Chooses a stable empty-space radius between the first and last planetary orbit. */
    private static Vec3 interplanetaryArrival(Random random, Sun sun, List<Planet> planets) {
        if (planets.isEmpty()) {
            throw new IllegalStateException("A wormhole destination galaxy must contain at least one planet");
        }
        double firstOrbit = planets.stream()
                .mapToDouble(planet -> sun.getCenter().distanceTo(planet.getCenter()))
                .min()
                .orElseThrow();
        double outerOrbit = planets.stream()
                .mapToDouble(planet -> sun.getCenter().distanceTo(planet.getCenter()))
                .max()
                .orElseThrow();
        double radius = InfiniteGalaxyLayout.interplanetaryArrivalRadius(random, firstOrbit, outerOrbit);
        return orbitPoint(sun.getCenter(), radius, random.nextDouble() * Math.PI * 2.0);
    }

    private static double outerRadius(Galaxy galaxy, ResourceKey<Level> dimension) {
        double planetRadius = PlanetRegistry.getAllPlanets().stream()
                .filter(planet -> planet.getGalaxy().equals(dimension) && !planet.isWormhole())
                .mapToDouble(planet -> horizontalDistance(galaxy.sun().getCenter(), planet.getCenter())
                        + planet.getBoundingBoxMax().subtract(planet.getBoundingBoxMin()).length() * 0.5)
                .max()
                .orElse(1_000.0);
        double starRadius = galaxy.suns().stream()
                .mapToDouble(star -> horizontalDistance(galaxy.sun().getCenter(), star.getCenter()) + starRadius(star))
                .max()
                .orElse(0.0);
        return Math.max(planetRadius, starRadius);
    }

    /** Generates one to three non-overlapping stars on the shared y=200 ecliptic plane. */
    private static List<Sun> generateStars(Random random, String galaxyName) {
        int count = InfiniteStarSystemLayout.randomStarCount(random);
        List<Sun> stars = new ArrayList<>(count);
        double occupiedRadius = 0.0;
        for (int index = 0; index < count; index++) {
            InfiniteStarSystemLayout.SpectralClass spectralClass =
                    InfiniteStarSystemLayout.randomSpectralClass(random);
            double radius = InfiniteStarSystemLayout.randomRadius(random, spectralClass);
            Vec3 center;
            if (index == 0) {
                center = new Vec3(0.0, 200.0, 0.0);
            } else {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double distance = occupiedRadius + radius
                        + InfiniteStarSystemLayout.BODY_CLEARANCE
                        + 900.0 + random.nextDouble() * 1_500.0;
                center = orbitPoint(new Vec3(0.0, 200.0, 0.0), distance, angle);
            }
            Sun star = new Sun(
                    center.subtract(radius, radius, radius),
                    center.add(radius, radius, radius),
                    radius * 1.5,
                    StarIdentity.name(galaxyName, count, index),
                    spectralClass.name(),
                    spectralClass.color()
            );
            stars.add(star);
            occupiedRadius = Math.max(occupiedRadius, horizontalDistance(center, new Vec3(0.0, 200.0, 0.0)) + radius);
            LOGGER.info(
                    "[DEEPSPACE-INFINITE] phase=STAR_GENERATED galaxy={} star={} class={} radius={} center={}",
                    galaxyName,
                    star.getName(),
                    spectralClass,
                    radius,
                    center
            );
        }
        return List.copyOf(stars);
    }

    /** Places a planet near its selected host while conservatively separating every cubic body. */
    private static Vec3 placePlanetNearHost(
            Random random,
            Sun host,
            double halfExtent,
            double initialOrbitRadius,
            List<Sun> stars,
            List<Planet> planets
    ) {
        double bodyRadius = halfExtent * Math.sqrt(3.0);
        double orbitRadius = initialOrbitRadius;
        for (int attempt = 0; attempt < 256; attempt++) {
            Vec3 candidate = orbitPoint(host.getCenter(), orbitRadius, random.nextDouble() * Math.PI * 2.0);
            boolean overlapsStar = stars.stream().anyMatch(star -> InfiniteStarSystemLayout.overlaps(
                    candidate.x, candidate.z, bodyRadius,
                    star.getCenter().x, star.getCenter().z, starRadius(star)
            ));
            boolean overlapsPlanet = planets.stream().anyMatch(planet -> {
                double otherRadius = planet.getBoundingBoxMax().subtract(planet.getBoundingBoxMin()).length() * 0.5;
                return InfiniteStarSystemLayout.overlaps(
                        candidate.x, candidate.z, bodyRadius,
                        planet.getCenter().x, planet.getCenter().z, otherRadius
                );
            });
            if (!overlapsStar && !overlapsPlanet) {
                return candidate;
            }
            orbitRadius += 350.0 + random.nextDouble() * 450.0;
        }
        throw new IllegalStateException("Could not place a generated planet without celestial overlap");
    }

    private static double starRadius(Sun star) {
        return star.getBoundingBoxMax().x - star.getCenter().x;
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        return Math.hypot(first.x - second.x, first.z - second.z);
    }

    private static DimensionProfile inspectDimension(
            MinecraftServer server,
            ResourceLocation id,
            InfinityBiomeGenerationRules.GenerationPlan biomePlan
    )
            throws ReflectiveOperationException {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        ServerLevel existing = server.getLevel(key);
        if (existing != null) {
            return new DimensionProfile(
                    existing.dimensionType().hasCeiling(),
                    existing.getMinBuildHeight(),
                    existing.getHeight(),
                    false,
                    null
            );
        }

        Class<?> randomDimensionClass = InfiniteDimensionsApi.loadClass("dimensions.RandomDimension");
        Constructor<?> constructor = randomDimensionClass.getConstructor(ResourceLocation.class, MinecraftServer.class);
        Object randomDimension;
        // The scoped plan is visible to the Infinity mixin only during this constructor call.
        try (InfinityBiomeGenerationRules.Scope ignored = InfinityBiomeGenerationRules.activate(biomePlan)) {
            randomDimension = constructor.newInstance(id, server);
        }
        Field typeField = randomDimensionClass.getField("type");
        Object type = typeField.get(randomDimension);
        if (type == null) {
            return new DimensionProfile(true, 0, 0, true, randomDimension);
        }
        Field dataField = type.getClass().getField("data");
        CompoundTag data = (CompoundTag) dataField.get(type);
        return new DimensionProfile(
                data.getBoolean("has_ceiling"),
                data.getInt("min_y"),
                data.getInt("height"),
                true,
                randomDimension
        );
    }

    /** Copies every non-generator rule from the live Overworld and only retains Infinity's terrain files. */
    private static DimensionProfile normalizeGeneratedDimensionType(
            MinecraftServer server,
            ResourceLocation id,
            DimensionProfile profile,
            boolean fixedNoon
    ) {
        int targetMinY = server.overworld().getMinBuildHeight();
        int targetHeight = server.overworld().getHeight();

        Path dimensionTypePath = generatedPackRoot(server, id)
                .resolve("data")
                .resolve(INFINITY_MOD_ID)
                .resolve("dimension_type")
                .resolve(id.getPath() + ".json");
        try {
            JsonObject overworldType = DimensionType.DIRECT_CODEC.encodeStart(
                    server.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                    server.overworld().dimensionType()
            ).getOrThrow().getAsJsonObject();
            applyGeneratedPlanetTimeRule(overworldType, fixedNoon);
            Files.writeString(dimensionTypePath, JSON.toJson(overworldType), StandardCharsets.UTF_8);
            normalizeGeneratedNoiseSettings(server, id, targetMinY, targetHeight);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not normalize generated dimension type " + id, exception);
        }

        Object definition = profile.generatedDefinition();
        if (definition != null) {
            try {
                Field minYField = definition.getClass().getField("min_y");
                Field heightField = definition.getClass().getField("height");
                Field typeField = definition.getClass().getField("type");
                Object type = typeField.get(definition);
                Field dataField = type.getClass().getField("data");
                CompoundTag typeData = (CompoundTag) dataField.get(type);
                Tag encodedOverworldType = DimensionType.DIRECT_CODEC.encodeStart(
                        server.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                        server.overworld().dimensionType()
                ).getOrThrow();
                if (!(encodedOverworldType instanceof CompoundTag overworldTypeData)) {
                    throw new IllegalStateException("Encoded Overworld dimension type was not a compound tag");
                }
                minYField.setInt(definition, targetMinY);
                heightField.setInt(definition, targetHeight);
                // Remove Infinity's End/Nether filters, time and environment flags before applying Overworld rules.
                new ArrayList<>(typeData.getAllKeys()).forEach(typeData::remove);
                typeData.merge(overworldTypeData);
                applyGeneratedPlanetTimeRule(typeData, fixedNoon);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                throw new IllegalStateException("Could not normalize in-memory dimension type " + id, exception);
            }
        }
        return new DimensionProfile(
                server.overworld().dimensionType().hasCeiling(),
                targetMinY,
                targetHeight,
                profile.previewGenerated(),
                definition
        );
    }

    private static void applyGeneratedPlanetTimeRule(JsonObject typeData, boolean fixedNoon) {
        if (fixedNoon) {
            // Ring-world surface dimensions stay at local noon; galaxy-space dimensions keep their own midnight type.
            typeData.addProperty("fixed_time", RingWorldNoonTime.NOON_DAY_TIME);
        } else {
            // Ordinary generated planet dimensions must retain a full day-night cycle.
            typeData.remove("fixed_time");
        }
    }

    private static void applyGeneratedPlanetTimeRule(CompoundTag typeData, boolean fixedNoon) {
        if (fixedNoon) {
            // Match the persisted dimension_type JSON used after restart.
            typeData.putLong("fixed_time", RingWorldNoonTime.NOON_DAY_TIME);
        } else {
            // Match the persisted dimension_type JSON used after restart.
            typeData.remove("fixed_time");
        }
    }

    /** Keeps chunk storage aligned with the DimensionType used by Sable's block-entity loader. */
    private static void normalizeGeneratedNoiseSettings(
            MinecraftServer server,
            ResourceLocation id,
            int targetMinY,
            int targetHeight
    ) throws IOException {
        Path noiseSettingsRoot = generatedPackRoot(server, id)
                .resolve("data")
                .resolve(INFINITY_MOD_ID)
                .resolve("worldgen")
                .resolve("noise_settings");
        if (!Files.isDirectory(noiseSettingsRoot)) {
            return;
        }
        try (var files = Files.walk(noiseSettingsRoot)) {
            for (Path settingsPath : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                JsonObject settings = JsonParser.parseString(
                        Files.readString(settingsPath, StandardCharsets.UTF_8)
                ).getAsJsonObject();
                if (!settings.has("noise") || !settings.get("noise").isJsonObject()) {
                    continue;
                }
                JsonObject noise = settings.getAsJsonObject("noise");
                noise.addProperty("min_y", targetMinY);
                noise.addProperty("height", targetHeight);
                Files.writeString(settingsPath, JSON.toJson(settings), StandardCharsets.UTF_8);
            }
        }
    }

    /** Renames opaque Infinity biome IDs before the generated registries are read from disk. */
    private static void nameGeneratedBiomes(
            MinecraftServer server,
            ResourceLocation id,
            long dimensionSeed,
            InfinityBiomeGenerationRules.GenerationPlan biomePlan
    ) {
        try {
            for (InfiniteBiomeNaming.BiomeRename rename : InfiniteBiomeNaming.renameGeneratedBiomes(
                    generatedPackRoot(server, id),
                    mix64(dimensionSeed ^ 0x53F89D42C7A61B0EL),
                    biomePlan
            )) {
                LOGGER.info(
                        "[DEEPSPACE-INFINITE] phase=BIOME_NAMED dimension={} biome=infinity:{} new=infinity:{} terrain={}",
                        id,
                        rename.oldPath(),
                        rename.newPath(),
                        rename.terrain()
                );
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not name generated biomes for " + id, exception);
        }
    }

    /**
     * 把无限生成星球的天空颜色重写为传入的大气颜色：大气星球直接使用随机大气色，
     * 无大气星球使用黑空。地表雾气统一清除，避免 Infinity 自带的有色地表雾叠加在星球画面上。
     */
    private static int normalizeGeneratedBiomeVisuals(
            MinecraftServer server,
            ResourceLocation id,
            int atmosphereColor
    ) {
        Path biomesDir = generatedPackRoot(server, id)
                .resolve("data")
                .resolve(INFINITY_MOD_ID)
                .resolve("worldgen")
                .resolve("biome");
        if (!Files.isDirectory(biomesDir)) {
            return AIRLESS_SKY_COLOR;
        }

        int skyColor = atmosphereColor;
        int fogColor = AIRLESS_SKY_COLOR;

        try (var files = Files.list(biomesDir)) {
            for (Path biomePath : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                JsonObject biome = JsonParser.parseString(
                        Files.readString(biomePath, StandardCharsets.UTF_8)
                ).getAsJsonObject();
                biome.addProperty("sky_color", skyColor);
                biome.addProperty("fog_color", fogColor);
                biome.addProperty("water_fog_color", fogColor);
                Files.writeString(biomePath, JSON.toJson(biome), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not normalize generated biome visuals for " + id, exception);
        }
        LOGGER.info(
                "[DEEPSPACE-INFINITE] phase=BIOME_SKY_NORMALIZED dimension={} atmosphere={} skyColor={} fogColor={}",
                id,
                skyColor != AIRLESS_SKY_COLOR,
                skyColor,
                fogColor
        );
        return atmosphereColor;
    }

    /** Reads scoring facts only; it never removes, replaces, or selects generated biomes. */
    private static Planet.ParadiseProfile inspectParadiseProfile(
            MinecraftServer server,
            ResourceLocation id,
            InfinityBiomeGenerationRules.GenerationPlan plan,
            boolean hasAtmosphere,
            int atmosphereColor
    ) {
        boolean particles = false;
        boolean hostileMobs = false;
        Path packRoot = generatedPackRoot(server, id);
        Path biomesDir = packRoot.resolve("data").resolve(INFINITY_MOD_ID).resolve("worldgen").resolve("biome");
        try {
            if (Files.isDirectory(biomesDir)) {
                try (var files = Files.list(biomesDir)) {
                    for (Path biomePath : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                        JsonObject biome = JsonParser.parseString(Files.readString(biomePath, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                        JsonObject effects = biome.has("effects") && biome.get("effects").isJsonObject()
                                ? biome.getAsJsonObject("effects") : new JsonObject();
                        particles |= effects.has("particle");
                        hostileMobs |= hasHostileSpawner(biome.get("spawners"));
                    }
                }
            }
            // Surface likeness is judged later from globe colors, not from copied Overworld blocks or biome tints.
            boolean blueSky = hasAtmosphere && ParadiseRating.isBlue(atmosphereColor);
            return new Planet.ParadiseProfile(
                    false,
                    particles,
                    hostileMobs,
                    false,
                    false,
                    false,
                    false,
                    hasAtmosphere,
                    blueSky
            );
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not inspect Paradise Probe facts for " + id, exception);
        }
    }

    /** All four authored primary-ring habitats use the known hospitable Overworld baseline. */
    private static Planet.ParadiseProfile primaryRingParadiseProfile() {
        return new Planet.ParadiseProfile(
                true,
                false,
                true,
                true,
                true,
                true,
                true,
                true,
                true
        );
    }

    private static boolean hasHostileSpawner(JsonElement spawners) {
        if (spawners == null || !spawners.isJsonObject()) {
            return false;
        }
        JsonObject object = spawners.getAsJsonObject();
        return object.has("monster")
                && object.get("monster").isJsonArray()
                && !object.getAsJsonArray("monster").isEmpty();
    }

    private static boolean isPreferredGrassColor(int rgb) {
        return ParadiseRating.isPreferredGrassColor(rgb);
    }

    private static boolean fileContains(Path path, String needle) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(needle);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect " + path, exception);
        }
    }

    private static boolean isOceanBiomeName(String biome) {
        String normalized = biome.toLowerCase(Locale.ROOT);
        return normalized.contains("ocean") || normalized.contains("river");
    }

    /** 随机生成一个大气颜色；近白色大气沿用主世界浅蓝天空。 */
    private static int randomAtmosphereColor(Random random) {
        int color = random.nextInt(0x1000000);
        return isNearWhite(color) ? OVERWORLD_SKY_COLOR : color;
    }

    private static boolean isNearWhite(int rgb) {
        int red = rgb >>> 16 & 0xFF;
        int green = rgb >>> 8 & 0xFF;
        int blue = rgb & 0xFF;
        return red >= 200 && green >= 200 && blue >= 200;
    }

    /** Removes only Infinity's floating cube/polyhedron features from DeepSpace-managed planet packs. */
    private static void removeFixedShapeDecorations(MinecraftServer server, ResourceLocation id) {
        Path worldgenRoot = generatedPackRoot(server, id)
                .resolve("data")
                .resolve(INFINITY_MOD_ID)
                .resolve("worldgen");
        Path placedFeatures = worldgenRoot.resolve("placed_feature");
        Path biomes = worldgenRoot.resolve("biome");
        if (!Files.isDirectory(placedFeatures) || !Files.isDirectory(biomes)) {
            return;
        }

        Set<String> blockedFeatures = new HashSet<>();
        try (var files = Files.list(placedFeatures)) {
            for (Path featurePath : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                JsonObject feature = JsonParser.parseString(
                        Files.readString(featurePath, StandardCharsets.UTF_8)
                ).getAsJsonObject();
                String type = feature.has("type") ? feature.get("type").getAsString() : "";
                if (InfiniteGalaxyLayout.isFixedShapeFeatureType(type)) {
                    String fileName = featurePath.getFileName().toString();
                    blockedFeatures.add("infinity:" + fileName.substring(0, fileName.length() - 5));
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not inspect fixed shape decorations for " + id, exception);
        }
        if (blockedFeatures.isEmpty()) {
            return;
        }

        try (var files = Files.list(biomes)) {
            for (Path biomePath : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                JsonObject biome = JsonParser.parseString(
                        Files.readString(biomePath, StandardCharsets.UTF_8)
                ).getAsJsonObject();
                if (removeFeatureReferences(biome.get("features"), blockedFeatures)) {
                    Files.writeString(biomePath, JSON.toJson(biome), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not remove fixed shape decorations for " + id, exception);
        }
        LOGGER.debug("Disabled {} fixed shape decorations in {}", blockedFeatures.size(), id);
    }

    static boolean removeFeatureReferences(JsonElement element, Set<String> blockedFeatures) {
        if (element == null || !element.isJsonArray()) {
            return false;
        }
        boolean changed = false;
        JsonArray array = element.getAsJsonArray();
        for (int index = array.size() - 1; index >= 0; index--) {
            JsonElement child = array.get(index);
            if (child.isJsonPrimitive()
                    && child.getAsJsonPrimitive().isString()
                    && blockedFeatures.contains(child.getAsString())) {
                array.remove(index);
                changed = true;
            } else if (child.isJsonArray()) {
                changed |= removeFeatureReferences(child, blockedFeatures);
            }
        }
        return changed;
    }

    /** Removes every generated spawn entry whose entity class owns a server boss bar. */
    private static void removeBossSpawns(MinecraftServer server, ResourceLocation id) {
        Path biomes = generatedPackRoot(server, id)
                .resolve("data")
                .resolve(INFINITY_MOD_ID)
                .resolve("worldgen")
                .resolve("biome");
        if (!Files.isDirectory(biomes)) {
            return;
        }
        try (var files = Files.list(biomes)) {
            for (Path biomePath : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                JsonObject biome = JsonParser.parseString(
                        Files.readString(biomePath, StandardCharsets.UTF_8)
                ).getAsJsonObject();
                JsonObject spawners = biome.has("spawners") && biome.get("spawners").isJsonObject()
                        ? biome.getAsJsonObject("spawners")
                        : null;
                if (spawners != null && removeBossSpawnEntries(server, spawners)) {
                    removeBossSpawnCosts(server, biome);
                    Files.writeString(biomePath, JSON.toJson(biome), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not remove boss spawns for " + id, exception);
        }
    }

    private static boolean removeBossSpawnEntries(MinecraftServer server, JsonElement element) {
        boolean changed = false;
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = array.size() - 1; index >= 0; index--) {
                JsonElement child = array.get(index);
                if (child.isJsonObject()
                        && child.getAsJsonObject().has("type")
                        && isGeneratedBossEntity(server, child.getAsJsonObject().get("type").getAsString())) {
                    array.remove(index);
                    changed = true;
                } else {
                    changed |= removeBossSpawnEntries(server, child);
                }
            }
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> child : element.getAsJsonObject().entrySet()) {
                changed |= removeBossSpawnEntries(server, child.getValue());
            }
        }
        return changed;
    }

    /** Instantiates a spawn-table candidate once to inspect its class before the dimension is registered. */
    private static boolean isGeneratedBossEntity(MinecraftServer server, String entityId) {
        if (VANILLA_BOSS_ENTITY_IDS.contains(entityId)) {
            return true;
        }
        ResourceLocation id = ResourceLocation.tryParse(entityId);
        return id != null && GENERATED_BOSS_ENTITY_TYPES.computeIfAbsent(
                id,
                ignored -> inspectGeneratedEntityType(server, id)
        );
    }

    private static boolean inspectGeneratedEntityType(MinecraftServer server, ResourceLocation id) {
        EntityType<?> type = id == null
                ? null
                : server.registryAccess().registryOrThrow(Registries.ENTITY_TYPE).get(id);
        if (type == null) {
            return false;
        }
        Entity candidate = type.create(server.overworld());
        return candidate != null && GENERATED_BOSS_CLASSES.computeIfAbsent(
                candidate.getClass(),
                InfiniteDimensionsIntegration::classOwnsBossBar
        );
    }

    private static void removeBossSpawnCosts(MinecraftServer server, JsonObject biome) {
        if (!biome.has("spawn_costs") || !biome.get("spawn_costs").isJsonObject()) {
            return;
        }
        JsonObject costs = biome.getAsJsonObject("spawn_costs");
        for (String entityId : Set.copyOf(costs.keySet())) {
            if (isGeneratedBossEntity(server, entityId)) {
                costs.remove(entityId);
            }
        }
    }

    private static boolean classOwnsBossBar(Class<?> entityClass) {
        if (EnderDragon.class.isAssignableFrom(entityClass) || WitherBoss.class.isAssignableFrom(entityClass)) {
            return true;
        }
        for (Class<?> type = entityClass; type != null && Entity.class.isAssignableFrom(type); type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && BossEvent.class.isAssignableFrom(field.getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Path generatedPackRoot(MinecraftServer server, ResourceLocation id) {
        validateGeneratedDimensionId(id);
        Path datapacksRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize();
        Path packRoot = datapacksRoot.resolve(id.getPath()).normalize();
        if (!datapacksRoot.equals(packRoot.getParent())) {
            throw new IllegalArgumentException("Generated dimension pack escaped the datapacks directory: " + id);
        }
        return packRoot;
    }

    /** Removes only an unregistered candidate pack created by the reflective preview above. */
    private static void deleteRejectedDimensionPack(MinecraftServer server, ResourceLocation id) {
        validateGeneratedDimensionId(id);
        Path datapacksRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize();
        Path rejectedPack = datapacksRoot.resolve(id.getPath()).normalize();
        if (!datapacksRoot.equals(rejectedPack.getParent())) {
            throw new IllegalArgumentException("Rejected dimension pack escaped the datapacks directory: " + id);
        }
        if (!Files.exists(rejectedPack)) {
            return;
        }
        deleteTree(rejectedPack, "dimension pack");
        LOGGER.debug("Deleted rejected Infinite Dimensions candidate pack {}", rejectedPack);
    }

    /** Removes the chunk directory only when its generated ID has been rejected by this integration. */
    private static void deleteRejectedDimensionStorage(MinecraftServer server, ResourceLocation id) {
        validateGeneratedDimensionId(id);
        Path worldRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize().getParent();
        if (worldRoot == null) {
            throw new IllegalStateException("World root is unavailable while deleting " + id);
        }
        Path dimensionsRoot = worldRoot.resolve("dimensions").normalize();
        Path rejectedStorage = dimensionsRoot.resolve(id.getNamespace()).resolve(id.getPath()).normalize();
        if (!rejectedStorage.startsWith(dimensionsRoot)) {
            throw new IllegalArgumentException("Rejected dimension storage escaped the dimensions directory: " + id);
        }
        if (Files.exists(rejectedStorage)) {
            deleteTree(rejectedStorage, "dimension storage");
            LOGGER.info("Deleted rejected Infinite Dimensions storage {}", rejectedStorage);
        }
    }

    /** Commits rejected-candidate cleanup only after every requested replacement planet exists. */
    private static void cleanupRejectedCandidates(
            MinecraftServer server,
            Map<ResourceLocation, RejectedCandidate> rejectedCandidates
    ) {
        for (Map.Entry<ResourceLocation, RejectedCandidate> entry : rejectedCandidates.entrySet()) {
            deleteRejectedDimensionPack(server, entry.getKey());
            if (entry.getValue().previewGenerated()) {
                deleteRejectedDimensionStorage(server, entry.getKey());
            } else {
                PENDING_REJECTED_LEVEL_STORAGE.add(entry.getKey());
            }
        }
        if (!rejectedCandidates.isEmpty()) {
            LOGGER.info(
                    "[DEEPSPACE-INFINITE] phase=REPLACEMENT_SET_COMMITTED rejectedCandidates={}",
                    rejectedCandidates.size()
            );
        }
    }

    private static void validateGeneratedDimensionId(ResourceLocation id) {
        if (!INFINITY_MOD_ID.equals(id.getNamespace()) || !id.getPath().matches("generated_[0-9]+")) {
            throw new IllegalArgumentException("Refusing to delete an unexpected dimension: " + id);
        }
    }

    private static void deleteTree(Path root, String description) {
        try {
            List<Path> paths;
            try (var walk = Files.walk(root)) {
                paths = walk.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not delete rejected " + description + " " + root, exception);
        }
    }

    private static void addInfinityDimension(MinecraftServer server, ResourceLocation id, Object generatedDefinition)
            throws ReflectiveOperationException {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        if (server.getLevel(key) != null) {
            return;
        }
        if (generatedDefinition == null) {
            throw new IllegalStateException("Generated definition is unavailable while adding " + id);
        }

        // Reuse the normalized preview; PortalCreator would regenerate and overwrite its min_y policy.
        Class<?> definitionClass = generatedDefinition.getClass();
        Class<?> dimensionGrabber = InfiniteDimensionsApi.loadClass("util.loading.DimensionGrabber");
        Method readDimension = dimensionGrabber.getMethod("readDimensionFromDisk", definitionClass);
        LevelStem stem = (LevelStem) readDimension.invoke(null, generatedDefinition);
        Method addWorld = server.getClass().getMethod("infinity$addWorld", ResourceKey.class, LevelStem.class);
        addWorld.invoke(server, key, stem);

        Class<?> portalCreator = InfiniteDimensionsApi.loadClass("util.teleport.PortalCreator");
        Method sendNewWorld = portalCreator.getMethod(
                "sendNewWorld",
                ServerPlayer.class,
                ResourceLocation.class,
                definitionClass
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendNewWorld.invoke(null, player, id, generatedDefinition);
        }
    }

    /** Keeps Deep Space planet transitions from invoking Infinity's full client resource-pack reload. */
    private static void disableInfinityShader(MinecraftServer server, ResourceLocation id, ServerLevel level)
            throws ReflectiveOperationException {
        Class<?> optionsClass = InfiniteDimensionsApi.loadClass("options.InfinityOptions");
        Method access = optionsClass.getMethod("access", Level.class);
        Object options = access.invoke(null, level);
        Field dataField = optionsClass.getField("data");
        CompoundTag data = (CompoundTag) dataField.get(options);
        if (data == null) {
            throw new IllegalStateException("Infinite Dimensions options are unavailable for " + id);
        }
        data.put("shader", new CompoundTag());

        // Persist the same policy so a later server restart cannot restore the generated shader.
        Path optionsPath = server.getWorldPath(LevelResource.DATAPACK_DIR)
                .resolve(id.getPath())
                .resolve("data")
                .resolve(INFINITY_MOD_ID)
                .resolve("options.json")
                .toAbsolutePath()
                .normalize();
        try {
            Files.writeString(optionsPath, data.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not disable the generated shader for " + id, exception);
        }
    }

    /** Rewrites Infinity's generated effect before level creation, so no runtime rejection is needed. */
    private static void sanitizeGeneratedDimensionOptions(MinecraftServer server, ResourceLocation id) {
        Path optionsPath = generatedPackRoot(server, id)
                .resolve("data")
                .resolve(INFINITY_MOD_ID)
                .resolve("options.json");
        try {
            CompoundTag options = TagParser.parseTag(
                    Files.readString(optionsPath, StandardCharsets.UTF_8)
            );
            // Disable the shader on disk so deferred world addition needs no second initialization pass.
            options.put("shader", new CompoundTag());
            // 无限星球地表不再保留 Infinity 生成的彩色滤镜/药水效果。
            options.remove("deepspace_effect");
            options.remove("effect");
            // 天体全部由 Deep Space 按星系物理位置渲染，禁用 Infinity 的随机太阳、月球和星点。
            options.put("moons", new ListTag());
            options.putFloat("solar_size", 0.0F);
            options.putInt("num_stars", 0);
            options.putFloat("star_brightness_day", 0.0F);
            options.putFloat("star_brightness_night", 0.0F);
            Files.writeString(optionsPath, options.toString(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sanitize generated dimension options for " + id, exception);
        }
    }

    private static void registerGalaxyDimension(MinecraftServer server, ResourceKey<Level> target)
            throws ReflectiveOperationException {
        if (server.getLevel(target) != null) {
            return;
        }
        Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        LevelStem spaceStem = stems.get(ResourceKey.create(Registries.LEVEL_STEM, Deepspace.path("space")));
        if (spaceStem == null) {
            throw new IllegalStateException("Deep Space level stem is unavailable");
        }
        Method addWorld = server.getClass().getMethod("infinity$addWorld", ResourceKey.class, LevelStem.class);
        addWorld.invoke(server, target, spaceStem);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private record GeneratedSystem(int chainIndex, Galaxy galaxy, List<Planet> planets, int randomPlanetCount) {
    }

    private record GeneratedPlanetWorld(
            String name,
            ResourceKey<Level> dimension,
            List<Planet.PlanetDecoration> decorations,
            int atmosphereEntryHeight,
            int atmosphereExitHeight,
            Planet.ParadiseProfile paradiseProfile
    ) {
    }

    /** Couples each prescribed biome pair to its distinct authored ring panorama. */
    private record RingOriginBiomes(
            String name,
            String firstBiome,
            String secondBiome,
            ResourceLocation texture
    ) {
    }


    private record DimensionProfile(
            boolean hasCeiling,
            int minY,
            int height,
            boolean previewGenerated,
            Object generatedDefinition
    ) {
    }

    private record RejectedCandidate(boolean previewGenerated) {
    }
}
