package net.quiwh.cwiworldgen.feature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Face-connected growth preserves tiny rotated aggregates at Minecraft's voxel resolution. */
final class IndustrialAggregateGrowth {
    record Coordinate(int x, int y, int z) {}
    record Voxel(Coordinate coordinate, double score, int nucleus) {}
    private static final Comparator<Voxel> ORDER = Comparator.comparingDouble(Voxel::score)
        .thenComparingInt(voxel -> voxel.coordinate.x)
        .thenComparingInt(voxel -> voxel.coordinate.y)
        .thenComparingInt(voxel -> voxel.coordinate.z);

    private IndustrialAggregateGrowth() {}

    static Set<Coordinate> select(List<Voxel> candidates, int budget) {
        if (budget <= 0) return Set.of();
        List<Voxel> ordered = new ArrayList<>(candidates);
        ordered.sort(ORDER);
        Map<Coordinate, Voxel> available = new HashMap<>();
        for (Voxel voxel : ordered) available.put(voxel.coordinate, voxel);
        PriorityQueue<Voxel> frontier = new PriorityQueue<>(ORDER);
        Set<Coordinate> queued = new HashSet<>();
        Set<Integer> nuclei = new HashSet<>();
        List<Voxel> seeds = new ArrayList<>();
        for (Voxel voxel : ordered) {
            if (nuclei.add(voxel.nucleus)) {
                seeds.add(voxel);
                if (seeds.size() >= Math.max(1, budget / 2)) break;
            }
        }
        Set<Coordinate> selected = new HashSet<>();
        for (Voxel seed : seeds) selected.add(seed.coordinate);
        // Prime each nucleus with a face neighbor before score competition can
        // spend a tiny body's remaining budget on only its easiest nucleus.
        for (Voxel seed : seeds) {
            if (selected.size() >= budget) break;
            var adjacent = adjacent(seed.coordinate);
            if (adjacent.stream().anyMatch(selected::contains)) continue;
            adjacent.stream().map(available::get).filter(voxel -> voxel != null).min(ORDER)
                .ifPresent(voxel -> selected.add(voxel.coordinate));
        }
        queued.addAll(selected);
        for (Coordinate seed : selected) {
            for (Coordinate adjacent : adjacent(seed)) {
                Voxel neighbor = available.get(adjacent);
                if (neighbor != null && queued.add(adjacent)) frontier.add(neighbor);
            }
        }
        int next = 0;
        while (selected.size() < budget && selected.size() < available.size()) {
            if (frontier.isEmpty()) {
                // Protected terrain can disconnect eligible pieces. Starting a new
                // eligible piece never bridges the protected gap with mineral blocks.
                while (next < ordered.size() && queued.contains(ordered.get(next).coordinate)) next++;
                if (next == ordered.size()) break;
                Voxel voxel = ordered.get(next++);
                queued.add(voxel.coordinate);
                frontier.add(voxel);
            }
            Voxel voxel = frontier.remove();
            Coordinate pos = voxel.coordinate;
            selected.add(pos);
            for (Coordinate adjacent : adjacent(pos)) {
                Voxel neighbor = available.get(adjacent);
                if (neighbor != null && queued.add(adjacent)) frontier.add(neighbor);
            }
        }
        return selected;
    }

    private static List<Coordinate> adjacent(Coordinate pos) {
        return List.of(new Coordinate(pos.x + 1, pos.y, pos.z), new Coordinate(pos.x - 1, pos.y, pos.z),
            new Coordinate(pos.x, pos.y + 1, pos.z), new Coordinate(pos.x, pos.y - 1, pos.z),
            new Coordinate(pos.x, pos.y, pos.z + 1), new Coordinate(pos.x, pos.y, pos.z - 1));
    }
}
