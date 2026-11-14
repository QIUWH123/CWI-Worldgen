package net.quiwh.cwiworldgen.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only cache views avoid the native presence method's synchronous storage scan. */
@Mixin(StructureCheck.class)
public interface StructureSearchPresenceAccess {
    @Accessor("loadedChunks")
    Long2ObjectMap<Object2IntMap<Structure>> cwiWorldgen$loadedStructures();
}
