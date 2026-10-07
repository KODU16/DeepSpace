package world.landfall.deepspace;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import world.landfall.deepspace.integration.DeepspaceOptions;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
@SuppressWarnings("removal") // NeoForge currently requires the deprecated MOD bus selector here.
@EventBusSubscriber(modid = Deepspace.MODID, bus = EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<DeepspaceOptions.Detail> PLANET_DECORATION_DETAIL = BUILDER.defineEnum("decorationDetail", DeepspaceOptions.Detail.BASIC);
    public static final ModConfigSpec.EnumValue<DeepspaceOptions.Detail> PLANET_SHADING_DETAIL = BUILDER.defineEnum("shadingDetail", DeepspaceOptions.Detail.BASIC);
    public static final ModConfigSpec.DoubleValue GENERATED_PLANET_TEXTURE_FRAGMENTATION = BUILDER
            .comment("Controls procedural planet continent fragmentation: 0 creates broad masses, 1 creates many smaller regions.")
            .defineInRange("generatedPlanetTextureFragmentation", 0.35, 0.0, 1.0);
    // All planet surfaces use this transfer plane, including planets already present in a save.
    public static final ModConfigSpec.IntValue PLANET_ATMOSPHERE_HEIGHT = BUILDER
            .comment("Planet atmosphere exit height in blocks. Applies to every planet, including existing saves.")
            .defineInRange("planetAtmosphereHeight", 550, 64, 4096);
    public static final ModConfigSpec.DoubleValue NIGHT_SKY_PLANET_MIN_FRACTION = BUILDER
            .comment("Minimum fraction of other planets selected for each surface night sky.")
            .defineInRange("nightSkyPlanetMinFraction", 0.3, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue NIGHT_SKY_PLANET_MAX_FRACTION = BUILDER
            .comment("Maximum fraction of other planets selected for each surface night sky.")
            .defineInRange("nightSkyPlanetMaxFraction", 1.0, 0.0, 1.0);
    public static final ModConfigSpec.BooleanValue INFINITE_DIMENSIONS_WORMHOLES = BUILDER
            .comment("Generate the deterministic undirected Infinite galaxy wormhole graph when Infinite Dimensions is installed.")
            .define("infiniteDimensionsWormholes", true);
    public static final ModConfigSpec.BooleanValue RING_WORLD_ORIGIN = BUILDER
            .comment("Make newly created saves use a four-segment ring world as the primary system. The choice is persisted per save.")
            .define("ringWorldOrigin", false);
    public static final ModConfigSpec.IntValue RING_WORLD_BROKEN_SECTION_COUNT = BUILDER
            .comment("Number of broken non-Overworld sections in the primary ring world (0-3). One percent of broken sections are repairable.")
            .defineInRange("ringWorldBrokenSectionCount", 0, 0, 3);
    public static final ModConfigSpec.DoubleValue INFINITE_WORMHOLE_DENSITY = BUILDER
            .comment("Infinite galaxy graph density multiplier. 1.0 averages 2.5 wormholes per galaxy; connectivity keeps the minimum average near 2.")
            .defineInRange("infiniteWormholeDensity", 1.0D, 0.0D, 2.4D);
    public static final ModConfigSpec.BooleanValue DISABLE_INFINITY_BOOK_PORTALS = BUILDER
            .comment("Disable Infinite Dimensions book-to-Nether-portal travel while both mods are installed.")
            .define("disableInfinityBookPortals", true);
    // Snapshot this setting once per new save; existing saves keep their original geometry.
    public static final ModConfigSpec.DoubleValue SPACE_OBJECT_SIZE_SCALE = BUILDER
            .comment("Space object size multiplier (0.1-100), locked per new save. Legacy saves use 1. Surface skies and relay sizes stay unchanged.")
            .defineInRange("spaceObjectSizeScale", 1.0D, 0.1D, 100.0D);
    public static final ModConfigSpec.DoubleValue SPACE_OBJECT_DISTANCE_SCALE = BUILDER
            .comment("Space coordinate multiplier (0.1-100), locked per new save. Legacy saves use 1. Relays move without resizing; ring worlds and their host stars ignore this multiplier.")
            .defineInRange("spaceObjectDistanceScale", 1.0D, 0.1D, 100.0D);
    static final ModConfigSpec SPEC = BUILDER.build();

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (!SPEC.isEmpty())
            ModOptions.init(PLANET_DECORATION_DETAIL.get(), PLANET_SHADING_DETAIL.get());
        else
            ModOptions.init();

    }
}
