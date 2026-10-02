package world.landfall.deepspace.blockentity;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.ModBlockEntities;
import world.landfall.deepspace.ModBlocks;
import world.landfall.deepspace.block.HyperRelayEngineBlock;
import world.landfall.deepspace.server.HyperRelayJumpManager;

/** Synchronizes engine indicators for every observer, including ships activated from a control seat. */
@EventBusSubscriber(modid = Deepspace.MODID)
public final class HyperRelayEngineBlockEntity extends BlockEntity {
    // Loaded engines must refresh even when Sable's plot is outside ordinary block-entity ticking coverage.
    private static final Set<HyperRelayEngineBlockEntity> LOADED_ENGINES =
            Collections.newSetFromMap(new WeakHashMap<>());
    private long lastFeedbackTick = Long.MIN_VALUE;

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) LOADED_ENGINES.add(this);
    }

    @Override
    public void setRemoved() {
        LOADED_ENGINES.remove(this);
        super.setRemoved();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Do not retain an engine or its last world when a server session ends.
        LOADED_ENGINES.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Copy the weak set because updating a block can replace or remove its block entity.
        for (HyperRelayEngineBlockEntity engine : new ArrayList<>(LOADED_ENGINES)) {
            if (engine.isRemoved()) {
                LOADED_ENGINES.remove(engine);
            } else if (engine.level instanceof ServerLevel serverLevel && serverLevel.getServer() == event.getServer()) {
                engine.serverTick();
            }
        }
    }

    public HyperRelayEngineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HYPER_RELAY_ENGINE_BLOCK_ENTITY_TYPE.get(), pos, state);
    }

    /** Older engines were plain blocks and have no saved block entity to start the indicator ticker. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            // Palette filtering skips every section without an engine before examining individual blocks.
            if (!section.maybeHas(state -> state.is(ModBlocks.HYPER_RELAY_ENGINE_BLOCK.get()))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(index) << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (!section.getBlockState(x, y, z).is(ModBlocks.HYPER_RELAY_ENGINE_BLOCK.get())) continue;
                BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + x, baseY + y,
                        chunk.getPos().getMinBlockZ() + z);
                if (chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK) == null) {
                    chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
                    chunk.setUnsaved(true);
                }
            }
        }
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        // A first native tick also registers plot loaders that do not invoke the usual onLoad hook.
        LOADED_ENGINES.add(this);
        // A session-wide clock keeps the refresh running independently of a dimension's tick state.
        long tick = serverLevel.getServer().getTickCount();
        if (Math.floorMod(tick + worldPosition.asLong(), 5L) != 0L || lastFeedbackTick == tick) return;
        lastFeedbackTick = tick;
        BlockState state = serverLevel.getBlockState(worldPosition);
        if (!state.is(ModBlocks.HYPER_RELAY_ENGINE_BLOCK.get())) return;
        SubLevel subLevel = Sable.HELPER.getContaining(serverLevel, worldPosition);
        boolean inRange = subLevel != null && HyperRelayJumpManager.findNearestRelayInRange(
                serverLevel.dimension(), subLevel) != null;
        boolean preparing = subLevel != null && HyperRelayJumpManager.isPreparingJump(subLevel);
        if (state.getValue(HyperRelayEngineBlock.IN_RANGE) != inRange
                || state.getValue(HyperRelayEngineBlock.PREPARING) != preparing) {
            // Only visual properties change: preserve the redstone edge and emit no world light.
            BlockState updated = state.setValue(HyperRelayEngineBlock.IN_RANGE, inRange)
                    .setValue(HyperRelayEngineBlock.PREPARING, preparing);
            if (serverLevel.setBlock(worldPosition, updated, Block.UPDATE_CLIENTS)) {
                // Keep the BE cache aligned with the live plot state for redstone and snapshot consumers.
                setBlockState(updated);
            }
        }
    }
}
