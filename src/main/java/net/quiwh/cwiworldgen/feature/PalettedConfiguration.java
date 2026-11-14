package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/** Wraps the original configuration and an explicitly supplied CWI block palette. */
public record PalettedConfiguration<T extends FeatureConfiguration>(T settings, PaletteConfiguration palette,
        WorldgenHeightBounds heightBounds) implements FeatureConfiguration {
    public PalettedConfiguration(T settings, PaletteConfiguration palette) {
        this(settings, palette, WorldgenHeightBounds.UNBOUNDED);
    }
    public static <T extends FeatureConfiguration> Codec<PalettedConfiguration<T>> codec(Codec<T> settings, String... required) {
        return RecordCodecBuilder.<PalettedConfiguration<T>>create(instance -> instance.group(
            settings.fieldOf("settings").forGetter(PalettedConfiguration<T>::settings),
            PaletteConfiguration.PALETTE_CODEC.fieldOf("palette").forGetter(value -> value.palette().palette()),
            WorldgenHeightBounds.CODEC.optionalFieldOf("height_bounds", WorldgenHeightBounds.UNBOUNDED).forGetter(PalettedConfiguration<T>::heightBounds)
        ).apply(instance, (config, palette, bounds) -> new PalettedConfiguration<>(config, new PaletteConfiguration(palette), bounds))).validate(config ->
            PaletteConfiguration.validate(config.palette(), required).map(ignored -> config));
    }
}
