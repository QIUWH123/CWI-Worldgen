package net.quiwh.cwiworldgen.structure;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Structure codecs used by CWI wasteland ruins. */
public final class WorldgenStructures {
    private static final DeferredRegister<StructureType<?>> TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, "cwi_worldgen");
    public static final java.util.function.Supplier<StructureType<TerrainCheckedJigsawStructure>> TERRAIN_CHECKED =
            TYPES.register("terrain_checked_jigsaw", () -> () -> TerrainCheckedJigsawStructure.CODEC);

    private WorldgenStructures() {}

    public static void register(IEventBus bus) {
        TYPES.register(bus);
    }
}
