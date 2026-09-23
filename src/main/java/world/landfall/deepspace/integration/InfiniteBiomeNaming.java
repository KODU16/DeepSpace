package world.landfall.deepspace.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Gives generated biomes stable readable IDs derived from their terrain contents. */
final class InfiniteBiomeNaming {
    private static final List<TerrainRule> TERRAIN_RULES = List.of(
            new TerrainRule("mountains", "mountain", "peak", "windswept", "emerald"),
            new TerrainRule("badlands", "badlands", "terracotta"),
            new TerrainRule("desert", "desert", "cactus", "sand"),
            new TerrainRule("ocean", "ocean", "coral", "kelp", "seagrass"),
            new TerrainRule("swamp", "swamp", "mangrove"),
            new TerrainRule("jungle", "jungle", "bamboo"),
            new TerrainRule("taiga", "taiga", "spruce"),
            new TerrainRule("tundra", "snow", "ice", "frozen"),
            new TerrainRule("meadow", "meadow", "flower"),
            new TerrainRule("forest", "forest", "tree"),
            new TerrainRule("caverns", "cave", "cavern", "dripstone"),
            new TerrainRule("islands", "island"),
            new TerrainRule("endlands", "chorus", "the_end", "end_"),
            new TerrainRule("nether_wastes", "crimson", "warped", "nether"),
            new TerrainRule("plains", "plains", "grass")
    );

    private InfiniteBiomeNaming() {
    }

    static List<BiomeRename> renameGeneratedBiomes(
            Path packRoot,
            long seed,
            InfinityBiomeGenerationRules.GenerationPlan plan
    ) throws IOException {
        Path biomeRoot = packRoot.resolve("data").resolve("infinity").resolve("worldgen").resolve("biome");
        if (!Files.isDirectory(biomeRoot)) {
            return List.of();
        }

        List<Path> generatedBiomes;
        try (var files = Files.list(biomeRoot)) {
            generatedBiomes = files
                    .filter(path -> path.getFileName().toString().matches("biome_-?[0-9]+\\.json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        if (generatedBiomes.isEmpty()) {
            return List.of();
        }

        Random random = new Random(seed);
        Set<String> usedNames = new HashSet<>();
        Map<String, String> replacements = new HashMap<>();
        List<BiomeRename> renames = new ArrayList<>();
        for (Path biomePath : generatedBiomes) {
            String json = Files.readString(biomePath, StandardCharsets.UTF_8);
            long biomeId = Long.parseLong(
                    biomePath.getFileName().toString().replace("biome_", "").replace(".json", "")
            );
            InfinityBiomeGenerationRules.BiomeRole role = plan.generatedRoles().get(biomeId);
            // The role and land archetype were chosen before Infinity generated this file.
            String terrain = role == InfinityBiomeGenerationRules.BiomeRole.OCEAN
                    ? "ocean"
                    : role == InfinityBiomeGenerationRules.BiomeRole.LAND
                            ? plan.landType(biomeId)
                            : classifyTerrain(json);
            String generatedName;
            do {
                generatedName = PronounceableNameGenerator.generate(
                        random,
                        InfiniteGalaxyLayout.NAME_MIN_LENGTH,
                        InfiniteGalaxyLayout.NAME_MAX_LENGTH
                ).toLowerCase(Locale.ROOT) + "_" + terrain;
            } while (!usedNames.add(generatedName));

            String oldPath = biomePath.getFileName().toString().replaceFirst("\\.json$", "");
            replacements.put("infinity:" + oldPath, "infinity:" + generatedName);
            renames.add(new BiomeRename(oldPath, generatedName, terrain));
        }

        // Rewrite every generated registry reference before moving the biome definitions themselves.
        try (var files = Files.walk(packRoot)) {
            for (Path jsonPath : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                String content = Files.readString(jsonPath, StandardCharsets.UTF_8);
                String rewritten = replaceReferences(content, replacements);
                if (!content.equals(rewritten)) {
                    Files.writeString(jsonPath, rewritten, StandardCharsets.UTF_8);
                }
            }
        }
        for (int index = 0; index < generatedBiomes.size(); index++) {
            Files.move(
                    generatedBiomes.get(index),
                    biomeRoot.resolve(renames.get(index).newPath() + ".json"),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
        return List.copyOf(renames);
    }

    static String classifyTerrain(String biomeJson) {
        String normalized = biomeJson.toLowerCase(Locale.ROOT);
        for (TerrainRule rule : TERRAIN_RULES) {
            if (rule.keywords().stream().anyMatch(normalized::contains)) {
                return rule.suffix();
            }
        }
        return "wilds";
    }

    private static String replaceReferences(String content, Map<String, String> replacements) {
        String rewritten = content;
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            rewritten = rewritten.replace(replacement.getKey(), replacement.getValue());
        }
        return rewritten;
    }

    record BiomeRename(String oldPath, String newPath, String terrain) {
    }

    private record TerrainRule(String suffix, List<String> keywords) {
        private TerrainRule(String suffix, String... keywords) {
            this(suffix, List.of(keywords));
        }
    }
}
