package net.quiwh.cwiworldgen.hydrology;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Detached topology; the reference provider's bounds use its native routing domain. */
public record HydrologySnapshot(String provider, int version, int seed,
                                int continentX, int continentZ, HydrologyBounds bounds,
                                List<DirectedReach> reaches) {
    public HydrologySnapshot {
        if (provider == null || provider.isBlank() || version < 1) {
            throw new IllegalArgumentException("A versioned hydrology provider is required");
        }
        Objects.requireNonNull(bounds, "bounds");
        reaches = List.copyOf(reaches);
        var byId = new HashMap<Long, DirectedReach>();
        for (DirectedReach reach : reaches) {
            if (byId.put(reach.id(), reach) != null) {
                throw new IllegalArgumentException("Duplicate reach ID");
            }
        }
        // Kahn's algorithm validates all split/rejoin edges in linear graph time.
        var indegree = new HashMap<Long, Integer>();
        for (DirectedReach reach : reaches) indegree.put(reach.id(), 0);
        for (DirectedReach reach : reaches) for (long downstream : reach.downstreamIds()) {
            if (!byId.containsKey(downstream)) throw new IllegalArgumentException("Missing downstream reach");
            indegree.merge(downstream, 1, Integer::sum);
        }
        var ready = new java.util.ArrayDeque<Long>();
        indegree.forEach((id, degree) -> { if (degree == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            var reach = byId.get(ready.remove());
            visited++;
            for (long downstream : reach.downstreamIds())
                if (indegree.merge(downstream, -1, Integer::sum) == 0) ready.add(downstream);
        }
        if (visited != reaches.size()) throw new IllegalArgumentException("Cyclic drainage graph");
    }
}
