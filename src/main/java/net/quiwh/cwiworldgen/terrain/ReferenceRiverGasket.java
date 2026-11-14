package net.quiwh.cwiworldgen.terrain;

import java.util.BitSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

/** Source river gasket adapted to an immutable hydrology halo and chunk-owned air writes. */
public final class ReferenceRiverGasket {
    private static final int MAX_RADIUS = 7;
    private static final int HALO_WIDTH = 16 + MAX_RADIUS * 2;

    private ReferenceRiverGasket() {}

    /** No terrain marker means no writes, including vanilla Nether and End routers. */
    public static int place(ChunkAccess chunk, RandomState state, long seed) {
        ReferenceTerrain terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        return terrain == null ? 0 : seal(chunk, seed, new TerrainColumns(chunk, terrain));
    }

    /** A source interval reproduces pre-carver river water; an empty interval represents dry land. */
    public record RiverColumn(int waterY, int sourceMinY, int sourceMaxY) {
        public static RiverColumn dry(int waterY) { return new RiverColumn(waterY, 1, 0); }
    }

    @FunctionalInterface
    public interface ColumnSampler {
        RiverColumn sample(int blockX, int blockZ);
    }

    /**
     * Reads owned source water and a fixed terrain-only halo. Neighbor chunks may not have reached
     * surface generation during CARVERS, so reading their mutable block states would be order dependent.
     */
    public static int seal(ChunkAccess chunk, long seed, ColumnSampler columns) {
        int originX = chunk.getPos().getMinBlockX();
        int originZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;
        BitSet writes = new BitSet(256 * chunk.getHeight());
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        RiverColumn[] halo = new RiverColumn[HALO_WIDTH * HALO_WIDTH];
        for (int z = -MAX_RADIUS; z < 16 + MAX_RADIUS; z++) {
            for (int x = -MAX_RADIUS; x < 16 + MAX_RADIUS; x++) {
                halo[index(x, z)] = columns.sample(originX + x, originZ + z);
            }
        }

        for (int sourceZ = -MAX_RADIUS; sourceZ < 16 + MAX_RADIUS; sourceZ++) {
            for (int sourceX = -MAX_RADIUS; sourceX < 16 + MAX_RADIUS; sourceX++) {
                RiverColumn source = halo[index(sourceX, sourceZ)];
                int bottom = Math.max(minY, source.sourceMinY());
                int top = Math.min(maxY, source.sourceMaxY());
                boolean ownedSource = owned(sourceX, sourceZ);
                for (int y = bottom; y <= top; y++) {
                    if (ownedSource) {
                        position.set(originX + sourceX, y, originZ + sourceZ);
                        BlockState state = chunk.getBlockState(position);
                        if (!state.is(Blocks.WATER) || !state.getFluidState().isSource()) continue;
                        markAir(chunk, writes, position.setY(y - 1), sourceX, sourceZ, y - 1, minY, maxY);
                    }
                    int radius = radiusFor(seed, originX + sourceX, y, originZ + sourceZ);
                    for (int targetZ = Math.max(0, sourceZ - radius); targetZ <= Math.min(15, sourceZ + radius); targetZ++) {
                        for (int targetX = Math.max(0, sourceX - radius); targetX <= Math.min(15, sourceX + radius); targetX++) {
                            int dx = targetX - sourceX, dz = targetZ - sourceZ;
                            if (dx * dx + dz * dz > radius * radius
                                    || y > halo[index(targetX, targetZ)].waterY()) continue;
                            below.set(originX + targetX, y - 1, originZ + targetZ);
                            if (chunk.getBlockState(below).is(Blocks.WATER)) continue;
                            position.set(originX + targetX, y, originZ + targetZ);
                            markAir(chunk, writes, position, targetX, targetZ, y, minY, maxY);
                        }
                    }
                }
            }
        }

        // Defer all writes so candidate discovery cannot consume this pass's own repairs as terrain.
        for (int bit = writes.nextSetBit(0); bit >= 0; bit = writes.nextSetBit(bit + 1)) {
            int y = minY + bit / 256;
            int local = bit & 255;
            position.set(originX + (local & 15), y, originZ + (local >> 4));
            chunk.setBlockState(position, y < 0 ? Blocks.DEEPSLATE.defaultBlockState()
                    : Blocks.STONE.defaultBlockState(), false);
        }
        return writes.cardinality();
    }

    /** Source random radius 3..7, keyed per water block to avoid traversal/chunk-order dependence. */
    public static int radiusFor(long seed, int x, int y, int z) {
        return 3 + RandomSource.create(seed ^ Mth.getSeed(x, y, z) ^ 0x1B8735935F356495L).nextInt(5);
    }

    private static void markAir(ChunkAccess chunk, BitSet writes, BlockPos pos,
            int localX, int localZ, int y, int minY, int maxY) {
        if (y >= minY && y <= maxY && chunk.getBlockState(pos).isAir()) {
            writes.set((y - minY) * 256 + (localZ << 4) + localX);
        }
    }

    private static boolean owned(int localX, int localZ) {
        return localX >= 0 && localX < 16 && localZ >= 0 && localZ < 16;
    }

    private static int index(int localX, int localZ) {
        return (localZ + MAX_RADIUS) * HALO_WIDTH + localX + MAX_RADIUS;
    }

    /** Pins the same immutable projection used by pre-carver water and retaining banks. */
    private static final class TerrainColumns implements ColumnSampler {
        private final ReferenceRiverColumn.Reader columns;

        private TerrainColumns(ChunkAccess chunk, ReferenceTerrain terrain) {
            columns = new ReferenceRiverColumn.Reader(terrain,
                    chunk.getPos().getMinBlockX() - MAX_RADIUS,
                    chunk.getPos().getMinBlockZ() - MAX_RADIUS, HALO_WIDTH, HALO_WIDTH);
        }

        @Override public RiverColumn sample(int blockX, int blockZ) {
            ReferenceRiverColumn column = columns.sample(blockX, blockZ);
            return new RiverColumn(column.waterTableY(), column.sourceMinY(), column.sourceMaxY());
        }
    }
}
