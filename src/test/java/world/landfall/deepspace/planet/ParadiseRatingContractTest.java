package world.landfall.deepspace.planet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone checks for grade boundaries and visual Overworld-likeness. */
public final class ParadiseRatingContractTest {
    private ParadiseRatingContractTest() {
    }

    public static void main(String[] args) throws IOException {
        require(ParadiseRating.grade(100) == ParadiseRating.Grade.S, "100 must be S");
        require(ParadiseRating.grade(90) == ParadiseRating.Grade.S, "90 must be S");
        require(ParadiseRating.grade(89) == ParadiseRating.Grade.A, "89 must be A");
        require(ParadiseRating.grade(80) == ParadiseRating.Grade.A, "80 must be A");
        require(ParadiseRating.grade(70) == ParadiseRating.Grade.B, "70 must be B");
        require(ParadiseRating.grade(60) == ParadiseRating.Grade.C, "60 must be C");
        require(ParadiseRating.grade(59) == ParadiseRating.Grade.D, "59 must be D");
        require(ParadiseRating.grade(49) == ParadiseRating.Grade.E, "49 must be E");
        require(ParadiseRating.Grade.S.color() == 0xFFFFB52E, "S must be orange-yellow");
        require(ParadiseRating.Grade.A.color() == 0xFFC879FF, "A must be purple");
        require(ParadiseRating.Grade.B.color() == 0xFFFFE45C, "B must be yellow");
        require(ParadiseRating.Grade.C.color() == 0xFF66D979, "C must be green");
        require(ParadiseRating.Grade.D.color() == 0xFFFF8F8F, "D must be light red");
        require(ParadiseRating.Grade.E.color() == 0xFFE53935, "E must be red");
        require(ParadiseRating.isBlue(0x69A8FF), "Sky blue must be recognized");
        require(!ParadiseRating.isBlue(0xFF7A50), "Orange must not be recognized as blue");
        require(ParadiseRating.isVegetationColor(0x5EA02E), "Globe green must count as vegetation");
        require(ParadiseRating.isWaterColor(0x3F76E4), "Deep ocean blue must count as water");
        require(ParadiseRating.isWaterColor(0x2EC8C0), "Tropical teal must count as water");
        require(!ParadiseRating.isVegetationColor(0x8A8A8A), "Stone grey is not vegetation");
        require(!ParadiseRating.isWaterColor(0x8A8A8A), "Stone grey is not water");
        require(ParadiseRating.isAlienColor(0x8B1A1A), "Nether red must be alien");
        require(ParadiseRating.isAlienColor(0xB04CFF), "Magenta must be alien");
        require(!ParadiseRating.isWaterColor(0xB04CFF), "Magenta must not count as water");

        ParadiseRating.Result primaryRing = ParadiseRating.evaluate(new ParadiseRating.Criteria(
                true, false, true, true, true, true, true, true, true
        ));
        require(primaryRing.score() == 97, "Overworld-like visual matches must score 97");
        require(primaryRing.grade() == ParadiseRating.Grade.S, "Overworld-like visual matches must be S");

        ParadiseRating.Result missingWater = ParadiseRating.evaluate(new ParadiseRating.Criteria(
                true, false, true, true, false, false, false, true, true
        ));
        ParadiseRating.Result withWater = ParadiseRating.evaluate(new ParadiseRating.Criteria(
                true, false, true, true, false, true, true, true, true
        ));
        require(withWater.score() > missingWater.score(),
                "Blue water-like globe colors must raise the score");

        ParadiseRating.PaletteShares earth = ParadiseRating.paletteShares(java.util.List.of(
                new Planet.SurfaceColor(0x5EA02E, 60),
                new Planet.SurfaceColor(0x3F76E4, 30),
                new Planet.SurfaceColor(0x8B6A3A, 10)
        ));
        require(earth.greenShare() >= 0.14F && earth.waterShare() >= 0.08F && earth.alienShare() < 0.28F,
                "Green continents plus blue oceans must look earthlike");

        ParadiseRating.PaletteShares alien = ParadiseRating.paletteShares(java.util.List.of(
                new Planet.SurfaceColor(0xB04CFF, 50),
                new Planet.SurfaceColor(0x8B1A1A, 50)
        ));
        require(alien.greenShare() == 0.0F && alien.waterShare() == 0.0F && alien.alienShare() >= 0.9F,
                "Magenta and nether palettes must not look earthlike");

        require(ParadiseRating.isNaturalInfiniteGeneratedWorld(
                        "infinity", "generated_123", "deepspace", "galaxy_0", "eden", false, false),
                "Natural Infinite generated worlds must qualify as habitats");
        require(!ParadiseRating.isNaturalInfiniteGeneratedWorld(
                        "infinity", "generated_123", "deepspace", "galaxy_0", "eden", false, true),
                "Datapack planets must not qualify as habitats");
        require(!ParadiseRating.isNaturalInfiniteGeneratedWorld(
                        "infinity", "generated_123", "deepspace", "space", "eden", false, false),
                "Primary-galaxy bodies must not qualify as habitats");
        require(!ParadiseRating.isNaturalInfiniteGeneratedWorld(
                        "infinity", "generated_123", "deepspace", "galaxy_0", "eden", true, false),
                "Ring-world edges must not qualify as habitats");
        require(!ParadiseRating.isNaturalInfiniteGeneratedWorld(
                        "minecraft", "overworld", "deepspace", "space", "overworld", false, false),
                "Overworld must not qualify as a star-bramble habitat");
        require(!ParadiseRating.isNaturalInfiniteGeneratedWorld(
                        "tropicraft", "tropics", "deepspace", "space", "tropica", false, false),
                "Tropica must not qualify as a star-bramble habitat");
        require(!ParadiseRating.supportsStarBramble(null),
                "Missing planets cannot host star bramble");

        String lush = Files.readString(Path.of("src/main/java/world/landfall/deepspace/block/LushPlantBlock.java"));
        String generation = Files.readString(Path.of("src/main/java/world/landfall/deepspace/worldgen/StarBrambleGeneration.java"));
        require(lush.contains("ParadiseRating.supportsStarBramble"),
                "Star bramble maturity must use the Infinite S-grade habitat gate");
        require(generation.contains("ParadiseRating.supportsStarBramble"),
                "Star bramble generation must use the Infinite S-grade habitat gate");
        require(!generation.contains("ParadiseRating.evaluate(planet).grade()"),
                "Star bramble generation must not treat analog S-grade worlds as habitats");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
