package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.*;

public final class GlowspursBlock extends HorizontalDirectionalBlock {
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public GlowspursBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(POWERED,false)); }
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return simpleCodec(GlowspursBlock::new); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACING,POWERED); }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) { return Block.canSupportCenter(level,pos.below(),Direction.UP); }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos other) { return state.canSurvive(level,pos) ? state : Blocks.AIR.defaultBlockState(); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Block.box(1,0,1,15,10,15); }
    @Override protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (entity instanceof LivingEntity) {
            entity.makeStuckInBlock(state,new Vec3(.45,1,.45));
            if (!level.isClientSide && !state.getValue(POWERED)) {
                level.playSound(null,pos,SoundEvents.PLAYER_HURT_SWEET_BERRY_BUSH,SoundSource.BLOCKS,1F,.6F);
                level.setBlock(pos,state.setValue(POWERED,true),2);
                level.scheduleTick(pos,this,40);
            }
        }
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(POWERED)) return;
        if (level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class,getShape(state,level,pos,CollisionContext.empty()).bounds().move(pos)).isEmpty()) {
            level.setBlock(pos,state.setValue(POWERED,false),2);
            level.playSound(null,pos,SoundEvents.PLAYER_HURT_SWEET_BERRY_BUSH,SoundSource.BLOCKS,.6F,.4F);
        }
        else level.scheduleTick(pos,this,40);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING,context.getHorizontalDirection().getOpposite()); }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING,rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
}
