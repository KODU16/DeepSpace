package world.landfall.deepspace.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * Selects ore blocks and their valid host blocks through tags so modded ores can participate.
 */
public record TaggedOreConfiguration(
        TagKey<Block> oreTag,
        TagKey<Block> replaceableTag,
        int size,
        int attempts
) implements FeatureConfiguration {
    public static final Codec<TaggedOreConfiguration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            TagKey.codec(Registries.BLOCK).fieldOf("ore_tag").forGetter(TaggedOreConfiguration::oreTag),
            TagKey.codec(Registries.BLOCK).fieldOf("replaceable_tag").forGetter(TaggedOreConfiguration::replaceableTag),
            Codec.intRange(1, 64).fieldOf("size").forGetter(TaggedOreConfiguration::size),
            Codec.intRange(1, 4096).fieldOf("attempts").forGetter(TaggedOreConfiguration::attempts)
    ).apply(instance, TaggedOreConfiguration::new));
}
