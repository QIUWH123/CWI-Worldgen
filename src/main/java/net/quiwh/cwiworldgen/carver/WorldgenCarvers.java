package net.quiwh.cwiworldgen.carver;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Targeted shapes remain effective even when vanilla chooses carvers from biome Y=0. */
public final class WorldgenCarvers {
    private static final DeferredRegister<WorldCarver<?>> TYPES =
            DeferredRegister.create(Registries.CARVER, "cwi_worldgen");
    static {
        TYPES.register("biome_cave", BiomeRestrictedCaveCarver::new);
        TYPES.register("biome_canyon", BiomeRestrictedCanyonCarver::new);
    }
    private WorldgenCarvers() {}
    public static void register(IEventBus bus) { TYPES.register(bus); }
}
