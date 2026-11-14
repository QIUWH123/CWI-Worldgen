package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.shapes.*;

public final class MagmaPadBlock extends HorizontalDirectionalBlock {
    public MagmaPadBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return simpleCodec(MagmaPadBlock::new); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACING); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Block.box(1,-2,1,15,-1,15); }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) { var fluid = level.getFluidState(pos.below()); return fluid.is(FluidTags.LAVA) && fluid.isSource() && level.getFluidState(pos).isEmpty(); }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos other) { return state.canSurvive(level, pos) ? state : Blocks.AIR.defaultBlockState(); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection()); }
    @Override public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) { if (!entity.isSteppingCarefully() && entity instanceof LivingEntity) entity.hurt(level.damageSources().hotFloor(), 1); super.stepOn(level,pos,state,entity); }
    @Override protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        // The recessed collision surface dispatches stepOn to the lava below.
        if (entity instanceof LivingEntity && !entity.isSteppingCarefully() && entity.onGround()
                && entity.blockPosition().equals(pos.below())) entity.hurt(level.damageSources().hotFloor(), 1);
        super.entityInside(state,level,pos,entity);
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
}
