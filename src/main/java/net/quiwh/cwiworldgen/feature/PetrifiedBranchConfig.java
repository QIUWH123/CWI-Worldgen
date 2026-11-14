/*
 * CWI local compatibility port of Darker Depths 2.1.5 worldgen algorithms.
 * Original authors: Naterbobber, Blackgear, Toxteer and Orcinus. All Rights Reserved.
 */
package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

public record PetrifiedBranchConfig(int minLength, int maxLength) implements FeatureConfiguration {
   public static final Codec<PetrifiedBranchConfig> CODEC = RecordCodecBuilder.create(
      instance -> instance.group(
               Codec.intRange(0, 32).fieldOf("minLength").forGetter(config -> config.minLength),
               Codec.intRange(0, 32).fieldOf("maxLength").forGetter(config -> config.maxLength)
            )
            .apply(instance, PetrifiedBranchConfig::new)
   );
}
