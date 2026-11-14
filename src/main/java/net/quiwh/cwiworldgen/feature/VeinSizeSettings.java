package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;

/** Weighted sizes capped at the former body size, with more large veins in matching biomes. */
public record VeinSizeSettings(boolean enabled, List<ResourceLocation> favoredBiomes) {
    public static final VeinSizeSettings FIXED = new VeinSizeSettings(false, List.of());

    public static final Codec<VeinSizeSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.BOOL.optionalFieldOf("enabled", false).forGetter(VeinSizeSettings::enabled),
        ResourceLocation.CODEC.listOf().optionalFieldOf("favored_biomes", List.of()).forGetter(VeinSizeSettings::favoredBiomes)
    ).apply(instance, VeinSizeSettings::new));

    public VeinSizeSettings {
        favoredBiomes = List.copyOf(favoredBiomes);
    }

    public float scale(RandomSource random, Holder<Biome> biome) {
        // Fixed-size legacy configurations must not advance the geometry RNG.
        if (!enabled) return 1F;
        boolean favored = biome.unwrapKey().map(key -> favoredBiomes.contains(key.location())).orElse(false);
        float roll = random.nextFloat();
        if (roll < (favored ? .4F : .6F)) return .6F;
        if (roll < (favored ? .75F : .9F)) return .8F;
        return 1F;
    }
}
