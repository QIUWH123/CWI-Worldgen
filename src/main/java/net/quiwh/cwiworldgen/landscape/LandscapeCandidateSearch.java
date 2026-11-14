package net.quiwh.cwiworldgen.landscape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import net.quiwh.cwiworldgen.hydrology.watershed.EnclosedPondPlanner;
import net.quiwh.cwiworldgen.hydrology.watershed.WatershedHydrology;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscapeProfiles;
import net.quiwh.cwiworldgen.terrain.RegionalLandforms;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;

/** Per-query directed nominations. Accepted generator descriptors remain read-only and world-owned. */
final class LandscapeCandidateSearch {
    private record Point(int x, int z) {}
    private record Owner(int x, int z, int size, long distance) {}
    private final LandscapeSurvey survey;
    private final LandscapeSurvey confirmations;
    private final ReferenceTerrain terrain;
    private final int originX, originZ, radius, maxSamples, sea, height;
    private final long radiusSquared, started = System.nanoTime(), budgetNanos;
    private final BooleanSupplier cancelled;
    private final IntConsumer progress;
    private final Set<Long> checked = new HashSet<>();
    private int samples;

    LandscapeCandidateSearch(LandscapeSurvey survey, ReferenceTerrain terrain, int originX, int originZ,
            int radius, int maxSamples, long budgetNanos, BooleanSupplier cancelled, IntConsumer progress) {
        if (Math.abs((long) originX) > LandscapeSearch.MAX_COORDINATE
                || Math.abs((long) originZ) > LandscapeSearch.MAX_COORDINATE
                || radius < 1 || radius > LandscapeSearch.MAX_RADIUS || maxSamples < 1
                || (maxSamples > LandscapeSearch.MAX_SAMPLES && maxSamples != Integer.MAX_VALUE) || budgetNanos <= 0)
            throw new IllegalArgumentException("Invalid landscape search bounds");
        this.survey = survey; this.terrain = terrain; this.originX = originX; this.originZ = originZ;
        this.radius = radius; this.maxSamples = maxSamples; this.radiusSquared = (long) radius * radius;
        var levels = terrain.levels(); this.sea = levels.waterLevel; this.height = levels.worldHeight;
        this.budgetNanos = budgetNanos; this.cancelled = java.util.Objects.requireNonNull(cancelled);
        this.progress = java.util.Objects.requireNonNull(progress);
        // Nested confirmation can inspect several completed columns. Share this query's deadline
        // while retaining the original cancellation source for accurate terminal status.
        this.confirmations = new LandscapeSurvey(terrain, () -> stop() != null);
    }

    LandscapeSearch.Result run(LandscapeType type) {
        java.util.Objects.requireNonNull(type);
        var reason = stop();
        if (reason != null) return result(null, reason);
        // Preserve the useful origin fast path without building descriptors for an entire area.
        var found = point(type, originX, originZ);
        if ((reason = stop()) != null) return result(null, reason);
        if (found != null) return result(found, LandscapeSearch.Reason.FOUND);
        if (formation(type)) return formations(type);
        if (type == LandscapeType.POND && terrain.generationVersion() >= 12) return ponds(type);
        if (isWaterType(type) && terrain.generationVersion() >= 2) {
            found = water(type);
            if ((reason = stop()) != null) return result(null, reason);
            if (found != null) return result(found, LandscapeSearch.Reason.FOUND);
        }
        if (!isWaterType(type)) {
            found = promisingTerrain(type);
            if ((reason = stop()) != null) return result(null, reason);
            if (found != null) return result(found, LandscapeSearch.Reason.FOUND);
        }
        if (samples >= maxSamples) return result(null, LandscapeSearch.Reason.SAMPLE_LIMIT);
        long remaining = budgetNanos - (System.nanoTime() - started);
        if (remaining <= 0) return result(null, LandscapeSearch.Reason.TIME_LIMIT);
        var fallback = LandscapeSearch.nearest(originX, originZ, radius, type.spacing(),
                maxSamples == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxSamples - samples, remaining, () -> stop() != null,
                (x, z) -> point(type, x, z));
        reason = stop();
        return result(reason == null ? fallback.candidate() : null,
                reason == null ? fallback.reason() : reason);
    }

