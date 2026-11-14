/*
 * CWI worldgen compatibility port. Algorithms adapted from Darker Depths 2.1.5,
 * by Naterbobber, Blackgear, Toxteer and Orcinus (upstream: All Rights Reserved).
 * This local port preserves generation behavior while injecting CWI block palettes.
 */
package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

public record SmallBoulderConfiguration(BlockStateProvider mainBlock, Optional<BlockStateProvider> decoratorBlock, float decoratorChance)
   implements FeatureConfiguration {
   public static final Codec<SmallBoulderConfiguration> CODEC = RecordCodecBuilder.create(
      instance -> instance.group(
               BlockStateProvider.CODEC.fieldOf("main_block").forGetter(SmallBoulderConfiguration::mainBlock),
               BlockStateProvider.CODEC.optionalFieldOf("decorator_block").forGetter(SmallBoulderConfiguration::decoratorBlock),
               Codec.FLOAT.fieldOf("decorator_chance").forGetter(SmallBoulderConfiguration::decoratorChance)
            )
            .apply(instance, SmallBoulderConfiguration::new)
   );
}
