/*
 * Adapted from Geode+ 1.2.5 by YeoXuHang.
 * Upstream: https://github.com/YeoXuHang/Geode-Plus
 * Licensed under CC BY-NC-SA 3.0: https://creativecommons.org/licenses/by-nc-sa/3.0/
 * Changes: Minecraft 1.21.1 names and CWI-owned feature/block tag namespaces.
 */
package net.quiwh.cwiworldgen.feature.geode;

import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.GeodeBlockSettings;
import net.minecraft.world.level.levelgen.GeodeCrackSettings;
import net.minecraft.world.level.levelgen.GeodeLayerSettings;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

public class GeodeFeature extends Feature<GeodeConfiguration> {
   private static final Direction[] DIRECTIONS = Direction.values();
   private static final TagKey<Block> INVALID_BLOCKS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "geode_invalid_blocks"));

   public GeodeFeature(Codec<GeodeConfiguration> codec) {
      super(codec);
   }

   public boolean place(FeaturePlaceContext<GeodeConfiguration> featurePlaceContext) {
      GeodeConfiguration geodeConfiguration = (GeodeConfiguration)featurePlaceContext.config();
      RandomSource randomSource = featurePlaceContext.random();
      BlockPos blockPos = featurePlaceContext.origin();
      WorldGenLevel worldGenLevel = featurePlaceContext.level();
      int i = geodeConfiguration.minGenOffset;
      int j = geodeConfiguration.maxGenOffset;
      List<Pair<BlockPos, Integer>> list = Lists.newLinkedList();
      int k = geodeConfiguration.distributionPoints.sample(randomSource);
      WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(worldGenLevel.getSeed()));
      NormalNoise normalNoise = NormalNoise.create(worldgenRandom, -4, new double[]{1.0});
      List<BlockPos> list2 = Lists.newLinkedList();
      double d = (double)k / (double)geodeConfiguration.outerWallDistance.getMaxValue();
      GeodeLayerSettings geodeLayerSettings = geodeConfiguration.geodeLayerSettings;
      GeodeBlockSettings geodeBlockSettings = geodeConfiguration.geodeBlockSettings;
      GeodeCrackSettings geodeCrackSettings = geodeConfiguration.geodeCrackSettings;
      double e = 1.0 / Math.sqrt(geodeLayerSettings.filling);
      double f = 1.0 / Math.sqrt(geodeLayerSettings.innerLayer + d);
      double g = 1.0 / Math.sqrt(geodeLayerSettings.middleLayer + d);
      double h = 1.0 / Math.sqrt(geodeLayerSettings.outerLayer + d);
      double l = 1.0 / Math.sqrt(geodeCrackSettings.baseCrackSize + randomSource.nextDouble() / 2.0 + (k > 3 ? d : 0.0));
      boolean bl = (double)randomSource.nextFloat() < geodeCrackSettings.generateCrackChance;
      int m = 0;

      for (int n = 0; n < k; n++) {
         int o = geodeConfiguration.outerWallDistance.sample(randomSource);
         int p = geodeConfiguration.outerWallDistance.sample(randomSource);
         int q = geodeConfiguration.outerWallDistance.sample(randomSource);
         BlockPos blockPos2 = blockPos.offset(o, p, q);
         BlockState blockState = worldGenLevel.getBlockState(blockPos2);
         if (blockState.isAir() || blockState.is(INVALID_BLOCKS)) {
            if (++m > geodeConfiguration.invalidBlocksThreshold) {
               return false;
            }
         }

         list.add(Pair.of(blockPos2, geodeConfiguration.pointOffset.sample(randomSource)));
      }

      if (bl) {
         int var50 = randomSource.nextInt(4);
         int o = k * 2 + 1;
         if (var50 == 0) {
            list2.add(blockPos.offset(o, 7, 0));
            list2.add(blockPos.offset(o, 5, 0));
            list2.add(blockPos.offset(o, 1, 0));
         } else if (var50 == 1) {
            list2.add(blockPos.offset(0, 7, o));
            list2.add(blockPos.offset(0, 5, o));
            list2.add(blockPos.offset(0, 1, o));
         } else if (var50 == 2) {
            list2.add(blockPos.offset(o, 7, o));
            list2.add(blockPos.offset(o, 5, o));
            list2.add(blockPos.offset(o, 1, o));
         } else {
            list2.add(blockPos.offset(0, 7, 0));
            list2.add(blockPos.offset(0, 5, 0));
            list2.add(blockPos.offset(0, 1, 0));
         }
      }

      List<BlockPos> list3 = Lists.newArrayList();
      Predicate<BlockState> predicate = isReplaceable(geodeConfiguration.geodeBlockSettings.cannotReplace);

      for (BlockPos blockPos3 : BlockPos.betweenClosed(blockPos.offset(i, i, i), blockPos.offset(j, j, j))) {
         double r = normalNoise.getValue((double)blockPos3.getX(), (double)blockPos3.getY(), (double)blockPos3.getZ())
            * geodeConfiguration.noiseMultiplier;
         double s = 0.0;
         double t = 0.0;

         for (Pair<BlockPos, Integer> pair : list) {
            s += Mth.fastInvSqrt(blockPos3.distSqr((Vec3i)pair.getFirst()) + (double)((Integer)pair.getSecond()).intValue()) + r;
         }

         for (BlockPos blockPos4 : list2) {
            t += Mth.fastInvSqrt(blockPos3.distSqr(blockPos4) + (double)geodeCrackSettings.crackPointOffset) + r;
         }

         if (!(s < h)) {
            if (bl && t >= l && s < e) {
               this.safeSetBlock(worldGenLevel, blockPos3, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), predicate);

               for (Direction direction : DIRECTIONS) {
                  BlockPos blockPos5 = blockPos3.relative(direction);
                  FluidState fluidState = worldGenLevel.getFluidState(blockPos5);
                  if (!fluidState.isEmpty()) {
                     worldGenLevel.scheduleTick(blockPos5, fluidState.getType(), 0);
                  }
               }
            } else if (s >= e) {
               this.safeSetBlock(worldGenLevel, blockPos3, geodeBlockSettings.fillingProvider.getState(randomSource, blockPos3), predicate);
            } else if (s >= f) {
               boolean bl2 = (double)randomSource.nextFloat() < geodeConfiguration.useAlternateLayer0Chance;
               if (bl2) {
                  this.safeSetBlock(worldGenLevel, blockPos3, geodeBlockSettings.alternateInnerLayerProvider.getState(randomSource, blockPos3), predicate);
               } else {
                  this.safeSetBlock(worldGenLevel, blockPos3, geodeBlockSettings.innerLayerProvider.getState(randomSource, blockPos3), predicate);
               }

               if ((!geodeConfiguration.placementsRequireLayer0Alternate || bl2) && (double)randomSource.nextFloat() < geodeConfiguration.usePotentialPlacementsChance) {
                  list3.add(blockPos3.immutable());
               }
            } else if (s >= g) {
               this.safeSetBlock(worldGenLevel, blockPos3, geodeBlockSettings.middleLayerProvider.getState(randomSource, blockPos3), predicate);
            } else if (s >= h) {
               this.safeSetBlock(worldGenLevel, blockPos3, geodeBlockSettings.outerLayerProvider.getState(randomSource, blockPos3), predicate);
            }
         }
      }

      List<BlockState> list4 = geodeBlockSettings.innerPlacements;

      for (BlockPos blockPos2 : list3) {
         BlockState blockState = (BlockState)Util.getRandom(list4, randomSource);

         for (Direction direction2 : DIRECTIONS) {
            if (blockState.hasProperty(BlockStateProperties.FACING)) {
               blockState = (BlockState)blockState.setValue(BlockStateProperties.FACING, direction2);
            }

            BlockPos blockPos6 = blockPos2.relative(direction2);
            BlockState blockState2 = worldGenLevel.getBlockState(blockPos6);
            if (blockState.hasProperty(BlockStateProperties.WATERLOGGED)) {
               blockState = (BlockState)blockState.setValue(BlockStateProperties.WATERLOGGED, blockState2.getFluidState().isSource());
            }

            if (canClusterGrowAtState(blockState2)) {
               this.safeSetBlock(worldGenLevel, blockPos6, blockState, predicate);
               break;
            }
         }
      }

      return true;
   }

   public static boolean canClusterGrowAtState(BlockState blockState) {
      return blockState.isAir() || blockState.is(net.minecraft.world.level.block.Blocks.WATER) && blockState.getFluidState().getAmount() == 8;
   }
}
