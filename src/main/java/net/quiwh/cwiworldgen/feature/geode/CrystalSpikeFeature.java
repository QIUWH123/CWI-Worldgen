/*
 * Adapted from Geode+ 1.2.5 by YeoXuHang.
 * Upstream: https://github.com/YeoXuHang/Geode-Plus
 * Licensed under CC BY-NC-SA 3.0: https://creativecommons.org/licenses/by-nc-sa/3.0/
 * Changes: Minecraft 1.21.1 names and CWI-owned feature/block tag namespaces.
 */
package net.quiwh.cwiworldgen.feature.geode;

import com.google.common.collect.Sets;
import com.mojang.serialization.Codec;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.DripstoneUtils;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.quiwh.cwiworldgen.feature.geode.config.GeodeCrystalSpikeConfig;

public class CrystalSpikeFeature extends Feature<GeodeCrystalSpikeConfig> {
   public CrystalSpikeFeature(Codec<GeodeCrystalSpikeConfig> codec) {
      super(codec);
   }

   public boolean place(FeaturePlaceContext<GeodeCrystalSpikeConfig> context) {
      WorldGenLevel world = context.level();
      BlockPos blockPos = context.origin();
      RandomSource random = context.random();
      GeodeCrystalSpikeConfig config = (GeodeCrystalSpikeConfig)context.config();
      if (CrystalSpikePlacement.generationVersion(world) >= 16) {
         return CrystalSpikePlacement.place(context, state -> state.is(BlockTags.BASE_STONE_NETHER),
               Blocks.BLACKSTONE.defaultBlockState());
      }
      HashSet<BlockPos> trigList = Sets.newHashSet();
      HashSet<BlockPos> clusterPos = Sets.newHashSet();
      boolean flag = false;
      int radiusCheck = config.xzRadius.sample(random) + 1;
      int randomChance = random.nextInt(4);
      int stepHeight = radiusCheck + 14 + Mth.nextInt(random, 10, 14);
      if (world.isStateAtPosition(blockPos.relative(config.crystal_direction.getDirection().getOpposite()), DripstoneUtils::isEmptyOrWaterOrLava)
         && world.getBlockState(blockPos).is(BlockTags.BASE_STONE_NETHER)
         && this.placeSpike(world, blockPos, radiusCheck, stepHeight, randomChance, trigList, config.crystal_direction.getDirection(), random)) {
         flag = this.placeCrystals(world, random, config, trigList, clusterPos, flag);
      }

      return flag;
   }

   private boolean placeCrystals(
      WorldGenLevel world, RandomSource random, GeodeCrystalSpikeConfig config, HashSet<BlockPos> trigList, HashSet<BlockPos> clusterPos, boolean flag
   ) {
      for (BlockPos pos : trigList) {
         if (world.isStateAtPosition(pos, DripstoneUtils::isEmptyOrWaterOrLava)) {
            this.setBlock(world, pos, config.crystal_state);
            clusterPos.add(pos);
            flag = true;
         }
      }

      for (BlockPos posx : clusterPos) {
         if (random.nextInt(6) == 0) {
            for (Direction direction : Direction.values()) {
               BlockPos relative = posx.relative(direction);
               if (random.nextBoolean() && world.isStateAtPosition(relative, DripstoneUtils::isEmptyOrWater) && world.getBlockState(posx).equals(config.crystal_state)) {
                  this.setBlock(
                     world,
                     relative,
                     (BlockState)((BlockState)config.cluster_state.setValue(AmethystClusterBlock.FACING, direction))
                        .setValue(AmethystClusterBlock.WATERLOGGED, world.getFluidState(relative).getType() == Fluids.WATER)
                  );
               }
            }
         }
      }

      return flag;
   }

   public boolean placeSpike(
      LevelAccessor world,
      BlockPos blockPos,
      int startRadius,
      int height,
      int randomChance,
      HashSet<BlockPos> crystalPos,
      Direction direction,
      RandomSource random
   ) {
      boolean flag = false;

      for (int y = 0; y < height; y++) {
         int radius = startRadius - y / 2;

         for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
               BlockPos pos = new BlockPos(blockPos.getX() + x, blockPos.getY(), blockPos.getZ() + z);
               if (x * x + z * z <= radius * radius) {
                  if (direction != Direction.DOWN && direction == Direction.UP) {
                     MutableBlockPos mut = pos.mutable();

                     for (int i = 0; i < 10 && world.isStateAtPosition(mut.above(), DripstoneUtils::isEmptyOrWaterOrLava); i++) {
                        mut.move(Direction.UP);
                     }

                     pos = mut.immutable();
                     if (world.isStateAtPosition(pos.above(), DripstoneUtils::isEmptyOrWaterOrLava)) {
                        return false;
                     }
                  }

                  this.calciteBloom(world, pos.relative(direction), random, radius);

                  float var24 = switch (randomChance) {
                     case 0 -> 2.617994F;
                     case 1 -> (float) Math.PI * 11.0F / 6.0F;
                     case 2 -> (float) (Math.PI / 6);
                     case 3 -> 3.6651917F;
                     default -> throw new IllegalStateException("Unexpected value: " + randomChance);
                  };
                  float q = Mth.cos(var24) * (float)y;
                  float k = Mth.sin((float) (Math.PI / 2)) * (float)y;
                  float l = Mth.sin(var24) * (float)y;
                  float xx = direction == Direction.UP ? -q : q;
                  float yy = direction == Direction.UP ? -k : k;
                  float zz = direction == Direction.UP ? -l : l;
                  BlockPos trigPos = pos.offset((int)xx, (int)yy, (int)zz);
                  if (world.isStateAtPosition(trigPos, DripstoneUtils::isEmptyOrWaterOrLava)) {
                     crystalPos.add(trigPos);
                     flag = true;
                  } else {
                     crystalPos.remove(trigPos);
                  }
               }
            }
         }
      }

      return flag;
   }

   private boolean calciteBloom(LevelAccessor world, BlockPos blockPos, RandomSource random, int crystalRadius) {
      int radius = crystalRadius / 4;
      int height = ConstantInt.of(2).sample(random);
      boolean flag = false;

      for (int x = -radius; x <= radius; x++) {
         for (int z = -radius; z <= radius; z++) {
            for (int y = -height; y <= height; y++) {
               BlockPos pos = new BlockPos(blockPos.getX() + x, blockPos.getY() + y, blockPos.getZ() + z);

               for (Direction direction : Direction.values()) {
                  if (world.getBlockState(pos).is(BlockTags.BASE_STONE_NETHER) && world.isStateAtPosition(pos.relative(direction), DripstoneUtils::isEmptyOrWaterOrLava)) {
                     world.setBlock(pos, Blocks.BLACKSTONE.defaultBlockState(), 2);
                     flag = true;
                  }
               }
            }
         }
      }

      return flag;
   }
}
