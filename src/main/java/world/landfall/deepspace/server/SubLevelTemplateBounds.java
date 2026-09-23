package world.landfall.deepspace.server;

import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.List;

/** Restricts a Dimensional Sable snapshot to the active plot bounds used by blueprint exports. */
final class SubLevelTemplateBounds {
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY,
            BlockState.CODEC,
            PalettedContainer.Strategy.SECTION_STATES,
            Blocks.AIR.defaultBlockState()
    );

    private SubLevelTemplateBounds() {
    }

    static TrimResult trimToActiveBounds(CompoundTag template, ServerLevelPlot sourcePlot, ServerLevel sourceLevel) {
        BoundingBox3ic bounds = sourcePlot.getBoundingBox();
        if (bounds == BoundingBox3i.EMPTY || bounds.volume() <= 0) {
            throw new IllegalStateException("Cannot transfer a Sable plot without active block bounds");
        }

        int kept = 0;
        int removed = 0;
        CompoundTag chunks = template.getCompound("chunks");
        for (String chunkKey : List.copyOf(chunks.getAllKeys())) {
            long packedChunk = Long.parseLong(chunkKey);
            ChunkPos localChunk = new ChunkPos(ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk));
            ChunkPos globalChunk = sourcePlot.toGlobal(localChunk);
            CompoundTag sections = chunks.getCompound(chunkKey).getCompound("sections");
            for (String sectionKey : List.copyOf(sections.getAllKeys())) {
                int sectionIndex = Integer.parseInt(sectionKey);
                int sectionMinY = sourceLevel.getSectionYFromSectionIndex(sectionIndex) << 4;
                CompoundTag section = sections.getCompound(sectionKey);
                PalettedContainer<BlockState> states = BLOCK_STATE_CODEC.parse(
                        NbtOps.INSTANCE,
                        section.getCompound("block_states")
                ).getOrThrow();
                boolean hasRetainedBlock = false;
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState state = states.get(x, y, z);
                            if (state.isAir()) {
                                continue;
                            }
                            int globalX = globalChunk.getMinBlockX() + x;
                            int globalY = sectionMinY + y;
                            int globalZ = globalChunk.getMinBlockZ() + z;
                            if (bounds.contains(globalX, globalY, globalZ)) {
                                kept++;
                                hasRetainedBlock = true;
                            } else {
                                states.set(x, y, z, Blocks.AIR.defaultBlockState());
                                removed++;
                            }
                        }
                    }
                }
                if (!hasRetainedBlock) {
                    sections.remove(sectionKey);
                    continue;
                }
                Tag encoded = BLOCK_STATE_CODEC.encodeStart(NbtOps.INSTANCE, states).getOrThrow();
                section.put("block_states", encoded);
            }
        }
        return new TrimResult(kept, removed, bounds.minY(), bounds.maxY());
    }

    record TrimResult(int keptBlocks, int removedBlocks, int minY, int maxY) {
    }
}
