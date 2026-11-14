package net.quiwh.cwiworldgen.placement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Filters heightmapped lake candidates using the same seeded district noise as the surface. */
public final class HazardDistrictFilter extends PlacementFilter {
    public static final MapCodec<HazardDistrictFilter> CODEC = RecordCodecBuilder.<HazardDistrictFilter>mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("noise").forGetter(value -> value.noise.location()),
            Codec.doubleRange(-4., 4.).fieldOf("min_noise").forGetter(value -> value.minNoise),
            Codec.doubleRange(-4., 4.).fieldOf("max_noise").forGetter(value -> value.maxNoise),
            Codec.intRange(-2032, 2031).fieldOf("min_y").forGetter(value -> value.minY),
            Codec.intRange(-2032, 2031).fieldOf("max_y").forGetter(value -> value.maxY),
            Codec.intRange(0, 32).optionalFieldOf("max_slope", 6).forGetter(value -> value.maxSlope)
    ).apply(instance, HazardDistrictFilter::new)).validate(value ->
            value.minNoise <= value.maxNoise && value.minY <= value.maxY
                    ? DataResult.success(value) : DataResult.error(() -> "District filter bounds are reversed"));

    private final ResourceKey<NormalNoise.NoiseParameters> noise;
    private final double minNoise;
    private final double maxNoise;
    private final int minY;
    private final int maxY;
    private final int maxSlope;

    public HazardDistrictFilter(ResourceLocation noise, double minNoise, double maxNoise,
            int minY, int maxY, int maxSlope) {
        this.noise = ResourceKey.create(Registries.NOISE, noise);
        this.minNoise = minNoise;
        this.maxNoise = maxNoise;
        this.minY = minY;
        this.maxY = maxY;
        this.maxSlope = maxSlope;
    }

    @Override
    protected boolean shouldPlace(PlacementContext context, RandomSource random, BlockPos position) {
        var districtNoise = context.getLevel().getLevel().getChunkSource().randomState().getOrCreateNoise(noise);
        double district = districtNoise.getValue(position.getX(), 0., position.getZ());
        return accepts(district, position.getX(), position.getZ(),
                (x, z) -> context.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z));
    }

    /** Pure eligibility calculation also used by native boundary/shape tests. */
    public boolean accepts(double district, int x, int z, IntBinaryOperator height) {
        if (district < minNoise || district > maxNoise) return false;
        int center = height.applyAsInt(x, z);
        if (center < minY || center > maxY) return false;
        int lowest = center;
        int highest = center;
        for (int dx = -4; dx <= 4; dx += 4) {
            for (int dz = -4; dz <= 4; dz += 4) {
                int sample = height.applyAsInt(x + dx, z + dz);
                lowest = Math.min(lowest, sample);
                highest = Math.max(highest, sample);
                if (highest - lowest > maxSlope) return false;
            }
        }
        return true;
    }

    @Override
    public PlacementModifierType<?> type() {
        return WorldgenPlacements.HAZARD_DISTRICT.get();
    }
}
