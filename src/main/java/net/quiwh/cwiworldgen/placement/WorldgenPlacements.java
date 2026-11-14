package net.quiwh.cwiworldgen.placement;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Seeded geographic filters shared by CWI surface features. */
public final class WorldgenPlacements {
    private static final DeferredRegister<PlacementModifierType<?>> TYPES =
            DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE, "cwi_worldgen");
    public static final java.util.function.Supplier<PlacementModifierType<HazardDistrictFilter>> HAZARD_DISTRICT =
            TYPES.register("hazard_district", () -> () -> HazardDistrictFilter.CODEC);
    public static final java.util.function.Supplier<PlacementModifierType<SceneryDistrictFilter>> SCENERY_DISTRICT =
            TYPES.register("scenery_district", () -> () -> SceneryDistrictFilter.CODEC);

    private WorldgenPlacements() {}

    public static void register(IEventBus bus) {
        TYPES.register(bus);
    }
}
