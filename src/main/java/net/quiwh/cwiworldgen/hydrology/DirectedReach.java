package net.quiwh.cwiworldgen.hydrology;

import java.util.List;

/** Empty points identify directed query-field geometry, not a zero-length reach. */
public record DirectedReach(long id, long downstreamId, boolean hasDownstream,
                            double downstreamJoinProgress, List<ReachPoint> points, List<Long> downstreamIds) {
    public DirectedReach(long id, long downstreamId, boolean hasDownstream, double downstreamJoinProgress, List<ReachPoint> points) {
        this(id, downstreamId, hasDownstream, downstreamJoinProgress, points, hasDownstream ? List.of(downstreamId) : List.of());
    }
    public DirectedReach {
        if (!Double.isFinite(downstreamJoinProgress)
                || downstreamJoinProgress < 0 || downstreamJoinProgress > 1) {
            throw new IllegalArgumentException("Parent attachment progress must be in [0,1]");
        }
        if (hasDownstream && id == downstreamId) {
            throw new IllegalArgumentException("A reach cannot drain into itself");
        }
        downstreamIds = List.copyOf(downstreamIds);
        if (hasDownstream != !downstreamIds.isEmpty() || hasDownstream && downstreamIds.getFirst() != downstreamId
                || downstreamIds.contains(id) || new java.util.HashSet<>(downstreamIds).size() != downstreamIds.size())
            throw new IllegalArgumentException("Inconsistent directed downstream edges");
        points = List.copyOf(points);
        if (points.size() == 1) {
            throw new IllegalArgumentException("A reconstructed reach needs at least two points");
        }
    }
}
