package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.*;
import net.minecraft.world.phys.shapes.*;

public final class CrystalMelonBlock extends Block implements SimpleWaterloggedBlock {
    public CrystalMelonBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.WATERLOGGED,false)); }
    @Override protected MapCodec<? extends Block> codec() { return simpleCodec(CrystalMelonBlock::new); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(BlockStateProperties.WATERLOGGED); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Block.box(1,0,1,15,14,15); }
    @Override protected FluidState getFluidState(BlockState state) { return state.getValue(BlockStateProperties.WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state); }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos other) {
        if (state.getValue(BlockStateProperties.WATERLOGGED)) level.scheduleTick(pos,Fluids.WATER,Fluids.WATER.getTickDelay(level));
        return super.updateShape(state,direction,neighbor,level,pos,other);
    }
    @Override public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextFloat() > .2) return;
        int corner = random.nextInt(4);
        double x = pos.getX() + ((corner == 0 || corner == 3) ? .035 : .965);
        double z = pos.getZ() + ((corner == 0 || corner == 1) ? .035 : .965);
        level.addParticle(ParticleTypes.END_ROD,x,pos.getY()+random.nextFloat()/2-.035+.5,z,0,-.02,0);
    }
}
