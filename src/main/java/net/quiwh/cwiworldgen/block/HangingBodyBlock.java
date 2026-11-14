package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

public final class HangingBodyBlock extends GrowingPlantBodyBlock {
    private final boolean glimmering;
    public HangingBodyBlock(Properties properties, boolean glimmering) {
        super(properties, Direction.DOWN, glimmering ? Block.box(1,0,1,15,16,15) : Block.box(2,3,2,14,16,14), false);
        this.glimmering = glimmering;
    }
    @Override protected MapCodec<? extends GrowingPlantBodyBlock> codec() { return simpleCodec(p -> new HangingBodyBlock(p, glimmering)); }
    @Override protected GrowingPlantHeadBlock getHeadBlock() { return (GrowingPlantHeadBlock) WorldgenBlocks.get(glimmering ? "glimmering_vines" : "petrified_roots"); }
    @Override public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) { return glimmering; }
}
