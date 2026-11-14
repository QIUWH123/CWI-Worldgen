package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.*;
import net.minecraft.world.phys.shapes.*;

public final class GlowshroomBlock extends Block implements SimpleWaterloggedBlock, BonemealableBlock {
    public static final IntegerProperty CLUSTERS = IntegerProperty.create("clusters", 1, 3);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public GlowshroomBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(CLUSTERS, 1).setValue(WATERLOGGED, false)); }
    @Override protected MapCodec<? extends Block> codec() { return simpleCodec(GlowshroomBlock::new); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(CLUSTERS, WATERLOGGED); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(CLUSTERS)) {
            case 2 -> Block.box(3,0,3,13,9,13);
            case 3 -> Block.box(2,0,2,14,10,14);
            default -> Block.box(5,0,5,11,9,11);
        };
    }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) { return Block.canSupportCenter(level, pos.below(), Direction.UP); }
    @Override protected FluidState getFluidState(BlockState state) { return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state); }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos other) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return direction == Direction.DOWN && !state.canSurvive(level, pos) ? Blocks.AIR.defaultBlockState() : state;
    }
    @Override protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) { return context.getItemInHand().is(asItem()) && state.getValue(CLUSTERS) < 3 || super.canBeReplaced(state, context); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState old = context.getLevel().getBlockState(context.getClickedPos());
        return old.is(this) ? old.setValue(CLUSTERS, Math.min(3, old.getValue(CLUSTERS)+1)) : defaultBlockState().setValue(WATERLOGGED,
            context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER);
    }
    @Override public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state) { return state.getValue(CLUSTERS) == 1; }
    @Override public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state) { return random.nextFloat() < .4 && state.getValue(CLUSTERS) == 1; }
    @Override public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        var key = ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "huge_glowshroom_planted"));
        level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolder(key).ifPresent(feature ->
            feature.value().place(level, level.getChunkSource().getGenerator(), random, pos));
    }
}
