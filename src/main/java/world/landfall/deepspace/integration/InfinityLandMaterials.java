package world.landfall.deepspace.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.io.IOException;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** Selects coherent per-biome layers from Infinity's worldgen block pool without advancing its terrain RNG. */
final class InfinityLandMaterials {
    private static final Set<String> FIXED_TERRAIN = Set.of(
            "minecraft:grass_block", "minecraft:stone", "minecraft:dirt", "minecraft:coarse_dirt",
            "minecraft:rooted_dirt", "minecraft:podzol", "minecraft:mycelium", "minecraft:mud",
            "minecraft:moss_block", "minecraft:sand", "minecraft:red_sand", "minecraft:snow_block",
            "minecraft:deepslate", "minecraft:netherrack", "minecraft:end_stone", "minecraft:bedrock",
            "minecraft:gravel");

    private InfinityLandMaterials() { }

    static JsonObject surfaceRule(Object dimension, long biomeId, String biome) throws IOException {
        try {
            Object provider = dimension.getClass().getField("PROVIDER").get(dimension);
            Class<?> configType = InfiniteDimensionsApi.loadClass("util.core.ConfigType");
            Object pool = configType.getField("FULL_BLOCKS_WG").get(null);
            var draw = provider.getClass().getMethod("randomName", Random.class, configType);
            Random random = new Random(biomeId ^ 0x4453504C41594552L);
            Set<String> used = new HashSet<>();
            JsonObject[] layers = new JsonObject[3];
            for (int layer = 0; layer < layers.length; layer++) {
                for (int attempt = 0; attempt < 1024 && layers[layer] == null; attempt++) {
                    String name = (String) draw.invoke(provider, random, pool);
                    ResourceLocation id = ResourceLocation.tryParse(name);
                    if (id == null || FIXED_TERRAIN.contains(name) || used.contains(name)
                            || !BuiltInRegistries.BLOCK.containsKey(id)) continue;
                    BlockState state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
                    if (state.isAir() || !state.getFluidState().isEmpty()) continue;
                    if (state.hasProperty(BlockStateProperties.PERSISTENT)) {
                        state = state.setValue(BlockStateProperties.PERSISTENT, true);
                    }
                    layers[layer] = BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().getAsJsonObject();
                    used.add(name);
                }
                // Do not silently restore grass/stone if a custom provider has no usable material pool.
                if (layers[layer] == null) throw new IOException("Infinity worldgen pool lacks distinct random land materials");
            }
            return layeredRule(biome, layers[0], layers[1], layers[2]);
        } catch (ReflectiveOperationException exception) {
            throw new IOException("Could not obtain Infinity worldgen block materials", exception);
        }
    }

    /** Apply all solid terrain layers, including cliffs and caves, before any fixed-material fallback. */
    static JsonObject layeredRule(String biome, JsonObject top, JsonObject filler, JsonObject underground) {
        JsonArray layers = new JsonArray();
        layers.add(condition(depth(0), block(top)));
        layers.add(condition(depth(3), block(filler)));
        layers.add(block(underground));
        JsonObject sequence = new JsonObject();
        sequence.addProperty("type", "minecraft:sequence");
        sequence.add("sequence", layers);
        JsonObject selector = new JsonObject();
        selector.addProperty("type", "minecraft:biome");
        JsonArray biomes = new JsonArray();
        biomes.add(biome);
        selector.add("biome_is", biomes);
        return condition(selector, sequence);
    }

    private static JsonObject depth(int offset) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "minecraft:stone_depth");
        result.addProperty("offset", offset);
        result.addProperty("add_surface_depth", false);
        result.addProperty("secondary_depth_range", 0);
        result.addProperty("surface_type", "floor");
        return result;
    }

    private static JsonObject block(JsonObject state) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "minecraft:block");
        result.add("result_state", state.deepCopy());
        return result;
    }

    private static JsonObject condition(JsonObject test, JsonObject rule) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "minecraft:condition");
        result.add("if_true", test);
        result.add("then_run", rule);
        return result;
    }
}
