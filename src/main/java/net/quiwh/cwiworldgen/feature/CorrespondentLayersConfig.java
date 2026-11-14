/*
 * CWI worldgen compatibility port. Algorithms adapted from Darker Depths 2.1.5,
 * by Naterbobber, Blackgear, Toxteer and Orcinus (upstream: All Rights Reserved).
 * This local port preserves generation behavior while injecting CWI block palettes.
 */
package net.quiwh.cwiworldgen.feature;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

public class CorrespondentLayersConfig implements FeatureConfiguration {
   public static final Codec<CorrespondentLayersConfig> CODEC = RecordCodecBuilder.create(
      p_161304_ -> p_161304_.group(
               TagKey.hashedCodec(Registries.BLOCK).fieldOf("replaceable").forGetter(config -> config.replaceable),
               BlockStateProvider.CODEC.listOf().fieldOf("layers").forGetter(config -> ImmutableList.copyOf(config.layers)),
               PlacedFeature.CODEC.fieldOf("vegetation_feature").forGetter(p_161320_ -> p_161320_.vegetationFeature),
               CaveSurface.CODEC.fieldOf("surface").forGetter(p_161318_ -> p_161318_.surface),
               IntProvider.codec(1, 128).fieldOf("depth").forGetter(p_161316_ -> p_161316_.depth),
               Codec.floatRange(0.0F, 1.0F).fieldOf("extra_bottom_block_chance").forGetter(p_161314_ -> p_161314_.extraBottomBlockChance),
               Codec.intRange(1, 256).fieldOf("vertical_range").forGetter(p_161312_ -> p_161312_.verticalRange),
               Codec.floatRange(0.0F, 1.0F).fieldOf("vegetation_chance").forGetter(p_161310_ -> p_161310_.vegetationChance),
               IntProvider.CODEC.fieldOf("xz_radius").forGetter(p_161308_ -> p_161308_.xzRadius),
               Codec.floatRange(0.0F, 1.0F).fieldOf("extra_edge_column_chance").forGetter(p_161306_ -> p_161306_.extraEdgeColumnChance),
               Codec.BOOL.fieldOf("xz_replace").forGetter(config -> config.xzReplace),
               WorldgenHeightBounds.CODEC.optionalFieldOf("height_bounds", WorldgenHeightBounds.UNBOUNDED).forGetter(config -> config.heightBounds)
            )
            .apply(p_161304_, CorrespondentLayersConfig::new)
   );
   public final TagKey<Block> replaceable;
   public final List<BlockStateProvider> layers;
   public final Holder<PlacedFeature> vegetationFeature;
   public final CaveSurface surface;
   public final IntProvider depth;
   public final float extraBottomBlockChance;
   public final int verticalRange;
   public final float vegetationChance;
   public final IntProvider xzRadius;
   public final float extraEdgeColumnChance;
   public final boolean xzReplace;
   public final WorldgenHeightBounds heightBounds;

   public CorrespondentLayersConfig(TagKey<Block> replaceableTags, List<BlockStateProvider> layers,
      Holder<PlacedFeature> feature, CaveSurface surface, IntProvider depth, float extraBottomBlockChance,
      int verticalRange, float vegetationChance, IntProvider xzRadius, float extraEdgeColumnChance, boolean xzReplace) {
      this(replaceableTags, layers, feature, surface, depth, extraBottomBlockChance, verticalRange,
         vegetationChance, xzRadius, extraEdgeColumnChance, xzReplace, WorldgenHeightBounds.UNBOUNDED);
   }

   public CorrespondentLayersConfig(
      TagKey<Block> replaceableTags,
      List<BlockStateProvider> layers,
      Holder<PlacedFeature> feature,
      CaveSurface p_161296_,
      IntProvider p_161297_,
      float p_161298_,
      int p_161299_,
      float p_161300_,
      IntProvider p_161301_,
      float p_161302_,
      boolean xzReplace,
      WorldgenHeightBounds heightBounds
   ) {
      this.replaceable = replaceableTags;
      this.layers = layers;
      this.vegetationFeature = feature;
      this.surface = p_161296_;
      this.depth = p_161297_;
      this.extraBottomBlockChance = p_161298_;
      this.verticalRange = p_161299_;
      this.vegetationChance = p_161300_;
      this.xzRadius = p_161301_;
      this.extraEdgeColumnChance = p_161302_;
      this.xzReplace = xzReplace;
      this.heightBounds = heightBounds;
   }
}
