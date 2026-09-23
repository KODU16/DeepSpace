package world.landfall.deepspace.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.stream.Stream;

/**
 * Preserves noise terrain and biome features while removing every generated structure set.
 */
public final class StructurelessNoiseChunkGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<StructurelessNoiseChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source")
                            .forGetter(StructurelessNoiseChunkGenerator::getBiomeSource),
                    NoiseGeneratorSettings.CODEC.fieldOf("settings")
                            .forGetter(StructurelessNoiseChunkGenerator::generatorSettings)
            ).apply(instance, instance.stable(StructurelessNoiseChunkGenerator::new))
    );

    public StructurelessNoiseChunkGenerator(
            BiomeSource biomeSource,
            Holder<NoiseGeneratorSettings> settings
    ) {
        super(biomeSource, settings);
    }

    @Override
    public ChunkGeneratorStructureState createState(
            HolderLookup<StructureSet> structureSetLookup,
            RandomState randomState,
            long seed
    ) {
        return ChunkGeneratorStructureState.createForFlat(
                randomState,
                seed,
                getBiomeSource(),
                Stream.empty()
        );
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }
}
