/*
 * CWI local compatibility port of Darker Depths 2.1.5 worldgen algorithms.
 * Original authors: Naterbobber, Blackgear, Toxteer and Orcinus. All Rights Reserved.
 */
package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction.Plane;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.VegetationPatchFeature;
import net.minecraft.world.level.levelgen.feature.configurations.VegetationPatchConfiguration;

public class LavaVegetationPatchFeature extends VegetationPatchFeature {
   public LavaVegetationPatchFeature(Codec<VegetationPatchConfiguration> codec) {
      super(codec);
   }

   protected Set<BlockPos> placeGroundPatch(
      WorldGenLevel world,
      VegetationPatchConfiguration config,
      RandomSource random,
      BlockPos pos,
      Predicate<BlockState> statePredicate,
      int xRadius,
      int zRadius
   ) {
      Set<BlockPos> landGroundPatch = super.placeGroundPatch(world, config, random, pos, statePredicate, xRadius, zRadius);
      Set<BlockPos> lavaGroundPatch = new HashSet<>();
      MutableBlockPos mutable = new MutableBlockPos();

      for (BlockPos position : landGroundPatch) {
         if (((LavaPatchConfiguration)config).heightBounds.contains(position)
               && !isOdd(position) && !isExposed(world, position, mutable) && !isExposedCorner(world, position, mutable)) {
            lavaGroundPatch.add(position);
         }
      }

      for (BlockPos positionx : lavaGroundPatch) {
         world.setBlock(positionx, Blocks.LAVA.defaultBlockState(), 2);
      }

      return lavaGroundPatch;
   }

   @Override
   protected boolean placeGround(WorldGenLevel world, VegetationPatchConfiguration config,
         Predicate<BlockState> replaceable, RandomSource random, MutableBlockPos pos, int depth) {
      var bounds = ((LavaPatchConfiguration)config).heightBounds;
      for (int index = 0; index < depth; index++) {
         if (!bounds.contains(pos)) return index != 0;
         BlockState target = config.groundState.getState(random, pos);
         if (!target.is(world.getBlockState(pos).getBlock())) {
            if (!replaceable.test(world.getBlockState(pos))) return index != 0;
            world.setBlock(pos, target, 2);
            pos.move(config.surface.getDirection());
         }
      }
      return true;
   }

   private static boolean isExposed(WorldGenLevel world, BlockPos pos, MutableBlockPos mutable) {
      return isExposedDirection(world, pos, mutable, Direction.NORTH)
         || isExposedDirection(world, pos, mutable, Direction.EAST)
         || isExposedDirection(world, pos, mutable, Direction.SOUTH)
         || isExposedDirection(world, pos, mutable, Direction.WEST)
         || isExposedDirection(world, pos, mutable, Direction.DOWN);
   }

   private static boolean isExposedCorner(WorldGenLevel world, BlockPos pos, MutableBlockPos mutable) {
      return isExposedPosition(world, pos, mutable, 1, 1)
         || isExposedPosition(world, pos, mutable, 1, -1)
         || isExposedPosition(world, pos, mutable, -1, -1)
         || isExposedPosition(world, pos, mutable, -1, 1);
   }

   private static boolean isOdd(BlockPos pos) {
      return pos.getY() % 2 != 0;
   }

   private static boolean isExposedPosition(WorldGenLevel world, BlockPos pos, MutableBlockPos mutable, int x, int z) {
      mutable.setWithOffset(pos, x, 0, z);
      return world.getBlockState(mutable).isAir() || world.getBlockState(mutable).is(BlockTags.REPLACEABLE);
   }

   private static boolean isExposedDirection(WorldGenLevel world, BlockPos pos, MutableBlockPos mutable, Direction direction) {
      mutable.setWithOffset(pos, direction);
      return !world.getBlockState(mutable).isFaceSturdy(world, mutable, direction.getOpposite());
   }

   protected boolean placeVegetation(WorldGenLevel world, VegetationPatchConfiguration config, ChunkGenerator generator, RandomSource random, BlockPos pos) {
      if (!((LavaPatchConfiguration)config).heightBounds.contains(pos.above())
            || !((LavaPatchConfiguration)config).heightBounds.contains(pos.below(2))) return false;
      PaletteConfiguration palette = ((LavaPatchConfiguration)config).palette;
      if (random.nextFloat() < 0.1F) {
         world.setBlock(
            pos.above(),
            (BlockState)PaletteConfiguration.with(palette.state("magma_pad"), BlockStateProperties.HORIZONTAL_FACING, Plane.HORIZONTAL.getRandomDirection(random)),
            2
         );
         return true;
      } else {
         // Preserve the seeded random stream for subsequent magma-pad placements.
         random.nextFloat();
         return false;
      }
   }
}
