package net.quiwh.cwiworldgen.terrain;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.Supplier;
import net.minecraft.core.QuartPos;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.biome.Continentalness;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainCategory;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.WorldSettings.ControlPoints;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.Tile;
import net.quiwh.cwiworldgen.terrain.reference.noise.NoiseUtil;

/** A serializable marker; RandomState binds it to that world's terrain engine. */
public record ReferenceTerrainDensity(Field field, int generationVersion) implements DensityFunction.SimpleFunction {
    public ReferenceTerrainDensity(Field field) { this(field, 1); }
    public ReferenceTerrainDensity { TerrainGenerationProfile.requireSupported(generationVersion); }
    public static final MapCodec<ReferenceTerrainDensity> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            StringRepresentable.fromEnum(Field::values).fieldOf("field").forGetter(ReferenceTerrainDensity::field),
            Codec.intRange(1, 22).optionalFieldOf("generation_version", 1).forGetter(ReferenceTerrainDensity::generationVersion)
    ).apply(instance, ReferenceTerrainDensity::new));
    private static final KeyDispatchDataCodec<ReferenceTerrainDensity> DISPATCH = KeyDispatchDataCodec.of(CODEC);
    private static final ThreadLocal<Boolean> ACCURATE_TERRAIN = new ThreadLocal<>();

    public static boolean isAccurateSampling() { return Boolean.TRUE.equals(ACCURATE_TERRAIN.get()); }

    /** Keeps a structure's native projection and terrain survey on the completed terrain. */
    public static <T> T withAccurateTerrain(Supplier<T> action) {
        Boolean previous = ACCURATE_TERRAIN.get();
        ACCURATE_TERRAIN.set(true);
        try {
            return action.get();
        } finally {
            if (previous == null) ACCURATE_TERRAIN.remove();
            else ACCURATE_TERRAIN.set(previous);
        }
    }

    public enum Field implements StringRepresentable {
        HEIGHT, HEIGHT_OFFSET, CONTINENT, EROSION, WEIRDNESS, CAVE_ROOF;
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    @Override public double compute(FunctionContext context) {
        throw new IllegalStateException("Reference terrain must be bound to a world seed before sampling");
    }
    @Override public double minValue() { return -1_000_000.; }
    @Override public double maxValue() { return 1_000_000.; }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return DISPATCH; }

    /** Exact float quantization and offset used by the supplied ClampToNearestUnit. */
    public static double heightOffset(float height) {
        float quantized = ((int) (height * 256.) + 1) / 256.F;
        return (-0.50375F - 0.5F) + 2. * quantized;
    }

    public static final class Seeded implements DensityFunction.SimpleFunction {
        private final ReferenceTerrain terrain;
        private final Field field;
        private final boolean accurate;
        private final Levels levels;
        private final ControlPoints points;
        private final ThreadLocal<Sample> sample = ThreadLocal.withInitial(Sample::new);

        public Seeded(ReferenceTerrain terrain, Field field, boolean accurate) {
            this.terrain = terrain;
            this.field = field;
            this.accurate = accurate;
            this.levels = terrain.levels();
            this.points = terrain.preset().world().controlPoints;
        }

        public Seeded accurate() { return accurate ? this : new Seeded(terrain, field, true); }

        /** Locate may reuse these exact owned fields across Y, within the same sampling mode. */
        public boolean searchYIndependent() { return field != Field.CAVE_ROOF; }

        @Override public double compute(FunctionContext context) {
            if (field == Field.CAVE_ROOF && terrain.generationVersion() < 8) return -64.;
            int x = context.blockX(), z = context.blockZ();
            boolean fullResolution = accurate || Boolean.TRUE.equals(ACCURATE_TERRAIN.get());
            if (!fullResolution) {
                x = QuartPos.toBlock(QuartPos.fromBlock(x));
                z = QuartPos.toBlock(QuartPos.fromBlock(z));
            }
            Sample cached = sample.get();
            if (!cached.present || cached.x != x || cached.z != z || cached.accurate != fullResolution) {
                if (fullResolution) {
                    int chunkX = x >> 4, chunkZ = z >> 4;
                    if (cached.reader == null || cached.reader.getChunkX() != chunkX
                            || cached.reader.getChunkZ() != chunkZ) {
                        cached.reader = terrain.prepareChunk(chunkX, chunkZ);
                    }
                    cached.cell = cached.reader.getCell(x, z);
                } else {
                    cached.cell = terrain.sampleRawForDensity(x, z);
                }
                cached.x = x;
                cached.z = z;
                cached.present = true;
                cached.accurate = fullResolution;
            }
            Cell cell = cached.cell;
            return switch (field) {
                case HEIGHT -> cell.height;
                case HEIGHT_OFFSET -> heightOffset(cell.height);
                case EROSION -> cell.erosion;
                case WEIRDNESS -> cell.weirdness;
                case CONTINENT -> continent(cell, levels, points);
                case CAVE_ROOF -> ReferenceCaveRoof.density(terrain.generationVersion(), terrain.seed(),
                        x, context.blockY(), z, cell.height * 256.);
            };
        }
        @Override public double minValue() { return -1_000_000.; }
        @Override public double maxValue() { return 1_000_000.; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return DISPATCH; }
        private static final class Sample {
            int x, z;
            boolean present;
            boolean accurate;
            Cell cell;
            Tile.Chunk reader;
        }
    }

    /** Original CellSampler.Field.CONTINENT mapping; CWI supplies the biome palette. */
    private static float continent(Cell cell, Levels levels, ControlPoints points) {
        if (cell.terrain == TerrainType.MUSHROOM_FIELDS) return Continentalness.MUSHROOM_FIELDS.mid();
        if (cell.terrain.isDeepOcean()) {
            float alpha = NoiseUtil.clamp(cell.continentEdge, 0.F, points.deepOcean);
            alpha = NoiseUtil.lerp(alpha, 0.F, points.deepOcean, 0.F, 1.F);
            return NoiseUtil.lerp(Continentalness.DEEP_OCEAN.min() + .05F, Continentalness.DEEP_OCEAN.max(), alpha);
        }
        if (cell.terrain.isShallowOcean()) {
            float alpha = NoiseUtil.clamp(cell.continentEdge, points.deepOcean, points.shallowOcean);
            alpha = NoiseUtil.lerp(alpha, points.deepOcean, points.shallowOcean, 0.F, .98F);
            return NoiseUtil.lerp(Continentalness.OCEAN.min(), Continentalness.OCEAN.max(), alpha);
        }
        if (cell.terrain.getDelegate() == TerrainCategory.BEACH && cell.height + cell.beachNoise < levels.water(5)) {
            float alpha = NoiseUtil.clamp(cell.continentEdge, points.shallowOcean, points.beach);
            alpha = NoiseUtil.lerp(alpha, points.shallowOcean, points.beach, 0.F, 1.F);
            return NoiseUtil.lerp(Continentalness.COAST.min(), Continentalness.COAST.max(), alpha);
        }
        float alpha = NoiseUtil.clamp(cell.continentEdge, points.beach, points.inland);
        alpha = NoiseUtil.lerp(alpha, points.beach, points.inland, 0.F, 1.F);
        return NoiseUtil.lerp(Continentalness.NEAR_INLAND.mid(), Continentalness.FAR_INLAND.max(), alpha);
    }
}
