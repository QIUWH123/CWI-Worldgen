package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

public final class HangingHeadBlock extends GrowingPlantHeadBlock {
    private final boolean glimmering;
    public HangingHeadBlock(Properties properties, boolean glimmering) {
        super(properties, Direction.DOWN, glimmering ? Block.box(4,9,4,12,16,12) : Block.box(2,3,2,14,16,14), false, .1);
        this.glimmering = glimmering;
    }
    @Override protected MapCodec<? extends GrowingPlantHeadBlock> codec() { return simpleCodec(p -> new HangingHeadBlock(p, glimmering)); }
    @Override protected int getBlocksToGrowWhenBonemealed(RandomSource random) { return NetherVines.getBlocksToGrowWhenBonemealed(random); }
    @Override protected boolean canGrowInto(BlockState state) { return state.isAir(); }
    @Override protected Block getBodyBlock() { return WorldgenBlocks.get(glimmering ? "glimmering_vine_plant" : "petrified_roots_plant"); }
    @Override protected boolean isRandomlyTicking(BlockState state) { return glimmering && super.isRandomlyTicking(state); }
    @Override public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) { return glimmering; }
}
