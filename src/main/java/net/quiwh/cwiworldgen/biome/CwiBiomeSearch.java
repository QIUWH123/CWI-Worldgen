package net.quiwh.cwiworldgen.biome;

import com.mojang.datafixers.util.Pair;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.quiwh.cwiworldgen.terrain.ReferenceTerrainDensity;

/**
 * CWI-aware locate queries, preserving Minecraft's horizontal spiral and vertical sample order.
 * A query belongs to its calling thread. Background commands advance it on the server thread;
 * no assumption about another mod's climate functions being worker-safe is required.
 */
public final class CwiBiomeSearch {
    private CwiBiomeSearch() {}

    public static Query create(BoundedCaveBiomeSource source, BlockPos origin, int radius,
            int horizontalStep, int verticalStep, Predicate<Holder<Biome>> target,
            Climate.Sampler sampler, int minBuildY, int maxBuildY, BooleanSupplier cancelled) {
        return create((BiomeSource) source, origin, radius, horizontalStep, verticalStep, target,
                sampler, minBuildY, maxBuildY, cancelled);
    }

    public static Query create(BiomeSource source, BlockPos origin, int radius,
            int horizontalStep, int verticalStep, Predicate<Holder<Biome>> target,
            Climate.Sampler sampler, int minBuildY, int maxBuildY, BooleanSupplier cancelled) {
        return new Query(source, origin, radius, horizontalStep, verticalStep, target, sampler,
                minBuildY, maxBuildY, cancelled, false);
    }

    /** Command searches share a circular horizontal block range with landforms and structures. */
    public static Query createWithinRadius(BiomeSource source, BlockPos origin, int radius,
            int horizontalStep, int verticalStep, Predicate<Holder<Biome>> target,
            Climate.Sampler sampler, int minBuildY, int maxBuildY, BooleanSupplier cancelled) {
        return new Query(source, origin, radius, horizontalStep, verticalStep, target, sampler,
                minBuildY, maxBuildY, cancelled, true);
    }

    public static final class Query {
        private final BiomeSource source;
        private final BoundedCaveBiomeSource bounded;
        private final BlockPos origin;
        private final int horizontalStep;
        private final long radiusSquared;
        private final boolean circular;
        private final Set<Holder<Biome>> targets;
        private final boolean surfaceTarget;
        private final Climate.Sampler sampler;
        private final BooleanSupplier cancellation;
        private final Iterator<BlockPos.MutableBlockPos> columns;
        private final int[] heights;
        private final MemoDensity[] fields;
        private final SurfaceClimateSearch surfaceClimate;
        // Query-local, bounded descriptors; never retain a world/sampler after the query retires.
        private final Map<Long, BoundedCaveBiomeSource.District> districts = new LinkedHashMap<>(32, .75F, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, BoundedCaveBiomeSource.District> entry) {
                return size() > 128;
            }
        };
        private int x, z, heightIndex;
        private boolean hasColumn, done, cancelled;
        private long samples, skippedSamples;
        private Pair<BlockPos, Holder<Biome>> result;

