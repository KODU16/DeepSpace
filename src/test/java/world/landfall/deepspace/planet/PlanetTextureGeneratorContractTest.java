package world.landfall.deepspace.planet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Regression checks for the bounded per-planet terrain sampling budget. */
public final class PlanetTextureGeneratorContractTest {
    private PlanetTextureGeneratorContractTest() {
    }

    public static void main(String[] arguments) {
        assertTrue(PlanetTextureGenerator.MAX_CHUNKS_PER_PLANET_PER_TICK == 10,
                "each planet must sample at most ten chunks per server tick");
        assertTrue(Planet.TextureGenerationDetail.valueOf("FEATURES")
                        == Planet.TextureGenerationDetail.FEATURES,
                "data packs must be able to request feature-complete texture sampling");
        assertTrue(generatorSource().contains("syncPlanetToAllPlayers(planet.getId())"),
                "each completed surface map must synchronize only its changed planet");
        assertTrue(generatorSource().contains("coarseBiomeVotes.merge(chunkBiome, 1, Integer::sum)")
                        && generatorSource().contains("tier == PlanetTextureTier.FULL")
                        && generatorSource().contains("other eligible planets remain queued")
                        && generatorSource().contains("break;\n        }"),
                "planet type must use one coarse vote per chunk and materials must wait for full sampling");
        assertTrue(!generatorSource().contains("processTypeOnlyChunk")
                        && generatorSource().contains("private final boolean bundledTexture")
                        && generatorSource().contains("if (!bundledTexture)"),
                "bundled-texture planets must complete the full scan without replacing authored textures");
        assertTrue(generatorSource().contains("chunkBiomeCounts.merge(PlanetBiomeType.classify(biomeId)")
                        && generatorSource().contains("coarseBiomeVotes.merge(chunkBiome, 1, Integer::sum)")
                        && generatorSource().contains("sampleIndex < PlanetTextureTier.COARSE.chunks()")
                        && generatorSource().contains("typeVoteSamples(seedFromWorldAndPlanet")
                        && generatorSource().contains("one deterministic random chunk from each"),
                "six separated random full-scan chunks must determine the translated planet type");
        String screen = read("src/main/java/world/landfall/deepspace/client/SolarSystemScreen.java");
        assertTrue(screen.contains("getSampledFluids().stream().limit(2)")
                        && screen.contains("getSampledBlocks().stream().limit(5)")
                        && screen.contains("SurfaceScanStatus.SCANNING")
                        && screen.contains("PlanetTextureTier.FULL")
                        && screen.contains("planet.getTexture().isPresent() || planet.getGeneratedTextureTier()")
                        && screen.contains("gui.deepspace.paradise.unknown"),
                "surface details must enforce their limits and expose the scanning state");
        String cache = read("src/main/java/world/landfall/deepspace/planet/PlanetSurfaceMapCache.java");
        assertTrue(cache.contains("planet.getDimension().location()")
                        && cache.contains("#atlas3x2_v2#"),
                "surface caches must be isolated by dimension and metadata version");
        String registry = read("src/main/java/world/landfall/deepspace/planet/PlanetRegistry.java");
        assertTrue(registry.contains("\"sarrion\"")
                        && registry.contains("new Vec2(-1000, -1000),\n                new Vec2(1000, 1000),\n                List.of()"),
                "Sarrion must use procedural textures without a bundled texture resource");
    }

    /** Reads the implementation contract without requiring a running Minecraft server. */
    private static String generatorSource() {
        return read("src/main/java/world/landfall/deepspace/planet/PlanetTextureGenerator.java");
    }

    private static String read(String file) {
        Path source = Path.of(file);
        try {
            return Files.readString(source);
        } catch (IOException exception) {
            throw new AssertionError("Could not read texture generator source", exception);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
