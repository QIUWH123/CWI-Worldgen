package net.quiwh.cwiworldgen.terrain;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.ContinentalHydrology;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.RiverCarverSettings.RiverZone;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.Tile;

/**
 * Immutable projection of native river geometry, shared by placement, sealing and screening.
 * The source interval describes the original gasket's ocean-bounded scan, not all placed water.
 */
public record ReferenceRiverColumn(int groundY, int waterTableY, int waterY, int placementFloorY,
        int lowestNeighborWaterY, int retainingTopY, boolean waterCell, Transition transition,
        int sourceMinY, int sourceMaxY) {
    public enum Transition { NONE, STEP, DROP }

    public boolean hasWater() { return waterCell && waterY > groundY; }

    /** Highest solid that the pre-surface river pass adds to this column. */
    public int solidTopY() {
        return hasWater() && transition == Transition.DROP && lowestNeighborWaterY > placementFloorY
                ? lowestNeighborWaterY : retainingTopY;
    }

    /** Highest possible block written by this column, including dry retaining banks. */
    public int maximumWriteY() { return hasWater() ? waterY : retainingTopY; }

    /** Exactly reproduces the source's cardinal-neighbor drop and retaining-bank decisions. */
    public static ReferenceRiverColumn project(Levels levels, Cell cell,
            Cell north, Cell south, Cell east, Cell west) {
        return project(levels, cell, north, south, east, west, 21);
    }

    /** Profile22 retains actual water edges without raising the entire natural dry valley. */
    public static ReferenceRiverColumn project(Levels levels, Cell cell,
            Cell north, Cell south, Cell east, Cell west, int generationVersion) {
        int ground = levels.scale(cell.height);
        int ocean = levels.scale(levels.water);
        int table = waterTableY(cell, levels);
        int water = Math.max(ocean, table);
        boolean taggedWater = isWaterCell(cell);
        int lowest = water;
        int retaining = ground;
        boolean transition = false, drop = false;
        for (int direction = 0; direction < 4; direction++) {
            Cell neighbor = switch (direction) {
                case 0 -> north;
                case 1 -> south;
                case 2 -> east;
                default -> west;
            };
            if (taggedWater && ground < water && isWaterCell(neighbor)) {
                int neighborWater = Math.max(ocean, waterTableY(neighbor, levels));
                if (neighborWater < water) {
                    transition = true;
                    lowest = Math.min(lowest, neighborWater);
                    drop |= water - neighborWater > 1;
                }
            } else if (!taggedWater && ground >= ocean
                    && (neighbor.terrain.isRiver() || neighbor.terrain.isLake())) {
                // Native retaining banks use the raw table and do not require a water tag.
                retaining = Math.max(retaining, waterTableY(neighbor, levels));
            }
        }
        boolean broadRetainingFill = generationVersion < 22 || !cell.cwiPlannedWater || !cell.cwiNaturalRiver;
        if (broadRetainingFill && !taggedWater && ground > ocean && (cell.riverZone == RiverZone.Banks
                || cell.riverZone == RiverZone.ValleyFloor || cell.riverZone == RiverZone.ValleyFadeout)) {
            retaining = Math.max(retaining, table);
        }
        int floor = drop ? (ground + lowest) / 2 : ground;
        Transition kind = drop ? Transition.DROP : transition ? Transition.STEP : Transition.NONE;
        int sourceMin = 1, sourceMax = 0;
        if (taggedWater && ground < water) {
            sourceMin = Math.max(ocean, drop ? water : floor + 1);
            sourceMax = transition && !drop ? water - 1 : water;
        }
        return new ReferenceRiverColumn(ground, table, water, floor, lowest, retaining,
                taggedWater, kind, sourceMin, sourceMax);
    }

    public static boolean isWaterCell(Cell cell) {
        return (cell.terrain.isRiver() || cell.terrain.isLake() || cell.terrain.isWetland())
                && cell.riverWaterLevel > 0;
    }

    public static int waterTableY(Cell cell, Levels levels) {
        if (cell.cwiPlannedWater) return levels.scale(cell.cwiWaterHeight);
        return levels.scale(ContinentalHydrology.getComplexWaterHeight(
                cell.waterTable, cell.globalContinentScale, cell.continentSizeModifier) + levels.water);
    }

    public static ReferenceRiverColumn sample(ReferenceTerrain terrain, int x, int z) {
        return project(terrain.levels(), terrain.sample(x, z), terrain.sample(x, z - 1),
                terrain.sample(x, z + 1), terrain.sample(x + 1, z), terrain.sample(x - 1, z),
                terrain.generationVersion());
    }

    /**
     * Pins completed immutable tiles for a fixed rectangular interior and its one-column rim.
     * No mutable neighboring Minecraft chunks are read or written.
     */
    public static final class Reader {
        private final int minX, minZ, width, depth;
        private final int generationVersion;
        private final Levels levels;
        private final Cell[] cells;

        public Reader(ReferenceTerrain terrain, int minX, int minZ, int width, int depth) {
            if (width < 1 || depth < 1) throw new IllegalArgumentException("River halo dimensions must be positive");
            this.minX = minX - 1;
            this.minZ = minZ - 1;
            this.width = width + 2;
            this.depth = depth + 2;
            this.generationVersion = terrain.generationVersion();
            this.levels = terrain.levels();
            this.cells = new Cell[this.width * this.depth];
            Map<Long, Tile.Chunk> readers = new HashMap<>();
            for (int z = 0; z < this.depth; z++) for (int x = 0; x < this.width; x++) {
                int blockX = this.minX + x, blockZ = this.minZ + z;
                long key = ChunkPos.asLong(blockX >> 4, blockZ >> 4);
                Tile.Chunk reader = readers.computeIfAbsent(key,
                        ignored -> terrain.prepareChunk(blockX >> 4, blockZ >> 4));
                cells[z * this.width + x] = reader.getCell(blockX, blockZ);
            }
        }

        public ReferenceRiverColumn sample(int blockX, int blockZ) {
            int x = blockX - minX, z = blockZ - minZ;
            if (x <= 0 || x >= width - 1 || z <= 0 || z >= depth - 1)
                throw new IndexOutOfBoundsException("River column lies outside its pinned halo");
            int index = z * width + x;
            return project(levels, cells[index], cells[index - width], cells[index + width],
                    cells[index + 1], cells[index - 1], generationVersion);
        }
    }
}
