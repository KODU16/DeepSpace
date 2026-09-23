package world.landfall.deepspace.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

/**
 * Builds a normal ore vein after choosing one ore block from a shared block tag.
 */
public final class TaggedOreFeature extends Feature<TaggedOreConfiguration> {
    private final OreFeature oreDelegate = new OreFeature(OreConfiguration.CODEC);

    public TaggedOreFeature(Codec<TaggedOreConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<TaggedOreConfiguration> context) {
        TaggedOreConfiguration config = context.config();
        Registry<Block> blocks = context.level().registryAccess().registryOrThrow(Registries.BLOCK);
        HolderSet.Named<Block> ores = blocks.getTag(config.oreTag()).orElse(null);
        if (ores == null || ores.size() == 0) {
            return false;
        }

        int chunkMinX = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(context.origin().getX()));
        int chunkMinZ = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(context.origin().getZ()));
        int minY = context.level().getMinBuildHeight();
        int buildHeight = context.level().getMaxBuildHeight() - minY;
        boolean placedAny = false;

        // Scatter all attempts here because vanilla's count placement codec is capped at 256.
        for (int attempt = 0; attempt < config.attempts(); attempt++) {
            Block ore = ores.getRandomElement(context.random()).orElseThrow().value();
            BlockPos veinOrigin = new BlockPos(
                    chunkMinX + context.random().nextInt(16),
                    minY + context.random().nextInt(buildHeight),
                    chunkMinZ + context.random().nextInt(16)
            );
            placedAny |= placeSelectedOre(context, config, ore, veinOrigin);
        }
        return placedAny;
    }

    private boolean placeSelectedOre(
            FeaturePlaceContext<TaggedOreConfiguration> context,
            TaggedOreConfiguration config,
            Block ore,
            BlockPos origin
    ) {
        OreConfiguration oreConfig = new OreConfiguration(
                new TagMatchTest(config.replaceableTag()),
                ore.defaultBlockState(),
                config.size()
        );
        FeaturePlaceContext<OreConfiguration> oreContext = new FeaturePlaceContext<>(
                context.topFeature(),
                context.level(),
                context.chunkGenerator(),
                context.random(),
                origin,
                oreConfig
        );
        return oreDelegate.place(oreContext);
    }
}
