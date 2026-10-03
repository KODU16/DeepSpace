package world.landfall.deepspace.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import world.landfall.deepspace.ModBlockEntities;
import world.landfall.deepspace.ModItems;
import world.landfall.deepspace.blockentity.OxygenatorBlockEntity;

/** Standalone redstone oxygenator retaining the existing axis and waterlogged save properties. */
public class OxygenatorBlock extends Block implements EntityBlock, SimpleWaterloggedBlock {
    private static final VoxelShape SHAPE = Block.box(1, 1, 1, 15, 15, 15);

    public OxygenatorBlock() {
        super(Properties.of().noOcclusion().strength(2));
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
                .setValue(BlockStateProperties.WATERLOGGED, false));
    }

    @Override
    protected java.util.List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return java.util.List.of(ModItems.OXYGENATOR_BLOCK_ITEM.toStack(1));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OxygenatorBlockEntity(pos, state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.AXIS, BlockStateProperties.WATERLOGGED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(BlockStateProperties.AXIS, context.getNearestLookingDirection().getAxis())
                .setValue(BlockStateProperties.WATERLOGGED, context.getLevel().getFluidState(context.getClickedPos())
                        .getType() == Fluids.WATER);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(BlockStateProperties.WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(BlockStateProperties.WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // Use vanilla block-entity ticking instead of Create's kinetic ticking hooks.
        return !level.isClientSide && type == ModBlockEntities.OXYGENATOR_BLOCK_ENTITY_TYPE.get()
                ? (world, pos, blockState, entity) -> OxygenatorBlockEntity.tick(world, pos, blockState,
                        (OxygenatorBlockEntity) entity) : null;
    }
}
