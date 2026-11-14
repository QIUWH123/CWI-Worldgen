package net.quiwh.cwiworldgen.landscape;

import java.util.Objects;
import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/** Pure bounded traversal for an asynchronous command; it never reads a live world. */
public final class LandscapeSearch {
    public static final int MAX_COORDINATE = 29_999_984;
    public static final int DEFAULT_RADIUS = 8192;
    public static final int MAX_RADIUS = 32768;
    public static final int MAX_SAMPLES = 16_384;

    private LandscapeSearch() {}

    @FunctionalInterface
    public interface Probe {
        /** A verified anchor may differ from the sampled column, or be absent. */
        Candidate at(int x, int z);
    }

    public record Candidate(int x, int y, int z) {}
    public enum Reason { FOUND, EXHAUSTED, SAMPLE_LIMIT, TIME_LIMIT, CANCELLED }
    public record Result(Candidate candidate, int samples, Reason reason) {}

    /**
     * Visits an origin-relative grid in outward square rings, within a circular radius.
     * This finds a nearby verified anchor; the sparse grid does not prove nearestness
     * or absence between samples. The budget cannot interrupt one in-flight probe.
     */
    public static Result find(int originX, int originZ, int radius, int step, int maxSamples,
            long budgetNanos, BooleanSupplier cancelled, Probe probe) {
        if (!inWorld(originX, originZ) || radius < 1 || radius > MAX_RADIUS
                || step < 1 || step > 192 || maxSamples < 1 || (maxSamples > MAX_SAMPLES && maxSamples != Integer.MAX_VALUE)
                || budgetNanos <= 0) throw new IllegalArgumentException("Invalid landscape search bounds");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(probe, "probe");
        Traversal traversal = new Traversal(originX, originZ, radius, maxSamples,
                budgetNanos, cancelled, probe);
        Result result = traversal.visit(0, 0);
        if (result != null) return result;
        int rings = radius / step;
        for (int ring = 1; ring <= rings; ring++) {
            int edge = ring * step;
            for (int offset = -edge; offset <= edge; offset += step) {
                result = traversal.visit(offset, -edge);
                if (result != null) return result;
                result = traversal.visit(offset, edge);
                if (result != null) return result;
            }
            // Top and bottom already own the corners.
            for (int offset = -edge + step; offset < edge; offset += step) {
                result = traversal.visit(-edge, offset);
                if (result != null) return result;
                result = traversal.visit(edge, offset);
                if (result != null) return result;
            }
        }
        return new Result(null, traversal.samples, Reason.EXHAUSTED);
    }

    /**
     * Visits the same origin-relative circular grid in increasing horizontal distance.
     * A returned anchor is confirmed and nearby; relocating probes do not establish exact nearestness.
     * The frontier grows with the checked points rather than allocating the whole search square.
     */
    public static Result nearest(int originX, int originZ, int radius, int step, int maxSamples,
            long budgetNanos, BooleanSupplier cancelled, Probe probe) {
        if (!inWorld(originX, originZ) || radius < 1 || radius > MAX_RADIUS
                || step < 1 || step > 192 || maxSamples < 1 || (maxSamples > MAX_SAMPLES && maxSamples != Integer.MAX_VALUE)
                || budgetNanos <= 0) throw new IllegalArgumentException("Invalid landscape search bounds");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(probe, "probe");
        Traversal traversal = new Traversal(originX, originZ, radius, maxSamples,
                budgetNanos, cancelled, probe);
        record Point(int x, int z) {
            long distance() { return (long) x * x + (long) z * z; }
            long key() { return ((long) x << 32) ^ (z & 0xffffffffL); }
        }
        var frontier = new PriorityQueue<Point>(Comparator.comparingLong(Point::distance)
                .thenComparingInt(Point::x).thenComparingInt(Point::z));
        var seen = new HashSet<Long>();
        Point origin = new Point(0, 0);
        frontier.add(origin); seen.add(origin.key());
        long radiusSquared = (long) radius * radius;
        while (!frontier.isEmpty()) {
            Result stopped = traversal.stopReason();
            if (stopped != null) return stopped;
            Point next = frontier.remove();
            Result result = traversal.visit(next.x, next.z);
            if (result != null) return result;
            for (int direction = 0; direction < 4; direction++) {
                int x = next.x + (direction == 0 ? -step : direction == 1 ? step : 0);
                int z = next.z + (direction == 2 ? -step : direction == 3 ? step : 0);
                Point neighbor = new Point(x, z);
                if (neighbor.distance() <= radiusSquared && inWorld((long) originX + x, (long) originZ + z)
                        && seen.add(neighbor.key())) frontier.add(neighbor);
            }
        }
        return new Result(null, traversal.samples, Reason.EXHAUSTED);
    }

    private static boolean inWorld(long x, long z) {
        return Math.abs(x) <= MAX_COORDINATE && Math.abs(z) <= MAX_COORDINATE;
    }

    private static final class Traversal {
        private final int originX, originZ, maxSamples;
        private final long radiusSquared, budgetNanos, started = System.nanoTime();
        private final BooleanSupplier cancelled;
        private final Probe probe;
        private int samples;

        private Traversal(int originX, int originZ, int radius, int maxSamples, long budgetNanos,
                BooleanSupplier cancelled, Probe probe) {
            this.originX = originX;
            this.originZ = originZ;
            this.radiusSquared = (long) radius * radius;
            this.maxSamples = maxSamples;
            this.budgetNanos = budgetNanos;
            this.cancelled = cancelled;
            this.probe = probe;
        }

        private Result visit(int offsetX, int offsetZ) {
            long x = (long) originX + offsetX, z = (long) originZ + offsetZ;
            if ((long) offsetX * offsetX + (long) offsetZ * offsetZ > radiusSquared || !inWorld(x, z))
                return null;
            Result stopped = stopReason();
            if (stopped != null) return stopped;
            if (samples >= maxSamples) return new Result(null, samples, Reason.SAMPLE_LIMIT);
            samples++;
            Candidate candidate = probe.at((int) x, (int) z);
            stopped = stopReason();
            if (stopped != null) return stopped;
            if (candidate == null || !inWorld(candidate.x(), candidate.z())) return null;
            long dx = (long) candidate.x() - originX, dz = (long) candidate.z() - originZ;
            if (dx * dx + dz * dz > radiusSquared) return null;
            return new Result(candidate, samples, Reason.FOUND);
        }

        private Result stopReason() {
            if (cancelled.getAsBoolean()) return new Result(null, samples, Reason.CANCELLED);
            if (System.nanoTime() - started >= budgetNanos) return new Result(null, samples, Reason.TIME_LIMIT);
            return null;
        }
    }
}
