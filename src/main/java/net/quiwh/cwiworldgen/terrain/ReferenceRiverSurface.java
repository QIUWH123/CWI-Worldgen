package net.quiwh.cwiworldgen.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

/**
 * Places the original elevated river water and retaining banks before CWI surface rules run.
 * Neighbor terrain is sampled without writing to neighboring chunks.
 */
public final class ReferenceRiverSurface {
    private ReferenceRiverSurface() {}

    public static void place(ChunkAccess chunk, BiomeManager biomeManager, ReferenceTerrain terrain) {
        int originX = chunk.getPos().getMinBlockX();
        int originZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;
        var columns = new ReferenceRiverColumn.Reader(terrain, originX, originZ, 16, 16);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState flowingWater = water.setValue(BlockStateProperties.LEVEL, 1);
        BlockState ice = Blocks.ICE.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int globalX = originX + localX;
                int globalZ = originZ + localZ;
                ReferenceRiverColumn column = columns.sample(globalX, globalZ);
                if (column.hasWater()) {
                    int waterY = column.waterY();
                    int scaledY = column.placementFloorY();
                    int lowestNeighborWaterY = column.lowestNeighborWaterY();
                    boolean transitionColumn = column.transition() != ReferenceRiverColumn.Transition.NONE;
                    boolean multiBlockDrop = column.transition() == ReferenceRiverColumn.Transition.DROP;

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
                            if (topBlock && (terrain.generationVersion() < 12 || !terrain.pondColumn(globalX, globalZ).wet())) {
                                chunk.markPosForPostprocessing(pos);
                            }
                        }
                    }
                } else if (!column.waterCell() && column.retainingTopY() > column.groundY()) {
                    fillStone(chunk, pos, globalX, globalZ, column.groundY(),
                            column.retainingTopY(), minY, maxY, stone);
                }
            }
        }
    }

    private static void fillStone(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z,
            int groundY, int targetY, int minY, int maxY, BlockState stone) {
        for (int y = Math.max(groundY + 1, minY); y <= Math.min(targetY, maxY); y++) {
            pos.set(x, y, z);
            chunk.setBlockState(pos, stone, false);
        }
    }
}
