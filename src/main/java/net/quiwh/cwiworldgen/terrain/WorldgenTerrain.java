package net.quiwh.cwiworldgen.terrain;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.quiwh.cwiworldgen.CwiWorldgen;

public final class WorldgenTerrain {
    private static final DeferredRegister<MapCodec<? extends DensityFunction>> TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, CwiWorldgen.ID);
    private static final DeferredRegister<MapCodec<? extends SurfaceRules.RuleSource>> SURFACES =
            DeferredRegister.create(Registries.MATERIAL_RULE, CwiWorldgen.ID);
    static {
        TYPES.register("reference_terrain", () -> ReferenceTerrainDensity.CODEC);
        TYPES.register("landscape", () -> ReferenceLandscapeDensity.CODEC);
        SURFACES.register("biome_rocks", () -> BiomeRockRule.CODEC);
        SURFACES.register("cliff_rocks", () -> CliffRockRule.CODEC);
    }
    private WorldgenTerrain() {}
    public static void register(IEventBus bus) {
        TYPES.register(bus);
        SURFACES.register(bus);
    }
}
