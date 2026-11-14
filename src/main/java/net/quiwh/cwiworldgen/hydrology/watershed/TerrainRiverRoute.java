package net.quiwh.cwiworldgen.hydrology.watershed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/** Bounded, deterministic lateral refinement. No terrain is loaded or modified. */
public final class TerrainRiverRoute {
    public record Point(double x, double z) {}
    /** Retains the original corridor across tributary retries; shifted output is never its next input. */
    public record Corridor(List<Point> original) {
        public Corridor { original = List.copyOf(original); }
        public List<Point> refine(DoubleBinaryOperator ground, DoubleUnaryOperator widthAt,
                double downstreamWater, double sea) {
            return refineOriginal(original, ground, widthAt, downstreamWater, sea);
        }
        public List<Point> refine(DoubleBinaryOperator ground, double width,
                double downstreamWater, double sea) {
            return refine(ground, ignored -> width, downstreamWater, sea);
        }
    }
    private static final double[] BANK_OFFSETS = {-1, -.5, .5, 1};
    private TerrainRiverRoute() {}

    public static List<Point> refine(List<Point> path, DoubleBinaryOperator ground,
            double width, double downstreamWater, double sea) {
        return refine(path, ground, ignored -> width, downstreamWater, sea);
    }

    public static List<Point> refine(List<Point> path, DoubleBinaryOperator ground,
            DoubleUnaryOperator widthAt, double downstreamWater, double sea) {
        return new Corridor(path).refine(ground, widthAt, downstreamWater, sea);
    }

    private static List<Point> refineOriginal(List<Point> path, DoubleBinaryOperator ground,
            DoubleUnaryOperator widthAt, double downstreamWater, double sea) {
        if (path.size() < 3) return List.copyOf(path);
        double[] arc = new double[path.size()];
        for (int i = 1; i < arc.length; i++) arc[i] = arc[i - 1] + distance(path.get(i - 1), path.get(i));
        double length = arc[arc.length - 1];
        if (length < 48) return List.copyOf(path);
        int steps = Math.max(8, (int)Math.ceil(length / 8));
        Point first = path.getFirst(), last = path.getLast();
        double chord = distance(first, last);
        if (chord < 1E-8) return List.copyOf(path);
        double nx = -(last.z - first.z) / chord, nz = (last.x - first.x) / chord;
        final int lanes = 13, center = lanes / 2;
        double[][] cost = new double[steps + 1][lanes];
        int[][] previous = new int[steps + 1][lanes];
        Point[] centers = new Point[steps + 1];
        double[] envelopes = new double[steps + 1];
        double collar = Math.min(length * .24, Math.max(12,
                Math.max(widthAt.applyAsDouble(0), widthAt.applyAsDouble(1)) * 1.3));
        for (int i = 0; i <= steps; i++) {
            double travelled = length * i / steps;
            centers[i] = at(path, arc, travelled);
            double ramp = Math.clamp(Math.min(travelled - collar, length - travelled - collar)
                    / Math.max(8, length * .15), 0, 1);
            envelopes[i] = ramp * ramp * (3 - 2 * ramp);
            Arrays.fill(cost[i], Double.POSITIVE_INFINITY);
            Arrays.fill(previous[i], -1);
        }
        double headWater = Math.max(downstreamWater, finite(ground, first) - 2);
        cost[0][center] = 0;
        for (int i = 1; i <= steps; i++) for (int lane = 0; lane < lanes; lane++) {
            if ((i == steps || envelopes[i] == 0) && lane != center) continue;
            double offset = (lane - center) * 8 * envelopes[i];
            Point point = new Point(centers[i].x + nx * offset, centers[i].z + nz * offset);
            double host = finite(ground, point), support = host - 1;
            double width = widthAt.applyAsDouble(i / (double)steps);
            // Lateral search evaluates actual channel flanks; final dense validation checks
            // all interior offsets against the refined tangent before a route is accepted.
            for (double side : BANK_OFFSETS)
                support = Math.min(support, finite(ground,
                        new Point(point.x + nx * width * side, point.z + nz * width * side)) - 1);
            support = Math.max(sea, support);
            double perched = Math.max(0, downstreamWater - support);
            double preferred = downstreamWater + (headWater - downstreamWater) * (1 - i / (double)steps);
            double rise = Math.max(0, host - preferred);
            double prematureDrop = Math.max(0, preferred - support);
            double local = rise * rise * .025 + prematureDrop * prematureDrop * .2
                    + perched * perched * 50 + offset * offset * .003;
            for (int p = Math.max(0, lane - 2); p <= Math.min(lanes - 1, lane + 2); p++) {
                double oldOffset = (p - center) * 8 * envelopes[i - 1];
                double transition = (offset - oldOffset) * (offset - oldOffset) * .08;
                double candidate = cost[i - 1][p] + local + transition;
                if (candidate < cost[i][lane]) { cost[i][lane] = candidate; previous[i][lane] = p; }
            }
        }
        if (!Double.isFinite(cost[steps][center])) return List.copyOf(path);
        double[] shifts = new double[steps + 1];
        int lane = center;
        for (int i = steps; i > 0; i--) {
            shifts[i] = (lane - center) * 8 * envelopes[i];
            lane = previous[i][lane];
        }
        // Repeated bounded averaging suppresses staircase lanes. Endpoint collars are fixed.
        for (int pass = 0; pass < 3; pass++) {
            double[] smoothed = shifts.clone();
            for (int i = 1; i < steps; i++) if (envelopes[i] > 0)
                smoothed[i] = (shifts[i - 1] + shifts[i] * 2 + shifts[i + 1]) * .25;
            shifts = smoothed;
        }
        List<Point> result = new ArrayList<>();
        result.add(first);
        int section = 0;
        for (int i = 1; i < path.size(); i++) {
            double t = arc[i] / length * steps;
            section = Math.min(steps - 1, (int)t);
            double blend = t - section;
            double shift = shifts[section] * (1 - blend) + shifts[section + 1] * blend;
            Point p = path.get(i);
            Point refined = i == path.size() - 1 ? last : new Point(p.x + nx * shift, p.z + nz * shift);
            Point before = result.getLast();
            int subdivisions = Math.max(1, (int)Math.ceil(distance(before, refined) / 2));
            for (int j = 1; j <= subdivisions; j++) {
                double u = j / (double)subdivisions;
                result.add(j == subdivisions ? refined : new Point(before.x + (refined.x - before.x) * u,
                        before.z + (refined.z - before.z) * u));
            }
        }
        return List.copyOf(result);
    }

    private static Point at(List<Point> points, double[] arc, double target) {
        int index = Arrays.binarySearch(arc, target);
        if (index >= 0) return points.get(index);
        index = -index - 1;
        if (index == 0) return points.getFirst();
        if (index >= arc.length) return points.getLast();
        double t = (target - arc[index - 1]) / (arc[index] - arc[index - 1]);
        Point a = points.get(index - 1), b = points.get(index);
        return new Point(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t);
    }
    private static double finite(DoubleBinaryOperator ground, Point p) {
        double value = ground.applyAsDouble(p.x, p.z);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite route ground");
        return value;
    }
    private static double distance(Point a, Point b) { return Math.hypot(a.x - b.x, a.z - b.z); }
}
