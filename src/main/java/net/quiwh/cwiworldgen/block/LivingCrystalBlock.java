package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;

public final class LivingCrystalBlock extends Block {
    public LivingCrystalBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(HeatPropagation.HEAT,0)); }
    @Override protected MapCodec<? extends Block> codec() { return simpleCodec(LivingCrystalBlock::new); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(HeatPropagation.HEAT); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(HeatPropagation.HEAT,HeatPropagation.neighborHeat(context.getLevel(),context.getClickedPos())); }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos other) {
        if (!level.isClientSide()) level.scheduleTick(pos,this,10);
        return super.updateShape(state,direction,neighbor,level,pos,other);
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) { HeatPropagation.update(level,pos,state); }
    @Override protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(HeatPropagation.HEAT) > 0 || random.nextInt(5) != 0) return;
        BlockPos target = pos.relative(Direction.getRandom(random));
        BlockState current = level.getBlockState(target), next = null;
        boolean crack = false;
        if (current.is(BlockTags.DIAMOND_ORES)) {
            next = WorldgenBlocks.get("dead_living_crystal").defaultBlockState()
                .setValue(CrystalHuskBlock.GROWTH,1)
                .setValue(HeatPropagation.HEAT,HeatPropagation.neighborHeat(level,target));
            crack = true;
        } else if (current.is(Blocks.MELON)) next = WorldgenBlocks.get("stone_melon").defaultBlockState();
        else if (current.is(WorldgenBlocks.get("stone_melon"))) {
            int growth = current.getValue(StoneMelonBlock.GROWTH);
            if (growth >= 2) {
                next = WorldgenBlocks.get("crystal_melon").defaultBlockState();
                level.setBlock(pos,WorldgenBlocks.get("dead_living_crystal").defaultBlockState()
                    .setValue(HeatPropagation.HEAT,HeatPropagation.neighborHeat(level,pos)),2);
            } else next = current.setValue(StoneMelonBlock.GROWTH,growth+1);
            crack = true;
        }
        if (crack) {
            level.playSound(null,target,SoundEvents.AMETHYST_BLOCK_PLACE,SoundSource.BLOCKS,1,1);
            level.levelEvent(2001,target,Block.getId(current));
        }
        if (next != null) level.setBlockAndUpdate(target,next);
    }
}
