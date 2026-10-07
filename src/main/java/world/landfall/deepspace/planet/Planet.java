package world.landfall.deepspace.planet;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import world.landfall.deepspace.Config;
import world.landfall.deepspace.Deepspace;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a planet in the Deep Space dimension with its associated dimension and bounding box.
 */
public class Planet {
    public static final int AUTOMATIC_ATMOSPHERE_HEIGHT = Integer.MIN_VALUE;
    private static final int DEFAULT_ATMOSPHERE_SKY_COLOR = 0x78A7FF;
    private static final int SPACE_EXIT_OFFSET = 10;
    private static final int PLANET_ENTRY_HEIGHT = 400;

    /** Selects how far temporary terrain generation advances before colors are sampled. */
    public enum TextureGenerationDetail {
        SURFACE,
        FEATURES
    }

    /** Tracks whether reliable full-resolution surface materials are available. */
    public enum SurfaceScanStatus {
        UNKNOWN,
        SCANNING,
        COMPLETE
    }

    public record SurfaceColor(int rgb, int weight) {
        public SurfaceColor {
            rgb &= 0xFFFFFF;
            if (weight < 1) {
                throw new IllegalArgumentException("Surface color weight must be positive");
            }
        }

        public static void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull SurfaceColor color) {
            buffer.writeInt(color.rgb);
            buffer.writeVarInt(color.weight);
        }

