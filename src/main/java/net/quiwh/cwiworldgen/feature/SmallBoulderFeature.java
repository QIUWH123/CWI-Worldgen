/*
 * CWI worldgen compatibility port. Algorithms adapted from Darker Depths 2.1.5,
 * by Naterbobber, Blackgear, Toxteer and Orcinus (upstream: All Rights Reserved).
 * This local port preserves generation behavior while injecting CWI block palettes.
 */
package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import net.quiwh.cwiworldgen.feature.SmallBoulderConfiguration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.quiwh.cwiworldgen.terrain.LocalRockMaterials;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

public class SmallBoulderFeature extends Feature<SmallBoulderConfiguration> {
   public SmallBoulderFeature(Codec<SmallBoulderConfiguration> codec) {
      super(codec);
   }

   public boolean place(FeaturePlaceContext<SmallBoulderConfiguration> context) {
      int version = (Object) context.level().getLevel().getChunkSource().randomState() instanceof TerrainRandomState state
            && state.cwiWorldgen$terrain() != null ? state.cwiWorldgen$terrain().generationVersion() : 0;
      return place(context, version);
   }

   boolean place(FeaturePlaceContext<SmallBoulderConfiguration> context, int generationVersion) {
      WorldGenLevel level = context.level();
      BlockPos origin = context.origin();
      RandomSource random = context.random();
      SmallBoulderConfiguration config = (SmallBoulderConfiguration)context.config();
      int tallHeight = 2;
      int shortHeight = 1;
      Map<BlockPos, Integer> footprint = new HashMap<>();
      boolean isNorthWestToSouthEast = random.nextBoolean();
      int heightA = random.nextBoolean() ? tallHeight : shortHeight;
      int heightB = heightA == tallHeight ? shortHeight : tallHeight;
      if (isNorthWestToSouthEast) {
         for (int x = -1; x <= 0; x++) {
            for (int z = -1; z <= 0; z++) {
               footprint.put(new BlockPos(x, 0, z), heightA);
            }
         }

         for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) {
               footprint.merge(new BlockPos(x, 0, z), heightB, Math::max);
            }
         }
      } else {
         for (int x = 0; x <= 1; x++) {
            for (int z = -1; z <= 0; z++) {
               footprint.put(new BlockPos(x, 0, z), heightA);
            }
         }

         for (int x = -1; x <= 0; x++) {
            for (int z = 0; z <= 1; z++) {
               footprint.merge(new BlockPos(x, 0, z), heightB, Math::max);
            }
         }
      }

      if (generationVersion >= 12) {
         // Validate every possible read/write before the first ground lookup. Both placement
         // heights are included because the legacy shape can shift down one block.
         for (Entry<BlockPos, Integer> entry : footprint.entrySet()) {
            for (int y = -2; y < entry.getValue(); y++) {
               if (!ownedAccess(level, origin, origin.offset(entry.getKey().getX(), y, entry.getKey().getZ()))) return false;
            }
         }
         if (config.decoratorBlock().isPresent() && config.decoratorChance() > 0.0F) {
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
               if (Math.abs(x) == 2 && Math.abs(z) == 2) continue;
               // Decorator scans y=3..-1 and may write one above a solid block.
               for (int y = -2; y <= 4; y++)
                  if (!ownedAccess(level, origin, origin.offset(x, y, z))) return false;
            }
         }
      }

      boolean needsShift = false;

      for (BlockPos localPos : footprint.keySet()) {
         BlockPos groundCheck = origin.offset(localPos.getX(), -1, localPos.getZ());
         if (!level.getBlockState(groundCheck).isSolidRender(level, groundCheck)) {
            needsShift = true;
            break;
         }
      }

      BlockPos placementOrigin = needsShift ? origin.below() : origin;
      if (needsShift) {
         for (BlockPos localPosx : footprint.keySet()) {
            BlockPos groundCheck = placementOrigin.offset(localPosx.getX(), -1, localPosx.getZ());
            if (!level.getBlockState(groundCheck).isSolidRender(level, groundCheck)) {
               return false;
            }
         }
      }

      Map<BlockPos, LocalRockMaterials.Family> hosts = new HashMap<>();
      if (generationVersion >= 12) {
         for (BlockPos localPos : footprint.keySet()) {
            BlockPos supportPos = placementOrigin.offset(localPos.getX(), -1, localPos.getZ());
            if (!level.hasChunk(supportPos.getX() >> 4, supportPos.getZ() >> 4)) return false;
            BlockState support = level.getBlockState(supportPos);
            if (!LocalRockMaterials.natural(support) || !support.isFaceSturdy(level, supportPos, Direction.UP)) return false;
            var host = LocalRockMaterials.find(level, supportPos, 12);
            if (host != null) hosts.put(localPos, host);
         }
      }

      for (Entry<BlockPos, Integer> entry : footprint.entrySet()) {
         int x = entry.getKey().getX();
         int z = entry.getKey().getZ();
         int height = entry.getValue();

         for (int y = 0; y < height; y++) {
            BlockPos checkPos = placementOrigin.offset(x, y, z);
            BlockState checkState = level.getBlockState(checkPos);
            if (generationVersion >= 12 && (!level.ensureCanWrite(checkPos) || !checkState.getFluidState().isEmpty())) return false;
            if (!checkState.isAir() && !checkState.canBeReplaced()) {
               return false;
            }
         }
      }

      Map<BlockPos, BlockState> preparedStates = new HashMap<>();
      if (generationVersion >= 12) {
         // Sample in the original placement order, validating the complete palette before
         // any write. An unresolved generic rock must not leave a partial mixed boulder.
         for (Entry<BlockPos, Integer> entry : footprint.entrySet()) {
            var host = hosts.get(entry.getKey());
            for (int y = 0; y < entry.getValue(); y++) {
               BlockPos targetPos = placementOrigin.offset(entry.getKey().getX(), y, entry.getKey().getZ());
               BlockState requested = config.mainBlock().getState(random, targetPos);
               if (host == null && LocalRockMaterials.family(requested) != null) return false;
               preparedStates.put(targetPos, LocalRockMaterials.matching(requested, host));
            }
         }
      }

      boolean placedAnyBlock = false;

      for (Entry<BlockPos, Integer> entry : footprint.entrySet()) {
         int x = entry.getKey().getX();
         int z = entry.getKey().getZ();
         int height = entry.getValue();

         for (int yx = 0; yx < height; yx++) {
            BlockPos targetPos = placementOrigin.offset(x, yx, z);
            BlockState mainState = generationVersion >= 12 ? preparedStates.get(targetPos)
                  : config.mainBlock().getState(random, targetPos);
            this.setBlock(level, targetPos, mainState);
            placedAnyBlock = true;
         }
      }

      if (placedAnyBlock && config.decoratorBlock().isPresent() && config.decoratorChance() > 0.0F) {
         BlockStateProvider decoratorProvider = config.decoratorBlock().get();

         for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
               if ((Math.abs(x) != 2 || Math.abs(z) != 2) && !(random.nextFloat() >= config.decoratorChance() / 2.0F)) {
                  for (int yx = 3; yx >= -1; yx--) {
                     BlockPos surfaceCheck = placementOrigin.offset(x, yx, z);
                     BlockState surfaceState = level.getBlockState(surfaceCheck);
                     if (surfaceState.isSolidRender(level, surfaceCheck)) {
                        BlockPos decoratorPos = surfaceCheck.above();
                        if (level.isEmptyBlock(decoratorPos)) {
                           this.setBlock(level, decoratorPos, decoratorProvider.getState(random, decoratorPos));
                        }
                        break;
                     }
                  }
               }
            }
         }
      }

      return placedAnyBlock;
   }
   private static boolean ownedAccess(WorldGenLevel level, BlockPos origin, BlockPos pos) {
      return (pos.getX() >> 4) == (origin.getX() >> 4) && (pos.getZ() >> 4) == (origin.getZ() >> 4)
            && level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
            && !level.isOutsideBuildHeight(pos) && level.ensureCanWrite(pos);
   }

}
