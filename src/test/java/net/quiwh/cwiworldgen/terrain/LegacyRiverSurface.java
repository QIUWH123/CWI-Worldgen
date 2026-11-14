package net.quiwh.cwiworldgen.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.ContinentalHydrology;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.RiverCarverSettings;

/**
 * Frozen pre-refactor water-placement oracle (0.2.5); test sources only.
 * Compares complete native block arrays against the shared column consumer.
 * Neighbor terrain is sampled without writing to neighboring chunks.
 */
final class LegacyRiverSurface {
    private static final int[] NEIGHBOR_X = {0, 0, 1, -1};
    private static final int[] NEIGHBOR_Z = {-1, 1, 0, 0};

    private LegacyRiverSurface() {}

    public static void place(ChunkAccess chunk, BiomeManager biomeManager, ReferenceTerrain terrain) {
        int originX = chunk.getPos().getMinBlockX();
        int originZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;
        Levels levels = terrain.levels();
        int oceanY = levels.scale(levels.water);
        Cell[] samples = new Cell[18 * 18];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState flowingWater = water.setValue(BlockStateProperties.LEVEL, 1);
        BlockState ice = Blocks.ICE.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int globalX = originX + localX;
                int globalZ = originZ + localZ;
                Cell cell = sample(terrain, samples, originX, originZ, localX, localZ);
                int scaledY = levels.scale(cell.height);

                if (isWaterCell(cell)) {
                    int waterY = Math.max(oceanY, waterHeight(cell, levels));
                    if (waterY <= scaledY) {
                        continue;
                    }

                    boolean transitionColumn = false;
                    boolean multiBlockDrop = false;
                    int lowestNeighborWaterY = waterY;
                    for (int i = 0; i < NEIGHBOR_X.length; i++) {
                        Cell neighbor = sample(terrain, samples, originX, originZ,
                                localX + NEIGHBOR_X[i], localZ + NEIGHBOR_Z[i]);
                        if (isWaterCell(neighbor)) {
                            int neighborWaterY = Math.max(oceanY, waterHeight(neighbor, levels));
                            if (neighborWaterY < waterY) {
                                transitionColumn = true;
                                lowestNeighborWaterY = Math.min(lowestNeighborWaterY, neighborWaterY);
                                if (waterY - neighborWaterY > 1) {
                                    multiBlockDrop = true;
                                }
                            }
                        }
                    }

                    if (transitionColumn && multiBlockDrop) {
                        scaledY = (scaledY + lowestNeighborWaterY) / 2;
                    }

                    for (int y = Math.max(scaledY + 1, minY); y <= Math.min(waterY, maxY); y++) {
                        boolean topBlock = y == waterY;
                        pos.set(globalX, y, globalZ);
                        if (topBlock && transitionColumn && multiBlockDrop) {
                            chunk.setBlockState(pos, water, false);
                            chunk.markPosForPostprocessing(pos);
                        } else if (topBlock && transitionColumn) {
                            chunk.setBlockState(pos,
                                    biomeManager.getBiome(pos).value().coldEnoughToSnow(pos) ? ice : flowingWater,
                                    false);
                        } else if (!topBlock && multiBlockDrop) {
                            if (y > lowestNeighborWaterY) {
                                chunk.setBlockState(pos, flowingWater, false);
                                chunk.markPosForPostprocessing(pos);
                            } else {
                                chunk.setBlockState(pos, stone, false);
                            }
                        } else {
                            chunk.setBlockState(pos, water, false);
                            if (topBlock) {
                                chunk.markPosForPostprocessing(pos);
                            }
                        }
                    }
                } else if (scaledY >= oceanY) {
                    int maxNeighborWaterY = scaledY;
                    for (int i = 0; i < NEIGHBOR_X.length; i++) {
                        Cell neighbor = sample(terrain, samples, originX, originZ,
                                localX + NEIGHBOR_X[i], localZ + NEIGHBOR_Z[i]);
                        if (neighbor.terrain.isRiver() || neighbor.terrain.isLake()) {
                            maxNeighborWaterY = Math.max(maxNeighborWaterY, waterHeight(neighbor, levels));
                        }
                    }

                    int waterTableY = waterHeight(cell, levels);
                    boolean inRiverZone = cell.riverZone == RiverCarverSettings.RiverZone.Banks
                            || cell.riverZone == RiverCarverSettings.RiverZone.ValleyFloor
                            || cell.riverZone == RiverCarverSettings.RiverZone.ValleyFadeout;
                    if (inRiverZone && scaledY < waterTableY && scaledY > oceanY) {
                        fillStone(chunk, pos, globalX, globalZ, scaledY, waterTableY, minY, maxY, stone);
                    }
                    if (maxNeighborWaterY > scaledY) {
                        fillStone(chunk, pos, globalX, globalZ, scaledY, maxNeighborWaterY, minY, maxY, stone);
                    }
                }
            }
        }
    }

    private static boolean isWaterCell(Cell cell) {
        return (cell.terrain.isRiver() || cell.terrain.isLake() || cell.terrain.isWetland())
                && cell.riverWaterLevel > 0;
    }

    private static int waterHeight(Cell cell, Levels levels) {
        return levels.scale(ContinentalHydrology.getComplexWaterHeight(
                cell.waterTable, cell.globalContinentScale, cell.continentSizeModifier) + levels.water);
    }

    private static Cell sample(ReferenceTerrain terrain, Cell[] samples, int originX, int originZ,
            int localX, int localZ) {
        int index = (localX + 1) * 18 + localZ + 1;
        Cell cell = samples[index];
        if (cell == null) {
            cell = terrain.sample(originX + localX, originZ + localZ);
            samples[index] = cell;
        }
        return cell;
    }

    private static void fillStone(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z,
            int groundY, int targetY, int minY, int maxY, BlockState stone) {
        for (int y = Math.max(groundY + 1, minY); y <= Math.min(targetY, maxY); y++) {
            pos.set(x, y, z);
            chunk.setBlockState(pos, stone, false);
        }
    }
}
