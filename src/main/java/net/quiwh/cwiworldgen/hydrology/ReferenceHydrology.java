package net.quiwh.cwiworldgen.hydrology;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Heightmap;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.Rivermap;

/** Observational adapter over the native directed river graph and its forward warps. */
public final class ReferenceHydrology {
    public static final String PROVIDER = "cwi:reference_forward_field";
    public static final int VERSION = 1;
    private final Heightmap heightmap;
    private final int seed;

    public ReferenceHydrology(Heightmap heightmap, int seed) {
        this.heightmap = heightmap;
        this.seed = seed;
    }

    public HydrologySnapshot snapshot(int blockX, int blockZ) {
        Cell cell = new Cell();
        heightmap.continent().apply(cell, blockX, blockZ);
        Rivermap map = heightmap.continent().getRivermap(cell);
        List<Rivermap.ReachData> topology = map.topology();
        List<DirectedReach> reaches = new ArrayList<>(topology.size());
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (var reach : topology) {
            reaches.add(new DirectedReach(id(map, reach.index()),
                    reach.hasParent() ? id(map, reach.parentIndex()) : 0,
                    reach.hasParent(), reach.downstreamJoinProgress(), List.of()));
            minX = Math.min(minX, Math.min(reach.x1(), reach.x2()));
            minZ = Math.min(minZ, Math.min(reach.z1(), reach.z2()));
            maxX = Math.max(maxX, Math.max(reach.x1(), reach.x2()));
            maxZ = Math.max(maxZ, Math.max(reach.z1(), reach.z2()));
        }
        if (topology.isEmpty()) {
            minX = maxX = map.getX();
            minZ = maxZ = map.getZ();
        }
        return new HydrologySnapshot(PROVIDER, VERSION, seed, map.getX(), map.getZ(),
                new HydrologyBounds(minX, minZ, maxX, maxZ), reaches);
    }

    public HydrologyQuery query(int blockX, int blockZ) {
        Cell cell = new Cell();
        heightmap.apply(cell, blockX, blockZ, false);
        if (cell.terrain.isLake()) return HydrologyQuery.invalid("still_lake");
        if (!cell.terrain.isRiver()) return HydrologyQuery.invalid("outside_native_river");
        Rivermap map = heightmap.continent().getRivermap(cell);
        return query(map, cell, blockX, blockZ, seed);
    }

    /** Pure query entry point, also useful for validating a native graph fixture. */
    public static HydrologyQuery query(Rivermap map, Cell cell, float x, float z, int seed) {
        if (cell.terrain.isLake()) return HydrologyQuery.invalid("still_lake");
        if (!cell.terrain.isRiver()) return HydrologyQuery.invalid("outside_native_river");
        List<Candidate> candidates = new ArrayList<>();
        boolean singular = false;
        for (var query : map.queryGeometry(cell, x, z)) {
            if (!query.nativeChannel()) continue;
            var center = query.center();
            if (!(center.progress() >= 0 && center.progress() <= 1)) continue;
            Candidate candidate = candidate(query);
            if (candidate == null) {
                singular = true;
            } else {
                candidates.add(candidate);
            }
        }
        if (singular) return HydrologyQuery.invalid("singular_or_folded_mapping");
        if (candidates.isEmpty()) {
            return HydrologyQuery.invalid(singular ? "singular_or_folded_mapping" : "outside_directed_reach");
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distance));
        Candidate nearest = candidates.getFirst();
        if (nearest.query().lakeExpanded()) return HydrologyQuery.invalid("still_lake_expansion");
        if (candidates.size() > 1 && candidates.get(1).distance() - nearest.distance() < 1) {
            return HydrologyQuery.invalid("ambiguous_junction");
        }
        var center = nearest.query().center();
        return new HydrologyQuery(true,
                reachId(seed, map.getX(), map.getZ(), nearest.query().reach().index()),
                center.progress(), nearest.tangentX(), nearest.tangentZ(),
                center.signedDistance(), "directed_native_reach");
    }

    private record Candidate(Rivermap.ReachQuery query, double distance,
                              double tangentX, double tangentZ) {}

    private static Candidate candidate(Rivermap.ReachQuery query) {
        var east = query.east();
        var west = query.west();
        var south = query.south();
        var north = query.north();
        double spanX = query.probeSpanX();
        double spanZ = query.probeSpanZ();
        if (!Double.isFinite(spanX) || !Double.isFinite(spanZ)
                || !(spanX > 0) || !(spanZ > 0)) return null;
        double xx = (east.x() - west.x()) / spanX;
        double xz = (south.x() - north.x()) / spanZ;
        double zx = (east.z() - west.z()) / spanX;
        double zz = (south.z() - north.z()) / spanZ;
        double determinant = xx * zz - xz * zx;
        double scale = Math.max(1, Math.hypot(xx, zx) * Math.hypot(xz, zz));
        if (!Double.isFinite(determinant) || determinant <= 1E-4 * scale) return null;
        double dx = query.reach().x2() - query.reach().x1();
        double dz = query.reach().z2() - query.reach().z1();
        double tx = (zz * dx - xz * dz) / determinant;
        double tz = (-zx * dx + xx * dz) / determinant;
        double length = Math.hypot(tx, tz);
        if (!Double.isFinite(length) || length < 1E-8) return null;
        tx /= length;
        tz /= length;
        double progressSlope = (east.progress() - west.progress()) / spanX * tx
                + (south.progress() - north.progress()) / spanZ * tz;
        if (!(progressSlope > 0) || !Double.isFinite(query.center().signedDistance())) return null;
        double lateralGradient = Math.hypot((east.signedDistance() - west.signedDistance()) / spanX,
                (south.signedDistance() - north.signedDistance()) / spanZ);
        if (!(lateralGradient > 1E-6)) return null;
        double worldDistance = Math.abs(query.center().signedDistance()) / lateralGradient;
        return new Candidate(query, worldDistance, tx, tz);
    }

    private long id(Rivermap map, int index) {
        return reachId(seed, map.getX(), map.getZ(), index);
    }

    private static long reachId(int seed, int continentX, int continentZ, int index) {
        long value = ((long) seed << 32) ^ (continentX & 0xFFFFFFFFL);
        value = mix(value ^ ((long) continentZ << 32));
        return mix(value ^ (index & 0xFFFFFFFFL));
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
