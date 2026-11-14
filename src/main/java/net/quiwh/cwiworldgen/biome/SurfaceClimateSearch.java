package net.quiwh.cwiworldgen.biome;

import com.google.common.collect.ImmutableList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.quiwh.cwiworldgen.mixin.MultiNoiseBiomeSourceAccessor;

/** Query-local removal of common climate costs, retaining Minecraft's actual search tree. */
final class SurfaceClimateSearch {
    // Seven squared distances (including offset), each <= (2 * LIMIT)^2, fit in a long.
    private static final long LIMIT = 1_000_000L;
    private static final Class<?> IMMUTABLE_LIST_N = List.of().getClass();
    private static final Class<?> IMMUTABLE_LIST_12 = List.of(0).getClass();
    private final MultiNoiseBiomeSource source;
    private final Climate.Sampler sampler;
    private final DensityFunction[] fields;
    private final boolean[] shared;
    private final long[] representative;
    private final long[] point = new long[6];

    private SurfaceClimateSearch(MultiNoiseBiomeSource source, Climate.Sampler sampler,
            DensityFunction[] fields, boolean[] shared, long[] representative) {
        this.source = source;
        this.sampler = sampler;
        this.fields = fields;
        this.shared = shared;
        this.representative = representative;
    }

    static SurfaceClimateSearch create(BiomeSource source, Climate.Sampler sampler) {
        // Unknown sources/subclasses retain their coordinate-based implementation.
        if (source.getClass() != MultiNoiseBiomeSource.class
                || !(source instanceof MultiNoiseBiomeSourceAccessor access)) return null;
        var parameters = access.cwiWorldgen$parameters();
        if (parameters.getClass() != Climate.ParameterList.class) return null;
        var rows = parameters.values();
        // Native ParameterList keeps the caller's list while its index takes a snapshot.
        // A mutable list could already differ from that index before this query starts.
        if (!(rows instanceof ImmutableList<?>) && rows.getClass() != IMMUTABLE_LIST_N
                && rows.getClass() != IMMUTABLE_LIST_12) return null;
        if (rows.isEmpty()) return null;
        DensityFunction[] fields = {sampler.temperature(), sampler.humidity(), sampler.continentalness(),
                sampler.erosion(), sampler.depth(), sampler.weirdness()};
        Climate.Parameter[] first = axes(rows.getFirst().getFirst());
        boolean[] shared = {true, true, true, true, true, true};
        for (var row : rows) {
            if (!safe(row.getFirst().offset())) return null;
            Climate.Parameter[] values = axes(row.getFirst());
            for (int axis = 0; axis < 6; axis++) {
                var value = values[axis];
                if (value.min() > value.max() || !safe(value.min()) || !safe(value.max())) return null;
                shared[axis] &= value.equals(first[axis]);
            }
        }
        long[] representative = new long[6];
        boolean useful = false;
        for (int axis = 0; axis < 6; axis++) {
            // Only omitted fields need a prior bound; retained values are checked at each lookup.
            double min = fields[axis].minValue(), max = fields[axis].maxValue();
            shared[axis] &= Double.isFinite(min) && Double.isFinite(max) && min <= max
                    && safe(Climate.quantizeCoord((float) min)) && safe(Climate.quantizeCoord((float) max));
            representative[axis] = first[axis].min();
            useful |= shared[axis];
        }
        return useful ? new SurfaceClimateSearch((MultiNoiseBiomeSource) source, sampler, fields, shared, representative) : null;
    }

    Holder<Biome> sample(int qx, int qy, int qz) {
        var context = new DensityFunction.SinglePointContext(QuartPos.toBlock(qx), QuartPos.toBlock(qy), QuartPos.toBlock(qz));
        for (int axis = 0; axis < 6; axis++) {
            point[axis] = shared[axis] ? representative[axis] : Climate.quantizeCoord((float) fields[axis].compute(context));
            if (!safe(point[axis])) return source.getNoiseBiome(qx, qy, qz, sampler);
        }
        // Every native node has the same bounds on omitted axes. Removing their common,
        // non-overflowing score preserves traversal, nearest choices and last-result ties.
        // Do not skip a non-target lookup: it can affect a later tie on this same tree.
        return source.getNoiseBiome(new Climate.TargetPoint(point[0], point[1], point[2], point[3], point[4], point[5]));
    }

    private static Climate.Parameter[] axes(Climate.ParameterPoint point) {
        return new Climate.Parameter[]{point.temperature(), point.humidity(), point.continentalness(),
                point.erosion(), point.depth(), point.weirdness()};
    }

    private static boolean safe(long value) { return value >= -LIMIT && value <= LIMIT; }
}
