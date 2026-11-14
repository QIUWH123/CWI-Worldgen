package net.quiwh.cwiworldgen.hydrology.current;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Bounded footprint reads use already-loaded chunks and already-prepared exact Y intervals. */
public final class BoatCurrentSurface {
    public static final int MAX_SURFACE_RISE = 16;
    private BoatCurrentSurface() {}
    public static double target(Boat boat) {
        Level level = boat.level();
        var box = boat.getBoundingBox();
        int minX = Mth.floor(box.minX + .001), maxX = Mth.floor(box.maxX - .001);
        int minZ = Mth.floor(box.minZ + .001), maxZ = Mth.floor(box.maxZ - .001);
        int minY = Math.max(level.getMinBuildHeight(), Mth.floor(box.minY) - 1);
        int hullTop = Math.min(level.getMaxBuildHeight() - 2, Mth.ceil(box.maxY) + 1);
        if ((maxX - minX + 1) * (maxZ - minZ + 1) > 16 || hullTop - minY > 6) return Double.NaN;
        double[] surfaces = new double[16];
        int count = 0;
        double lowest = Double.POSITIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            var chunk = RuntimeCurrentSampler.loadedChunk(level, x >> 4, z >> 4);
            if (chunk == null) return Double.NaN;
            CurrentChunk field;
            if (level instanceof ServerLevel server) field = ServerCurrentFields.fieldIfLoaded(server, x >> 4, z >> 4);
            else {
                if (!level.dimension().location().toString().equals(RuntimeCurrentSampler.CLIENT_FIELDS.dimension())) return Double.NaN;
                field = RuntimeCurrentSampler.CLIENT_FIELDS.get(chunk.getPos().toLong());
            }
            if (field == null) continue;
            // Begin beside the hull, then follow connected water upward. A top-down
            // scan could select a separate pond above an air gap. The fixed rise
            // budget also permits recovery when the hull is deeply submerged.
            for (int y = minY; y <= Math.min(hullTop,minY+2); y++) {
                var column = field.column(x & 15, y, z & 15);
                if (!column.plannedWater()) continue;
                BlockPos position = new BlockPos(x, y, z);
                var state = chunk.getBlockState(position);
                if (!state.is(Blocks.WATER)) continue;
                int limit = Math.min(level.getMaxBuildHeight()-2,y+MAX_SURFACE_RISE);
                while (y < limit && field.column(x & 15,y+1,z & 15).plannedWater()
                        && chunk.getBlockState(position.above()).is(Blocks.WATER)) {
                    position = position.above(); y++;
                    state = chunk.getBlockState(position);
                }
                if (chunk.getBlockState(position.above()).is(Blocks.WATER)) break;
                // Above is known non-water: own height is the actual exposed fluid surface,
                // and cannot ask a neighboring chunk through FluidState#getHeight.
                double surface = y + state.getFluidState().getOwnHeight();
                surfaces[count++] = surface;
                lowest = Math.min(lowest, surface);
                break;
            }
        }
        if (count == 0) return Double.NaN;
        double supported = lowest;
        for (int index = 0; index < count; index++)
            if (surfaces[index] <= lowest + 1.25) supported = Math.max(supported, surfaces[index]);
        return supported - boat.getBbHeight() + .1;
    }
}
