package net.quiwh.cwiworldgen.hydrology.current;

import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverColumn;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

/** Queries completed immutable terrain; never requests Minecraft chunks or changes blocks. */
public final class CurrentFieldBuilder {
    private CurrentFieldBuilder() {}
    public static CurrentChunk build(ReferenceTerrain terrain, ChunkPos chunk, double strength) {
        long[] fields = new long[CurrentChunk.COLUMNS];
        if (terrain == null || terrain.generationVersion() < 2) return new CurrentChunk(chunk.toLong(), fields);
        int minX = chunk.getMinBlockX(), minZ = chunk.getMinBlockZ();
        var nativeCells = terrain.prepareChunk(chunk.x, chunk.z);
        var columns = new ReferenceRiverColumn.Reader(terrain, minX - 9, minZ - 9, 34, 34);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int blockX = minX + x, blockZ = minZ + z;
            var column = columns.sample(blockX, blockZ);
            if (!column.hasWater() || !nativeCells.getCell(blockX, blockZ).terrain.isRiver()) continue;
            var query = terrain.queryHydrology(blockX, blockZ);
            int floor = column.placementFloorY() + 1;
            if (column.transition() == ReferenceRiverColumn.Transition.DROP) floor = Math.max(floor, column.lowestNeighborWaterY() + 1);
            if (floor > column.waterY()) continue;
            int drop = Math.max(0, column.waterY() - column.lowestNeighborWaterY());
            int impactDirection = impactDirection(columns, blockX, blockZ, column.waterY());
            if (!query.validDirection() || strength <= 0) {
                fields[z * 16 + x] = CurrentColumn.still(floor, column.waterY(), drop, impactDirection).pack();
                continue;
            }
            int width = 1;
            for (int side : new int[]{-1, 1}) {
                for (int distance = 1; distance <= 8; distance++) {
                    int dx = (int) Math.round(-query.tangentZ() * distance * side);
                    int dz = (int) Math.round(query.tangentX() * distance * side);
                    if (!columns.sample(blockX + dx, blockZ + dz).hasWater()) break;
                    width++;
                }
            }
            int forwardX = blockX + (int) Math.round(query.tangentX() * 4);
            int forwardZ = blockZ + (int) Math.round(query.tangentZ() * 4);
            int backwardX = blockX - (int) Math.round(query.tangentX() * 4);
            int backwardZ = blockZ - (int) Math.round(query.tangentZ() * 4);
            double slope = downhillSlope(column, columns.sample(backwardX, backwardZ), columns.sample(forwardX, forwardZ));
            double speed = profileSpeed(column.waterY() - floor + 1, width, slope, drop) * strength;
            fields[z * 16 + x] = CurrentColumn.of(floor, column.waterY(), query.tangentX(), query.tangentZ(), speed, drop, impactDirection).pack();
        }
        return new CurrentChunk(chunk.toLong(), fields);
    }

    /** Dry projections still carry a synthetic water table; only wet endpoints describe a current's grade. */
    static double downhillSlope(ReferenceRiverColumn current, ReferenceRiverColumn backward, ReferenceRiverColumn forward) {
        if (backward.hasWater() && forward.hasWater())
            return Math.max(0, backward.waterY() - forward.waterY()) / 8.;
        if (backward.hasWater()) return Math.max(0, backward.waterY() - current.waterY()) / 4.;
        if (forward.hasWater()) return Math.max(0, current.waterY() - forward.waterY()) / 4.;
        return 0;
    }

    private static int impactDirection(ReferenceRiverColumn.Reader columns, int x, int z, int waterY) {
        int impact = 0, lowest = waterY;
        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (int index = 0; index < directions.length; index++) {
            var offset = directions[index];
            var next = columns.sample(x + offset.getStepX(), z + offset.getStepZ());
            if (next.waterCell() && next.waterY() < lowest) { lowest = next.waterY(); impact = index; }
        }
        return impact;
    }

    public static double profileSpeed(int depth, int width, double slope, int drop) {
        return Math.min(.065, .017 + Math.min(8, depth) * .0015 + Math.min(17, width) * .00055
                + Math.min(.8, slope) * .018 + Math.min(8, drop) * .0018);
    }
}
