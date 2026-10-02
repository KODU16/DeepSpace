package world.landfall.deepspace.planet;

import java.util.Map;

/** Checks the ocean label against combined land coverage in saved biome samples. */
public final class PlanetBiomeTypeContractTest {
    private PlanetBiomeTypeContractTest() {
    }

    public static void main(String[] args) {
        require("forest".equals(PlanetBiomeType.dominant(Map.of(
                "infinity:a_ocean", 40,
                "infinity:b_forest", 35,
                "infinity:c_jungle", 25
        ))), "Several land biomes together must beat a larger single ocean biome");
        require("ocean".equals(PlanetBiomeType.dominant(Map.of(
                "infinity:a_ocean", 51,
                "infinity:b_forest", 49
        ))), "An actual ocean majority must keep the ocean label");
        require("plains".equals(PlanetBiomeType.dominant(Map.of(
                "infinity:a_ocean", 50,
                "infinity:b_plains", 50
        ))), "An exact land-ocean tie must not be ocean-dominant");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
