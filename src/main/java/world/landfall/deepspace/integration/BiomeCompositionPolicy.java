package world.landfall.deepspace.integration;

import java.util.List;
import java.util.Random;

/** Pure deterministic policy for choosing a planet's biome roles before Infinity runs. */
public final class BiomeCompositionPolicy {
    private static final List<String> LAND_TYPES = List.of(
            "mountains", "badlands", "desert", "swamp", "jungle", "taiga",
            "tundra", "meadow", "forest", "plains", "wilds"
    );

    private BiomeCompositionPolicy() {
    }

    public static Composition fromSeed(long seed) {
        Random random = new Random(seed ^ 0x44535042494F4D45L);
        int compositionRoll = random.nextInt(100);
        // Reserve five percent of random planets for ocean-dominant compositions.
        if (compositionRoll < 1) {
            return new Composition(false, true, 0, "ocean");
        }
        String landType = LAND_TYPES.get(random.nextInt(LAND_TYPES.size()));
        if (compositionRoll < 5) {
            return new Composition(true, true, random.nextInt(10, 50), landType);
        }
        if (compositionRoll < 25) {
            return new Composition(true, false, 100, landType);
        }
        return new Composition(true, true, random.nextInt(65, 91), landType);
    }

    public record Composition(boolean hasLand, boolean hasOcean, int landPercent, String landType) {
    }

    /** Spread three distinct archetypes across the palette without changing composition probabilities. */
    public static List<String> landTypes(String first) {
        int start = Math.max(0, LAND_TYPES.indexOf(first));
        return List.of(LAND_TYPES.get(start), LAND_TYPES.get((start + 4) % LAND_TYPES.size()),
                LAND_TYPES.get((start + 7) % LAND_TYPES.size()));
    }

    /** Rolls once per planet so all three land biomes use the selected material mode. */
    public static boolean randomLandMaterials(long planetSeed) {
        return new Random(planetSeed ^ 0x4453504D41544552L).nextInt(100) < 80;
    }
}
