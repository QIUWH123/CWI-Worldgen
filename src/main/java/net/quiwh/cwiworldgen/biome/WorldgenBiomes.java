package net.quiwh.cwiworldgen.biome;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Native biome source codecs used by the CWI world preset. */
public final class WorldgenBiomes {
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, "cwi_worldgen");
    static { SOURCES.register("bounded_caves", () -> BoundedCaveBiomeSource.CODEC); }

    private WorldgenBiomes() {}

    public static void register(IEventBus bus) { SOURCES.register(bus); }
}
