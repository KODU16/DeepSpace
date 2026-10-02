package world.landfall.deepspace.block;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.slf4j.Logger;
import world.landfall.deepspace.ModBlockEntities;
import world.landfall.deepspace.blockentity.HyperRelayEngineBlockEntity;
import world.landfall.deepspace.server.HyperRelayJumpManager;

/**
 * Hyperspace engine. A redstone rising edge starts a hyper-relay jump, provided the block is placed
 * on a Sable sub-level whose bounding box is within {@link HyperRelayJumpManager#ACTIVATION_DISTANCE}
 * blocks of the nearest hyper relay.
 */
public class HyperRelayEngineBlock extends Block implements EntityBlock {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    // Visual state follows server-confirmed range and preparation rather than redstone power.
    public static final BooleanProperty IN_RANGE = BooleanProperty.create("in_range");
    public static final BooleanProperty PREPARING = BooleanProperty.create("preparing");

    public HyperRelayEngineBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(POWERED, false)
                .setValue(IN_RANGE, false).setValue(PREPARING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED, IN_RANGE, PREPARING);
    }

    @Override
    protected void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block neighborBlock,
            BlockPos neighborPos,
            boolean movedByPiston
    ) {
        if (level.isClientSide()) {
            return;
        }
        boolean powered = state.getValue(POWERED);
        boolean hasSignal = level.hasNeighborSignal(pos);
        if (hasSignal && !powered) {
            level.setBlock(pos, state.setValue(POWERED, true), 3);
            triggerJump(level, pos);
        } else if (!hasSignal && powered) {
            level.setBlock(pos, state.setValue(POWERED, false), 3);
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HyperRelayEngineBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.HYPER_RELAY_ENGINE_BLOCK_ENTITY_TYPE.get()) {
            return null;
        }
        return (tickLevel, pos, tickState, entity) -> {
            if (entity instanceof HyperRelayEngineBlockEntity engine) engine.serverTick();
        };
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        // Enchantment glyphs mark an accepted preparation/countdown, even after the redstone pulse ends.
        if (!state.getValue(PREPARING)) return;
        level.addParticle(ParticleTypes.ENCHANT, pos.getX() + 0.5D, pos.getY() + 0.7D, pos.getZ() + 0.5D,
                (random.nextDouble() - 0.5D) * 2.0D, 0.4D + random.nextDouble(),
                (random.nextDouble() - 0.5D) * 2.0D);
    }

    private void triggerJump(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        if (subLevel == null) {
            LOGGER.info("[DEEPSPACE-JUMP] phase=ENGINE_REJECTED reason=not_on_sublevel pos={}", pos);
            return;
        }
        HyperRelayJumpManager.tryStartJump(serverLevel.getServer(), serverLevel, subLevel);
    }
}