        public static SurfaceColor fromNetwork(@NotNull FriendlyByteBuf buffer) {
            return new SurfaceColor(buffer.readInt(), buffer.readVarInt());
        }
    }

    /** Compact server-sampled surface statistic synchronized for optional HUD integrations. */
    public record SurfaceSample(@NotNull String id, int count) {
        public SurfaceSample {
            Objects.requireNonNull(id, "Surface sample ID cannot be null");
            if (count < 1) {
                throw new IllegalArgumentException("Surface sample count must be positive");
            }
        }

        public static void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull SurfaceSample sample) {
            buffer.writeUtf(sample.id);
            buffer.writeVarInt(sample.count);
        }

        public static SurfaceSample fromNetwork(@NotNull FriendlyByteBuf buffer) {
            return new SurfaceSample(buffer.readUtf(), buffer.readVarInt());
        }
    }

    /** Generation-time facts used by the Paradise Probe when live samples are unavailable. */
    public record ParadiseProfile(
            boolean grassSurface,
            boolean biomeParticles,
            boolean hostileMobs,
            boolean preferredGrassColor,
            boolean dimensionFilter,
            boolean ocean,
            boolean blueOcean,
            boolean atmosphere,
            boolean blueSky
    ) {
        public static final ParadiseProfile DEFAULT = new ParadiseProfile(
                false, false, false, false, false, false, false, false, false
        );

        public static void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull ParadiseProfile profile) {
            buffer.writeBoolean(profile.grassSurface);
            buffer.writeBoolean(profile.biomeParticles);
            buffer.writeBoolean(profile.hostileMobs);
            buffer.writeBoolean(profile.preferredGrassColor);
            buffer.writeBoolean(profile.dimensionFilter);
            buffer.writeBoolean(profile.ocean);
            buffer.writeBoolean(profile.blueOcean);
            buffer.writeBoolean(profile.atmosphere);
            buffer.writeBoolean(profile.blueSky);
        }

        public static ParadiseProfile fromNetwork(@NotNull FriendlyByteBuf buffer) {
            return new ParadiseProfile(
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean()
            );
        }
    }

    public record PlanetDecoration(@NotNull String type, float scale, int color) {
        public final static String ATMOSPHERE = "atmosphere";
        public final static String RINGS = "rings";
        public final static String ASTEROIDS = "asteroids";
        public static final Codec<PlanetDecoration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("type").forGetter(decoration -> decoration.type),
                Codec.FLOAT.fieldOf("scale").forGetter(PlanetDecoration::scale),
                Codec.INT.optionalFieldOf("color", 0).forGetter(PlanetDecoration::color)
        ).apply(instance, PlanetDecoration::new));

        public static void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull PlanetDecoration decoration) {
            buffer.writeUtf(decoration.type);
            buffer.writeFloat(decoration.scale);
            buffer.writeInt(decoration.color);
        }

        public static PlanetDecoration fromNetwork(@NotNull FriendlyByteBuf buffer) {
            return new PlanetDecoration(buffer.readUtf(), buffer.readFloat(), buffer.readInt());
        }
    }

    public static final Codec<Planet> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.STRING.fieldOf("id").forGetter(Planet::getId),
            Codec.STRING.fieldOf("name").forGetter(Planet::getName),
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(planet -> planet.getDimension().location()),
            Vec3.CODEC.fieldOf("boundingBoxMin").forGetter(Planet::getUnscaledBoundingBoxMin),
            Vec3.CODEC.fieldOf("boundingBoxMax").forGetter(Planet::getUnscaledBoundingBoxMax),
            Codec.list(PlanetDecoration.CODEC).optionalFieldOf("decorations").forGetter(Planet::getDecorations),
            Codec.DOUBLE.listOf().comapFlatMap((values) -> Util.fixedSize(values, 2).map((fixed) -> new Vec2(fixed.get(0).floatValue(), fixed.get(1).floatValue())), (value) -> List.of((double)value.x, (double)value.y)).fieldOf("physicalMin").forGetter(Planet::getPhysicalMin),
            Codec.DOUBLE.listOf().comapFlatMap((values) -> Util.fixedSize(values, 2).map((fixed) -> new Vec2(fixed.get(0).floatValue(), fixed.get(1).floatValue())), (value) -> List.of((double)value.x, (double)value.y)).fieldOf("physicalMax").forGetter(Planet::getPhysicalMax),
            Codec.STRING.optionalFieldOf("description", "").forGetter(Planet::getDescription),
            ResourceLocation.CODEC.optionalFieldOf("texture").forGetter(Planet::getSingleTexture),
            ResourceLocation.CODEC.listOf().optionalFieldOf("textures", List.of()).forGetter(Planet::getSixFaceTextures),
            ResourceLocation.CODEC.optionalFieldOf("galaxy", Deepspace.path("space")).forGetter(planet -> planet.getGalaxy().location()),
            Vec3.CODEC.optionalFieldOf("warpTarget").forGetter(Planet::getUnscaledWarpTarget),
            Codec.INT.optionalFieldOf("atmosphereEntryHeight", AUTOMATIC_ATMOSPHERE_HEIGHT).forGetter(Planet::getAtmosphereEntryHeight),
            Codec.INT.optionalFieldOf("atmosphereExitHeight", AUTOMATIC_ATMOSPHERE_HEIGHT).forGetter(Planet::getAtmosphereExitHeight),
            Codec.BOOL.optionalFieldOf("ringWorldEdge", false).forGetter(Planet::isRingWorldEdge)
        ).apply(instance, (id, name, dimensionLocation, min, max, decorations, physicalMin, physicalMax, description, texture, textures, galaxyLocation, warpTarget, entryHeight, exitHeight, ringWorldEdge) ->
            new Planet(id, name, ResourceKey.create(Registries.DIMENSION, dimensionLocation),
                    min, max, decorations.orElseGet(List::of), description,
                    physicalMin, physicalMax, resolveTextures(texture, textures),
                    ResourceKey.create(Registries.DIMENSION, galaxyLocation), warpTarget.orElse(null),
                    entryHeight, exitHeight, ringWorldEdge
            )
        )
    );


    private final String id;
    private final String name;
    private final ResourceKey<Level> dimension;
    private final Vec3 boundingBoxMin;
    private final Vec3 boundingBoxMax;
    private final String description;
    private final Collection<PlanetDecoration> decorations;
    private final Vec2 physicalMin;
    private final Vec2 physicalMax;
    private final List<ResourceLocation> textures;
    private final ResourceKey<Level> galaxy;
    @Nullable
    private final Vec3 warpTarget;
    private final int atmosphereEntryHeight;
    private final int atmosphereExitHeight;
    private final boolean ringWorldEdge;
    private final TextureGenerationDetail textureGenerationDetail;
    private volatile List<SurfaceColor> generatedSurfaceColors = List.of();
    private volatile short[] generatedSurfaceMap = new short[0];
    // Restored and synchronized together with the pixels, so an existing preview is never mistaken for FULL.
    private volatile PlanetTextureTier generatedTextureTier;
    private volatile float generatedTextureFragmentation;
    private volatile long generatedTextureSeed;
    private volatile int generatedAtmosphereColor;
    private volatile List<SurfaceSample> sampledBiomes = List.of();
    private volatile List<SurfaceSample> sampledFluids = List.of();
    private volatile List<SurfaceSample> sampledBlocks = List.of();
    private volatile String planetTypeBiome = "";
    private volatile SurfaceScanStatus surfaceScanStatus = SurfaceScanStatus.UNKNOWN;
    private volatile int surfaceScanCompletedChunks;
    private volatile int surfaceScanTotalChunks;
    private volatile String discovererName = "";
    private volatile ParadiseProfile paradiseProfile = ParadiseProfile.DEFAULT;
    private volatile boolean paradiseProfilePresent;

    /**
     * Creates a new Planet instance.
     *
     * @param id The unique identifier for this planet
     * @param name The display name of the planet
     * @param dimension The dimension this planet represents
     * @param boundingBoxMin The minimum coordinates of the planet's bounding box in Deep Space
     * @param boundingBoxMax The maximum coordinates of the planet's bounding box in Deep Space
     * @param description Optional description of the planet
     */
    public Planet(@NotNull String id, @NotNull String name, @NotNull ResourceKey<Level> dimension,
                  @NotNull Vec3 boundingBoxMin, @NotNull Vec3 boundingBoxMax, @Nullable Collection<PlanetDecoration> decorations, @Nullable String description,
                  @NotNull Vec2 physicalMin, @NotNull Vec2 physicalMax, @Nullable ResourceLocation texture) {
        this(
                id,
                name,
                dimension,
                boundingBoxMin,
                boundingBoxMax,
                decorations,
                description,
                physicalMin,
                physicalMax,
                texture == null ? List.of() : List.of(texture)
        );
    }

    /**
     * Creates a planet with either one repeated texture or six face textures.
     */
    public Planet(@NotNull String id, @NotNull String name, @NotNull ResourceKey<Level> dimension,
                  @NotNull Vec3 boundingBoxMin, @NotNull Vec3 boundingBoxMax, @Nullable Collection<PlanetDecoration> decorations, @Nullable String description,
                  @NotNull Vec2 physicalMin, @NotNull Vec2 physicalMax, @NotNull List<ResourceLocation> textures) {
        this(id, name, dimension, boundingBoxMin, boundingBoxMax, decorations, description,
                physicalMin, physicalMax, textures,
                ResourceKey.create(Registries.DIMENSION, Deepspace.path("space")), null,
                AUTOMATIC_ATMOSPHERE_HEIGHT, AUTOMATIC_ATMOSPHERE_HEIGHT);
    }

    /** Creates a planet hosted by a specific galaxy, or a wormhole when warpTarget is present. */
    public Planet(@NotNull String id, @NotNull String name, @NotNull ResourceKey<Level> dimension,
                  @NotNull Vec3 boundingBoxMin, @NotNull Vec3 boundingBoxMax,
                  @Nullable Collection<PlanetDecoration> decorations, @Nullable String description,
                  @NotNull Vec2 physicalMin, @NotNull Vec2 physicalMax,
                  @NotNull List<ResourceLocation> textures, @NotNull ResourceKey<Level> galaxy,
                  @Nullable Vec3 warpTarget) {
        this(id, name, dimension, boundingBoxMin, boundingBoxMax, decorations, description,
                physicalMin, physicalMax, textures, galaxy, warpTarget,
                AUTOMATIC_ATMOSPHERE_HEIGHT, AUTOMATIC_ATMOSPHERE_HEIGHT);
    }

    /** Creates a hosted planet with explicit surface transition heights. */
    public Planet(@NotNull String id, @NotNull String name, @NotNull ResourceKey<Level> dimension,
                  @NotNull Vec3 boundingBoxMin, @NotNull Vec3 boundingBoxMax,
                  @Nullable Collection<PlanetDecoration> decorations, @Nullable String description,
                  @NotNull Vec2 physicalMin, @NotNull Vec2 physicalMax,
                  @NotNull List<ResourceLocation> textures, @NotNull ResourceKey<Level> galaxy,
                  @Nullable Vec3 warpTarget, int atmosphereEntryHeight, int atmosphereExitHeight) {
        this(id, name, dimension, boundingBoxMin, boundingBoxMax, decorations, description,
                physicalMin, physicalMax, textures, galaxy, warpTarget,
                atmosphereEntryHeight, atmosphereExitHeight, false);
    }

    /** Creates a hosted planet that may render as a ring world edge instead of a cube. */
    public Planet(@NotNull String id, @NotNull String name, @NotNull ResourceKey<Level> dimension,
                  @NotNull Vec3 boundingBoxMin, @NotNull Vec3 boundingBoxMax,
                  @Nullable Collection<PlanetDecoration> decorations, @Nullable String description,
                  @NotNull Vec2 physicalMin, @NotNull Vec2 physicalMax,
                  @NotNull List<ResourceLocation> textures, @NotNull ResourceKey<Level> galaxy,
                  @Nullable Vec3 warpTarget, int atmosphereEntryHeight, int atmosphereExitHeight,
                  boolean ringWorldEdge) {
        this(id, name, dimension, boundingBoxMin, boundingBoxMax, decorations, description,
                physicalMin, physicalMax, textures, galaxy, warpTarget, atmosphereEntryHeight,
                atmosphereExitHeight, ringWorldEdge, TextureGenerationDetail.SURFACE);
    }

    /** Creates a hosted planet with an explicit temporary texture-generation depth. */
    public Planet(@NotNull String id, @NotNull String name, @NotNull ResourceKey<Level> dimension,
                  @NotNull Vec3 boundingBoxMin, @NotNull Vec3 boundingBoxMax,
                  @Nullable Collection<PlanetDecoration> decorations, @Nullable String description,
                  @NotNull Vec2 physicalMin, @NotNull Vec2 physicalMax,
                  @NotNull List<ResourceLocation> textures, @NotNull ResourceKey<Level> galaxy,
                  @Nullable Vec3 warpTarget, int atmosphereEntryHeight, int atmosphereExitHeight,
                  boolean ringWorldEdge, @NotNull TextureGenerationDetail textureGenerationDetail) {
        this.id = Objects.requireNonNull(id, "Planet ID cannot be null");
        this.name = Objects.requireNonNull(name, "Planet name cannot be null");
        this.dimension = Objects.requireNonNull(dimension, "Planet dimension cannot be null");
        this.boundingBoxMin = Objects.requireNonNull(boundingBoxMin, "Bounding box minimum cannot be null");
        this.boundingBoxMax = Objects.requireNonNull(boundingBoxMax, "Bounding box maximum cannot be null");
        this.description = description != null ? description : "";
        this.decorations = decorations != null ? decorations : List.of();
        this.physicalMin = physicalMin;
        this.physicalMax = physicalMax;
        this.textures = validateTextures(textures);
        this.galaxy = Objects.requireNonNull(galaxy, "Galaxy dimension cannot be null");
        this.warpTarget = warpTarget;
        this.atmosphereEntryHeight = atmosphereEntryHeight;
        this.atmosphereExitHeight = atmosphereExitHeight;
        this.ringWorldEdge = ringWorldEdge;
        this.textureGenerationDetail = Objects.requireNonNull(
                textureGenerationDetail, "Texture generation detail cannot be null"
        );
        this.generatedTextureSeed = PlanetTextureLayout.seedFromWorldAndPlanet(0L, id);
        // Validate bounding box
        if (boundingBoxMin.x > boundingBoxMax.x || boundingBoxMin.y > boundingBoxMax.y || boundingBoxMin.z > boundingBoxMax.z) {
            throw new IllegalArgumentException("Invalid bounding box: minimum coordinates must be less than maximum coordinates");
        }
        if (atmosphereEntryHeight != AUTOMATIC_ATMOSPHERE_HEIGHT
                && atmosphereExitHeight != AUTOMATIC_ATMOSPHERE_HEIGHT
                && atmosphereExitHeight <= atmosphereEntryHeight) {
            throw new IllegalArgumentException("Atmosphere exit height must exceed entry height");
        }
    }

    /**
     * Creates a new Planet instance without description.
     */
    public Planet(@NotNull String id, @NotNull String name, @NotNull ResourceKey<Level> dimension,
                  @NotNull Vec3 boundingBoxMin, @NotNull Vec3 boundingBoxMax, @NotNull Collection<PlanetDecoration> decorations,
                  @NotNull Vec2 physicalMin, @NotNull Vec2 physicalMax) {
        this(id, name, dimension, boundingBoxMin, boundingBoxMax, decorations, "", physicalMin, physicalMax, (ResourceLocation) null);
    }

    public Planet(@NotNull String id, @NotNull String name, @NotNull ResourceKey<Level> dimension,
                  @NotNull Vec3 boundingBoxMin, @NotNull Vec3 boundingBoxMax, @Nullable Collection<PlanetDecoration> decorations, @Nullable String description,
                  @NotNull Vec2 physicalMin, @NotNull Vec2 physicalMax) {
        this(id, name, dimension, boundingBoxMin, boundingBoxMax, decorations, description, physicalMin, physicalMax, (ResourceLocation) null);
    }

    /**
     * @return The unique identifier for this planet
     */
    @NotNull
    public String getId() {
        return id;
    }

    /**
     * @return The display name of the planet
     */
    @NotNull
    public String getName() {
        if (!isWormhole()) {
            return name;
        }
        // Keep stored identifiers compatible while presenting the new player-facing term.
        if (name.endsWith(" Wormhole")) {
            return name.substring(0, name.length() - " Wormhole".length()) + " Hyper Relay";
        }
        if (name.endsWith("虫洞")) {
            return name.substring(0, name.length() - 2) + "超空间中继器";
        }
        return name;
    }

    /**
     * @return The dimension this planet represents
     */
    @NotNull
    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    /**
     * @return The minimum coordinates of the planet's bounding box in Deep Space
     */
    @NotNull
    public Vec3 getBoundingBoxMin() {
        return SpaceObjectScale.planetBound(this, false);
    }

    /**
     * @return The maximum coordinates of the planet's bounding box in Deep Space
     */
    @NotNull
    public Vec3 getBoundingBoxMax() {
        return SpaceObjectScale.planetBound(this, true);
    }
    /**
     * @return The minimum coordinates of the dimension as it appears in the planet texture
     */
    @NotNull
    public Vec2 getPhysicalMin() {
        return physicalMin;
    }

    /**
     * @return The maximum coordinates of the dimension as it appears in the planet texture
     */
    @NotNull
    public Vec2 getPhysicalMax() {
        return physicalMax;
    }

    @NotNull
    public Optional<List<PlanetDecoration>> getDecorations() {
        return Optional.of(decorations.stream().toList());
    }

    /** 返回该星球是否带有大气装饰，用于无大气星球的黑色星空渲染。 */
    public boolean hasAtmosphere() {
        return decorations.stream().anyMatch(decoration -> PlanetDecoration.ATMOSPHERE.equals(decoration.type()));
    }

    /**
     * @return The description of the planet
     */
    @NotNull
    public String getDescription() {
        return description;
    }

    /**
     * Returns the first explicitly configured texture for backward-compatible callers.
     */
    @NotNull
    public Optional<ResourceLocation> getTexture() {
        return textures.stream().findFirst();
    }

    /**
     * Returns zero textures for procedural generation, one repeated texture, or six face textures.
     */
    @NotNull
    public List<ResourceLocation> getTextures() {
        return textures;
    }

    /** Returns the space dimension in which this celestial body is rendered. */
    @NotNull
    public ResourceKey<Level> getGalaxy() {
        return galaxy;
    }

    public boolean isWormhole() {
        return warpTarget != null;
    }

    /** Player-facing alias retained alongside the legacy API name for binary compatibility. */
    public boolean isHyperRelay() {
        return isWormhole();
    }

    @NotNull
    public Optional<Vec3> getWarpTarget() {
        // Warp destinations use the target system geometry, including size-only ring arrivals.
        return getUnscaledWarpTarget().map(target -> SpaceObjectScale.arrival(
                target, PlanetRegistry.getGalaxyByDimension(dimension)));
    }

    /** Canonical coordinates are serialized and used for generation and surface skies. */
    public Vec3 getUnscaledBoundingBoxMin() { return boundingBoxMin; }
    public Vec3 getUnscaledBoundingBoxMax() { return boundingBoxMax; }
    public Optional<Vec3> getUnscaledWarpTarget() { return Optional.ofNullable(warpTarget); }

    public int getAtmosphereEntryHeight() {
        return atmosphereEntryHeight;
    }

    public int getAtmosphereExitHeight() {
        return atmosphereExitHeight;
    }

    /** 环世界棱体：渲染为缩放后的环世界 geo 模型，而不是普通行星的六面方块。 */
    public boolean isRingWorldEdge() {
        return ringWorldEdge;
    }

    /** Returns the data-pack selected generation stage for a procedural surface texture. */
    @NotNull
    public TextureGenerationDetail getTextureGenerationDetail() {
        return textureGenerationDetail;
    }

    /** Returns an equivalent definition with the data-pack requested temporary generation depth. */
    @NotNull
    public Planet withTextureGenerationDetail(@NotNull TextureGenerationDetail detail) {
        Planet updated = new Planet(
                id, name, dimension, boundingBoxMin, boundingBoxMax, decorations, description,
                physicalMin, physicalMax, textures, galaxy, warpTarget, atmosphereEntryHeight,
                atmosphereExitHeight, ringWorldEdge, detail
        );
        updated.setGeneratedSurfaceColors(generatedSurfaceColors, generatedTextureFragmentation, generatedTextureSeed);
        updated.setGeneratedSurfaceMap(generatedSurfaceMap);
        updated.setGeneratedAtmosphereColor(generatedAtmosphereColor);
        updated.setSurfaceSamples(sampledBiomes, sampledFluids, sampledBlocks);
        updated.setPlanetTypeBiome(planetTypeBiome);
        updated.setSurfaceScanStatus(surfaceScanStatus);
        updated.setSurfaceScanProgress(surfaceScanCompletedChunks, surfaceScanTotalChunks);
        updated.setDiscovererName(discovererName);
        updated.setParadiseProfile(paradiseProfile);
        return updated;
    }

    /** Lands every planet transfer at Y=400, below the configurable exit boundary. */
    public int resolveAtmosphereEntryHeight(int runtimeMaxBuildHeight) {
        return Math.min(PLANET_ENTRY_HEIGHT, Config.PLANET_ATMOSPHERE_HEIGHT.get() - SPACE_EXIT_OFFSET - 1);
    }

    /** Resolves the shared atmosphere exit plane independently of dimension build height. */
    public int resolveAtmosphereExitHeight(int runtimeMaxBuildHeight) {
        return Config.PLANET_ATMOSPHERE_HEIGHT.get();
    }

    private Optional<ResourceLocation> getSingleTexture() {
        return textures.size() == 1 ? Optional.of(textures.getFirst()) : Optional.empty();
    }

    private List<ResourceLocation> getSixFaceTextures() {
        return textures.size() == 6 ? textures : List.of();
    }

    @NotNull
    public List<SurfaceColor> getGeneratedSurfaceColors() {
        return generatedSurfaceColors;
    }

    /** Returns the compact RGB565 atlas for the currently completed sampling tier. */
    public short[] getGeneratedSurfaceMap() {
        return generatedSurfaceMap.clone();
    }

    /** Read the current sampling tier without cloning the map on every proximity check. */
    public PlanetTextureTier getGeneratedTextureTier() {
        return generatedTextureTier;
    }

    /** Stores an immutable copy so network and render threads cannot mutate shared map pixels. */
    public void setGeneratedSurfaceMap(short @NotNull [] pixels) {
        generatedSurfaceMap = pixels.clone();
        generatedTextureTier = PlanetTextureTier.fromPixelCount(pixels.length);
    }

    public float getGeneratedTextureFragmentation() {
        return generatedTextureFragmentation;
    }

    public long getGeneratedTextureSeed() {
        return generatedTextureSeed;
    }

    public int getGeneratedAtmosphereColor() {
        return generatedAtmosphereColor;
    }

    /** 返回该星球当前应使用的天空色：无大气为黑空，有大气但未配置颜色时使用原版蓝天。 */
    public int getSkyColor() {
        int decorationColor = decorations.stream()
                .filter(decoration -> PlanetDecoration.ATMOSPHERE.equals(decoration.type()))
                .mapToInt(PlanetDecoration::color)
                .filter(color -> color != 0)
                .findFirst()
                .orElse(0);
        if (decorationColor != 0) {
            return decorationColor & 0xFFFFFF;
        }
        if (generatedAtmosphereColor != 0) {
            return generatedAtmosphereColor;
        }
        return hasAtmosphere() ? DEFAULT_ATMOSPHERE_SKY_COLOR : 0;
    }

    /** Stores the average sky color selected by the generated dimension's biome source. */
    public void setGeneratedAtmosphereColor(int color) {
        generatedAtmosphereColor = color & 0xFFFFFF;
    }

    /**
     * Stores the server-sampled palette and total seed used by every client.
     */
    public void setGeneratedSurfaceColors(
            @NotNull Collection<SurfaceColor> colors,
            float fragmentation,
            long textureSeed
    ) {
        this.generatedSurfaceColors = List.copyOf(colors);
        this.generatedTextureFragmentation = Math.clamp(fragmentation, 0.0f, 1.0f);
        this.generatedTextureSeed = textureSeed;
    }

    @NotNull
    public List<SurfaceSample> getSampledBiomes() {
        return sampledBiomes;
    }

    @NotNull
    public List<SurfaceSample> getSampledFluids() {
        return sampledFluids;
    }

    @NotNull
    public List<SurfaceSample> getSampledBlocks() {
        return sampledBlocks;
    }

    /** Returns the biome type chosen from the six coarse chunk votes. */
    @NotNull
    public String getPlanetTypeBiome() {
        return planetTypeBiome;
    }

    public void setPlanetTypeBiome(@Nullable String biomeId) {
        planetTypeBiome = PlanetBiomeType.classify(biomeId);
    }

    @NotNull
    public SurfaceScanStatus getSurfaceScanStatus() {
        return surfaceScanStatus;
    }

    public void setSurfaceScanStatus(@NotNull SurfaceScanStatus status) {
        surfaceScanStatus = Objects.requireNonNull(status, "Surface scan status cannot be null");
    }

    /** Reports the current full-resolution sampling progress to client interfaces. */
    public int getSurfaceScanCompletedChunks() {
        return surfaceScanCompletedChunks;
    }

    public int getSurfaceScanTotalChunks() {
        return surfaceScanTotalChunks;
    }

    public void setSurfaceScanProgress(int completedChunks, int totalChunks) {
        surfaceScanCompletedChunks = Math.max(0, completedChunks);
        surfaceScanTotalChunks = Math.max(0, totalChunks);
    }

    /** Returns an empty value until the first player enters this planet dimension. */
    @NotNull
    public String getDiscovererName() {
        return discovererName;
    }

    public void setDiscovererName(@Nullable String discovererName) {
        this.discovererName = discovererName == null ? "" : discovererName;
    }

    public void setSurfaceSamples(
            @NotNull Collection<SurfaceSample> biomes,
            @NotNull Collection<SurfaceSample> fluids,
            @NotNull Collection<SurfaceSample> blocks
    ) {
        sampledBiomes = List.copyOf(biomes);
        sampledFluids = List.copyOf(fluids);
        sampledBlocks = List.copyOf(blocks);
    }

    @NotNull
    public ParadiseProfile getParadiseProfile() {
        return paradiseProfile;
    }

    public void setParadiseProfile(@NotNull ParadiseProfile paradiseProfile) {
        this.paradiseProfile = Objects.requireNonNull(paradiseProfile, "Paradise profile cannot be null");
        this.paradiseProfilePresent = true;
    }

    /** Indicates whether the profile contains an actual generated or synchronized assessment. */
    public boolean hasParadiseProfile() {
        return paradiseProfilePresent;
    }

    /**
     * Checks if a given position is within this planet's bounding box.
     *
     * @param position The position to check
     * @return true if the position is within the bounding box, false otherwise
     */
    public boolean isWithinBounds(@NotNull Vec3 position) {
        Objects.requireNonNull(position, "Position cannot be null");
        Vec3 min = getBoundingBoxMin();
        Vec3 max = getBoundingBoxMax();
        return position.x >= min.x && position.x <= max.x &&
               position.y >= min.y && position.y <= max.y &&
               position.z >= min.z && position.z <= max.z;
    }

    /** Returns the exact axis-aligned cube used by the planet renderer. */
    @NotNull
    public AABB getModelBounds() {
        return new AABB(getBoundingBoxMin(), getBoundingBoxMax());
    }

    /** Checks collision against the same cube coordinates used to build the rendered model. */
    public boolean intersectsModel(@NotNull AABB bounds) {
        Objects.requireNonNull(bounds, "Bounds cannot be null");
        // Contact checks use the same scaled bounds as space rendering and transfers.
        Vec3 min = getBoundingBoxMin();
        Vec3 max = getBoundingBoxMax();
        return PlanetModelBounds.intersects(
                min.x, min.y, min.z,
                max.x, max.y, max.z,
                bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, bounds.maxY, bounds.maxZ
        );
    }

    /**
     * Checks if a player is touching the planet
     *
     * @param player The player to check
     * @return true if the player is colliding with this planet, false otherwise
     */
    public boolean isPlayerTouching(@NotNull Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        if (!player.level().dimension().equals(galaxy)) {
            return false;
        }
        return intersectsModel(player.getBoundingBox());
    }

    /**
     * Gets the center point of the planet's bounding box.
     *
     * @return The center coordinates
     */
    @NotNull
    public Vec3 getCenter() {
        return SpaceObjectScale.planetCenter(this);
    }

    /** Returns the authored center independently of the current save's scale. */
    public Vec3 getUnscaledCenter() {
        return new Vec3(
            (boundingBoxMin.x + boundingBoxMax.x) / 2.0,
            (boundingBoxMin.y + boundingBoxMax.y) / 2.0,
            (boundingBoxMin.z + boundingBoxMax.z) / 2.0
        );
    }

    /** 返回保持尺寸和运行时表面数据、但水平中心移动到指定 X/Z 的副本。 */
    @NotNull
    public Planet withHorizontalCenter(double centerX, double centerZ) {
        Vec3 halfSize = boundingBoxMax.subtract(boundingBoxMin).scale(0.5);
        double centerY = (boundingBoxMin.y + boundingBoxMax.y) * 0.5;
        Vec3 newCenter = new Vec3(centerX, centerY, centerZ);
        Planet moved = new Planet(
                id,
                name,
                dimension,
                newCenter.subtract(halfSize),
                newCenter.add(halfSize),
                decorations,
                description,
                physicalMin,
                physicalMax,
                textures,
                galaxy,
                warpTarget,
                atmosphereEntryHeight,
                atmosphereExitHeight,
                ringWorldEdge,
                textureGenerationDetail
        );
        moved.setGeneratedSurfaceColors(generatedSurfaceColors, generatedTextureFragmentation, generatedTextureSeed);
        moved.setGeneratedSurfaceMap(generatedSurfaceMap);
        moved.setGeneratedAtmosphereColor(generatedAtmosphereColor);
        moved.setSurfaceSamples(sampledBiomes, sampledFluids, sampledBlocks);
        moved.setPlanetTypeBiome(planetTypeBiome);
        moved.setSurfaceScanStatus(surfaceScanStatus);
        moved.setSurfaceScanProgress(surfaceScanCompletedChunks, surfaceScanTotalChunks);
        moved.setDiscovererName(discovererName);
        moved.setParadiseProfile(paradiseProfile);
        return moved;
    }

    /**
     * Writes this planet to a network buffer for client synchronization.
     *
     * @param buffer The buffer to write to
     */
    public void toNetwork(@NotNull FriendlyByteBuf buffer) {
        Objects.requireNonNull(buffer, "Buffer cannot be null");
        buffer.writeUtf(id);
        buffer.writeUtf(name);
        buffer.writeResourceLocation(dimension.location());
        buffer.writeDouble(boundingBoxMin.x);
        buffer.writeDouble(boundingBoxMin.y);
        buffer.writeDouble(boundingBoxMin.z);
        buffer.writeDouble(boundingBoxMax.x);
        buffer.writeDouble(boundingBoxMax.y);
        buffer.writeDouble(boundingBoxMax.z);
        buffer.writeCollection(decorations, PlanetDecoration::toNetwork);
        buffer.writeDouble(physicalMin.x);
        buffer.writeDouble(physicalMin.y);
        buffer.writeDouble(physicalMax.x);
        buffer.writeDouble(physicalMax.y);
        buffer.writeUtf(description);
        buffer.writeCollection(textures, FriendlyByteBuf::writeResourceLocation);
        buffer.writeCollection(generatedSurfaceColors, SurfaceColor::toNetwork);
        buffer.writeVarInt(generatedSurfaceMap.length);
        for (short pixel : generatedSurfaceMap) {
            buffer.writeShort(pixel);
        }
        buffer.writeFloat(generatedTextureFragmentation);
        buffer.writeLong(generatedTextureSeed);
        buffer.writeInt(generatedAtmosphereColor);
        buffer.writeCollection(sampledBiomes, SurfaceSample::toNetwork);
        buffer.writeCollection(sampledFluids, SurfaceSample::toNetwork);
        buffer.writeCollection(sampledBlocks, SurfaceSample::toNetwork);
        buffer.writeUtf(planetTypeBiome);
        buffer.writeEnum(surfaceScanStatus);
        buffer.writeVarInt(surfaceScanCompletedChunks);
        buffer.writeVarInt(surfaceScanTotalChunks);
        buffer.writeUtf(discovererName);
        ParadiseProfile.toNetwork(buffer, paradiseProfile);
        buffer.writeResourceLocation(galaxy.location());
        buffer.writeBoolean(warpTarget != null);
        if (warpTarget != null) {
            buffer.writeVec3(warpTarget);
        }
        buffer.writeInt(atmosphereEntryHeight);
        buffer.writeInt(atmosphereExitHeight);
        buffer.writeBoolean(ringWorldEdge);
        buffer.writeEnum(textureGenerationDetail);
    }

    /**
     * Reads a planet from a network buffer.
     *
     * @param buffer The buffer to read from
     * @return The planet instance
     */
    @NotNull
    public static Planet fromNetwork(@NotNull FriendlyByteBuf buffer) {
        Objects.requireNonNull(buffer, "Buffer cannot be null");
        String id = buffer.readUtf();
        String name = buffer.readUtf();
        ResourceLocation dimensionLocation = buffer.readResourceLocation();
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionLocation);
        Vec3 boundingBoxMin = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        Vec3 boundingBoxMax = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        Collection<PlanetDecoration> decorations = buffer.readList(PlanetDecoration::fromNetwork);
        Vec2 physicalMin = new Vec2((float)buffer.readDouble(), (float)buffer.readDouble());
        Vec2 physicalMax = new Vec2((float)buffer.readDouble(), (float)buffer.readDouble());
        String description = buffer.readUtf();
        List<ResourceLocation> textures = buffer.readList(FriendlyByteBuf::readResourceLocation);
        List<SurfaceColor> surfaceColors = buffer.readList(SurfaceColor::fromNetwork);
        int surfaceMapLength = buffer.readVarInt();
        if (surfaceMapLength != 0 && PlanetTextureTier.fromPixelCount(surfaceMapLength) == null) {
            throw new IllegalArgumentException("Invalid generated surface map length: " + surfaceMapLength);
        }
        short[] surfaceMap = new short[surfaceMapLength];
        for (int index = 0; index < surfaceMapLength; index++) {
            surfaceMap[index] = buffer.readShort();
        }
        float fragmentation = buffer.readFloat();
        long textureSeed = buffer.readLong();
        int atmosphereColor = buffer.readInt();
        List<SurfaceSample> sampledBiomes = buffer.readList(SurfaceSample::fromNetwork);
        List<SurfaceSample> sampledFluids = buffer.readList(SurfaceSample::fromNetwork);
        List<SurfaceSample> sampledBlocks = buffer.readList(SurfaceSample::fromNetwork);
        String planetTypeBiome = buffer.readUtf();
        SurfaceScanStatus surfaceScanStatus = buffer.readEnum(SurfaceScanStatus.class);
        int surfaceScanCompletedChunks = buffer.readVarInt();
        int surfaceScanTotalChunks = buffer.readVarInt();
        String discovererName = buffer.readUtf();
        ParadiseProfile paradiseProfile = ParadiseProfile.fromNetwork(buffer);
        ResourceKey<Level> galaxy = ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation());
        Vec3 warpTarget = buffer.readBoolean() ? buffer.readVec3() : null;
        int atmosphereEntryHeight = buffer.readInt();
        int atmosphereExitHeight = buffer.readInt();
        boolean ringWorldEdge = buffer.readBoolean();
        TextureGenerationDetail textureGenerationDetail = buffer.readEnum(TextureGenerationDetail.class);
        Planet planet = new Planet(id, name, dimension, boundingBoxMin, boundingBoxMax, decorations,
                description, physicalMin, physicalMax, textures, galaxy, warpTarget,
                atmosphereEntryHeight, atmosphereExitHeight, ringWorldEdge, textureGenerationDetail);
        planet.setGeneratedSurfaceColors(surfaceColors, fragmentation, textureSeed);
        planet.setGeneratedSurfaceMap(surfaceMap);
        planet.setGeneratedAtmosphereColor(atmosphereColor);
        planet.setSurfaceSamples(sampledBiomes, sampledFluids, sampledBlocks);
        planet.setPlanetTypeBiome(planetTypeBiome);
        planet.setSurfaceScanStatus(surfaceScanStatus);
        planet.setSurfaceScanProgress(surfaceScanCompletedChunks, surfaceScanTotalChunks);
        planet.setDiscovererName(discovererName);
        planet.setParadiseProfile(paradiseProfile);
        return planet;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Planet planet = (Planet) obj;
        return Objects.equals(id, planet.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Planet{" +
               "id='" + id + '\'' +
               ", name='" + name + '\'' +
               ", dimension=" + dimension +
               ", boundingBoxMin=" + boundingBoxMin +
               ", boundingBoxMax=" + boundingBoxMax +
               ", description='" + description + '\'' +
               '}';
    }
    // Map the resized space model back to the unchanged physical surface footprint.
    public float blockScale() {
        var dist1 = Math.abs(getBoundingBoxMax().subtract(getBoundingBoxMin()).x);
        var dist2 = Math.abs(physicalMax.x - physicalMin.x);
        return (float) (dist2 / dist1);
    }

    private static List<ResourceLocation> resolveTextures(
            Optional<ResourceLocation> texture,
            List<ResourceLocation> textures
    ) {
        return textures.isEmpty() ? texture.map(List::of).orElseGet(List::of) : textures;
    }

    private static List<ResourceLocation> validateTextures(List<ResourceLocation> textures) {
        Objects.requireNonNull(textures, "Textures cannot be null");
        if (!textures.isEmpty() && textures.size() != 1 && textures.size() != 6) {
            throw new IllegalArgumentException("A planet must define exactly one or six textures");
        }
        return List.copyOf(textures);
    }
} 
