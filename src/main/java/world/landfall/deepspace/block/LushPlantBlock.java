package world.landfall.deepspace.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import world.landfall.deepspace.ModItems;
import world.landfall.deepspace.planet.ParadiseRating;
import world.landfall.deepspace.planet.PlanetRegistry;

/** A two-block plant sharing one harvest state and server-side regrowth timer. */
public class LushPlantBlock extends DoublePlantBlock {
    public static final MapCodec<LushPlantBlock> CODEC = simpleCodec(LushPlantBlock::new);
    public static final BooleanProperty MATURE = BooleanProperty.create("mature");
    private static final int REGROW_TICKS = 72_000;

    public LushPlantBlock(BlockBehaviour.Properties properties) {
        super(properties.noCollission().instabreak().randomTicks().lightLevel(state -> state.getValue(MATURE) ? 14 : 0));
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(MATURE, true));
    }

    /** Places the plant with its habitat-appropriate maturity state immediately. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state == null || context.getLevel().isClientSide()) return state;
        return state.setValue(MATURE, isStarBrambleHabitat(context.getLevel()));
    }

    @Override
    public MapCodec<LushPlantBlock> codec() {
        return CODEC;
    }

    /** Synchronizes the upper half after double-plant placement. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!state.getValue(MATURE)) {
            BlockPos upper = pos.above();
            BlockState upperState = level.getBlockState(upper);
            if (upperState.is(this)) level.setBlock(upper, upperState.setValue(MATURE, false), Block.UPDATE_ALL);
        }
    }

    /** Allows solid full supports while rejecting fluids and incomplete blocks. */
    @Override
    protected boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) return super.canSurvive(state, level, pos);
        BlockPos support = pos.below();
        var shape = level.getBlockState(support).getCollisionShape(level, support);
        return level.getFluidState(support).isEmpty() && !shape.isEmpty()
                && shape.max(Direction.Axis.Y) >= 1.0D;
    }

    /** Exposes the same support rule to the world-generation pass. */
    public boolean canGenerateAt(net.minecraft.world.level.LevelReader level, BlockPos pos) {
        return pos.getY() < level.getMaxBuildHeight() - 1 && level.isEmptyBlock(pos.above())
                && canSurvive(defaultBlockState(), level, pos);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MATURE);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                            BlockHitResult hit) {
        // Both halves harvest through the root to prevent duplicate produce.
        BlockPos root = state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        BlockState rootState = level.getBlockState(root);
        if (!rootState.is(this) || !rootState.getValue(MATURE)) return InteractionResult.PASS;
        if (!level.isClientSide) {
            // Drop the harvest into the world like vanilla berries so other mods can observe it.
            ItemEntity drop = new ItemEntity(level, root.getX() + 0.5D, root.getY() + 0.8D, root.getZ() + 0.5D,
                    new ItemStack(ModItems.STARBULB_ITEM.get()));
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
            setMature(level, root, false);
            level.scheduleTick(root, this, REGROW_TICKS);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        // Harvested plants only regrow on natural Infinite S-grade habitats.
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER && !state.getValue(MATURE)
                && isStarBrambleHabitat(level)) {
            setMature(level, pos, true);
        }
    }

    /** Keep both halves in the same harvest state. */
    private void setMature(Level level, BlockPos root, boolean mature) {
        for (BlockPos part : new BlockPos[]{root, root.above()}) {
            BlockState partState = level.getBlockState(part);
            if (partState.is(this)) level.setBlock(part, partState.setValue(MATURE, mature), Block.UPDATE_ALL);
        }
    }

    /** Enforces S-grade habitat rules without requiring a block entity per plant. */
    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        // Only the root checks the habitat, so each plant has one withering chance.
        if (state.getValue(HALF) != DoubleBlockHalf.LOWER) return;
        boolean paradise = isStarBrambleHabitat(level);
        if (paradise) {
            if (!state.getValue(MATURE)) setMature(level, pos, true);
        } else {
            // Keep the dead lower half as a withered bush, then remove only the upper half.
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.DEAD_BUSH.defaultBlockState(), Block.UPDATE_ALL);
            BlockPos upper = pos.above();
            BlockState upperState = level.getBlockState(upper);
            if (upperState.is(this) && upperState.getValue(HALF) == DoubleBlockHalf.UPPER) {
                level.setBlock(upper, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    public ItemStack getCloneItemStack(Level level, BlockPos pos, BlockState state) {
        return new ItemStack(ModItems.STAR_BRAMBLE_ITEM.get());
    }

    /** Mature fruiting is limited to natural Infinite S-grade planets. */
    private static boolean isStarBrambleHabitat(Level level) {
        return ParadiseRating.supportsStarBramble(PlanetRegistry.getPlanetByDimension(level.dimension()));
    }
}





