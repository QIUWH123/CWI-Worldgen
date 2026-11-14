package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;

public final class CaveSproutsBlock extends BushBlock {
    private final int height;
    private final boolean dry;
    public CaveSproutsBlock(Properties properties, int height, boolean dry) { super(properties); this.height = height; this.dry = dry; }
    @Override protected MapCodec<? extends BushBlock> codec() { return simpleCodec(p -> new CaveSproutsBlock(p, height, dry)); }
    @Override protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        if (height == 10) return state.isSolid();
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!dry) return id.equals("kubejs:phyllite") || id.equals("kubejs:mossy_phyllite")
            || state.is(Blocks.MOSS_BLOCK) || super.mayPlaceOn(state,level,pos);
        return id.equals("kubejs:quartzite") || id.equals("kubejs:conglomerate") || id.equals("kubejs:quartzite_deepslate")
            || state.is(Blocks.MUD) || state.is(Blocks.PACKED_MUD) || state.is(BlockTags.DIRT)
            || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(BlockTags.TERRACOTTA);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Block.box(2,0,2,14,height,14); }
}
