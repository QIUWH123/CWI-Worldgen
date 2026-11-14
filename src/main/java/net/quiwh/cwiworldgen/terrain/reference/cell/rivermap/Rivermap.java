package net.quiwh.cwiworldgen.terrain.reference.cell.rivermap;

import net.quiwh.cwiworldgen.terrain.reference.concurrent.cache.ExpiringEntry;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Heightmap;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.gen.GenWarp;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.Network;
import net.quiwh.cwiworldgen.terrain.reference.noise.domain.Domain;

public class Rivermap implements ExpiringEntry {
    private int x;
    private int z;
    private Domain lakeWarp;
    private Domain riverWarp;
    private Network[] networks;
    private long timestamp;
    
    public Rivermap(int x, int z, Network[] networks, GenWarp warp) {
        this.timestamp = System.currentTimeMillis();
        this.x = x;
        this.z = z;
        this.networks = networks;
        this.lakeWarp = warp.lake();
        this.riverWarp = warp.river();
    }
    
    public void apply(Cell cell, float x, float z) {
        float rx = this.riverWarp.getX(x, z, 0);
        float rz = this.riverWarp.getZ(x, z, 0);
        float lx = this.lakeWarp.getOffsetX(rx, rz, 0);
        float lz = this.lakeWarp.getOffsetZ(rx, rz, 0);
        for (Network network : this.networks) {
            if (network.contains(rx, rz)) {
                network.carve(cell, rx, rz, lx, lz);
            }
        }
    }
    
    /** Immutable numeric topology in native routing coordinates, in stable preorder. */
    public java.util.List<ReachData> topology() {
        java.util.List<ReachData> result = new java.util.ArrayList<>();
        for (Network root : this.networks) {
            collectTopology(root, null, -1, result);
        }
        return java.util.List.copyOf(result);
    }

    public record ReachData(int index, int parentIndex, boolean hasParent,
                            float downstreamJoinProgress,
                            float x1, float z1, float x2, float z2) {}

    public record Mapping(float x, float z, float progress, float signedDistance) {}

    public record ReachQuery(ReachData reach, Mapping center, Mapping east,
                             Mapping west, Mapping south, Mapping north,
                             boolean lakeExpanded, boolean nativeChannel,
                             double probeSpanX, double probeSpanZ) {}

    private static void collectTopology(Network network, Network parent, int parentIndex,
                                         java.util.List<ReachData> result) {
        var river = network.riverCarver().getRiver();
        float join = 0;
        if (parent != null) {
            var downstream = parent.riverCarver().getRiver();
            join = net.quiwh.cwiworldgen.terrain.reference.noise.module.Line.distanceOnLine(
                    river.x2, river.z2, downstream.x1, downstream.z1, downstream.x2, downstream.z2);
        }
        int index = result.size();
        result.add(new ReachData(index, parentIndex, parent != null, join,
                river.x1, river.z1, river.x2, river.z2));
        for (Network child : network.children()) {
            collectTopology(child, network, index, result);
        }
    }

    /** Exact native forward transforms; no inverse or guessed world-space polyline. */
    public java.util.List<ReachQuery> queryGeometry(float x, float z) {
        return queryGeometry(null, x, z);
    }

    public java.util.List<ReachQuery> queryGeometry(Cell cell, float x, float z) {
        java.util.List<ReachQuery> result = new java.util.ArrayList<>();
        java.util.List<ReachData> topology = topology();
        float stepX = Math.max(0.5F, Math.ulp(x));
        float stepZ = Math.max(0.5F, Math.ulp(z));
        float eastX = x + stepX;
        float westX = x - stepX;
        float southZ = z + stepZ;
        float northZ = z - stepZ;
        double spanX = (double) eastX - westX;
        double spanZ = (double) southZ - northZ;
        if (!Float.isFinite(eastX) || !Float.isFinite(westX)
                || !Float.isFinite(southZ) || !Float.isFinite(northZ)
                || !(spanX > 0) || !(spanZ > 0)) return java.util.List.of();
        Mapping center = globalMapping(x, z);
        Mapping east = globalMapping(eastX, z);
        Mapping west = globalMapping(westX, z);
        Mapping south = globalMapping(x, southZ);
        Mapping north = globalMapping(x, northZ);
        int[] cursor = {0};
        for (Network root : this.networks) {
            boolean active = root.contains(center.x(), center.z());
            queryNetwork(root, cell, active, topology, cursor, result,
                    center, east, west, south, north, spanX, spanZ);
        }
        return java.util.List.copyOf(result);
    }