    private LandscapeSearch.Candidate promisingTerrain(LandscapeType type) {
        if (before() != null) return null;
        Cell base = new Cell();
        long remaining = budgetNanos - (System.nanoTime() - started);
        if (remaining <= 0) return null;
        // Unlimited commands should nominate rare broad terrain throughout the requested
        // circle before paying for the denser final grid. Direct API budgets stay bounded.
        boolean broadIdentity = switch (type) {
            case BADLANDS, PLATEAU, HILLS, MOUNTAINS, MOUNTAIN_RANGE, VOLCANO -> true;
            default -> false;
        };
        int nominations = broadIdentity && maxSamples == Integer.MAX_VALUE
                ? Integer.MAX_VALUE : Math.min(512, maxSamples - samples);
        var prioritized = LandscapeSearch.nearest(originX, originZ, radius, 192,
                nominations, remaining, () -> stop() != null, (x, z) -> {
                    if (before() != null || checked.contains(pack(x, z))) return null;
                    count();
                    terrain.sampleBaseInto(base, x, z);
                    boolean plausible = switch (type) {
                        case CLIFF, ROCK_SHELF -> base.height * height > sea + 8;
                        default -> LandscapeType.classify(base, sea, height).contains(type);
                    };
                    // Base facts only prioritize; every rejected nomination remains eligible for
                    // the full final-terrain grid. Erosion and water boundaries are not excluded.
                    return plausible && before() == null ? point(type, x, z) : null;
                });
        return prioritized.candidate();
    }

    private LandscapeSearch.Result formations(LandscapeType type) {
        var landscape = terrain.landscape();
        if (landscape == null) return result(null, LandscapeSearch.Reason.EXHAUSTED);
        int size = type == LandscapeType.CAVERN ? landscape.cavernCellSize() : ReferenceLandscapeProfiles.SURFACE_CELL;
        for (Owner owner : owners(size)) {
            var reason = before();
            if (reason != null) return result(null, reason);
            count();
            int x = owner.x * size + size / 2, z = owner.z * size + size / 2;
            var shape = type == LandscapeType.CAVERN ? landscape.cavernFormationAt(x, z) : landscape.surfaceFormationAt(x, z);
            if ((reason = stop()) != null) return result(null, reason);
            if (shape == null || !shape.kind().name().equals(type.name())) continue;
            var found = confirmations.formation(type, shape);
            if ((reason = stop()) != null) return result(null, reason);
            if (valid(found)) return result(found, LandscapeSearch.Reason.FOUND);
        }
        return result(null, LandscapeSearch.Reason.EXHAUSTED);
    }

    private LandscapeSearch.Result ponds(LandscapeType type) {
        for (Owner owner : owners(EnclosedPondPlanner.CELL_SIZE)) {
            var reason = before();
            if (reason != null) return result(null, reason);
            if (!EnclosedPondPlanner.eligible(terrain.seed(), owner.x, owner.z)) continue;
            count();
            var mask = terrain.landformPondMask(owner.x, owner.z);
            if ((reason = stop()) != null) return result(null, reason);
            if (mask == null) continue;
            var candidates = points();
            for (var entry : mask.columns().entrySet()) if (entry.getValue().wet()) {
                long packed = entry.getKey(); add(candidates, (int) (packed >> 32), (int) packed);
            }
            var found = verify(type, candidates);
            if ((reason = stop()) != null) return result(null, reason);
            if (found != null) return result(found, LandscapeSearch.Reason.FOUND);
        }
        return result(null, LandscapeSearch.Reason.EXHAUSTED);
    }

    private LandscapeSearch.Candidate water(LandscapeType type) {
        Set<Long> continents = new HashSet<>();
        Cell base = new Cell();
        // A routing plan can be cold and expensive. Discover each owning continent once;
        // sparse discovery is supplemented by the ordinary final-terrain grid below.
        var first = waterPlan(type, originX, originZ, continents, base);
        if (first != null || before() != null) return first;
        for (Owner owner : owners(1024)) {
            if (before() != null) return null;
            int x = Math.clamp(originX, owner.x * owner.size, owner.x * owner.size + owner.size - 1);
            int z = Math.clamp(originZ, owner.z * owner.size, owner.z * owner.size + owner.size - 1);
            var found = waterPlan(type, x, z, continents, base);
            if (found != null || before() != null) return found;
        }
        return null;
    }

