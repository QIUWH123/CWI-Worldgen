package net.quiwh.cwiworldgen.hydrology.current;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Observed water only: level components drain toward observed lower, connected water. */
public final class WaterSurfaceReflow {
    public static final int MAX_SURFACES = 32768;
    private WaterSurfaceReflow() {}
    public record Surface(int x, int z, int bottom, int top) {
        public Surface {
            if (bottom > top || bottom < -2032 || top > 2031
                    || Math.abs((long)x) > 30000000 || Math.abs((long)z) > 30000000)
                throw new IllegalArgumentException("Invalid observed water interval");
        }
    }
    public record Flow(Surface surface, double x, double z, double speed) {}
    private static final int[][] OFFSETS = {{-1,0},{0,-1},{0,1},{1,0}};

    public static List<Flow> solve(List<Surface> observed) {
        if (observed.size() > MAX_SURFACES) throw new IllegalArgumentException("Reflow surface budget exceeded");
        List<Surface> cells = new ArrayList<>(observed);
        cells.sort(Comparator.comparingInt(Surface::x).thenComparingInt(Surface::z)
                .thenComparingInt(Surface::top).thenComparingInt(Surface::bottom));
        Map<Long,List<Integer>> columns = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0 && cells.get(i).equals(cells.get(i-1)))
                throw new IllegalArgumentException("Duplicate observed surface");
            var c = cells.get(i);
            var column = columns.computeIfAbsent(key(c.x,c.z), ignored -> new ArrayList<>());
            if (column.size() >= 2 || (!column.isEmpty() && cells.get(column.getLast()).top >= c.bottom))
                throw new IllegalArgumentException("At most two disjoint observed water intervals per column");
            column.add(i);
        }
        int[][] neighbors = new int[cells.size()][];
        int[] distance = new int[cells.size()], receiver = new int[cells.size()];
        Arrays.fill(distance, Integer.MAX_VALUE); Arrays.fill(receiver, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < cells.size(); i++) {
            Surface c = cells.get(i);
            List<Integer> adjacent = new ArrayList<>();
            for (var d : OFFSETS) for (int j : columns.getOrDefault(key(c.x+d[0],c.z+d[1]), List.of())) {
                Surface n = cells.get(j);
                // A solid vertical gap separates stacked bodies; never connect by X/Z alone.
                if (n.top < c.bottom || c.top < n.bottom) continue;
                adjacent.add(j);
                if (n.top < c.top && (receiver[i] < 0 || n.top < cells.get(receiver[i]).top)) receiver[i] = j;
            }
            neighbors[i] = adjacent.stream().mapToInt(Integer::intValue).toArray();
            if (receiver[i] >= 0) { distance[i] = 0; queue.add(i); }
        }
        while (!queue.isEmpty()) {
            int i = queue.remove();
            for (int j : neighbors[i]) if (cells.get(j).top == cells.get(i).top && distance[j] > distance[i]+1) {
                distance[j] = distance[i]+1;
                queue.add(j);
            }
        }
        List<Flow> result = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            Surface c = cells.get(i);
            double dx = 0, dz = 0, drop = 0;
            if (receiver[i] >= 0) {
                Surface n = cells.get(receiver[i]);
                dx = n.x-c.x; dz = n.z-c.z; drop = c.top-n.top;
            } else if (distance[i] < Integer.MAX_VALUE) {
                // Choose an observed edge, not the average of exits. Averaging an
                // L-shaped pair would point through the unobserved diagonal corner.
                for (int j : neighbors[i]) if (cells.get(j).top == c.top && distance[j] < distance[i]) {
                    Surface n = cells.get(j); dx = n.x-c.x; dz = n.z-c.z; break;
                }
            }
            double length = Math.hypot(dx,dz);
            double speed = length == 0 ? 0 : Math.min(.065, .022 + Math.min(8,c.top-c.bottom+1)*.002 + Math.min(4,drop)*.004);
            result.add(new Flow(c, length == 0 ? 0 : dx/length, length == 0 ? 0 : dz/length, speed));
        }
        return List.copyOf(result);
    }
    private static long key(int x,int z) { return ((long)x<<32) ^ (z&0xffffffffL); }
}
