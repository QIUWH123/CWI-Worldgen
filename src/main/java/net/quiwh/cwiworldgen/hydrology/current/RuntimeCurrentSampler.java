package net.quiwh.cwiworldgen.hydrology.current;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.quiwh.cwiworldgen.config.CurrentConfig;

/** Every consumer checks the actual loaded water; plans never grant current to stacked ponds or machines. */
public final class RuntimeCurrentSampler {
    private RuntimeCurrentSampler() {}
    public static final CurrentFieldCache CLIENT_FIELDS = new CurrentFieldCache(4096, 8192);

    public static CurrentColumn sample(Entity entity) {
        if (entity.isRemoved() || entity.isPassenger()) return CurrentColumn.EMPTY;
        Level level = entity.level();
        int x = entity.getBlockX(), z = entity.getBlockZ();
        int y = Mth.floor(entity.getBoundingBox().minY + .05);
        ChunkAccess chunk = loadedChunk(level, x >> 4, z >> 4);
        if (chunk == null) return CurrentColumn.EMPTY;
        CurrentChunk field;
        if (level instanceof ServerLevel server) {
            field = ServerCurrentFields.fieldIfLoaded(server, x >> 4, z >> 4);
        } else {
            if (!level.dimension().location().toString().equals(CLIENT_FIELDS.dimension())) return CurrentColumn.EMPTY;
            field = CLIENT_FIELDS.get(chunk.getPos().toLong());
        }
        return sample(field, chunk, new BlockPos(x, y, z));
    }

    /** A supplied chunk is already loaded; this method has no Level/chunk-provider dependency. */
    public static CurrentColumn sample(CurrentChunk field, ChunkAccess chunk, BlockPos position) {
        if (field == null || chunk == null || field.chunkPos() != chunk.getPos().toLong()
                || (position.getX() >> 4) != chunk.getPos().x || (position.getZ() >> 4) != chunk.getPos().z) return CurrentColumn.EMPTY;
        CurrentColumn column = field.column(position.getX() & 15, position.getY(), position.getZ() & 15);
        if (!column.contains(position.getY()) || !chunk.getBlockState(position).is(Blocks.WATER)) return CurrentColumn.EMPTY;
        return column;
    }

    public static ChunkAccess loadedChunk(Level level, int x, int z) {
        if (level instanceof ServerLevel server) return server.getChunkSource().getChunkNow(x, z);
        return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
    }
}
