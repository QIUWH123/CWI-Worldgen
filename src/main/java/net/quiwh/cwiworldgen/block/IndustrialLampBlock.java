package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.shapes.*;

public final class IndustrialLampBlock extends FaceAttachedHorizontalDirectionalBlock {
    public IndustrialLampBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACE,AttachFace.WALL).setValue(FACING,Direction.NORTH)); }
    @Override protected MapCodec<? extends FaceAttachedHorizontalDirectionalBlock> codec() { return simpleCodec(IndustrialLampBlock::new); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACE,FACING); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(FACE) == AttachFace.FLOOR) return Shapes.or(Block.box(3,0,3,13,2,13),Block.box(4,2,4,12,8,12));
        if (state.getValue(FACE) == AttachFace.CEILING) return Shapes.or(Block.box(3,14,3,13,16,13),Block.box(4,8,4,12,14,12));
        return switch (state.getValue(FACING)) {
            case NORTH -> Shapes.or(Block.box(3,3,14,13,13,16),Block.box(4,4,8,12,12,14));
            case SOUTH -> Shapes.or(Block.box(3,3,0,13,13,2),Block.box(4,4,2,12,12,8));
            case EAST -> Shapes.or(Block.box(0,3,3,2,13,13),Block.box(2,4,4,8,12,12));
            default -> Shapes.or(Block.box(14,3,3,16,13,13),Block.box(8,4,4,14,12,12));
        };
    }
}