    private Mapping globalMapping(float x, float z) {
        return new Mapping(this.riverWarp.getX(x, z, 0), this.riverWarp.getZ(x, z, 0), 0, 0);
    }

    private static void queryNetwork(Network network, Cell cell, boolean active,
                                     java.util.List<ReachData> topology, int[] cursor,
                                     java.util.List<ReachQuery> result,
                                     Mapping center, Mapping east, Mapping west,
                                     Mapping south, Mapping north, double spanX, double spanZ) {
        ReachData reach = topology.get(cursor[0]++);
        Mapping mapped = localMapping(network, center);
        Mapping mappedEast = localMapping(network, east);
        Mapping mappedWest = localMapping(network, west);
        Mapping mappedSouth = localMapping(network, south);
        Mapping mappedNorth = localMapping(network, north);
        if (active) {
            boolean lake = cell != null
                    && network.riverCarver() instanceof net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.UpliftRiverCarver carver
                    && carver.hydrologyLakeMultiplier(cell, mapped.progress(), mapped.x(), mapped.z()) > 1.0F;
            boolean channel = cell == null
                    || !(network.riverCarver() instanceof net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.UpliftRiverCarver carver)
                    || carver.hydrologyChannelContains(cell, mapped.progress(), mapped.x(), mapped.z());
            result.add(new ReachQuery(reach, mapped, mappedEast, mappedWest, mappedSouth, mappedNorth, lake, channel, spanX, spanZ));
        }
        for (Network child : network.children()) {
            queryNetwork(child, cell, active, topology, cursor, result,
                    mapped, mappedEast, mappedWest, mappedSouth, mappedNorth, spanX, spanZ);
        }
    }

    private static Mapping localMapping(Network network, Mapping input) {
        var river = network.riverCarver().getRiver();
        var warp = network.riverCarver().getWarp();
        float x = input.x();
        float z = input.z();
        float t = net.quiwh.cwiworldgen.terrain.reference.noise.module.Line.distanceOnLine(
                x, z, river.x1, river.z1, river.x2, river.z2);
        if (warp.test(t)) {
            long offset = warp.getOffset(x, z, t, river);
            x += net.quiwh.cwiworldgen.terrain.reference.util.PosUtil.unpackLeftf(offset);
            z += net.quiwh.cwiworldgen.terrain.reference.util.PosUtil.unpackRightf(offset);
            t = net.quiwh.cwiworldgen.terrain.reference.noise.module.Line.distanceOnLine(
                    x, z, river.x1, river.z1, river.x2, river.z2);
        }
        float signed = river.ndx * (z - river.z1) - river.ndz * (x - river.x1);
        return new Mapping(x, z, t, signed);
    }

    @Override
    public long getTimestamp() {
        return this.timestamp;
    }
    
    public int getX() {
        return this.x;
    }
    
    public int getZ() {
        return this.z;
    }
    
    public static Rivermap get(Cell cell, Rivermap instance, Heightmap heightmap) {
        return get(cell.continentX, cell.continentZ, instance, heightmap);
    }
    
    public static Rivermap get(int x, int z, Rivermap instance, Heightmap heightmap) {
        if (instance != null && x == instance.getX() && z == instance.getZ()) {
            return instance;
        }
        return heightmap.continent().getRivermap(x, z);
    }
}
