package world.landfall.deepspace.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Six authored habitats use continuous irregular terrain with green land and blue oceans. */
public final class DachaChunkGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<DachaChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(DachaChunkGenerator::getBiomeSource),
            NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(DachaChunkGenerator::generatorSettings),
            Codec.intRange(0, 5).fieldOf("style").forGetter(generator -> generator.style)
    ).apply(instance, DachaChunkGenerator::new));
    private final int style;

    public DachaChunkGenerator(BiomeSource biomes, Holder<NoiseGeneratorSettings> settings, int style) {
        super(biomes, settings);
        if (style < 0 || style > 5) throw new IllegalArgumentException("Dacha style must be in [0, 5]");
        this.style = style;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    /** Water follows the continuous height field rather than repeating every chunk. */
    public static boolean waterAt(int style, int x, int z) {
        return terrainHeight(style, x, z) < DachaTerrain.SEA_LEVEL;
    }

    /** Distinct rolling plains, terraces, ridges, islands, wetlands and uplands share seamless coasts. */
    public static int terrainHeight(int style, int x, int z) {
        return DachaTerrain.height(style, x, z);
    }

    private BlockState blockAt(int top, int y) {
        if (y == getMinY()) return Blocks.BEDROCK.defaultBlockState();
        if (y > top) return (y <= getSeaLevel() ? Blocks.WATER : Blocks.AIR).defaultBlockState();
        if (y == top) return (top < getSeaLevel() ? Blocks.GRAVEL : Blocks.GRASS_BLOCK).defaultBlockState();
        return (y >= top - 3 ? Blocks.DIRT : Blocks.STONE).defaultBlockState();
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random,
                                                       StructureManager structures, ChunkAccess chunk) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = Math.max(getMinY(), chunk.getMinBuildHeight());
        for (int x = chunk.getPos().getMinBlockX(); x <= chunk.getPos().getMaxBlockX(); x++) {
            for (int z = chunk.getPos().getMinBlockZ(); z <= chunk.getPos().getMaxBlockZ(); z++) {
                // Compute expensive noise once per column, never once per stone block.
                int terrain = terrainHeight(style, x, z);
                int top = Math.min(chunk.getMaxBuildHeight() - 1, Math.max(getSeaLevel(), terrain));
                for (int y = minY; y <= top; y++) chunk.setBlockState(pos.set(x, y, z), blockAt(terrain, y), false);
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        int top = terrainHeight(style, x, z);
        if (type != Heightmap.Types.OCEAN_FLOOR && type != Heightmap.Types.OCEAN_FLOOR_WG) top = Math.max(top, getSeaLevel());
        return Math.clamp(top + 1, level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        BlockState[] states = new BlockState[level.getHeight()];
        int terrain = terrainHeight(style, x, z);
        for (int i = 0; i < states.length; i++) states[i] = blockAt(terrain, level.getMinBuildHeight() + i);
        return new NoiseColumn(level.getMinBuildHeight(), states);
    }

    // Surface blocks are already installed; both real chunks and isolated globe samples use them unchanged.
    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {}

    @Override
    public void buildSurface(ChunkAccess chunk, WorldGenerationContext context, RandomState random,
                             StructureManager structures, BiomeManager manager, Registry<Biome> biomes, Blender blender) {}

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager manager,
                             StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {}

    // Run vanilla biome decoration normally: trees, vegetation, lakes and ores use their configured placements.
    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
        super.applyBiomeDecoration(level, chunk, structures);
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> sets, RandomState random, long seed) {
        return ChunkGeneratorStructureState.createForFlat(random, seed, getBiomeSource(), Stream.empty());
    }
}
