package net.quiwh.cwiworldgen.block;

import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.Fluid;

/** Neighbor-driven DD heat; CWI disables the original chunk heat-baking queue. */
final class HeatPropagation {
    static final IntegerProperty HEAT = IntegerProperty.create("heat_level", 0, 4);
    private static final TagKey<Block> HEATABLE = blockTag("heatable");
    private static final TagKey<Block> PROVIDER = blockTag("heat_provider");
    static TagKey<Block> blockTag(String name) { return TagKey.create(Registries.BLOCK,id(name)); }
    private static TagKey<Fluid> fluidTag(String name) { return TagKey.create(Registries.FLUID,id(name)); }
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("cwi_worldgen",name); }
    static int neighborHeat(LevelAccessor level, BlockPos pos) {
        int heat = 0;
        for (Direction direction : Direction.values()) heat = Math.max(heat,determineHeat(level.getBlockState(pos.relative(direction))));
        return heat > 0 ? heat-1 : 0;
    }
    static int determineHeat(BlockState state) {
        if (state.is(HEATABLE)) return state.hasProperty(HEAT) ? state.getValue(HEAT) : 0;
        if (state.getBlock() instanceof CampfireBlock
                && (!state.getValue(CampfireBlock.LIT) || state.getValue(CampfireBlock.WATERLOGGED))) return 0;
        String[] heat = {"low_heat","medium_heat","high_heat","very_high_heat"};
        if (state.is(PROVIDER)) {
            for (int i=3;i>=0;i--) if (state.is(blockTag(heat[i]))) return i+2;
        } else if (state.getFluidState().is(fluidTag("heat_provider"))) {
            for (int i=3;i>=0;i--) if (state.getFluidState().is(fluidTag(heat[i]))) return i+2;
        }
        return 0;
    }
    static void update(Level level, BlockPos pos, BlockState state) {
        int heat = neighborHeat(level,pos);
        if (state.getValue(HEAT) != heat) level.setBlock(pos,state.setValue(HEAT,heat),3);
    }
    private HeatPropagation() {}
}
