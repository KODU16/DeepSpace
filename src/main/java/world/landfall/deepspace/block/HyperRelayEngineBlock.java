package world.landfall.deepspace.block;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.slf4j.Logger;
import world.landfall.deepspace.server.HyperRelayJumpManager;

/**
 * Hyperspace engine. A redstone rising edge starts a hyper-relay jump, provided the block is placed
 * on a Sable sub-level whose bounding box is within {@link HyperRelayJumpManager#ACTIVATION_DISTANCE}
 * blocks of the nearest hyper relay.
 */
public class HyperRelayEngineBlock extends Block {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public HyperRelayEngineBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
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
