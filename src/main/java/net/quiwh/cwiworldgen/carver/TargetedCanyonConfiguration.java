package net.quiwh.cwiworldgen.carver;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;

public final class TargetedCanyonConfiguration extends CanyonCarverConfiguration {
    public static final Codec<TargetedCanyonConfiguration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("biome").forGetter(value -> value.biome),
            CanyonCarverConfiguration.CODEC.fieldOf("settings").forGetter(value -> value.settings)
    ).apply(instance, TargetedCanyonConfiguration::new));
    public final ResourceLocation biome;
    private final CanyonCarverConfiguration settings;

    public TargetedCanyonConfiguration(ResourceLocation biome, CanyonCarverConfiguration settings) {
        super(settings, settings.verticalRotation, settings.shape);
        this.biome = biome;
        this.settings = settings;
    }
}
