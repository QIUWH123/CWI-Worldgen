/*
 * CWI local compatibility port of Darker Depths 2.1.5 worldgen algorithms.
 * Original authors: Naterbobber, Blackgear, Toxteer and Orcinus. All Rights Reserved.
 */
package net.quiwh.cwiworldgen.feature;

import com.google.common.collect.Lists;
import com.mojang.serialization.Codec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.DripstoneUtils;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.material.Fluids;

public class PetrifiedBranchFeature extends Feature<PalettedConfiguration<PetrifiedBranchConfig>> {
   public PetrifiedBranchFeature(Codec<PalettedConfiguration<PetrifiedBranchConfig>> codec) {
      super(codec);
   }

   public boolean place(FeaturePlaceContext<PalettedConfiguration<PetrifiedBranchConfig>> context) {
      if (PetrifiedBranchPlacement.generationVersion(context.level()) >= 16) {
         return PetrifiedBranchPlacement.place(context);
      }
      PaletteConfiguration palette = context.config().palette();
      WorldGenLevel world = context.level();
      BlockPos pos = context.origin();
      RandomSource random = context.random();
      PetrifiedBranchConfig config = context.config().settings();
      if (world.isEmptyBlock(pos) && world.getBlockState(pos.above()).is(BlockTags.BASE_STONE_OVERWORLD)) {
         boolean flag = false;
         List<BlockPos> woodPos = Lists.newArrayList();
         List<BlockPos> porousPos = Lists.newArrayList();
         int height = UniformInt.of(config.minLength(), config.maxLength()).sample(random);

         for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
               for (int y = -1; y <= height; y++) {
                  BlockPos blockPos = new BlockPos(pos.getX() + x, pos.getY() - y, pos.getZ() + z);
                  BlockState state = random.nextBoolean() && random.nextInt(10) == 0
                     ? palette.state("porous_petrified_log")
                     : palette.state("petrified_log");
                  if (random.nextInt(3) == 0) {
                     if (state.is(palette.block("porous_petrified_log"))) {
                        porousPos.add(blockPos);
                     }

                     if (world.isStateAtPosition(blockPos, DripstoneUtils::isEmptyOrWaterOrLava)) {
                        world.setBlock(blockPos, state, 2);
                        woodPos.add(blockPos);
                     }

                     flag = true;
                  }
               }
            }
         }

         for (BlockPos blockPos : woodPos) {
            if (world.getFluidState(blockPos.below()).isEmpty()
               && world.getFluidState(blockPos.below(2)).isEmpty()
               && world.getBlockState(blockPos.below(2)).canBeReplaced()
               && world.getBlockState(blockPos.below()).canBeReplaced()) {
               world.setBlock(blockPos.below(), palette.state("petrified_roots_plant"), 2);
               world.setBlock(blockPos.below(2), palette.state("petrified_roots"), 2);
            } else if (!world.getFluidState(blockPos.below()).isEmpty()
               || !world.getBlockState(blockPos.below()).canBeReplaced()
               || world.getBlockState(blockPos.below(2)).is(palette.block("petrified_log")) && !world.isEmptyBlock(blockPos.below(2))) {
               if (world.getFluidState(blockPos.below()).isEmpty()
                  && world.getBlockState(blockPos.below()).canBeReplaced()
                  && world.getBlockState(blockPos.below(2)).is(palette.block("petrified_log"))) {
                  world.setBlock(blockPos.below(), palette.state("petrified_roots_plant"), 2);
               }
            } else {
               world.setBlock(blockPos.below(), palette.state("petrified_roots"), 2);
            }
         }

         if (flag && random.nextInt(5) == 0) {
            for (Direction direction : Direction.values()) {
               for (BlockPos blockPosx : porousPos) {
                  if (random.nextBoolean() && world.isStateAtPosition(blockPosx.relative(direction), DripstoneUtils::isEmptyOrWater)) {
                     world.setBlock(
                        blockPosx.relative(direction),
                        PaletteConfiguration.with(PaletteConfiguration.with(palette.state("amber_cluster"), AmethystClusterBlock.WATERLOGGED, world.getFluidState(blockPosx.relative(direction)).getType() == Fluids.WATER), AmethystClusterBlock.FACING, direction),
                        2
                     );
                  }
               }
            }
         }

         return flag;
      } else {
         return false;
      }
   }
}
