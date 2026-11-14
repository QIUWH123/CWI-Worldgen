/* Layer pattern schema adapted from Create. Copyright The Create Team. MIT; see CREATE_CODE_LICENSE.txt. */
package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration.TargetBlockState;

/** The original Create layers plus a separate, reduced compound pocket palette. */
public record LayeredDepositConfiguration(List<List<Layer>> layerPatterns, int size,
        float discardChanceOnAirExposure, BlockState deposit, float pocketFraction, float groupRetention,
        VeinSizeSettings sizeSettings) implements FeatureConfiguration {
    public LayeredDepositConfiguration(List<List<Layer>> layerPatterns, int size,
            float discardChanceOnAirExposure, BlockState deposit, float pocketFraction) {
        this(layerPatterns, size, discardChanceOnAirExposure, deposit, pocketFraction, .3F, VeinSizeSettings.FIXED);
    }

    public LayeredDepositConfiguration(List<List<Layer>> layerPatterns, int size,
            float discardChanceOnAirExposure, BlockState deposit, float pocketFraction, VeinSizeSettings sizeSettings) {
        this(layerPatterns, size, discardChanceOnAirExposure, deposit, pocketFraction, .3F, sizeSettings);
    }
    public record Layer(List<List<TargetBlockState>> targets, int minSize, int maxSize, int weight) {
        public static final Codec<Layer> CODEC = RecordCodecBuilder.<Layer>create(instance -> instance.group(
            TargetBlockState.CODEC.listOf().listOf().fieldOf("targets").forGetter(Layer::targets),
            Codec.intRange(1, 64).fieldOf("min_size").forGetter(Layer::minSize),
            Codec.intRange(1, 64).fieldOf("max_size").forGetter(Layer::maxSize),
            Codec.intRange(1, 100000).fieldOf("weight").forGetter(Layer::weight)
        ).apply(instance, Layer::new)).validate(layer -> layer.maxSize >= layer.minSize
            && !layer.targets.isEmpty() && layer.targets.size() <= 256
            && layer.targets.stream().allMatch(choice -> !choice.isEmpty())
                ? DataResult.success(layer)
                : DataResult.error(() -> "Layer targets must be nonempty and sizes ordered"));
    }

    public static final Codec<LayeredDepositConfiguration> CODEC = RecordCodecBuilder.<LayeredDepositConfiguration>create(instance -> instance.group(
        Layer.CODEC.listOf().listOf().fieldOf("layer_patterns").forGetter(LayeredDepositConfiguration::layerPatterns),
        Codec.intRange(2, 64).fieldOf("size").forGetter(LayeredDepositConfiguration::size),
        Codec.floatRange(0, 1).fieldOf("discard_chance_on_air_exposure").forGetter(LayeredDepositConfiguration::discardChanceOnAirExposure),
        BlockState.CODEC.fieldOf("deposit").forGetter(LayeredDepositConfiguration::deposit),
        Codec.floatRange(.01F, 1).fieldOf("pocket_fraction").forGetter(LayeredDepositConfiguration::pocketFraction),
        Codec.floatRange(0, 1).optionalFieldOf("group_retention", .3F).forGetter(LayeredDepositConfiguration::groupRetention),
        VeinSizeSettings.CODEC.optionalFieldOf("size_settings", VeinSizeSettings.FIXED).forGetter(LayeredDepositConfiguration::sizeSettings)
    ).apply(instance, LayeredDepositConfiguration::new)).validate(config -> !config.layerPatterns.isEmpty()
        && config.layerPatterns.size() <= 64
        && config.layerPatterns.stream().allMatch(pattern -> pattern.size() >= 2 && pattern.size() <= 64)
        && !config.deposit.isAir() && config.deposit.getFluidState().isEmpty() && !config.deposit.hasBlockEntity()
            ? DataResult.success(config)
            : DataResult.error(() -> "Layered deposits require valid multi-layer patterns and a solid deposit block"));
}
