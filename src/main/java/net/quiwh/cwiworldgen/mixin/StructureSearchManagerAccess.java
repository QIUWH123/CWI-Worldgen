package net.quiwh.cwiworldgen.mixin;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Native presence-cache owner; accessed only on the server thread. */
@Mixin(StructureManager.class)
public interface StructureSearchManagerAccess {
    @Accessor("structureCheck")
    StructureCheck cwiWorldgen$structureCheck();
}
