package world.landfall.deepspace.integration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Refines newly generated planet terrain without modifying global noise registries or existing chunks. */
final class InfinityTerrainRefinement {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double CONTINENT_FREQUENCY = 1.6;
    private static final double LAND_PATCH_FREQUENCY = 2.0;

    private InfinityTerrainRefinement() {
    }

    static boolean apply(MinecraftServer server, Path packRoot,
                      InfinityBiomeGenerationRules.GenerationPlan plan, Object dimension) throws IOException {
        if (plan.prescribedBiomes() != null || plan.generatedRoles().isEmpty()) {
            return true;
        }
        Path worldgen = packRoot.resolve("data/infinity/worldgen");
        Map<String, LandStyle> styles = new java.util.LinkedHashMap<>();
        Map<String, JsonObject> randomMaterials = new HashMap<>();
        for (var entry : plan.generatedRoles().entrySet()) {
            if (entry.getValue() != InfinityBiomeGenerationRules.BiomeRole.LAND) {
                continue;
            }
            String biomeName = "biome_" + entry.getKey();
            LandStyle style = styleFor(plan.landType(entry.getKey()));
            styles.put("infinity:" + biomeName, style);
            if (plan.randomLandMaterials(entry.getKey())) {
                randomMaterials.put("infinity:" + biomeName,
                        InfinityLandMaterials.surfaceRule(dimension, entry.getKey(), "infinity:" + biomeName));
            }
            Path biomePath = worldgen.resolve("biome").resolve(biomeName + ".json");
            JsonObject biome = read(biomePath);
            biome.addProperty("temperature", style.temperature());
            biome.addProperty("downfall", style.downfall());
            biome.addProperty("has_precipitation", style.downfall() > 0.0);
            biome.addProperty("temperature_modifier", "none");
            JsonObject effects = biome.has("effects") ? biome.getAsJsonObject("effects") : new JsonObject();
            effects.addProperty("grass_color", style.color());
            effects.addProperty("foliage_color", style.color());
            effects.addProperty("water_color", style.waterColor());
            effects.addProperty("grass_color_modifier", "none");
            biome.add("effects", effects);
            Files.writeString(biomePath, JSON.toJson(biome), StandardCharsets.UTF_8);
        }
        Path settingsRoot = worldgen.resolve("noise_settings");
        if (!Files.isDirectory(settingsRoot)) {
            throw new IOException("Generated planet has no noise settings: " + settingsRoot);
        }
        Map<String, JsonElement> densityCache = new HashMap<>();
        try (var files = Files.walk(settingsRoot)) {
            for (Path settingsPath : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                JsonObject settings = read(settingsPath);
                if (settings.has("noise_router")) {
                    JsonObject router = expandDensity(server, packRoot, settings.get("noise_router"), densityCache, 0)
                            .getAsJsonObject();
                    // Humidity changes faster than continents, retaining readable land patches within a 480x320 sample.
                    router.add("vegetation", climateNoise("minecraft:vegetation", LAND_PATCH_FREQUENCY));
                    settings.add("noise_router", router);
                }
                // Check actual base-height output before any planet dimension is registered or sampled for textures.
                if (InfinityTerrainValidation.hasHighPlateau(server, settings, dimension)) return false;
                JsonArray surfaceRules = new JsonArray();
                for (var entry : styles.entrySet()) {
                    surfaceRules.add(randomMaterials.containsKey(entry.getKey())
                            ? randomMaterials.get(entry.getKey()) : landSurface(entry.getKey(), entry.getValue()));
                }
                if (settings.has("surface_rule")) {
                    surfaceRules.add(settings.get("surface_rule"));
                }
                JsonObject sequence = new JsonObject();
                sequence.addProperty("type", "minecraft:sequence");
                sequence.add("sequence", surfaceRules);
                settings.add("surface_rule", sequence);
                Files.writeString(settingsPath, JSON.toJson(settings), StandardCharsets.UTF_8);
            }
        }
        return true;
    }