        private Query(BiomeSource source, BlockPos origin, int radius, int horizontalStep,
                int verticalStep, Predicate<Holder<Biome>> target, Climate.Sampler sampler,
                int minBuildY, int maxBuildY, BooleanSupplier cancellation, boolean circular) {
            if (radius < 0 || horizontalStep <= 0 || verticalStep <= 0 || minBuildY >= maxBuildY)
                throw new IllegalArgumentException("Invalid biome search bounds");
            this.source = Objects.requireNonNull(source);
            this.bounded = source instanceof BoundedCaveBiomeSource cwi ? cwi : null;
            this.origin = Objects.requireNonNull(origin).immutable();
            this.horizontalStep = horizontalStep;
            this.radiusSquared = (long) radius * radius;
            this.circular = circular;
            this.cancellation = Objects.requireNonNull(cancellation);
            this.targets = source.possibleBiomes().stream().filter(Objects.requireNonNull(target))
                    .collect(Collectors.toUnmodifiableSet());
            this.surfaceTarget = bounded != null && bounded.searchSurfaceSource().possibleBiomes().stream().anyMatch(targets::contains);
            if (bounded == null) {
                // Other sources retain their original sampler and evaluation behavior.
                this.fields = new MemoDensity[0];
                this.sampler = sampler;
            } else {
                this.fields = new MemoDensity[]{new MemoDensity(sampler.temperature()), new MemoDensity(sampler.humidity()),
                        new MemoDensity(sampler.continentalness()), new MemoDensity(sampler.erosion()),
                        new MemoDensity(sampler.depth()), new MemoDensity(sampler.weirdness())};
                this.sampler = new Climate.Sampler(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], sampler.spawnTarget());
            }
            this.surfaceClimate = surfaceTarget ? SurfaceClimateSearch.create(bounded.searchSurfaceSource(), this.sampler) : null;
            this.heights = Mth.outFromOrigin(origin.getY(), minBuildY + 1, maxBuildY, verticalStep).toArray();
            this.columns = BlockPos.spiralAround(BlockPos.ZERO, Math.floorDiv(radius, horizontalStep),
                    Direction.EAST, Direction.SOUTH).iterator();
            if (source instanceof FixedBiomeSource && !targets.isEmpty()) {
                // Vanilla fixed sources return the origin even outside build-height bounds.
                this.result = Pair.of(this.origin, targets.iterator().next());
                this.done = true;
            } else {
                this.done = targets.isEmpty() || heights.length == 0;
            }
        }

        /** Advance without changing sample order. A single density evaluation is not preemptible. */
        public boolean step(int maxSamples, long maxNanos) {
            if (maxSamples < 1 || maxNanos < 1) throw new IllegalArgumentException("Invalid biome search budget");
            if (done) return true;
            long started = System.nanoTime();
            for (int visited = 0; visited < maxSamples; visited++) {
                if (cancellation.getAsBoolean()) {
                    cancelled = true;
                    done = true;
                    return true;
                }
                if (System.nanoTime() - started >= maxNanos) return false;
                if (!hasColumn) {
                    if (!columns.hasNext()) { done = true; return true; }
                    BlockPos column = columns.next();
                    long dx = (long) column.getX() * horizontalStep;
                    long dz = (long) column.getZ() * horizontalStep;
                    if (circular && (dx * dx + dz * dz > radiusSquared
                            || Math.abs((long) origin.getX() + dx) > 29_999_984
                            || Math.abs((long) origin.getZ() + dz) > 29_999_984)) continue;
                    x = origin.getX() + column.getX() * horizontalStep;
                    z = origin.getZ() + column.getZ() * horizontalStep;
                    heightIndex = 0;
                    hasColumn = true;
                }
                int y = heights[heightIndex++];
                if (heightIndex == heights.length) hasColumn = false;
                samples++;
                Holder<Biome> found = sample(x, y, z);
                if (found != null) {
                    result = Pair.of(new BlockPos(x, y, z), found);
                    done = true;
                    return true;
                }
            }
            return done;
        }

        public boolean done() { return done; }
        public boolean cancelled() { return cancelled; }
        public Pair<BlockPos, Holder<Biome>> result() { return result; }
        public long samples() { return samples; }
        public long skippedSamples() { return skippedSamples; }
        /** Actual evaluations of the six climate fields, excluding memoized repeat reads. */
        public long densityEvaluations() {
            long count = 0;
            for (MemoDensity field : fields) count += field.evaluations;
            return count;
        }
        public long depthEvaluations() { return fields.length == 0 ? 0 : fields[4].evaluations; }

        private Holder<Biome> sample(int blockX, int blockY, int blockZ) {
            int qx = QuartPos.fromBlock(blockX), qy = QuartPos.fromBlock(blockY), qz = QuartPos.fromBlock(blockZ);
            if (bounded == null) {
                Holder<Biome> candidate = source.getNoiseBiome(qx, qy, qz, sampler);
                return targets.contains(candidate) ? candidate : null;
            }
            int sx = QuartPos.toBlock(qx), sy = QuartPos.toBlock(qy), sz = QuartPos.toBlock(qz);
            Holder<Biome> candidate;
            if (sy + 5 > bounded.caveCeiling()) {
                if (!surfaceTarget) { skippedSamples++; return null; }
                candidate = surface(qx, qy, qz);
            } else if (!surfaceTarget) {
                // Cave-only requests can reject impossible districts and Y bands before any terrain field.
                var district = district(sx, sz);
                var underground = district.contains(sx, sy, sz) ? district.region().biome() : bounded.searchFallbackBiome();
                if (!targets.contains(underground)) { skippedSamples++; return null; }
                if (sampler.depth().compute(new DensityFunction.SinglePointContext(sx, sy, sz)) <= .2) return null;
                candidate = underground;
            } else {
                // Preserve the cheap cave gate before sampling the complete surface climate.
                double depth = sampler.depth().compute(new DensityFunction.SinglePointContext(sx, sy, sz));
                if (!(depth <= .2)) {
                    var district = district(sx, sz);
                    candidate = district.contains(sx, sy, sz) ? district.region().biome() : bounded.searchFallbackBiome();
                } else {
                    // MultiNoise samples depth again; the query memo avoids that repeated terrain work.
                    candidate = surface(qx, qy, qz);
                }
            }
            if (!targets.contains(candidate)) return null;
            // Keep the complete generation source authoritative, including any future cave policy.
            Holder<Biome> confirmed = source.getNoiseBiome(qx, qy, qz, sampler);
            return targets.contains(confirmed) ? confirmed : null;
        }

        private BoundedCaveBiomeSource.District district(int blockX, int blockZ) {
            int cx = Math.floorDiv(blockX, bounded.cellSize()), cz = Math.floorDiv(blockZ, bounded.cellSize());
            long key = (long) cx << 32 ^ (cz & 0xffffffffL);
            return districts.computeIfAbsent(key, ignored -> bounded.districtAt(blockX, blockZ, sampler));
        }

        private Holder<Biome> surface(int qx, int qy, int qz) {
            return surfaceClimate == null ? bounded.searchSurfaceSource().getNoiseBiome(qx, qy, qz, sampler)
                    : surfaceClimate.sample(qx, qy, qz);
        }
    }

    /** Query-local memoization; only proven owned terrain fields may reuse a horizontal column. */
    private static final class MemoDensity implements DensityFunction.SimpleFunction {
        private final DensityFunction delegate;
        private final boolean horizontal;
        private int x, y, z;
        private boolean present, accurate;
        private double value;
        private long evaluations;
        private MemoDensity(DensityFunction delegate) {
            this.delegate = delegate;
            this.horizontal = delegate instanceof ReferenceTerrainDensity.Seeded seeded && seeded.searchYIndependent();
        }
        @Override public double compute(FunctionContext context) {
            if (!(context instanceof DensityFunction.SinglePointContext)) {
                evaluations++;
                return delegate.compute(context);
            }
            boolean mode = ReferenceTerrainDensity.isAccurateSampling();
            if (!present || x != context.blockX() || (!horizontal && y != context.blockY())
                    || z != context.blockZ() || accurate != mode) {
                x = context.blockX(); y = context.blockY(); z = context.blockZ();
                accurate = mode;
                value = delegate.compute(context);
                present = true;
                evaluations++;
            }
            return value;
        }
        @Override public double minValue() { return delegate.minValue(); }
        @Override public double maxValue() { return delegate.maxValue(); }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return delegate.codec(); }
    }
}
