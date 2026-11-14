package net.quiwh.cwiworldgen.terrain;

import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainCategory;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.filter.Filterable;

/** Versioned dry rock faces and rare shelves, before the final slope calculation. */
public record RockFaceReliefFilter(long seed, int generationVersion, double worldHeight, double seaLevel) {
    private static final int HALO = 12;
    private static final int SLOPE_RADIUS = 8;
    private static final int PROTECTED_RADIUS = 8;

    public void apply(Filterable map) {
        if (generationVersion < 13) return;
        int side = map.getBlockSize().total(), stride = side + 1;
        var cells = map.getBacking();
        var heights = new float[cells.length];
        // Summed water/protection mask makes the full 17x17 dry envelope a constant-time query.
        var blocked = new int[stride * stride];
        for (int z = 0; z < side; z++) {
            int row = 0;
            for (int x = 0; x < side; x++) {
                Cell cell = cells[z * side + x];
                if (generationVersion >= 19 && cell != null) cell.cwiRockShelf = null;
                heights[z * side + x] = cell == null ? Float.NaN : cell.height;
                if (protectedColumn(cell)) row++;
                blocked[(z + 1) * stride + x + 1] = blocked[z * stride + x + 1] + row;
            }
        }
        int worldX = map.getBlockX() - map.getBlockSize().border();
        int worldZ = map.getBlockZ() - map.getBlockSize().border();
        var field = new RockFaceRelief.Field(seed, worldX + HALO, worldZ + HALO,
                worldX + side - HALO - 1, worldZ + side - HALO - 1, generationVersion);
        var shelves = generationVersion >= 16 ? new RockShelfRelief.Field(seed,
                worldX + HALO, worldZ + HALO, worldX + side - HALO - 1, worldZ + side - HALO - 1) : null;
        var shelfSelection = generationVersion >= 19 ? new RockShelfRelief.Selection() : null;
        double shoreClearance = generationVersion >= 16 ? RockShelfRelief.MAX_CUT + 5 : MAX_SHORE_CLEARANCE;
        for (int z = HALO; z < side - HALO; z++) for (int x = HALO; x < side - HALO; x++) {
            Cell cell = cells[z * side + x];
            if (!rockTerrain(cell) || cell.height * worldHeight <= seaLevel + shoreClearance) continue;
            double resistance = smooth((.45 - cell.erosion) / .8);
            if (resistance == 0 || count(blocked, stride, x - PROTECTED_RADIUS, z - PROTECTED_RADIUS, x + PROTECTED_RADIUS + 1, z + PROTECTED_RADIUS + 1) != 0) continue;
            double protectionFade = 1;
            for (int radius = PROTECTED_RADIUS + 1; radius < HALO; radius++)
                if (count(blocked, stride, x - radius, z - radius, x + radius + 1, z + radius + 1) != 0) {
                    protectionFade = smooth((radius - PROTECTED_RADIUS) / (double) (HALO - PROTECTED_RADIUS));
                    break;
                }
            double broadSlope;
            if (generationVersion >= 18) {
                double dx = heights[z * side + x + SLOPE_RADIUS] - heights[z * side + x - SLOPE_RADIUS];
                double dz = heights[(z + SLOPE_RADIUS) * side + x] - heights[(z - SLOPE_RADIUS) * side + x];
                // Uniform-orientation mean of the historical L1 gradient is 4/pi.
                broadSlope = Math.hypot(dx, dz) * worldHeight / (2 * SLOPE_RADIUS) * (4 / Math.PI);
            } else {
                broadSlope = (Math.abs(heights[z * side + x + SLOPE_RADIUS] - heights[z * side + x - SLOPE_RADIUS])
                        + Math.abs(heights[(z + SLOPE_RADIUS) * side + x] - heights[(z - SLOPE_RADIUS) * side + x])) * worldHeight / (2 * SLOPE_RADIUS);
            }
            double slope = smooth((broadSlope - .28) / .47);
            double cut = field.cut(worldX + x, worldZ + z, resistance * protectionFade, slope);
            RockShelfRelief.Sample acceptedShelf = null;
            if (shelves != null && broadSlope > .5) {
                double height = heights[z * side + x] * worldHeight;
                double west = heights[z * side + x - SLOPE_RADIUS] * worldHeight;
                double east = heights[z * side + x + SLOPE_RADIUS] * worldHeight;
                double north = heights[(z - SLOPE_RADIUS) * side + x] * worldHeight;
                double south = heights[(z + SLOPE_RADIUS) * side + x] * worldHeight;
                double lower = Math.min(Math.min(west, east), Math.min(north, south));
                double upper = Math.max(Math.max(west, east), Math.max(north, south));
                // Require both uphill and downhill support: no new cuts into crests or toes.
                double flank = smooth((broadSlope - .5) / .45)
                        * smooth((Math.min(height - lower, upper - height) - 3) / 3)
                        * smooth((height - seaLevel - 20) / 20);
                double shelfCut = shelfSelection == null
                        ? shelves.cut(worldX + x, worldZ + z, height, resistance * protectionFade, flank)
                        : shelves.cut(worldX + x, worldZ + z, height, resistance * protectionFade, flank, shelfSelection);
                // Retain only geometry that genuinely wins; allocation is limited to those columns.
                if (generationVersion >= 19 && shelfCut > cut && shelfCut > 0) {
                    float target = (float) (heights[z * side + x]
                            - Math.min(RockShelfRelief.MAX_CUT, shelfCut) / worldHeight);
                    if (Float.floatToRawIntBits(target) != Float.floatToRawIntBits(heights[z * side + x]))
                        acceptedShelf = shelfSelection.sample(worldX + x, worldZ + z, target);
                }
                cut = Math.max(cut, shelfCut);
                cut = Math.min(RockShelfRelief.MAX_CUT, cut);
            }
            if (cut > 0) {
                cell.height = (float) (heights[z * side + x] - cut / worldHeight);
                if (acceptedShelf != null) cell.cwiRockShelf = acceptedShelf;
            }
        }
    }

    private static final double MAX_SHORE_CLEARANCE = RockFaceRelief.MAX_CUT + 5;
    private static boolean rockTerrain(Cell cell) {
        if (cell == null || cell.terrain == null || cell.terrain.isVolcano() || cell.cwiIsland) return false;
        return cell.terrain.isMountain() || cell.terrain.getCategory() == TerrainCategory.LOWLAND;
    }
    private static boolean protectedColumn(Cell cell) {
        if (cell == null || cell.terrain == null || !Float.isFinite(cell.height)) return true;
        return cell.cwiPlannedWater || cell.cwiDryChannel || cell.cwiPond || cell.cwiPondProtected
                || cell.cwiIsland || cell.riverMask < .98F || cell.terrain.isSubmerged()
                || cell.terrain.isCoast() || cell.terrain.isRiver() || cell.terrain.isLake() || cell.terrain.isWetland();
    }
    private static int count(int[] sums, int stride, int x0, int z0, int x1, int z1) {
        return sums[z1 * stride + x1] - sums[z0 * stride + x1]
                - sums[z1 * stride + x0] + sums[z0 * stride + x0];
    }
    private static double smooth(double value) {
        value = Math.max(0, Math.min(1, value));
        return value * value * (3 - 2 * value);
    }
}
