package net.quiwh.cwiworldgen.terrain;

import java.util.LinkedHashMap;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;

/** Seed-owned immutable water envelopes, computed without reading Minecraft chunks. */
public final class ReferenceWaterProtection {
    public static final int CACHE_LIMIT = 8192;
    private final ReferenceTerrain terrain;
    private final LinkedHashMap<Long, WaterProtectionColumn> columns = new LinkedHashMap<>(256, .75F, true);

    public ReferenceWaterProtection(ReferenceTerrain terrain) { this.terrain = terrain; }

    public WaterProtectionColumn column(int x, int z) {
        if (terrain.generationVersion() < 12) return WaterProtectionColumn.NONE;
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        synchronized (columns) {
            var found = columns.get(key);
            if (found != null) return found;
        }
        // Complete tiles may plan neighboring rivers; never hold the envelope cache lock here.
        WaterProtectionColumn result = compute(x, z);
        synchronized (columns) {
            columns.put(key, result);
            if (columns.size() > CACHE_LIMIT) columns.remove(columns.keySet().iterator().next());
        }
        return result;
    }

    private WaterProtectionColumn compute(int x, int z) {
        var pond = terrain.pondColumn(x, z);
        if (pond.protectedColumn()) return new WaterProtectionColumn(true, pond.groundY(),
                pond.waterY(), pond.protectionFloorY());
        // Pin only the requested chunk. Neighbor readers stay lazy so dry/pond
        // early returns never generate an otherwise unused tile across a seam.
        var reader = terrain.prepareChunk(x >> 4, z >> 4);
        Cell cell = reader.getCell(x, z);
        if (!cell.cwiPlannedWater) return WaterProtectionColumn.NONE;
        var levels = terrain.levels();
        int ground = levels.scale(cell.height);
        int water = ReferenceRiverColumn.waterTableY(cell, levels);
        if (ReferenceRiverColumn.isWaterCell(cell) && water > ground)
            return new WaterProtectionColumn(true, ground, water, ground - 2);
        // A single native column beside exposed water retains its original side wall.
        // Wider valley banks and deep caves remain subject to their existing rules.
        int floor = Integer.MAX_VALUE, highWater = Integer.MIN_VALUE;
        for (int side = 0; side < 4; side++) {
            int nx = x + (side == 0 ? -1 : side == 1 ? 1 : 0);
            int nz = z + (side == 2 ? -1 : side == 3 ? 1 : 0);
            var neighborReader = (nx >> 4) == (x >> 4) && (nz >> 4) == (z >> 4)
                    ? reader : terrain.prepareChunk(nx >> 4, nz >> 4);
            Cell neighbor = neighborReader.getCell(nx, nz);
            if (!ReferenceRiverColumn.isWaterCell(neighbor)) continue;
            int bed = levels.scale(neighbor.height);
            int surface = ReferenceRiverColumn.waterTableY(neighbor, levels);
            if (surface <= bed || ground < surface) continue;
            floor = Math.min(floor, bed - 2);
            highWater = Math.max(highWater, surface);
        }
        return floor == Integer.MAX_VALUE ? WaterProtectionColumn.NONE
                : new WaterProtectionColumn(true, ground, highWater, floor);
    }

    public void clear() { synchronized (columns) { columns.clear(); } }
    public int size() { synchronized (columns) { return columns.size(); } }
}
