package world.landfall.deepspace.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import world.landfall.deepspace.Deepspace;

/**
 * Registers dimension-specific chunk generator codecs.
 */
public final class ModChunkGenerators {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, Deepspace.MODID);
    public static final DeferredHolder<
            MapCodec<? extends ChunkGenerator>,
            MapCodec<StructurelessNoiseChunkGenerator>
    > STRUCTURELESS_NOISE = CHUNK_GENERATORS.register(
            "structureless_noise",
            () -> StructurelessNoiseChunkGenerator.CODEC
    );

    private ModChunkGenerators() {
    }

    public static void register(IEventBus eventBus) {
        CHUNK_GENERATORS.register(eventBus);
    }
}
