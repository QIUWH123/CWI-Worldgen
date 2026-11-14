/*
 * CWI worldgen compatibility port. Algorithms adapted from Darker Depths 2.1.5,
 * by Naterbobber, Blackgear, Toxteer and Orcinus (upstream: All Rights Reserved).
 * This local port preserves generation behavior while injecting CWI block palettes.
 */
package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import net.quiwh.cwiworldgen.feature.RandomFloorPlacementConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

public class RandomFloorPlacementFeature extends Feature<RandomFloorPlacementConfig> {
   public RandomFloorPlacementFeature(Codec<RandomFloorPlacementConfig> codec) {
      super(codec);
   }

   public boolean place(FeaturePlaceContext<RandomFloorPlacementConfig> context) {
      WorldGenLevel level = context.level();
      BlockPos origin = context.origin();
      RandomSource random = context.random();
      RandomFloorPlacementConfig config = (RandomFloorPlacementConfig)context.config();
      int maxPlacement = ((RandomFloorPlacementConfig)context.config()).placeCount();
      int placed = 0;
      MutableBlockPos mutablePos = new MutableBlockPos();

      for (int i = 0; i < config.tries(); i++) {
         int dx = random.nextInt(config.xzRadius() * 2 + 1) - config.xzRadius();
         int dy = random.nextInt(config.yHeight() * 2 + 1) - config.yHeight();
         int dz = random.nextInt(config.xzRadius() * 2 + 1) - config.xzRadius();
         mutablePos.setWithOffset(origin, dx, dy, dz);
         if (!config.heightBounds().contains(mutablePos)) continue;
         if (level.isEmptyBlock(mutablePos)) {
            BlockState floorState = level.getBlockState(mutablePos.below());
            if (config.validFloorBlocks().contains(floorState.getBlock().builtInRegistryHolder())) {
               BlockState stateToPlace = config.stateProvider().getState(random, mutablePos);
               level.setBlock(mutablePos, stateToPlace, 2);
               if (++placed >= maxPlacement) {
                  break;
               }
            }
         }
      }

      return placed > 0;
   }
}
