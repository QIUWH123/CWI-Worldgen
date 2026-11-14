package net.quiwh.cwiworldgen.biome;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Finite irregular cave districts separated by ordinary caves, below a surface climate source. */
public final class BoundedCaveBiomeSource extends BiomeSource {
    private static final int BORDER = 16;
    private static final double MIN_DEPTH = .2;
    public static final MapCodec<BoundedCaveBiomeSource> CODEC = RecordCodecBuilder.<BoundedCaveBiomeSource>mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("surface_source").forGetter(value -> value.surfaceSource),
            Biome.CODEC.fieldOf("fallback_biome").forGetter(value -> value.fallbackBiome),
            Codec.intRange(-2032, 2031).fieldOf("cave_ceiling").forGetter(value -> value.caveCeiling),
            Codec.intRange(64, 4096).fieldOf("cell_size").forGetter(value -> value.cellSize),
            Region.CODEC.listOf().fieldOf("regions").forGetter(value -> value.regions),
            Codec.intRange(1, 22).optionalFieldOf("generation_version", 1).forGetter(value -> value.generationVersion)
    ).apply(instance, BoundedCaveBiomeSource::new)).validate(value -> {
        if (value.regions.isEmpty()) return DataResult.error(() -> "At least one cave region is required");
        if (value.cellSize % 4 != 0) return DataResult.error(() -> "Cave cell size must be a multiple of four blocks");
        if (value.regions.stream().anyMatch(region -> region.maxDiameter() > value.cellSize - BORDER * 2))
            return DataResult.error(() -> "Cave diameter must leave at least sixteen blocks on each cell border");
        return DataResult.success(value);
    });

    public record Region(Holder<Biome> biome, int minY, int maxY, int minDiameter, int maxDiameter, int weight) {
        public static final Codec<Region> CODEC = RecordCodecBuilder.<Region>create(instance -> instance.group(
                Biome.CODEC.fieldOf("biome").forGetter(Region::biome),
                Codec.intRange(-2032, 2031).fieldOf("min_y").forGetter(Region::minY),
                Codec.intRange(-2032, 2031).fieldOf("max_y").forGetter(Region::maxY),
                Codec.intRange(16, 4096).fieldOf("min_diameter").forGetter(Region::minDiameter),
                Codec.intRange(16, 4096).fieldOf("max_diameter").forGetter(Region::maxDiameter),
                Codec.intRange(1, 10000).fieldOf("weight").forGetter(Region::weight)
        ).apply(instance, Region::new)).validate(value ->
                value.minY() <= value.maxY() && value.maxY() - value.minY() >= 12
                        && value.minDiameter() <= value.maxDiameter()
                        ? DataResult.success(value)
                        : DataResult.error(() -> "Cave region height/diameter bounds are reversed or too narrow"));
    }

    private final BiomeSource surfaceSource;
    private final Holder<Biome> fallbackBiome;
    private final int caveCeiling;
    private final int cellSize;
    private final List<Region> regions;
    private final int totalWeight;
    private final int generationVersion;

    // One immutable entry bounds memory and safely publishes a fingerprint shared by chunk workers.
    // Sampling a different world's Climate.Sampler never inherits the preceding world's seed.
    private volatile SamplerFingerprint lastFingerprint;

    public BoundedCaveBiomeSource(BiomeSource surfaceSource, Holder<Biome> fallbackBiome,
            int caveCeiling, int cellSize, List<Region> regions) {
        this(surfaceSource, fallbackBiome, caveCeiling, cellSize, regions, 1);
    }

    public BoundedCaveBiomeSource(BiomeSource surfaceSource, Holder<Biome> fallbackBiome,
            int caveCeiling, int cellSize, List<Region> regions, int generationVersion) {
        this.surfaceSource = surfaceSource;
        this.fallbackBiome = fallbackBiome;
        this.caveCeiling = caveCeiling;
        this.cellSize = cellSize;
        this.regions = List.copyOf(regions);
        this.totalWeight = regions.stream().mapToInt(Region::weight).sum();
        this.generationVersion = net.quiwh.cwiworldgen.terrain.TerrainGenerationProfile.requireSupported(generationVersion);
    }

    public int generationVersion() { return generationVersion; }

    // Search-only accessors; generation continues through getNoiseBiome unchanged.
    BiomeSource searchSurfaceSource() { return surfaceSource; }
    Holder<Biome> searchFallbackBiome() { return fallbackBiome; }

    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius,
            int horizontalStep, int verticalStep, Predicate<Holder<Biome>> target,
            Climate.Sampler sampler, LevelReader level) {
        var query = CwiBiomeSearch.create(this, origin, radius, horizontalStep, verticalStep,
                target, sampler, level.getMinBuildHeight(), level.getMaxBuildHeight(), () -> false);
        while (!query.step(1024, Long.MAX_VALUE)) { }
        return query.result();
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() { return CODEC; }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.concat(surfaceSource.possibleBiomes().stream(),
                Stream.concat(Stream.of(fallbackBiome), regions.stream().map(Region::biome)));
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        int x = QuartPos.toBlock(quartX);
        int y = QuartPos.toBlock(quartY);
        int z = QuartPos.toBlock(quartZ);
        if (y + 5 > caveCeiling || sampler.depth().compute(new DensityFunction.SinglePointContext(x, y, z)) <= MIN_DEPTH)
            return surfaceSource.getNoiseBiome(quartX, quartY, quartZ, sampler);

        District district = districtAt(x, z, sampler);
        return district.contains(x, y, z) ? district.region().biome() : fallbackBiome;
    }

    /** The same immutable district geometry is used by biome selection and landscape density. */
    public District districtAt(int x, int z, Climate.Sampler sampler) {
        int cellX = Math.floorDiv(x, cellSize);
        int cellZ = Math.floorDiv(z, cellSize);
        long cellSeed = mix(fingerprint(sampler) ^ mix((long) cellX * 0x632be59bd9b4e019L)
                ^ mix((long) cellZ * 0x9e3779b97f4a7c15L));
        Region region = chooseRegion(cellSeed);
        double radius = .5 * (region.minDiameter()
                + unit(cellSeed + 1) * (region.maxDiameter() - region.minDiameter()));
        double room = cellSize - BORDER * 2. - radius * 2.;
        double centerX = (double) cellX * cellSize + BORDER + radius + unit(cellSeed + 2) * room;
        double centerZ = (double) cellZ * cellSize + BORDER + radius + unit(cellSeed + 3) * room;
        return new District(region, centerX, centerZ, radius, cellSeed);
    }

    public int cellSize() { return cellSize; }
    public int caveCeiling() { return caveCeiling; }

    /** Returns only an actual special cave district, never the ordinary-cave fallback. */
    public Region caveRegionAtBlock(int x, int y, int z, Climate.Sampler sampler) {
        int qx = QuartPos.toBlock(QuartPos.fromBlock(x));
        int qy = QuartPos.toBlock(QuartPos.fromBlock(y));
        int qz = QuartPos.toBlock(QuartPos.fromBlock(z));
        if (qy + 5 > caveCeiling || sampler.depth().compute(
                new DensityFunction.SinglePointContext(qx, qy, qz)) <= MIN_DEPTH) return null;
        District district = districtAt(qx, qz, sampler);
        return district.contains(qx, qy, qz) ? district.region() : null;
    }

    public record District(Region region, double centerX, double centerZ, double radius, long seed) {
        public boolean contains(int x, int y, int z) {
            // Reserve the entire biome zoom interval [4q-2,4q+5].
            if (y - 2 < region.minY() || y + 5 > region.maxY()) return false;
            double dx = x - centerX, dz = z - centerZ;
            if (dx * dx + dz * dz > radius * radius) return false;
            double rotation = unit(seed + 4) * Math.PI * 2.;
            double localX = dx * Math.cos(rotation) + dz * Math.sin(rotation);
            double localZ = (-dx * Math.sin(rotation) + dz * Math.cos(rotation)) / (.78 + unit(seed + 5) * .22);
            double angle = Math.atan2(localZ, localX);
            double outline = .88 + .07 * Math.sin(angle * 3. + unit(seed + 6) * Math.PI * 2.)
                    + .05 * Math.sin(angle * 5. + unit(seed + 7) * Math.PI * 2.);
            double fraction = (y - region.minY()) / (double) (region.maxY() - region.minY());
            double edge = radius * outline * (.82 + .18 * Math.sin(Math.PI * fraction));
            return localX * localX + localZ * localZ <= edge * edge;
        }
    }

    private Region chooseRegion(long seed) {
        int choice = (int) Long.remainderUnsigned(mix(seed), totalWeight);
        for (Region region : regions) {
            choice -= region.weight();
            if (choice < 0) return region;
        }
        throw new IllegalStateException("Cave region weights are empty");
    }

    private long fingerprint(Climate.Sampler sampler) {
        SamplerFingerprint cached = lastFingerprint;
        if (cached != null && cached.sampler() == sampler) return cached.value();
        long value = 0x517cc1b727220a95L;
        // Temperature and humidity keep the same seeded values in sparse and
        // filtered chunk samplers. Terrain-derived fields do not: erosion would
        // otherwise change the district seed between biome searches and chunks.
        for (int i = 0; i < 3; i++) {
            var point = new DensityFunction.SinglePointContext(
                    QuartPos.toBlock(i * 1973 - 3191), -32, QuartPos.toBlock(i * -3251 + 1879));
            value = mix(value ^ Climate.quantizeCoord((float) sampler.temperature().compute(point)));
            value = mix(value ^ Climate.quantizeCoord((float) sampler.humidity().compute(point)));
        }
        lastFingerprint = new SamplerFingerprint(sampler, value);
        return value;
    }

    private static double unit(long seed) { return (mix(seed) >>> 11) * 0x1.0p-53; }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record SamplerFingerprint(Climate.Sampler sampler, long value) {}
}
