package world.landfall.deepspace.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone checks for the pre-generation biome composition policy. */
public final class InfinityBiomeGenerationRulesContractTest {
    private InfinityBiomeGenerationRulesContractTest() {
    }

    public static void main(String[] args) throws IOException {
        int oceanOnly = 0;
        int oceanDominant = 0;
        int landOnly = 0;
        int mixed = 0;
        for (long seed = 0; seed < 100_000; seed++) {
            BiomeCompositionPolicy.Composition plan = BiomeCompositionPolicy.fromSeed(seed);
            require(plan.hasLand() || plan.hasOcean(), "A planet must have at least one biome role");
            // Land archetypes must be deterministic and pairwise distinct for every generated planet.
            if (plan.hasLand()) {
                var types = BiomeCompositionPolicy.landTypes(plan.landType());
                require(types.size() == 3 && types.stream().distinct().count() == 3,
                        "Land planets must supply three different archetypes");
                require(types.equals(BiomeCompositionPolicy.landTypes(plan.landType())),
                        "Land archetypes must remain stable across generation and naming");
            }
            if (!plan.hasLand()) {
                oceanOnly++;
                require(plan.landPercent() == 0, "Ocean-only planets must have zero land");
            } else if (!plan.hasOcean()) {
                landOnly++;
                require(plan.landPercent() == 100, "Land-only planets must have full land");
            } else {
                mixed++;
                if (plan.landPercent() < 50) oceanDominant++;
                require((plan.landPercent() >= 10 && plan.landPercent() < 50)
                                || (plan.landPercent() >= 65 && plan.landPercent() <= 90),
                        "Mixed planet ratio must preserve both biome roles");
            }
        }
        // Ocean-majority compositions should remain rare across many deterministic seeds.
        require(inRange(oceanOnly, 700, 1_300), "No-land probability drifted from 1%");
        require(inRange(oceanDominant, 3_500, 4_500), "Mixed ocean-majority probability drifted from 4%");
        require(inRange(landOnly, 19_000, 21_000), "No-ocean probability drifted from 20%");
        require(inRange(mixed, 78_000, 82_000), "Mixed probability drifted from 79%");

        require("minecraft:overworld".equals(InfinityGeneratorPolicy.resolveNoiseSettings(
                        true,
                        () -> { throw new AssertionError("Prescribed rings must not generate random noise settings"); }
                )),
                "Primary ring habitats must use Overworld terrain, water, and surface rules");
        require("infinity:random".equals(InfinityGeneratorPolicy.resolveNoiseSettings(
                        false, () -> "infinity:random"
                )),
                "Random planets must retain Infinity's generated noise settings");

        String spaceDimension = Files.readString(Path.of(
                "src/main/resources/data/deepspace/dimension/space.json"
        ));
        require(spaceDimension.contains("\"biome\": \"deepspace:space\""),
                "The galaxy-space stem must use the Deep Space biome");
        String integration = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/integration/InfiniteDimensionsIntegration.java"
        ));
        require(integration.contains("LevelStem spaceStem")
                        && integration.contains("Deepspace.path(\"space\")"),
                "Runtime galaxies must be registered from the Deep Space level stem");
    }

    private static boolean inRange(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
