package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Original diamond activation, heat inhibition, and four-stage living crystal cycle. */
public final class CrystalHuskBlock extends Block {
    public static final IntegerProperty GROWTH = IntegerProperty.create("crystal_growth_level",0,3);
    public static final IntegerProperty HEAT = HeatPropagation.HEAT;
    public CrystalHuskBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(GROWTH,0).setValue(HEAT,0)); }
    @Override protected MapCodec<? extends Block> codec() { return simpleCodec(CrystalHuskBlock::new); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(GROWTH,HEAT); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(HEAT,HeatPropagation.neighborHeat(context.getLevel(),context.getClickedPos())); }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.is(Items.DIAMOND) && state.getValue(GROWTH) == 0) {
            stack.consume(1,player);
            level.setBlock(pos,state.setValue(GROWTH,1),2);
            growthSounds(level,pos);
            level.scheduleTick(pos,this,1);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    static void growthSounds(Level level, BlockPos pos) {
        level.playSound(null,pos,SoundEvents.AMETHYST_BLOCK_CHIME,SoundSource.BLOCKS,1,1);
        level.playSound(null,pos,SoundEvents.TURTLE_EGG_CRACK,SoundSource.BLOCKS,1,1);
    }
    @Override protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        state = level.getBlockState(pos);
        if (state.getValue(HEAT) > 0) return;
        int growth = state.getValue(GROWTH);
        if (growth > 0 && growth < 3) {
            level.setBlock(pos,state.setValue(GROWTH,growth+1),3);
            growthSounds(level,pos);
            level.scheduleTick(pos,this,20);
        } else if (growth == 3) {
            level.setBlockAndUpdate(pos,WorldgenBlocks.get("living_crystal").defaultBlockState());
            level.playSound(null,pos,SoundEvents.DEEPSLATE_PLACE,SoundSource.BLOCKS,1,1);
        }
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos other) {
        if (!level.isClientSide()) level.scheduleTick(pos,this,10);
        return super.updateShape(state,direction,neighbor,level,pos,other);
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) { HeatPropagation.update(level,pos,state); }
}
