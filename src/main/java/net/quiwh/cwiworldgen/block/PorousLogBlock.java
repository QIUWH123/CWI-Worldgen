package net.quiwh.cwiworldgen.block;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

public final class PorousLogBlock extends RotatedPillarBlock {
    public PorousLogBlock(Properties properties) { super(properties); }
    @Override public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextInt(17) != 0) return;
        Direction direction = Direction.getRandom(random);
        BlockPos target = pos.relative(direction);
        BlockState current = level.getBlockState(target);
        if (direction != Direction.UP && BuddingAmethystBlock.canClusterGrowAtState(current)) level.setBlockAndUpdate(target,
            WorldgenBlocks.get("amber_cluster").defaultBlockState().setValue(AmethystClusterBlock.FACING,direction)
                .setValue(AmethystClusterBlock.WATERLOGGED,current.getFluidState().getType() == Fluids.WATER));
    }
}
