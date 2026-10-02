package world.landfall.deepspace.planet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.Config;
import world.landfall.deepspace.integration.InfiniteStarSystemLayout;
import world.landfall.deepspace.network.PlanetSyncPacket;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing planets and their configurations.
 * Handles loading from JSON files and synchronization between server and client.
 */
@EventBusSubscriber(modid = Deepspace.MODID)
public class PlanetRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PLANETS_CONFIG_FILE = "planets.json";
    
    private static final Map<String, Planet> planets = new ConcurrentHashMap<>();
    private static volatile Sun sun;
    private static final Map<ResourceKey<Level>, Planet> planetsByDimension = new ConcurrentHashMap<>();
    private static volatile Map<ResourceKey<Level>, List<Planet>> planetsByGalaxy = Map.of();
    private static final Map<String, Galaxy> galaxies = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Galaxy> galaxiesByDimension = new ConcurrentHashMap<>();
    private static volatile Map<ResourceLocation, Planet> datapackPlanets = Map.of();
    private static volatile Set<String> datapackPlanetIds = Set.of();
    private static volatile Galaxy runtimePrimaryGalaxy;
    private static volatile List<Planet> runtimePrimaryBodies = List.of();
    private static final Object registryLock = new Object();
    private static volatile Path configPath;
    
    /**
     * Data class for JSON serialization of planet configurations.
     */

    public static class SunConfig {
        public double[] boundingBoxMin;
        public double[] boundingBoxMax;
        public double hurtRadius;
        public SunConfig(Sun sun) {
            var boundingBoxMin = sun.getBoundingBoxMin();
            this.boundingBoxMin = new double[]{
                    boundingBoxMin.x,
                    boundingBoxMin.y,
                    boundingBoxMin.z
            };
            var boundingBoxMax = sun.getBoundingBoxMax();
            this.boundingBoxMax = new double[]{
                    boundingBoxMax.x,
                    boundingBoxMax.y,
                    boundingBoxMax.z
            };
            this.hurtRadius = sun.getHurtRadius();
        }
    }
    public static class PlanetConfig {
        public String id;
        public String name;
        public String dimension;
        public double[] boundingBoxMin;
        public double[] boundingBoxMax;
        public Collection<Planet.PlanetDecoration> decorations;
        public String description = "";
        public double[] physicalMin;
        public double[] physicalMax;
        public String texture;
        public List<String> textures;

        public PlanetConfig() {}
        
        public PlanetConfig(Planet planet) {
            this.id = planet.getId();
            this.name = planet.getName();
            this.dimension = planet.getDimension().location().toString();
            this.boundingBoxMin = new double[]{
                planet.getBoundingBoxMin().x,
                planet.getBoundingBoxMin().y,
                planet.getBoundingBoxMin().z
            };
            this.boundingBoxMax = new double[]{
                planet.getBoundingBoxMax().x,
                planet.getBoundingBoxMax().y,
                planet.getBoundingBoxMax().z
            };
            if (planet.getDecorations().isPresent())
                this.decorations = planet.getDecorations().get();
            else
                this.decorations = List.of();
            this.description = planet.getDescription();
            this.physicalMin = new double[]{planet.getPhysicalMin().x, planet.getPhysicalMin().y};
            this.physicalMax = new double[]{planet.getPhysicalMax().x, planet.getPhysicalMax().y};
            this.texture = planet.getTextures().size() == 1
                    ? planet.getTextures().getFirst().toString()
                    : null;
            this.textures = planet.getTextures().size() == 6
                    ? planet.getTextures().stream().map(ResourceLocation::toString).toList()
                    : null;
        }
    }
    
    /**
     * Container class for the JSON configuration file.
     */
    public static class PlanetsConfig {
        public List<PlanetConfig> planets = new ArrayList<>();
        public SunConfig sun;
        public PlanetsConfig() {}
        
        public PlanetsConfig(Collection<Planet> planets, Sun sun) {
            this.planets = planets.stream()
                .map(PlanetConfig::new)
                .toList();
            this.sun = new SunConfig(sun);
        }
    }
    
    /**
     * Initializes the planet registry with the given config directory.
     *
     * @param configDir The configuration directory path
     */
    public static void initialize(@NotNull Path configDir) {
        Objects.requireNonNull(configDir, "Config directory cannot be null");
        configPath = configDir.resolve(PLANETS_CONFIG_FILE);
        LOGGER.info("Planet registry initialized with config path: {}", configPath);
    }
    
    /**
     * Loads planets from the JSON configuration file.
     */
    public static void loadPlanets() {
        Path currentConfigPath = configPath;
        if (currentConfigPath == null) {
            LOGGER.error("Planet registry not initialized - cannot load planets");
            return;
        }
        
        synchronized (registryLock) {
            try {
                if (!Files.exists(currentConfigPath)) {
                    LOGGER.info("No planets configuration file found, creating default configuration");
                    createDefaultConfigurationUnsafe();
                    return;
                }
                
                String json = Files.readString(currentConfigPath);
                PlanetsConfig config = GSON.fromJson(json, PlanetsConfig.class);
                
                if (config == null || config.planets == null) {
                    LOGGER.warn("Invalid planets configuration file, creating default configuration");
                    createDefaultConfigurationUnsafe();
                    return;
                }
                
                planets.clear();
                planetsByDimension.clear();
                planetsByGalaxy = Map.of();
                
                for (PlanetConfig planetConfig : config.planets) {
                    try {
                        Planet planet = createPlanetFromConfig(planetConfig);
                        registerPlanetUnsafe(planet);
                        LOGGER.debug("Loaded planet: {}", planet.getId());
                    } catch (Exception e) {
                        LOGGER.error("Failed to load planet configuration: {}", planetConfig.id, e);
                    }
                }
                registerBuiltInGeneratedPlanetsUnsafe();
                registerDatapackPlanetsUnsafe();
                 
                LOGGER.info("Loaded {} planets from configuration", planets.size());
                try {
                    sun = createSunFromConfig(config.sun);
                    registerPrimaryGalaxyUnsafe();
                } catch (Exception e) {
                    LOGGER.error("Failed to load sun configuration: ", e);
                }
                applyRuntimePrimaryOverrideUnsafe();
            } catch (IOException e) {
                LOGGER.error("Failed to read planets configuration file", e);
                createDefaultConfigurationUnsafe();
            } catch (JsonSyntaxException e) {
                LOGGER.error("Invalid JSON in planets configuration file", e);
                createDefaultConfigurationUnsafe();
            }
        }
    }
    
    /**
     * Creates a default configuration with example planets. Must be called within registryLock.
     */
    private static void createDefaultConfigurationUnsafe() {
        planets.clear();
        planetsByDimension.clear();
        planetsByGalaxy = Map.of();
        
        // Add example planets
        try {
            // Overworld planet
            Planet overworld = new Planet(
                "overworld",
                "Overworld",
                Level.OVERWORLD,
                new Vec3(2000, 100, -100),
                new Vec3(2200, 300, 100),
                List.of(new Planet.PlanetDecoration(
                        Planet.PlanetDecoration.ATMOSPHERE,
                        1.1f,
                        0
                )),
                "The main world where players spawn",
                new Vec2(-1000, -1000),
                new Vec2(1000, 1000),
                // Cube face order: down, north, west, south, east, up.
                List.of(
                        Deepspace.path("textures/overworld_down.png"),
                        Deepspace.path("textures/overworld_north.png"),
                        Deepspace.path("textures/overworld_west.png"),
                        Deepspace.path("textures/overworld_south.png"),
                        Deepspace.path("textures/overworld_east.png"),
                        Deepspace.path("textures/overworld_up.png")
                )
            );
            registerPlanetUnsafe(overworld);
            
            // Nether planet
            Planet nether = new Planet(
                "sarrion",
                "Sarrion",
                ResourceKey.create(Registries.DIMENSION, Deepspace.path("sarrion")),
                new Vec3(-1000, 50, -1000),
                new Vec3(-750, 300, -750),
                List.of(
                        new Planet.PlanetDecoration(Planet.PlanetDecoration.ATMOSPHERE, 1.05f * 1.1f, Color.RED.getRGB()),
                        new Planet.PlanetDecoration(Planet.PlanetDecoration.RINGS, 1.0f, Color.RED.getRGB())
                ),
                "A hellish dimension filled with lava and dangerous creatures",
                new Vec2(-1000, -1000),
                new Vec2(1000, 1000),
                List.of()
            );
            registerPlanetUnsafe(nether);
            registerBuiltInGeneratedPlanetsUnsafe();
            sun = new Sun(
                    new Vec3(-200, 0, -200),
                    new Vec3(200, 400, 200),
                    500
            );
            registerPrimaryGalaxyUnsafe();
            registerDatapackPlanetsUnsafe();
            LOGGER.info("Created default planet configuration with {} planets", planets.size());
            
        } catch (Exception e) {
            LOGGER.error("Failed to create default planet configuration", e);
        }
    }

    /**
     * Keeps built-in data-driven planets available even when an older planets.json already exists.
     */
    private static void registerBuiltInGeneratedPlanetsUnsafe() {
        if (!planets.containsKey("aridia")) {
            var bounds = PlanetOrbitLayout.bounds(1650.0, 45.0, 200.0, 90.0);
            registerPlanetUnsafe(new Planet(
                    "aridia",
                    "Aridia",
                    ResourceKey.create(Registries.DIMENSION, Deepspace.path("aridia")),
                    bounds.min(),
                    bounds.max(),
                    List.of(new Planet.PlanetDecoration(
                            Planet.PlanetDecoration.ATMOSPHERE,
                            1.1f,
                            0xD8A84E
                    )),
                    "A dry planet composed entirely of desert biome",
                    new Vec2(-1000, -1000),
                    new Vec2(1000, 1000)
            ));
        }
        if (!planets.containsKey("pelagos")) {
            var bounds = PlanetOrbitLayout.bounds(2300.0, 90.0, 200.0, 110.0);
            registerPlanetUnsafe(new Planet(
                    "pelagos",
                    "Pelagos",
                    ResourceKey.create(Registries.DIMENSION, Deepspace.path("pelagos")),
                    bounds.min(),
                    bounds.max(),
                    List.of(new Planet.PlanetDecoration(
                            Planet.PlanetDecoration.ATMOSPHERE,
                            1.1f,
                            0x2E5AAC
                    )),
                    "An ocean planet composed entirely of deep ocean biome",
                    new Vec2(-1000, -1000),
                    new Vec2(1000, 1000)
            ));
        }
        if (!planets.containsKey("lithos")) {
            var bounds = PlanetOrbitLayout.bounds(4200.0, 180.0, 200.0, 120.0);
            registerPlanetUnsafe(new Planet(
                    "lithos",
                    "Lithos",
                    ResourceKey.create(Registries.DIMENSION, Deepspace.path("lithos")),
                    bounds.min(),
                    bounds.max(),
                    List.of(),
                    "An airless stone world with exceptionally dense ores",
                    new Vec2(-1000, -1000),
                    new Vec2(1000, 1000)
            ));
        }
        if (!planets.containsKey("termina")) {
            var bounds = PlanetOrbitLayout.bounds(6000.0, 270.0, 200.0, 480.0);
            registerPlanetUnsafe(new Planet(
                    "termina",
                    "Termina",
                    Level.END,
                    bounds.min(),
                    bounds.max(),
                    List.of(),
                    "The vanilla End with a central island and distant outer islands",
                    // Include the outer End islands beyond the vanilla empty ring.
                    new Vec2(-4096, -4096),
                    new Vec2(4096, 4096),
                    List.of(
                            Deepspace.path("textures/termina_down.png"),
                            Deepspace.path("textures/termina_north.png"),
                            Deepspace.path("textures/termina_west.png"),
                            Deepspace.path("textures/termina_south.png"),
                            Deepspace.path("textures/termina_east.png"),
                            Deepspace.path("textures/termina_up.png")
                    )
            ));
        }
        if (!planets.containsKey("cryosia")) {
            var bounds = PlanetOrbitLayout.bounds(7800.0, 315.0, 200.0, 60.0);
            registerPlanetUnsafe(new Planet(
                    "cryosia",
                    "Cryosia",
                    ResourceKey.create(Registries.DIMENSION, Deepspace.path("cryosia")),
                    bounds.min(),
                    bounds.max(),
                    List.of(new Planet.PlanetDecoration(
                            Planet.PlanetDecoration.ATMOSPHERE,
                            1.1f,
                            new Color(210, 235, 255).getRGB()
                    )),
                    "A half-scale world composed entirely of frozen peaks",
                    new Vec2(-1000, -1000),
                    new Vec2(1000, 1000)
            ));
        }
    }

    /**
     * Replaces additive data-pack definitions and rebuilds the effective registry.
     */
    static void replaceDatapackPlanets(@NotNull Map<ResourceLocation, Planet> loadedPlanets) {
        datapackPlanets = Map.copyOf(loadedPlanets);
        Set<String> ids = new HashSet<>();
        for (Planet planet : loadedPlanets.values()) {
            ids.add(planet.getId());
        }
        datapackPlanetIds = Set.copyOf(ids);
        if (configPath != null) {
            loadPlanets();
        }
    }

    /** Data-pack planets are authored worlds and must not use Infinite S-grade plant rules. */
    public static boolean isDatapackPlanet(@Nullable String planetId) {
        return planetId != null && datapackPlanetIds.contains(planetId);
    }

    private static void registerDatapackPlanetsUnsafe() {
        datapackPlanets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    registerPlanetUnsafe(entry.getValue());
                    LOGGER.debug("Loaded data-pack planet {} from {}", entry.getValue().getId(), entry.getKey());
                });
    }
    @NotNull
    private static Sun createSunFromConfig(@NotNull SunConfig config) {
        Objects.requireNonNull(config, "Sun config cannot be null");
        if (config.boundingBoxMin == null || config.boundingBoxMin.length != 3) {
            throw new IllegalArgumentException("Invalid bounding box minimum coordinates");
        }
        if (config.boundingBoxMax == null || config.boundingBoxMax.length != 3) {
            throw new IllegalArgumentException("Invalid bounding box maximum coordinates");
        }
        return new Sun(
                new Vec3(config.boundingBoxMin[0], config.boundingBoxMin[1], config.boundingBoxMin[2]),
                new Vec3(config.boundingBoxMax[0], config.boundingBoxMax[1], config.boundingBoxMax[2]),
                config.hurtRadius
        );
    }
    /**
     * Creates a Planet instance from a PlanetConfig.
     */
    @NotNull
    private static Planet createPlanetFromConfig(@NotNull PlanetConfig config) {
        Objects.requireNonNull(config, "Planet config cannot be null");
        
        if (config.id == null || config.id.trim().isEmpty()) {
            throw new IllegalArgumentException("Planet ID cannot be null or empty");
        }
        if (config.name == null || config.name.trim().isEmpty()) {
            throw new IllegalArgumentException("Planet name cannot be null or empty");
        }
        if (config.dimension == null || config.dimension.trim().isEmpty()) {
            throw new IllegalArgumentException("Planet dimension cannot be null or empty");
        }
        if (config.boundingBoxMin == null || config.boundingBoxMin.length != 3) {
            throw new IllegalArgumentException("Invalid bounding box minimum coordinates");
        }
        if (config.boundingBoxMax == null || config.boundingBoxMax.length != 3) {
            throw new IllegalArgumentException("Invalid bounding box maximum coordinates");
        }
        if (config.physicalMin == null || config.physicalMin.length != 2) {
            throw new IllegalArgumentException("Invalid physical minimum coordinates");
        }
        if (config.physicalMax == null || config.physicalMax.length != 2) {
            throw new IllegalArgumentException("Invalid physical maximum coordinates");
        }
        
        ResourceKey<Level> dimension = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.ResourceLocation.parse(config.dimension)
        );
        
        Vec3 boundingBoxMin = new Vec3(config.boundingBoxMin[0], config.boundingBoxMin[1], config.boundingBoxMin[2]);
        Vec3 boundingBoxMax = new Vec3(config.boundingBoxMax[0], config.boundingBoxMax[1], config.boundingBoxMax[2]);
        List<ResourceLocation> textures;
        if (config.textures != null && !config.textures.isEmpty()) {
            textures = config.textures.stream().map(ResourceLocation::parse).toList();
        } else if (config.texture != null && !config.texture.isBlank()) {
            textures = List.of(ResourceLocation.parse(config.texture));
        } else {
            textures = List.of();
        }

        return new Planet(
                config.id,
                config.name,
                dimension,
                boundingBoxMin,
                boundingBoxMax,
                config.decorations,
                config.description,
                new Vec2((float) config.physicalMin[0], (float) config.physicalMin[1]),
                new Vec2((float) config.physicalMax[0], (float) config.physicalMax[1]),
                textures
        );
    }
    
    /**
     * Registers a planet in the registry.
     *
     * @param planet The planet to register
     */
    public static void registerPlanet(@NotNull Planet planet) {
        Objects.requireNonNull(planet, "Planet cannot be null");
        
        synchronized (registryLock) {
            registerPlanetUnsafe(planet);
        }
    }

    /** Replaces one synchronized planet without rebuilding unrelated registry entries. */
    public static void replacePlanet(@NotNull Planet planet) {
        Objects.requireNonNull(planet, "Planet cannot be null");
        synchronized (registryLock) {
            Planet previous = planets.remove(planet.getId());
            if (previous != null && !previous.isWormhole()) {
                planetsByDimension.remove(previous.getDimension(), previous);
            }
            registerPlanetUnsafe(planet);
        }
    }
    
    /**
     * Registers a planet in the registry. Must be called within registryLock.
     *
     * @param planet The planet to register
     */
    private static void registerPlanetUnsafe(@NotNull Planet planet) {
        planets.put(planet.getId(), planet);
        // Wormholes point at galaxy dimensions and must not masquerade as planet surfaces.
        if (!planet.isWormhole()) {
            planetsByDimension.put(planet.getDimension(), planet);
        }
        rebuildPlanetsByGalaxyUnsafe();
        
        LOGGER.debug("Registered planet: {} for dimension: {}", planet.getId(), planet.getDimension().location());
    }
    
    /**
     * Unregisters a planet from the registry.
     *
     * @param planetId The ID of the planet to unregister
     * @return The unregistered planet, or null if not found
     */
    @Nullable
    public static Planet unregisterPlanet(@NotNull String planetId) {
        Objects.requireNonNull(planetId, "Planet ID cannot be null");
        
        synchronized (registryLock) {
            Planet planet = planets.remove(planetId);
            if (planet != null) {
                planetsByDimension.remove(planet.getDimension());
                rebuildPlanetsByGalaxyUnsafe();
                LOGGER.debug("Unregistered planet: {}", planetId);
            }
            return planet;
        }
    }
    
    /**
     * Gets a planet by its ID.
     *
     * @param planetId The planet ID
     * @return The planet, or null if not found
     */
    @Nullable
    public static Planet getPlanet(@NotNull String planetId) {
        Objects.requireNonNull(planetId, "Planet ID cannot be null");
        return planets.get(planetId);
    }

    /**
     * Gets the current sun.
     * @return The sun, or null if doesn't exist
     */
    @Nullable
    public static Sun getSun() {
        return sun;
    }
    /**
     * Sets the current sun
     * @param _sun The sun to set
     */
    public static boolean setSun(Sun _sun) {
        sun = _sun;
        synchronized (registryLock) {
            registerPrimaryGalaxyUnsafe();
        }
        return true;
    }

    private static void registerPrimaryGalaxyUnsafe() {
        // Never let config reloads shrink or move an active primary ring-world star.
        if (runtimePrimaryGalaxy != null) {
            sun = runtimePrimaryGalaxy.sun();
            registerGalaxyUnsafe(runtimePrimaryGalaxy);
            return;
        }
        if (sun == null) {
            return;
        }
        Vec3 center = sun.getCenter();
        double radius = StarIdentity.BASE_STAR_RADIUS;
        int primaryColor = sun.getColor() == 0xFFFFFF
                ? spectralColorForStage("G4")
                : sun.getColor();
        Sun primary = new Sun(
                center.subtract(radius, radius, radius),
                center.add(radius, radius, radius),
                sun.getHurtRadius(),
                "Sun",
                "G4",
                primaryColor
        );
        sun = primary;
        registerGalaxyUnsafe(new Galaxy(
                "landfall",
                "Landfall",
                ResourceKey.create(Registries.DIMENSION, Deepspace.path("space")),
                new Vec3(0.0, 200.0, 10_000.0),
                primary
        ));
    }

    /** Resolves the base spectral letter and returns the star tint shared with generated galaxies. */
    private static int spectralColorForStage(@Nullable String stage) {
        String normalized = stage == null ? "G" : stage.toUpperCase(Locale.ROOT);
        String baseClass = normalized.substring(0, 1);
        try {
            return InfiniteStarSystemLayout.SpectralClass.valueOf(baseClass).color();
        } catch (IllegalArgumentException ignored) {
            return InfiniteStarSystemLayout.SpectralClass.G.color();
        }
    }

    /** Registers a generated galaxy and makes it available to both gameplay and rendering. */
    public static void registerGalaxy(@NotNull Galaxy galaxy) {
        synchronized (registryLock) {
            registerGalaxyUnsafe(galaxy);
        }
    }

    /** Replaces every body hosted by the primary galaxy while preserving the override across data-pack reloads. */
    public static void replacePrimaryGalaxyBodies(@NotNull Galaxy galaxy, @NotNull Collection<Planet> bodies) {
        Objects.requireNonNull(galaxy, "Galaxy cannot be null");
        Objects.requireNonNull(bodies, "Planet bodies cannot be null");
        synchronized (registryLock) {
            runtimePrimaryGalaxy = galaxy;
            runtimePrimaryBodies = List.copyOf(bodies);
            applyRuntimePrimaryOverrideUnsafe();
        }
    }

    private static void applyRuntimePrimaryOverrideUnsafe() {
        Galaxy override = runtimePrimaryGalaxy;
        if (override == null) {
            return;
        }
        ResourceKey<Level> dimension = override.dimension();
        List<String> hostedIds = planets.values().stream()
                .filter(planet -> planet.getGalaxy().equals(dimension))
                .map(Planet::getId)
                .toList();
        hostedIds.forEach(id -> {
            Planet removed = planets.remove(id);
            if (removed != null && !removed.isWormhole()) {
                planetsByDimension.remove(removed.getDimension(), removed);
            }
        });
        rebuildPlanetsByGalaxyUnsafe();
        sun = override.sun();
        registerGalaxyUnsafe(override);
        runtimePrimaryBodies.forEach(PlanetRegistry::registerPlanetUnsafe);
    }

    private static void registerGalaxyUnsafe(@NotNull Galaxy galaxy) {
        galaxies.put(galaxy.id(), galaxy);
        galaxiesByDimension.put(galaxy.dimension(), galaxy);
    }

    @Nullable
    public static Galaxy getGalaxy(@NotNull String galaxyId) {
        return galaxies.get(galaxyId);
    }

    @Nullable
    public static Galaxy getGalaxyByDimension(@NotNull ResourceKey<Level> dimension) {
        return galaxiesByDimension.get(dimension);
    }

    @NotNull
    public static Collection<Galaxy> getAllGalaxies() {
        synchronized (registryLock) {
            return Collections.unmodifiableCollection(new ArrayList<>(galaxies.values()));
        }
    }

    @Nullable
    public static Sun getSunForGalaxy(@NotNull ResourceKey<Level> dimension) {
        Galaxy galaxy = getGalaxyByDimension(dimension);
        return galaxy == null ? null : galaxy.sun();
    }

    @Nullable
    public static Sun getSunForPlanet(@NotNull Planet planet) {
        Galaxy galaxy = getGalaxyByDimension(planet.getGalaxy());
        if (galaxy == null) {
            return null;
        }
        // The nearest star is the planet's host and therefore its day/night and solar-light reference.
        return galaxy.suns().stream()
                .min(Comparator.comparingDouble(candidate -> candidate.getCenter().distanceToSqr(planet.getCenter())))
                .orElse(galaxy.sun());
    }
    /**
     * Gets a planet by its dimension.
     *
     * @param dimension The dimension key
     * @return The planet, or null if not found
     */
    @Nullable
    public static Planet getPlanetByDimension(@NotNull ResourceKey<Level> dimension) {
        Objects.requireNonNull(dimension, "Dimension cannot be null");
        return planetsByDimension.get(dimension);
    }
    
    /**
     * Gets all registered planets.
     *
     * @return An unmodifiable collection of all planets
     */
    @NotNull
    public static Collection<Planet> getAllPlanets() {
        synchronized (registryLock) {
            return Collections.unmodifiableCollection(new ArrayList<>(planets.values()));
        }
    }

    /** Returns every planet and wormhole hosted by one galaxy. */
    @NotNull
    public static List<Planet> getPlanetsForGalaxy(@NotNull ResourceKey<Level> galaxy) {
        Objects.requireNonNull(galaxy, "Galaxy cannot be null");
        return planetsByGalaxy.getOrDefault(galaxy, List.of());
    }

    /** Finds the destination-side portal that points back to the galaxy being left. */
    @Nullable
    public static Planet getPairedWormhole(
            @NotNull ResourceKey<Level> destinationGalaxy,
            @NotNull ResourceKey<Level> sourceGalaxy
    ) {
        return getPlanetsForGalaxy(destinationGalaxy).stream()
                .filter(Planet::isWormhole)
                .filter(candidate -> candidate.getDimension().equals(sourceGalaxy))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * Finds planets that contain the given position within their bounding boxes.
     *
     * @param position The position to check
     * @return A list of planets containing the position
     */
    @NotNull
    public static List<Planet> getPlanetsAtPosition(@NotNull Vec3 position) {
        Objects.requireNonNull(position, "Position cannot be null");
        
        synchronized (registryLock) {
            return planets.values().stream()
                .filter(planet -> planet.isWithinBounds(position))
                .toList();
        }
    }
    
    /**
     * Checks if a planet with the given ID exists.
     *
     * @param planetId The planet ID to check
     * @return true if the planet exists, false otherwise
     */
    public static boolean hasPlanet(@NotNull String planetId) {
        Objects.requireNonNull(planetId, "Planet ID cannot be null");
        return planets.containsKey(planetId);
    }
    
    /**
     * Gets the number of registered planets.
     *
     * @return The number of planets
     */
    public static int getPlanetCount() {
        return planets.size(); // ConcurrentHashMap.size() is thread-safe
    }
    
    /**
     * Clears all registered planets.
     */
    public static void clear() {
        synchronized (registryLock) {
            planets.clear();
            planetsByDimension.clear();
            planetsByGalaxy = Map.of();
            galaxies.clear();
            galaxiesByDimension.clear();
            sun = null;
            LOGGER.debug("Cleared all planets from registry");
        }
    }

    /** Publishes an immutable galaxy index so server ticks never rescan the global planet registry. */
    private static void rebuildPlanetsByGalaxyUnsafe() {
        Map<ResourceKey<Level>, List<Planet>> grouped = new HashMap<>();
        for (Planet planet : planets.values()) {
            grouped.computeIfAbsent(planet.getGalaxy(), ignored -> new ArrayList<>()).add(planet);
        }
        Map<ResourceKey<Level>, List<Planet>> immutable = new HashMap<>();
        grouped.forEach((galaxy, bodies) -> immutable.put(galaxy, List.copyOf(bodies)));
        planetsByGalaxy = Map.copyOf(immutable);
    }

    public static void init() {
        // Initialize with server config directory
        Path serverConfigDir = Paths.get("config");
        initialize(serverConfigDir);
        loadPlanets();
        
        LOGGER.info("Planet registry loaded on server start");
    }

    /**
     * Samples missing planet textures after all datapack dimensions have been created.
     */
    public static void refreshGeneratedTextures(@NotNull MinecraftServer server) {
        refreshGeneratedTextures(server, getAllPlanets());
    }

    /**
     * Refreshes generated textures for a specific set of planets and re-synchronizes them.
     */
    public static void refreshGeneratedTextures(
            @NotNull MinecraftServer server,
            @NotNull Collection<Planet> planets
    ) {
        refreshGeneratedTextures(server, planets, true);
    }

    /**
     * Refreshes generated textures with a caller-selected surface sampling cost.
     */
    public static void refreshGeneratedTextures(
            @NotNull MinecraftServer server,
            @NotNull Collection<Planet> planets,
            boolean simulateChunks
    ) {
        float fragmentation = Config.GENERATED_PLANET_TEXTURE_FRAGMENTATION.get().floatValue();
        // The completion callback publishes palettes without holding the server-start event open.
        PlanetTextureGenerator.sampleMissingPalettes(server, planets, fragmentation, simulateChunks)
                .whenComplete((unused, error) -> {
                    if (server.isStopped()) {
                        return;
                    }
                    server.execute(() -> {
                        if (error != null) {
                            LOGGER.error("Procedural planet texture sampling ended unexpectedly", error);
                        }
                        PlanetDiscoveries.applyPersistedDiscoverers(server);
                        syncToAllPlayers();
                    });
                });
    }

    /** Prioritizes missing maps for a galaxy without regenerating cached planets. */
    public static void prewarmGeneratedTextures(
            @NotNull MinecraftServer server,
            @NotNull Collection<Planet> planets
    ) {
        float fragmentation = Config.GENERATED_PLANET_TEXTURE_FRAGMENTATION.get().floatValue();
        PlanetTextureGenerator.prewarmSurfaceMaps(server, planets, fragmentation)
                .whenComplete((unused, error) -> {
                    if (server.isStopped()) {
                        return;
                    }
                    server.execute(() -> {
                        if (error != null) {
                            LOGGER.error("Planet surface-map prewarming ended unexpectedly", error);
                        }
                        // Completed maps publish their own incremental updates from the generator.
                    });
                });
    }

    /** Prewarms textures from the relay entrance position. */
    public static void prewarmGeneratedTextures(
            @NotNull MinecraftServer server,
            @NotNull Collection<Planet> planets,
            @Nullable Vec3 entryPosition
    ) {
        float fragmentation = Config.GENERATED_PLANET_TEXTURE_FRAGMENTATION.get().floatValue();
        PlanetTextureGenerator.prewarmSurfaceMaps(server, planets, fragmentation, entryPosition)
                .whenComplete((unused, error) -> {
                    if (server.isStopped()) {
                        return;
                    }
                    server.execute(() -> {
                        if (error != null) {
                            LOGGER.error("Planet surface-map prewarming ended unexpectedly", error);
                        }
                    });
                });
    }
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        // Restore completed maps now; the player's current galaxy is queued after login.
        PlanetSurfaceMapCache.restore(server, getAllPlanets());
        refreshGeneratedTextures(server, getAllPlanets(), false);
    }
    
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (registryLock) {
            runtimePrimaryGalaxy = null;
            runtimePrimaryBodies = List.of();
        }
        LOGGER.info("Planet registry server stopped");
    }
    
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // Sync planets to the player when they join
        if (!event.getEntity().level().isClientSide && event.getEntity() instanceof ServerPlayer serverPlayer) {
            PlanetSurfaceMapCache.restore(serverPlayer.getServer(), getAllPlanets());
            PlanetDiscoveries.applyPersistedDiscoverers(serverPlayer);
            PlanetSyncPacket syncPacket = PlanetSyncPacket.createSyncPacket();
            PacketDistributor.sendToPlayer(serverPlayer, syncPacket);
            ResourceKey<Level> galaxy = StarMapExploration.resolveGalaxy(serverPlayer.level().dimension());
            if (galaxy != null) {
                // First surface login begins the owning galaxy's queue before the player enters space.
                prewarmGeneratedTextures(serverPlayer.getServer(), getPlanetsForGalaxy(galaxy));
            }
            LOGGER.debug("Synchronized planets to player: {}", serverPlayer.getName().getString());
        }
    }

    /**
     * Rebuilds generated palettes after /reload replaces data-pack planet definitions.
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            MinecraftServer server = event.getPlayerList().getServer();
            PlanetSurfaceMapCache.restore(server, getAllPlanets());
            refreshGeneratedTextures(server, getAllPlanets(), false);
            syncToAllPlayers();
        }
    }
    
    /**
     * Synchronizes planets to all connected players.
     */
    public static void syncToAllPlayers() {
        PlanetSyncPacket syncPacket = PlanetSyncPacket.createSyncPacket();
        PacketDistributor.sendToAllPlayers(syncPacket);
        LOGGER.info("Synchronized planets to all players");
    }

    /** Sends only the changed planet so clients do not rebuild every celestial mesh. */
    public static void syncPlanetToAllPlayers(@NotNull String planetId) {
        Planet planet = getPlanet(planetId);
        if (planet == null) {
            return;
        }
        PacketDistributor.sendToAllPlayers(PlanetSyncPacket.createPlanetUpdatePacket(planet));
        LOGGER.info("Synchronized generated texture update for planet {}", planetId);
    }

    /** Sends scan counters without forcing a render-thread texture rebuild. */
    public static void syncPlanetProgressToAllPlayers(@NotNull String planetId) {
        Planet planet = getPlanet(planetId);
        if (planet != null) {
            PacketDistributor.sendToAllPlayers(PlanetSyncPacket.createProgressUpdatePacket(planet));
        }
    }
} 


