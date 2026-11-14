/*
 * CWI worldgen compatibility port. Algorithms adapted from Darker Depths 2.1.5,
 * by Naterbobber, Blackgear, Toxteer and Orcinus (upstream: All Rights Reserved).
 * This local port preserves generation behavior while injecting CWI block palettes.
 */
package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import net.quiwh.cwiworldgen.feature.CorrespondentLayersConfig;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.DripstoneUtils;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

public class CorrespondentLayersFeature extends Feature<CorrespondentLayersConfig> {
   public CorrespondentLayersFeature(Codec<CorrespondentLayersConfig> codec) {
      super(codec);
   }

   public boolean place(FeaturePlaceContext<CorrespondentLayersConfig> context) {
      WorldGenLevel worldgenlevel = context.level();
      CorrespondentLayersConfig config = (CorrespondentLayersConfig)context.config();
      RandomSource random = context.random();
      BlockPos blockpos = context.origin();
      Predicate<BlockState> predicate = state -> state.is(config.replaceable);
      int i = config.xzRadius.sample(random) + 1;
      int j = config.xzRadius.sample(random) + 1;
      Set<BlockPos> set = this.placeGroundPatch(worldgenlevel, config, random, blockpos, predicate, i, j);
      this.distributeVegetation(context, worldgenlevel, config, random, set, i, j);
      return !set.isEmpty();
   }

   protected Set<BlockPos> placeGroundPatch(
      WorldGenLevel world, CorrespondentLayersConfig config, RandomSource random, BlockPos pos, Predicate<BlockState> predicate, int xRadius, int zRadius
   ) {
      MutableBlockPos mut = pos.mutable();
      MutableBlockPos mut1 = mut.mutable();
      Direction direction = config.surface.getDirection();
      Direction direction1 = direction.getOpposite();
      Set<BlockPos> set = new HashSet<>();

      for (int i = -xRadius; i <= xRadius; i++) {
         boolean flag = i == -xRadius || i == xRadius;

         for (int j = -zRadius; j <= zRadius; j++) {
            boolean flag1 = j == -zRadius || j == zRadius;
            boolean flag2 = flag || flag1;
            boolean flag3 = flag && flag1;
            boolean flag4 = flag2 && !flag3;
            if (!flag3 && (!flag4 || config.extraEdgeColumnChance != 0.0F && !(random.nextFloat() > config.extraEdgeColumnChance))) {
               mut.setWithOffset(pos, i, 0, j);

               for (int k = 0; world.isStateAtPosition(mut, DripstoneUtils::isEmptyOrWater) && k < config.verticalRange; k++) {
                  mut.move(direction);
               }

               for (int i1 = 0; world.isStateAtPosition(mut, state -> !DripstoneUtils.isEmptyOrWater(state)) && i1 < config.verticalRange; i1++) {
                  mut.move(direction1);
               }

               mut1.setWithOffset(mut, config.surface.getDirection());
               BlockState blockstate = world.getBlockState(mut1);
               if (world.isStateAtPosition(mut, DripstoneUtils::isEmptyOrWater) && blockstate.isFaceSturdy(world, mut1, config.surface.getDirection().getOpposite())) {
                  int l = config.depth.sample(random) + (config.extraBottomBlockChance > 0.0F && random.nextFloat() < config.extraBottomBlockChance ? 1 : 0);
                  BlockPos blockpos = mut1.immutable();
                  boolean flag5 = this.placeGround(world, config, predicate, random, mut1, l);
                  if (flag5) {
                     set.add(blockpos);
                  }
               }
            }
         }
      }

      return set;
   }

   protected void distributeVegetation(
      FeaturePlaceContext<CorrespondentLayersConfig> context,
      WorldGenLevel world,
      CorrespondentLayersConfig config,
      RandomSource random,
      Set<BlockPos> pos,
      int p_160619_,
      int p_160620_
   ) {
      for (BlockPos blockpos : pos) {
         if (config.vegetationChance > 0.0F && random.nextFloat() < config.vegetationChance) {
            this.placeVegetation(world, config, context.chunkGenerator(), random, blockpos);
         }
      }
   }

   protected boolean placeVegetation(WorldGenLevel world, CorrespondentLayersConfig config, ChunkGenerator generator, RandomSource random, BlockPos pos) {
      if (!config.heightBounds.contains(pos.relative(config.surface.getDirection().getOpposite()))) return false;
      return world.isStateAtPosition(pos.relative(config.surface.getDirection().getOpposite()), BlockStateBase::isAir)
            && world.getBlockState(pos).is(config.layers.get(0).getState(random, pos).getBlock())
         ? ((PlacedFeature)config.vegetationFeature.value()).place(world, generator, random, pos.relative(config.surface.getDirection().getOpposite()))
         : false;
   }

   protected boolean placeGround(
      WorldGenLevel world, CorrespondentLayersConfig config, Predicate<BlockState> predicate, RandomSource random, MutableBlockPos pos, int tries
   ) {
      boolean placedAnything = false;

      // Upstream uses layers, independently of sampled depth and xz_replace.
      for (int depth = 0; depth < config.layers.size(); depth++) {
         BlockPos currentPos = pos.below(depth);
         if (!config.heightBounds.contains(currentPos)) break;
         BlockState currentState = world.getBlockState(currentPos);
         if (!predicate.test(currentState)) {
            break;
         }

         BlockState newState = config.layers.get(depth).getState(random, currentPos);
         world.setBlock(currentPos, newState, 2);
         placedAnything = true;
      }

      return placedAnything;
   }
}
