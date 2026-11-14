package net.quiwh.cwiworldgen.carver;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;

/** Resource IDs avoid a configured-carver/biome registry dependency cycle. */
public final class TargetedCaveConfiguration extends CaveCarverConfiguration {
    public static final Codec<TargetedCaveConfiguration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("biome").forGetter(value -> value.biome),
            Settings.CODEC.fieldOf("settings").forGetter(value -> value.settings)
    ).apply(instance, TargetedCaveConfiguration::new));
    public final ResourceLocation biome;
    private final Settings settings;

    public TargetedCaveConfiguration(ResourceLocation biome, Settings settings) {
        super(settings.general(), settings.horizontal(), settings.vertical(), settings.floor());
        this.biome = biome;
        this.settings = settings;
    }

    /** Mirrors vanilla's cave codec while retaining its non-public floor provider. */
    public record Settings(CarverConfiguration general, FloatProvider horizontal, FloatProvider vertical, FloatProvider floor) {
        public static final Codec<Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                CarverConfiguration.CODEC.forGetter(Settings::general),
                FloatProvider.CODEC.fieldOf("horizontal_radius_multiplier").forGetter(Settings::horizontal),
                FloatProvider.CODEC.fieldOf("vertical_radius_multiplier").forGetter(Settings::vertical),
                FloatProvider.codec(-1.F, 1.F).fieldOf("floor_level").forGetter(Settings::floor)
        ).apply(instance, Settings::new));
    }
}
