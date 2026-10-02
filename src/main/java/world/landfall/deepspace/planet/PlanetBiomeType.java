package world.landfall.deepspace.planet;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Collapses concrete vanilla and modded biome IDs into translated planet-name categories. */
public final class PlanetBiomeType {
    private PlanetBiomeType() {
    }

    public static String classify(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return "";
        }
        String path = rawId.toLowerCase(Locale.ROOT);
        int namespaceSeparator = path.indexOf(':');
        if (namespaceSeparator >= 0) {
            path = path.substring(namespaceSeparator + 1);
        }

        // Specific families precede shared words such as forest and frozen.
        if (path.contains("ocean") || containsToken(path, "sea")) return "ocean";
        if (containsAny(path, "badlands", "mesa")) return "badlands";
        if (containsAny(path, "nether_wastes", "crimson_forest", "warped_forest",
                "soul_sand_valley", "basalt_deltas")) return "nether_wastes";
        if (containsAny(path, "end_highlands", "end_midlands", "end_barrens",
                "small_end_islands", "the_end", "endlands")) return "endlands";
        if (containsAny(path, "jungle", "rainforest", "rain_forest", "tropical_forest")) return "jungle";
        if (containsAny(path, "swamp", "marsh", "wetland", "mangrove")) return "swamp";
        if (containsAny(path, "desert", "dune", "xeric")) return "desert";
        if (containsAny(path, "taiga", "boreal")) return "taiga";
        if (containsAny(path, "tundra", "ice_spikes", "snowy_plains", "snowfield", "glacier")) return "tundra";
        if (containsAny(path, "cave", "cavern", "deep_dark")) return "caverns";
        if (containsAny(path, "mushroom_fields", "island", "archipelago", "beach", "shore")) return "islands";
        if (containsAny(path, "mountain", "peak", "slope", "windswept_hills", "highland")) return "mountains";
        if (containsAny(path, "meadow")) return "meadow";
        if (containsAny(path, "forest", "woodland", "woods", "grove")) return "forest";
        if (containsAny(path, "plains", "plain", "savanna", "grassland", "steppe", "field")) return "plains";
        return "wilds";
    }

    /** Ocean wins only when its sampled area exceeds all land categories combined. */
    public static String dominant(Map<String, Integer> biomeCounts) {
        Map<String, Long> categories = new TreeMap<>();
        biomeCounts.forEach((id, count) -> {
            if (count != null && count > 0) {
                categories.merge(classify(id), count.longValue(), Long::sum);
            }
        });
        long ocean = categories.getOrDefault("ocean", 0L);
        long land = categories.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("ocean") && !entry.getKey().isEmpty())
                .mapToLong(Map.Entry::getValue)
                .sum();
        if (ocean > land) return "ocean";
        return categories.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("ocean") && !entry.getKey().isEmpty())
                .max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(ocean > 0 ? "ocean" : "");
    }

    private static boolean containsAny(String path, String... markers) {
        for (String marker : markers) {
            if (path.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsToken(String path, String token) {
        for (String part : path.split("[^a-z0-9]+")) {
            if (part.equals(token)) {
                return true;
            }
        }
        return false;
    }
}
