package net.quiwh.cwiworldgen.feature;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.quiwh.cwiworldgen.CwiWorldgen;

/** Compound deposit generation alongside the imported feature codecs. */
public final class IndustrialFeatures {
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, CwiWorldgen.ID);
    static {
        FEATURES.register("industrial_deposit", () -> new IndustrialDepositFeature(IndustrialDepositConfiguration.CODEC));
        FEATURES.register("layered_deposit", () -> new LayeredDepositFeature(LayeredDepositConfiguration.CODEC));
    }
    private IndustrialFeatures() {}
    public static void register(IEventBus bus) { FEATURES.register(bus); }
}
