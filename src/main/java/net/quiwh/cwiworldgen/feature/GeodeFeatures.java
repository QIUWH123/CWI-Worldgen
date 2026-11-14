package net.quiwh.cwiworldgen.feature;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.feature.geode.CrystalSpikeFeature;
import net.quiwh.cwiworldgen.feature.geode.EchoCrystalSpikeFeature;
import net.quiwh.cwiworldgen.feature.geode.GeodeFeature;
import net.quiwh.cwiworldgen.feature.geode.config.GeodeCrystalSpikeConfig;

/** Native feature identities used by the migrated original CWI data. */
public final class GeodeFeatures {
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, CwiWorldgen.ID);

    static {
        FEATURES.register("crystal_spike", () -> new CrystalSpikeFeature(GeodeCrystalSpikeConfig.CODEC));
        FEATURES.register("echo_crystal_spike", () -> new EchoCrystalSpikeFeature(GeodeCrystalSpikeConfig.CODEC));
        FEATURES.register("geode", () -> new GeodeFeature(GeodeConfiguration.CODEC));
    }

    private GeodeFeatures() {}

    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
    }
}
