package net.quiwh.cwiworldgen.mixin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read only after native initialization, and only on the server thread. */
@Mixin(ChunkGeneratorStructureState.class)
public interface StructureSearchStateAccess {
    @Accessor("ringPositions")
    Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>> cwiWorldgen$ringPositions();
}
