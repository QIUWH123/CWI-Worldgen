package net.quiwh.cwiworldgen.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Server-thread request entry point without the public method's managed blocking. */
@Mixin(ServerChunkCache.class)
public interface StructureSearchChunkAccess {
    @Invoker("getChunkFutureMainThread")
    CompletableFuture<ChunkResult<ChunkAccess>> cwiWorldgen$requestStructureChunk(
            int chunkX, int chunkZ, ChunkStatus status, boolean create);
}
