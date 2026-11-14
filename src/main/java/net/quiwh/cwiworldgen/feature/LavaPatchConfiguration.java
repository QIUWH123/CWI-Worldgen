package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.feature.configurations.VegetationPatchConfiguration;

/** Native vegetation patch settings plus the block used above lava pools. */
public final class LavaPatchConfiguration extends VegetationPatchConfiguration {
    public static final Codec<LavaPatchConfiguration> CODEC = RecordCodecBuilder.<LavaPatchConfiguration>create(instance -> instance.group(
        VegetationPatchConfiguration.CODEC.fieldOf("settings").forGetter(value -> value),
        PaletteConfiguration.PALETTE_CODEC.fieldOf("palette").forGetter(value -> value.palette.palette()),
        WorldgenHeightBounds.CODEC.optionalFieldOf("height_bounds", WorldgenHeightBounds.UNBOUNDED).forGetter(value -> value.heightBounds)
    ).apply(instance, (settings, palette, bounds) -> new LavaPatchConfiguration(settings, new PaletteConfiguration(palette), bounds))).validate(config ->
            PaletteConfiguration.validate(config.palette, "magma_pad").map(ignored -> config));
    public final PaletteConfiguration palette;
    public final WorldgenHeightBounds heightBounds;

    public LavaPatchConfiguration(VegetationPatchConfiguration settings, PaletteConfiguration palette) {
        this(settings, palette, WorldgenHeightBounds.UNBOUNDED);
    }

    public LavaPatchConfiguration(VegetationPatchConfiguration settings, PaletteConfiguration palette, WorldgenHeightBounds heightBounds) {
        super(settings.replaceable, settings.groundState, settings.vegetationFeature, settings.surface,
            settings.depth, settings.extraBottomBlockChance, settings.verticalRange, settings.vegetationChance,
            settings.xzRadius, settings.extraEdgeColumnChance);
        this.palette = palette;
        this.heightBounds = heightBounds;
    }
}
