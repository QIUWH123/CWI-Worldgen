package net.quiwh.cwiworldgen.terrain;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

/** Subtractive only: the original heightfield remains a conservative upper envelope. */
public record ReferenceLandscapeDensity(BoundedCaveBiomeSource caves, int generationVersion) implements DensityFunction.SimpleFunction {
    public ReferenceLandscapeDensity(BoundedCaveBiomeSource caves) { this(caves, 1); }
    public ReferenceLandscapeDensity { TerrainGenerationProfile.requireSupported(generationVersion); }
    public static final MapCodec<ReferenceLandscapeDensity> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BoundedCaveBiomeSource.CODEC.codec().fieldOf("caves").forGetter(ReferenceLandscapeDensity::caves),
            Codec.intRange(1, 22).optionalFieldOf("generation_version", 1).forGetter(ReferenceLandscapeDensity::generationVersion)
    ).apply(instance, ReferenceLandscapeDensity::new));
    private static final KeyDispatchDataCodec<ReferenceLandscapeDensity> DISPATCH = KeyDispatchDataCodec.of(CODEC);

    @Override public double compute(FunctionContext context) { throw new IllegalStateException("Landscape density is not seed-bound"); }
    @Override public double minValue() { return -1.; }
    @Override public double maxValue() { return 64.; }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return DISPATCH; }

    public record Seeded(ReferenceTerrain terrain, boolean fullResolution) implements DensityFunction.SimpleFunction {
        public Seeded accurate() { return fullResolution ? this : new Seeded(terrain, true); }
        @Override public double compute(FunctionContext context) {
            return terrain.landscape().density(context.blockX(), context.blockY(), context.blockZ(),
                    fullResolution || ReferenceTerrainDensity.isAccurateSampling());
        }
        @Override public double minValue() { return -1.; }
        @Override public double maxValue() { return 64.; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return DISPATCH; }
    }
}