    private LandscapeSearch.Candidate waterPlan(LandscapeType type, int x, int z, Set<Long> continents, Cell base) {
        if (before() != null || !inside(x, z)) return null;
        terrain.sampleBaseInto(base, x, z);
        if (!continents.add(pack(base.continentX, base.continentZ))) return null;
        count();
        WatershedHydrology.SearchSnapshot snapshot = terrain.landformWaterSnapshot(x, z);
        if (stop() != null || snapshot == null) return null;
        var candidates = points();
        if (type == LandscapeType.LAKE || type == LandscapeType.LAGOON) {
            for (var basin : snapshot.basins()) {
                if (stop() != null) return null;
                if (type == LandscapeType.LAGOON && basin.kind() != RegionalLandforms.Kind.COAST) continue;
                addBasin(candidates, basin);
            }
        } else for (var reach : snapshot.reaches()) {
            if (stop() != null) return null;
            if (!reach.wet() && type != LandscapeType.GORGE && type != LandscapeType.RIVER_VALLEY) continue;
            var route = reach.points();
            for (int index = 1; index < route.size(); index++) {
                if (stop() != null) return null;
                var a = route.get(index - 1); var b = route.get(index);
                double dx = b.x() - a.x(), dz = b.z() - a.z(), square = dx * dx + dz * dz;
                if (square <= 1e-12) continue;
                double projected = Math.clamp(((originX - a.x()) * dx + (originZ - a.z()) * dz) / square, 0, 1);
                double nearX = a.x() + dx * projected, nearZ = a.z() + dz * projected;
                if (distance(nearX, nearZ) > radiusSquared + 4L * radius + 4) continue;
                add(candidates, nearX, nearZ);
                double spacing = type == LandscapeType.WATERFALL || type == LandscapeType.RAPIDS ? 24 : 48;
                int steps = Math.max(1, (int) Math.ceil(Math.sqrt(square) / spacing));
                for (int step = 0; step <= steps; step++)
                    add(candidates, a.x() + dx * step / steps, a.z() + dz * step / steps);
                // The generation route interpolates these accepted water elevations. Nominate
                // integer-level crossings directly so a one-column transition avoids coarse-grid gaps.
                if (type == LandscapeType.WATERFALL || type == LandscapeType.RAPIDS) {
                    double drop = a.waterY() - b.waterY();
                    if (drop > 1e-8) {
                        int min = (int) Math.ceil(b.waterY()), max = (int) Math.floor(a.waterY());
                        for (int water = min; water <= max && water - min <= 512; water++) {
                            double t = (a.waterY() - water) / drop;
                            double px = a.x() + dx * t, pz = a.z() + dz * t;
                            double length = Math.sqrt(square);
                            for (int shift = -2; shift <= 2; shift++)
                                add(candidates, px + dx / length * shift, pz + dz / length * shift);
                        }
                    }
                }
            }
        }
        return verify(type, candidates);
    }

    private void addBasin(TreeSet<Point> candidates, RegionalLandforms.Basin basin) {
        add(candidates, basin.centerX(), basin.centerZ());
        double dx = originX - basin.centerX(), dz = originZ - basin.centerZ();
        double normalized = Math.hypot(dx / basin.radiusX(), dz / basin.radiusZ());
        if (normalized > 1e-8) add(candidates, basin.centerX() + dx * Math.min(1, .65 / normalized),
                basin.centerZ() + dz * Math.min(1, .65 / normalized));
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            add(candidates, basin.centerX() + basin.radiusX() * x * .25,
                    basin.centerZ() + basin.radiusZ() * z * .25);
    }

    private LandscapeSearch.Candidate verify(LandscapeType type, TreeSet<Point> candidates) {
        for (Point point : candidates) {
            if (before() != null) return null;
            var found = point(type, point.x, point.z);
            if (found != null || stop() != null) return found;
        }
        return null;
    }

