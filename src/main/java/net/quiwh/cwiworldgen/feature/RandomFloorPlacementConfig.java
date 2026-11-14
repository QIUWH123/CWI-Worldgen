/*
 * CWI worldgen compatibility port. Algorithms adapted from Darker Depths 2.1.5,
 * by Naterbobber, Blackgear, Toxteer and Orcinus (upstream: All Rights Reserved).
 * This local port preserves generation behavior while injecting CWI block palettes.
 */
package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider;

public record RandomFloorPlacementConfig(
   WeightedStateProvider stateProvider, HolderSet<Block> validFloorBlocks, int tries, int xzRadius, int yHeight, int placeCount,
   WorldgenHeightBounds heightBounds
) implements FeatureConfiguration {
   public RandomFloorPlacementConfig(WeightedStateProvider stateProvider, HolderSet<Block> validFloorBlocks,
      int tries, int xzRadius, int yHeight, int placeCount) {
      this(stateProvider, validFloorBlocks, tries, xzRadius, yHeight, placeCount, WorldgenHeightBounds.UNBOUNDED);
   }
   public static final Codec<RandomFloorPlacementConfig> CODEC = RecordCodecBuilder.create(
      instance -> instance.group(
               WeightedStateProvider.CODEC.fieldOf("state_provider").forGetter(RandomFloorPlacementConfig::stateProvider),
               RegistryCodecs.homogeneousList(Registries.BLOCK).fieldOf("valid_floor_blocks").forGetter(RandomFloorPlacementConfig::validFloorBlocks),
               Codec.INT.fieldOf("tries").forGetter(RandomFloorPlacementConfig::tries),
               Codec.INT.fieldOf("xz_radius").forGetter(RandomFloorPlacementConfig::xzRadius),
               Codec.INT.fieldOf("y_height").forGetter(RandomFloorPlacementConfig::yHeight),
               Codec.INT.fieldOf("placement_count").forGetter(RandomFloorPlacementConfig::placeCount),
               WorldgenHeightBounds.CODEC.optionalFieldOf("height_bounds", WorldgenHeightBounds.UNBOUNDED).forGetter(RandomFloorPlacementConfig::heightBounds)
            )
            .apply(instance, RandomFloorPlacementConfig::new)
   );
}
