/*
 * CWI local compatibility port of Darker Depths 2.1.5 worldgen algorithms.
 * Original authors: Naterbobber, Blackgear, Toxteer and Orcinus. All Rights Reserved.
 */
package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

public class GemstoneFeature extends Feature<PaletteConfiguration> {
   public GemstoneFeature(Codec<PaletteConfiguration> codec) {
      super(codec);
   }

   public boolean place(FeaturePlaceContext<PaletteConfiguration> context) {
      PaletteConfiguration palette = context.config();
      WorldGenLevel worldIn = context.level();
      BlockPos pos = context.origin();
      BlockState state = worldIn.getBlockState(pos);
      if (!worldIn.isStateAtPosition(pos, blockstate -> blockstate.isAir() || blockstate.is(Blocks.WATER))) {
         return false;
      } else {
         for (Direction direction : Direction.values()) {
            if (AmethystClusterBlock.isFaceFull(worldIn.getBlockState(pos.relative(direction)).getCollisionShape(worldIn, pos.relative(direction)), direction.getOpposite())) {
               worldIn.setBlock(
                  pos,
                  PaletteConfiguration.with(PaletteConfiguration.with(palette.state("amber_cluster"), AmethystClusterBlock.FACING, direction.getOpposite()), BlockStateProperties.WATERLOGGED, state.is(Blocks.WATER)),
                  2
               );
               return true;
            }
         }

         return false;
      }
   }
}