    private LandscapeSearch.Candidate point(LandscapeType type, int x, int z) {
        if (before() != null || !inside(x, z) || !checked.add(pack(x, z))) return null;
        count();
        var found = confirmations.confirmAt(type, x, z);
        return stop() == null && valid(found) ? found : null;
    }

    private List<Owner> owners(int size) {
        var result = new ArrayList<Owner>();
        int minX = Math.floorDiv(Math.max(-LandscapeSearch.MAX_COORDINATE, originX - radius), size);
        int maxX = Math.floorDiv(Math.min(LandscapeSearch.MAX_COORDINATE, originX + radius), size);
        int minZ = Math.floorDiv(Math.max(-LandscapeSearch.MAX_COORDINATE, originZ - radius), size);
        int maxZ = Math.floorDiv(Math.min(LandscapeSearch.MAX_COORDINATE, originZ + radius), size);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            int nearX = Math.clamp(originX, x * size, x * size + size - 1);
            int nearZ = Math.clamp(originZ, z * size, z * size + size - 1);
            long distance = (long) (nearX - originX) * (nearX - originX) + (long) (nearZ - originZ) * (nearZ - originZ);
            if (distance <= radiusSquared) result.add(new Owner(x, z, size, distance));
        }
        result.sort(Comparator.comparingLong(Owner::distance).thenComparingInt(Owner::x).thenComparingInt(Owner::z));
        return result;
    }

    private TreeSet<Point> points() {
        return new TreeSet<>(Comparator.<Point>comparingLong(point -> distance(point.x, point.z))
                .thenComparingInt(Point::x).thenComparingInt(Point::z));
    }
    private void add(TreeSet<Point> points, double x, double z) {
        if (Double.isFinite(x + z) && Math.abs(x) <= LandscapeSearch.MAX_COORDINATE
                && Math.abs(z) <= LandscapeSearch.MAX_COORDINATE) add(points, (int) Math.round(x), (int) Math.round(z));
    }
    private void add(TreeSet<Point> points, int x, int z) {
        if (inside(x, z) && !checked.contains(pack(x, z))) {
            points.add(new Point(x, z));
            if (maxSamples != Integer.MAX_VALUE && points.size() > (long) maxSamples * 2) points.pollLast();
        }
    }
    private long distance(int x, int z) { long dx = (long) x - originX, dz = (long) z - originZ; return dx * dx + dz * dz; }
    private double distance(double x, double z) { return (x - originX) * (x - originX) + (z - originZ) * (z - originZ); }
    private boolean inside(int x, int z) { return Math.abs((long) x) <= LandscapeSearch.MAX_COORDINATE
            && Math.abs((long) z) <= LandscapeSearch.MAX_COORDINATE && distance(x, z) <= radiusSquared; }
    private boolean valid(LandscapeSearch.Candidate candidate) { return candidate != null && inside(candidate.x(), candidate.z()); }
    private void count() { samples++; progress.accept(samples); }
    private LandscapeSearch.Reason stop() {
        if (cancelled.getAsBoolean() || survey.cancelled()) return LandscapeSearch.Reason.CANCELLED;
        return System.nanoTime() - started >= budgetNanos ? LandscapeSearch.Reason.TIME_LIMIT : null;
    }
    private LandscapeSearch.Reason before() {
        var reason = stop();
        return reason != null ? reason : samples >= maxSamples ? LandscapeSearch.Reason.SAMPLE_LIMIT : null;
    }
    private LandscapeSearch.Result result(LandscapeSearch.Candidate candidate, LandscapeSearch.Reason reason) {
        return new LandscapeSearch.Result(candidate, samples, reason);
    }
    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private static boolean formation(LandscapeType type) { return type == LandscapeType.ARCH || type == LandscapeType.OVERHANG
            || type == LandscapeType.SPIRE || type == LandscapeType.CAVERN; }
    private static boolean isWaterType(LandscapeType type) { return switch (type) {
        case RIVER, RIVER_VALLEY, GORGE, LAKE, LAGOON, WATERFALL, RAPIDS -> true;
        default -> false;
    }; }
}
