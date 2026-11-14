/*
 * CWI local compatibility port of Darker Depths 2.1.5 worldgen algorithms.
 * Original authors: Naterbobber, Blackgear, Toxteer and Orcinus. All Rights Reserved.
 */
package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

public class CatacombsLavaLiningFeature extends Feature<PaletteConfiguration> {
   public CatacombsLavaLiningFeature(Codec<PaletteConfiguration> p_65792_) {
      super(p_65792_);
   }

   public boolean place(FeaturePlaceContext<PaletteConfiguration> context) {
      PaletteConfiguration palette = context.config();
      WorldGenLevel world = context.level();
      BlockPos origin = context.origin();
      RandomSource random = context.random();
      int blocksPlaced = 0;
      int searchRadius = 8;

      for (int x = -searchRadius; x <= searchRadius; x++) {
         for (int y = -searchRadius; y <= searchRadius; y++) {
            for (int z = -searchRadius; z <= searchRadius; z++) {
               BlockPos currentPos = origin.offset(x, y, z);
               BlockState blockState = world.getBlockState(currentPos);
               if (blockState.is(Blocks.LAVA)) {
                  for (Direction direction : Direction.values()) {
                     BlockPos adjacentPos = currentPos.relative(direction);
                     BlockState adjacentBlockState = world.getBlockState(adjacentPos);
                     if (!adjacentBlockState.is(Blocks.LAVA)
                        && (
                           adjacentBlockState.is(BlockTags.BASE_STONE_OVERWORLD)
                              || adjacentBlockState.is(palette.block("arid_deepslate"))
                              || adjacentBlockState.is(palette.block("aridrock"))
                              || adjacentBlockState.is(palette.block("duskrock"))
                              || adjacentBlockState.is(Blocks.PACKED_MUD)
                        )) {
                        world.setBlock(adjacentPos, Blocks.SMOOTH_BASALT.defaultBlockState(), 2);
                        blocksPlaced++;
                        if (world.getBlockState(adjacentPos.above()).is(palette.block("dry_sprouts"))) {
                           world.setBlock(adjacentPos.above(), Blocks.AIR.defaultBlockState(), 2);
                        }

                        this.placeCluster(world, adjacentPos, random, palette);
                     }
                  }
               }
            }
         }
      }

      return blocksPlaced > 0;
   }

   public void placeCluster(WorldGenLevel world, BlockPos centerPos, RandomSource random, PaletteConfiguration palette) {
      int clusterRadius = 1 + random.nextInt(2);

      for (int x = -clusterRadius; x <= clusterRadius; x++) {
         for (int y = -clusterRadius; y <= clusterRadius; y++) {
            for (int z = -clusterRadius; z <= clusterRadius; z++) {
               if (x * x + y * y + z * z <= clusterRadius * clusterRadius) {
                  BlockPos currentClusterPos = centerPos.offset(x, y, z);
                  BlockState currentClusterBlockState = world.getBlockState(currentClusterPos);
                  if (!currentClusterBlockState.is(Blocks.LAVA)
                     && !currentClusterBlockState.is(palette.block("darkslate"))
                     && (
                        currentClusterBlockState.is(palette.block("aridrock"))
                           || currentClusterBlockState.is(palette.block("arid_deepslate"))
                           || currentClusterBlockState.is(BlockTags.BASE_STONE_OVERWORLD)
                           || currentClusterBlockState.is(Blocks.PACKED_MUD)
                     )) {
                     world.setBlock(currentClusterPos, palette.state("duskrock"), 2);
                  }
               }
            }
         }
      }
   }
}
