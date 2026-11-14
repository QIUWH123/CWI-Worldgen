package net.quiwh.cwiworldgen.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Compact, overlapping forest-rock lobes with a small fixed volume budget. */
public final class AshBoulderShape {
    public record Column(int x, int z, int buried, int relief) {
        public int volume() { return buried + relief + 1; }
    }
    private record Point(int x, int z) {}

    private AshBoulderShape() {}

    public static List<Column> create(long seed) {
        Random random = new Random(seed);
        int volume = 12 + random.nextInt(9);
        int count = 3 + random.nextInt(Math.min(5, volume / 3) - 2);
        double angle = random.nextDouble() * Math.PI * 2;
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double radiusX = 1.25 + random.nextDouble() * .35;
        double radiusZ = .95 + random.nextDouble() * .35;
        double offsetX = .5 + random.nextDouble() * .6;
        double offsetZ = (random.nextDouble() - .5) * 1.1;
        List<Point> points = new ArrayList<>();
        points.add(new Point(0, 0));
        while (points.size() < count) {
            Point selected = null;
            double score = Double.NEGATIVE_INFINITY;
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                Point point = new Point(x, z);
                if (points.contains(point) || points.stream().noneMatch(member ->
                        Math.abs(member.x - point.x) + Math.abs(member.z - point.z) == 1)) continue;
                double u = x * cos + z * sin, v = -x * sin + z * cos;
                double main = u * u / (radiusX * radiusX) + v * v / (radiusZ * radiusZ);
                double lobe = (u - offsetX) * (u - offsetX) / .95
                        + (v - offsetZ) * (v - offsetZ) / .8;
                double value = -Math.min(main, lobe) + random.nextDouble() * .8;
                if (value > score) { score = value; selected = point; }
            }
            points.add(selected);
        }
        List<Column> columns = new ArrayList<>();
        for (Point point : points) columns.add(new Column(point.x, point.z, 1, 1));
        // Grow a few joined lobes instead of a uniform extruded patch. Each column
        // stays buried by 1–3 blocks and has 1–3 blocks of above-ground relief.
        for (int remaining = volume - count * 3; remaining > 0; remaining--) {
            List<Integer> available = new ArrayList<>();
            for (int index = 0; index < columns.size(); index++) {
                Column column = columns.get(index);
                if (column.buried < 3 || column.relief < 3) available.add(index);
            }
            int index = available.get(random.nextInt(available.size()));
            Column column = columns.get(index);
            boolean bury = column.relief >= 3 || column.buried < 3 && random.nextDouble() < .6;
            columns.set(index, new Column(column.x, column.z, column.buried + (bury ? 1 : 0),
                    column.relief + (bury ? 0 : 1)));
        }
        return List.copyOf(columns);
    }
}
