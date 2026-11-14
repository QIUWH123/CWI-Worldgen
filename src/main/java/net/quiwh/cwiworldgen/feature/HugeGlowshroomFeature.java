/*
 * CWI local compatibility port of Darker Depths 2.1.5 worldgen algorithms.
 * Original authors: Naterbobber, Blackgear, Toxteer and Orcinus. All Rights Reserved.
 */
package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

public class HugeGlowshroomFeature extends Feature<PaletteConfiguration> {
   public HugeGlowshroomFeature(Codec<PaletteConfiguration> codec) {
      super(codec);
   }

   public boolean place(FeaturePlaceContext<PaletteConfiguration> context) {
      PaletteConfiguration palette = context.config();
      WorldGenLevel world = context.level();
      BlockPos pos = context.origin();
      RandomSource rand = context.random();
      BlockState belowState = world.getBlockState(pos.below());
      boolean flag = (belowState.is(palette.block("mossy_grimestone")) || belowState.is(palette.block("glowshroom_block")) || belowState.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD));
      boolean flag2 = (
            world.isStateAtPosition(pos, BlockStateBase::isAir) || world.getBlockState(pos).is(palette.block("glowshroom")) || world.getBlockState(pos).canBeReplaced()
         )
         && !world.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.LAVA);
      if (flag2 && flag) {
         int height = Mth.nextInt(rand, 2, 4);
         int chanceHeight = 3;
         Map<BlockPos, BlockState> planned = new LinkedHashMap<>();
         if (height > chanceHeight) {
            this.hugeGlowshroom(world, pos, height, palette, rand, planned);
         } else {
            this.smallGlowshroom(world, pos, height, palette, rand, planned);
         }

         // Validate the complete stem and cap before replacing a planted mushroom.
         for (BlockPos target : planned.keySet()) {
            BlockState current = world.getBlockState(target);
            if (world.isOutsideBuildHeight(target) || current.is(net.minecraft.world.level.block.Blocks.LAVA)
                  || !(current.canBeReplaced() || target.equals(pos) && current.is(palette.block("glowshroom")))) {
               return false;
            }
         }
         planned.forEach((target, state) -> this.setBlock(world, target, state));
         return true;
      } else {
         return false;
      }
   }

   private void smallGlowshroom(WorldGenLevel world, BlockPos pos, int height, PaletteConfiguration palette, RandomSource random,
         Map<BlockPos, BlockState> planned) {
      for (int i = 0; i < height; i++) {
         BlockPos stalkPos = pos.above(i);
         planned.put(stalkPos, palette.state("glowshroom_stem"));
      }

      BlockPos capBase = pos.above(height - 1);
      int[][][] smallGlowshroomMap = new int[][][]{
         {{0, 2, 0}, {2, 0, 2}, {0, 2, 0}},
         {{3, 1, 3}, {1, 1, 1}, {3, 1, 3}},
         {{1, 1, 1}, {1, 1, 1}, {1, 1, 1}},
         {{1, 1, 1}, {1, 1, 1}, {1, 1, 1}},
         {{4, 4, 4}, {4, 4, 4}, {4, 4, 4}}
      };

      for (int y = 0; y < 5; y++) {
         for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
               int blockIndex = smallGlowshroomMap[y][x + 1][z + 1];
               BlockPos capPos = capBase.offset(x, y, z);
               if (blockIndex == 1 || blockIndex == 3 || world.getBlockState(capPos).canBeReplaced()) {
                  switch (blockIndex) {
                     case 1:
                        planned.put(capPos, palette.state("glowshroom_block"));
                        break;
                     case 2:
                        if (!world.getBlockState(capPos).is(net.minecraft.world.level.block.Blocks.WATER)) {
                           int glimmeringVinesRandom = random.nextInt(8);
                           if (glimmeringVinesRandom == 0) {
                              planned.put(capPos, palette.state("glimmering_vines"));
                           }
                        }
                        break;
                     case 3:
                        int glowVineRandom = random.nextInt(4);
                        if (world.getBlockState(capPos).is(net.minecraft.world.level.block.Blocks.WATER)) {
                           glowVineRandom = 1;
                        }

                        if (glowVineRandom == 0) {
                           planned.put(capPos, palette.state("glimmering_vines"));
                        } else {
                           planned.put(capPos, palette.state("glowshroom_block"));
                        }
                        break;
                     case 4:
                        int glowshroomRandom = random.nextInt(9);
                        int blockStateRandom = random.nextInt(3) + 1;
                        if (glowshroomRandom == 0) {
                           if (world.getBlockState(capPos).is(net.minecraft.world.level.block.Blocks.WATER)) {
                              planned.put(
                                 capPos,
                                 PaletteConfiguration.with(PaletteConfiguration.with(palette.state("glowshroom"), "clusters", Integer.toString(blockStateRandom)), BlockStateProperties.WATERLOGGED, true)
                              );
                           } else {
                              planned.put(
                                 capPos,
                                 PaletteConfiguration.with(palette.state("glowshroom"), "clusters", Integer.toString(blockStateRandom))
                              );
                           }
                        }
                  }
               }
            }
         }
      }
   }

   private void hugeGlowshroom(WorldGenLevel world, BlockPos pos, int height, PaletteConfiguration palette, RandomSource random,
         Map<BlockPos, BlockState> planned) {
      for (int i = 0; i < height; i++) {
         BlockPos stalkPos = pos.above(i);
         planned.put(stalkPos, palette.state("glowshroom_stem"));
      }

      BlockPos capBase = pos.above(height - 3);
      int[][][] smallGlowshroomMap = new int[][][]{
         {{0, 2, 2, 2, 0}, {2, 0, 0, 0, 2}, {2, 0, 0, 0, 2}, {2, 0, 0, 0, 2}, {0, 2, 2, 2, 0}},
         {{3, 1, 1, 1, 3}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {3, 1, 1, 1, 3}},
         {{1, 1, 1, 1, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 1, 1, 1, 1}},
         {{1, 1, 1, 1, 1}, {1, 0, 0, 0, 1}, {1, 0, 5, 0, 1}, {1, 0, 0, 0, 1}, {1, 1, 1, 1, 1}},
         {{4, 1, 1, 1, 4}, {1, 1, 1, 1, 1}, {1, 1, 1, 1, 1}, {1, 1, 1, 1, 1}, {4, 1, 1, 1, 4}},
         {{0, 4, 4, 4, 0}, {4, 4, 4, 4, 4}, {4, 4, 4, 4, 4}, {4, 4, 4, 4, 4}, {0, 4, 4, 4, 0}}
      };

      for (int y = 0; y < 6; y++) {
         for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
               int blockIndex = smallGlowshroomMap[y][x + 2][z + 2];
               BlockPos capPos = capBase.offset(x, y, z);
               if (blockIndex == 1 || blockIndex == 3 || blockIndex == 5 || world.getBlockState(capPos).canBeReplaced()) {
                  switch (blockIndex) {
                     case 1:
                        planned.put(capPos, palette.state("glowshroom_block"));
                        break;
                     case 2:
                        if (!world.getBlockState(capPos).is(net.minecraft.world.level.block.Blocks.WATER)) {
                           int glimmeringVinesRandom = random.nextInt(8);
                           if (glimmeringVinesRandom == 0) {
                              planned.put(capPos, palette.state("glimmering_vines"));
                           }
                        }
                        break;
                     case 3:
                        int glowVineRandom = random.nextInt(4);
                        if (world.getBlockState(capPos).is(net.minecraft.world.level.block.Blocks.WATER)) {
                           glowVineRandom = 1;
                        }

                        if (glowVineRandom == 0) {
                           planned.put(capPos, palette.state("glimmering_vines"));
                        } else {
                           planned.put(capPos, palette.state("glowshroom_block"));
                        }
                        break;
                     case 4:
                        int glowshroomRandom = random.nextInt(18);
                        int blockStateRandom = random.nextInt(3) + 1;
                        if (glowshroomRandom == 0) {
                           if (world.getBlockState(capPos).is(net.minecraft.world.level.block.Blocks.WATER)) {
                              planned.put(
                                 capPos,
                                 PaletteConfiguration.with(PaletteConfiguration.with(palette.state("glowshroom"), "clusters", Integer.toString(blockStateRandom)), BlockStateProperties.WATERLOGGED, true)
                              );
                           } else {
                              planned.put(
                                 capPos,
                                 PaletteConfiguration.with(palette.state("glowshroom"), "clusters", Integer.toString(blockStateRandom))
                              );
                           }
                        }
                        break;
                     case 5:
                        planned.put(capPos, palette.state("glowshroom_heart"));
                  }
               }
            }
         }
      }
   }
}
