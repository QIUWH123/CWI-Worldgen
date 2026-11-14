package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.*;
import net.minecraft.world.phys.shapes.*;

public final class ReinforcedRopeBlock extends Block implements SimpleWaterloggedBlock {
    public enum Part implements StringRepresentable { TOP, MIDDLE, BOTTOM; public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); } }
    public static final EnumProperty<Part> PART = EnumProperty.create("part",Part.class);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public ReinforcedRopeBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(PART,Part.MIDDLE).setValue(WATERLOGGED,false)); }
    @Override protected MapCodec<? extends Block> codec() { return simpleCodec(ReinforcedRopeBlock::new); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(PART,WATERLOGGED); }
    @Override public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) { return true; }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) { return Block.canSupportCenter(level,pos.above(),Direction.DOWN) || level.getBlockState(pos.above()).is(this); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return updatePart(defaultBlockState().setValue(WATERLOGGED,context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER),context.getLevel(),context.getClickedPos()); }
    private BlockState updatePart(BlockState state, LevelReader level, BlockPos pos) {
        return state.setValue(PART,!level.getBlockState(pos.below()).is(this) ? Part.BOTTOM : !level.getBlockState(pos.above()).is(this) && level.getBlockState(pos.above()).isFaceSturdy(level,pos.above(),Direction.DOWN) ? Part.TOP : Part.MIDDLE);
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos other) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos,Fluids.WATER,Fluids.WATER.getTickDelay(level));
        return state.canSurvive(level,pos) ? updatePart(state,level,pos) : Blocks.AIR.defaultBlockState();
    }
    @Override protected FluidState getFluidState(BlockState state) { return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(PART) == Part.BOTTOM ? Block.box(6,8,6,10,16,10) : state.getValue(PART) == Part.TOP
            ? Shapes.or(Block.box(6,0,6,10,15,10),Block.box(7,12,3,9,16,5),Block.box(7,12,11,9,16,13),Block.box(7,12,5,9,14,11)) : Block.box(6,0,6,10,16,10);
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Block.box(7.5,state.getValue(PART) == Part.BOTTOM ? 8 : 0,7.5,8.5,16,8.5); }
}
