package world.landfall.deepspace.planet;

/** Verifies generic planet naming for vanilla, Infinity, and modded biome IDs. */
public final class PlanetBiomeTypeTest {
    private PlanetBiomeTypeTest() {
    }

    public static void main(String[] arguments) {
        require("jungle", PlanetBiomeType.classify("infinity:generated_123_bamboo_jungle"));
        require("badlands", PlanetBiomeType.classify("minecraft:wooded_badlands"));
        require("ocean", PlanetBiomeType.classify("minecraft:deep_frozen_ocean"));
        require("plains", PlanetBiomeType.classify("example:lavender_grassland"));
        require("forest", PlanetBiomeType.classify("example:seasonal_forest"));
        require("wilds", PlanetBiomeType.classify("example:alien_plateau"));
    }

    private static void require(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
