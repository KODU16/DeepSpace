package world.landfall.deepspace.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.ModBlocks;
import world.landfall.deepspace.planet.ParadiseRating;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;

/** Applies the same sparse star-bramble generation to eligible Infinite and Dacha S-grade planets. */
@EventBusSubscriber(modid = Deepspace.MODID)
public final class StarBrambleGeneration {
    private StarBrambleGeneration() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Planet planet = PlanetRegistry.getPlanetByDimension(level.dimension());
        if (!ParadiseRating.supportsStarBramble(planet)) return;

        ChunkPos chunk = event.getChunk().getPos();
        RandomSource random = RandomSource.create(chunk.toLong() ^ level.getSeed());
        long seed = chunk.toLong() ^ level.getSeed();
        if (random.nextInt(10_000) != 0) return;

        int centerX = chunk.getMinBlockX() + random.nextInt(16);
        int centerZ = chunk.getMinBlockZ() + random.nextInt(16);
        int count = 20 + random.nextInt(11);
        for (int index = 0; index < count; index++) {
            int x = centerX + random.nextInt(11) - 5;
            int z = centerZ + random.nextInt(11) - 5;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos position = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(position);
            if (!state.isAir() || !ModBlocks.STAR_BRAMBLE.get().canGenerateAt(level, position)) continue;
            // Place both halves, matching vanilla tall-flower generation.
            net.minecraft.world.level.block.DoublePlantBlock.placeAt(
                    level, ModBlocks.STAR_BRAMBLE.get().defaultBlockState(), position, 2);
        }
    }
}
