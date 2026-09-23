package world.landfall.deepspace.integration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Supplies DeepSpace biome constraints while Infinity is constructing a dimension. */
public final class InfinityBiomeGenerationRules {
    private static final ThreadLocal<GenerationPlan> ACTIVE_PLAN = new ThreadLocal<>();
    private InfinityBiomeGenerationRules() {
    }

    /** Creates a deterministic composition before Infinity starts generating biome data. */
    public static GenerationPlan randomPlan(long seed) {
        BiomeCompositionPolicy.Composition composition = BiomeCompositionPolicy.fromSeed(seed);
        return GenerationPlan.random(
                composition.hasLand(), composition.hasOcean(), composition.landPercent(), composition.landType(),
                BiomeCompositionPolicy.randomLandMaterials(seed)
        );
    }

    /** Uses Infinity's generation entry point for the fixed ring-origin recipe as well. */
    public static GenerationPlan prescribedPlan(String firstBiome, String secondBiome) {
        return GenerationPlan.prescribed(firstBiome, secondBiome);
    }

    public static Scope activate(GenerationPlan plan) {
        if (ACTIVE_PLAN.get() != null) {
            throw new IllegalStateException("Nested Infinity biome generation plans are not supported");
        }
        ACTIVE_PLAN.set(plan);
        return () -> ACTIVE_PLAN.remove();
    }

    public static GenerationPlan activePlan() {
        return ACTIVE_PLAN.get();
    }

    /** Builds the biome-source NBT returned directly to Infinity's generator construction. */
    public static CompoundTag createBiomeSource(GenerationPlan plan, Random infinityRandom, List<Long> generatedIds) {
        if (plan.prescribedBiomes() != null) {
            CompoundTag source = new CompoundTag();
            source.putString("type", "minecraft:checkerboard");
            ListTag biomes = new ListTag();
            biomes.add(StringTag.valueOf(plan.prescribedBiomes().get(0)));
            biomes.add(StringTag.valueOf(plan.prescribedBiomes().get(1)));
            source.put("biomes", biomes);
            source.putInt("scale", 6);
            return source;
        }

        // Land uses three independent Infinity biome definitions, not three aliases of one biome.
        List<String> landBiomes = new ArrayList<>();
        if (plan.hasLand()) {
            for (int index = 0; index < 3; index++) {
                landBiomes.add(createGeneratedBiome(plan, BiomeRole.LAND, infinityRandom, generatedIds));
            }
        }
        String oceanBiome = plan.hasOcean() ? createGeneratedBiome(plan, BiomeRole.OCEAN, infinityRandom, generatedIds) : null;
        if (landBiomes.isEmpty()) {
            CompoundTag source = new CompoundTag();
            source.putString("type", "minecraft:fixed");
            source.putString("biome", oceanBiome);
            return source;
        }

        CompoundTag source = new CompoundTag();
        source.putString("type", "minecraft:multi_noise");
        ListTag biomes = new ListTag();
        double boundary = 1.0 - plan.landPercent() / 50.0;
        if (oceanBiome != null) {
            biomes.add(multiNoiseEntry(oceanBiome, -1.0, boundary));
        }
        // Humidity partitions create coherent patches; terrain continentalness still selects sea versus land.
        double[] humidityBounds = {-1.0, -0.18, 0.18, 1.0};
        for (int index = 0; index < landBiomes.size(); index++) {
            CompoundTag entry = multiNoiseEntry(landBiomes.get(index), boundary, 1.0);
            entry.getCompound("parameters").put("humidity", range(humidityBounds[index], humidityBounds[index + 1]));
            biomes.add(entry);
        }
        source.put("biomes", biomes);
        return source;
    }

    private static String createGeneratedBiome(
            GenerationPlan plan,
            BiomeRole role,
            Random random,
            List<Long> generatedIds
    ) {
        long id;
        do {
            id = random.nextLong();
        } while (generatedIds.contains(id));
        generatedIds.add(id);
        plan.recordRole(id, role);
        return "infinity:biome_" + id;
    }

    private static CompoundTag multiNoiseEntry(String biome, double continentalMin, double continentalMax) {
        CompoundTag entry = new CompoundTag();
        entry.putString("biome", biome);
        CompoundTag parameters = new CompoundTag();
        parameters.put("temperature", range(-1.0, 1.0));
        parameters.put("humidity", range(-1.0, 1.0));
        parameters.put("continentalness", range(continentalMin, continentalMax));
        parameters.put("erosion", range(-1.0, 1.0));
        parameters.putDouble("weirdness", 0.0);
        parameters.putDouble("depth", 0.0);
        parameters.putDouble("offset", 0.0);
        entry.put("parameters", parameters);
        return entry;
    }

    private static ListTag range(double min, double max) {
        ListTag range = new ListTag();
        range.add(DoubleTag.valueOf(min));
        range.add(DoubleTag.valueOf(max));
        return range;
    }

    public enum BiomeRole {
        LAND,
        OCEAN
    }

    public static final class GenerationPlan {
        private final boolean hasLand;
        private final boolean hasOcean;
        private final int landPercent;
        private final String landType;
        private final boolean randomLandMaterials;
        private final List<String> prescribedBiomes;
        private final Map<Long, BiomeRole> generatedRoles = new LinkedHashMap<>();
        private final Map<Long, String> generatedLandTypes = new LinkedHashMap<>();

        private GenerationPlan(
                boolean hasLand,
                boolean hasOcean,
                int landPercent,
                String landType,
                boolean randomLandMaterials,
                List<String> prescribedBiomes
        ) {
            this.hasLand = hasLand;
            this.hasOcean = hasOcean;
            this.landPercent = landPercent;
            this.landType = landType;
            this.randomLandMaterials = randomLandMaterials;
            this.prescribedBiomes = prescribedBiomes;
        }

        private static GenerationPlan random(boolean land, boolean ocean, int landPercent, String landType,
                                             boolean randomLandMaterials) {
            return new GenerationPlan(land, ocean, landPercent, landType, randomLandMaterials, null);
        }

        private static GenerationPlan prescribed(String first, String second) {
            return new GenerationPlan(false, false, 50, "", false, List.of(first, second));
        }

        private void recordRole(long id, BiomeRole role) {
            generatedRoles.put(id, role);
            if (role == BiomeRole.LAND) {
                generatedLandTypes.put(id, BiomeCompositionPolicy.landTypes(landType).get(generatedLandTypes.size()));
            }
        }

        public boolean hasLand() {
            return hasLand;
        }

        public boolean hasOcean() {
            return hasOcean;
        }

        public int landPercent() {
            return landPercent;
        }

        public String landType() {
            return landType;
        }

        /** True when Infinity replaced Overworld-like ground with alien materials. */
        public boolean usesRandomLandMaterials() {
            return randomLandMaterials;
        }

        /** Returns the actual archetype assigned before this biome was generated. */
        public String landType(long biomeId) {
            return generatedLandTypes.getOrDefault(biomeId, landType);
        }

        /** Assigned before generation so naming and material refinement agree on the same biome identities. */
        public boolean randomLandMaterials(long biomeId) {
            return generatedRoles.get(biomeId) == BiomeRole.LAND
                    && randomLandMaterials;
        }

        public List<String> prescribedBiomes() {
            return prescribedBiomes;
        }

        public Map<Long, BiomeRole> generatedRoles() {
            return Map.copyOf(generatedRoles);
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