    /** Inline density references so the same finer continental signal drives both terrain and biome selection. */
    private static JsonElement expandDensity(MinecraftServer server, Path packRoot, JsonElement value,
                                             Map<String, JsonElement> cache, int depth) throws IOException {
        if (depth > 128) {
            throw new IOException("Cyclic or excessively nested density function");
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String name = value.getAsString();
            if (cache.containsKey(name)) {
                return cache.get(name).deepCopy();
            }
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id == null) {
                return value.deepCopy();
            }
            String relative = "worldgen/density_function/" + id.getPath() + ".json";
            Path local = packRoot.resolve("data").resolve(id.getNamespace()).resolve(relative);
            JsonElement definition;
            if (Files.isRegularFile(local)) {
                definition = JsonParser.parseString(Files.readString(local, StandardCharsets.UTF_8));
            } else {
                var resource = server.getResourceManager().getResource(
                        ResourceLocation.fromNamespaceAndPath(id.getNamespace(), relative));
                if (resource.isEmpty()) {
                    return value.deepCopy();
                }
                try (var reader = resource.get().openAsReader()) {
                    definition = JsonParser.parseReader(reader);
                }
            }
            JsonElement expanded = expandDensity(server, packRoot, definition, cache, depth + 1);
            cache.put(name, expanded);
            return expanded.deepCopy();
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) {
                result.add(expandDensity(server, packRoot, child, cache, depth + 1));
            }
            return result;
        }
        if (!value.isJsonObject()) {
            return value.deepCopy();
        }
        JsonObject result = new JsonObject();
        for (var entry : value.getAsJsonObject().entrySet()) {
            // Noise IDs and function type IDs are not density-function references.
            result.add(entry.getKey(), entry.getKey().equals("noise") || entry.getKey().equals("type")
                    ? entry.getValue().deepCopy()
                    : expandDensity(server, packRoot, entry.getValue(), cache, depth + 1));
        }
        if (result.has("noise") && result.get("noise").isJsonPrimitive() && result.has("xz_scale")) {
            String noise = result.get("noise").getAsString();
            if (noise.equals("minecraft:continentalness") || noise.equals("minecraft:continentalness_large")) {
                result.addProperty("xz_scale", result.get("xz_scale").getAsDouble() * CONTINENT_FREQUENCY);
            }
        }
        return result;
    }

    private static JsonObject climateNoise(String noise, double scale) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "minecraft:noise");
        result.addProperty("noise", noise);
        result.addProperty("xz_scale", scale);
        result.addProperty("y_scale", 0.0);
        return result;
    }

    /** Override only exposed dry land; retain Infinity ocean floors, underground rules, features and bedrock. */
    private static JsonObject landSurface(String biome, LandStyle style) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "minecraft:block");
        JsonObject state = new JsonObject();
        state.addProperty("Name", style.surface());
        block.add("result_state", state);
        JsonObject floor = JsonParser.parseString("{\"type\":\"minecraft:stone_depth\",\"offset\":0,"
                + "\"add_surface_depth\":false,\"secondary_depth_range\":0,\"surface_type\":\"floor\"}")
                .getAsJsonObject();
        JsonObject water = JsonParser.parseString("{\"type\":\"minecraft:water\",\"offset\":0,"
                + "\"surface_depth_multiplier\":0,\"add_stone_depth\":false}").getAsJsonObject();
        JsonObject biomeCondition = new JsonObject();
        biomeCondition.addProperty("type", "minecraft:biome");
        JsonArray biomes = new JsonArray();
        biomes.add(biome);
        biomeCondition.add("biome_is", biomes);
        JsonObject aboveSurface = new JsonObject();
        aboveSurface.addProperty("type", "minecraft:above_preliminary_surface");
        return condition(aboveSurface, condition(biomeCondition, condition(floor, condition(water, block))));
    }

    private static JsonObject condition(JsonObject predicate, JsonObject rule) {
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "minecraft:condition");
        condition.add("if_true", predicate);
        condition.add("then_run", rule);
        return condition;
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Each archetype has a distinct surface block, climate, vegetation tint and water tint. */
    private static LandStyle styleFor(String type) {
        return switch (type) {
            case "mountains" -> new LandStyle("minecraft:stone", 0.2, 0.3, 0x849B80, 0x476B9E);
            case "badlands" -> new LandStyle("minecraft:red_sand", 1.8, 0.0, 0xBD844F, 0x547C91);
            case "desert" -> new LandStyle("minecraft:sand", 2.0, 0.0, 0xCEBD70, 0x4A87B0);
            case "swamp" -> new LandStyle("minecraft:mud", 0.8, 0.9, 0x617B3E, 0x617B64);
            case "jungle" -> new LandStyle("minecraft:moss_block", 0.95, 1.0, 0x3DAB43, 0x369E9B);
            case "taiga" -> new LandStyle("minecraft:podzol", 0.25, 0.8, 0x63875A, 0x3F689C);
            case "tundra" -> new LandStyle("minecraft:snow_block", -0.5, 0.5, 0xA6BEB4, 0x486DB5);
            case "meadow" -> new LandStyle("minecraft:grass_block", 0.6, 0.7, 0x85BD5A, 0x409BA8);
            case "forest" -> new LandStyle("minecraft:rooted_dirt", 0.7, 0.85, 0x4A8D43, 0x397E93);
            case "plains" -> new LandStyle("minecraft:coarse_dirt", 0.85, 0.4, 0xA2AF55, 0x4D8BC4);
            default -> new LandStyle("minecraft:mycelium", 0.9, 0.65, 0x9277AE, 0x7768A4);
        };
    }

    private record LandStyle(String surface, double temperature, double downfall, int color, int waterColor) {
    }
}
