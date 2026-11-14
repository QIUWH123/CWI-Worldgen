package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

public final class StoneMelonBlock extends Block {
    public static final IntegerProperty GROWTH = IntegerProperty.create("melon_growth_level",0,2);
    public StoneMelonBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(GROWTH,0)); }
    @Override protected MapCodec<? extends Block> codec() { return simpleCodec(StoneMelonBlock::new); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(GROWTH); }
}
