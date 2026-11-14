package net.quiwh.cwiworldgen;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.quiwh.cwiworldgen.config.CurrentConfig;
import net.quiwh.cwiworldgen.hydrology.current.RiverCurrents;
import net.quiwh.cwiworldgen.hydrology.current.CurrentCommands;
import net.quiwh.cwiworldgen.block.WorldgenBlocks;
import net.quiwh.cwiworldgen.feature.GeodeFeatures;
import net.quiwh.cwiworldgen.feature.WorldgenFeatures;
import net.quiwh.cwiworldgen.feature.IndustrialFeatures;
import net.quiwh.cwiworldgen.structure.WorldgenStructures;
import net.quiwh.cwiworldgen.placement.WorldgenPlacements;
import net.quiwh.cwiworldgen.carver.WorldgenCarvers;
import net.quiwh.cwiworldgen.biome.WorldgenBiomes;
import net.quiwh.cwiworldgen.terrain.WorldgenTerrain;
import org.slf4j.Logger;

/** Independent home for the original CWI world-generation rules. */
@Mod(CwiWorldgen.ID)
public final class CwiWorldgen {
    public static final String ID = "cwi_worldgen";
    public static final String NAME = "CWI Worldgen";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CwiWorldgen(IEventBus modBus, ModContainer container) {
        CurrentConfig.register(container);
        RiverCurrents.register(modBus);
        CurrentCommands.register();
        net.quiwh.cwiworldgen.landscape.LandscapeCommands.register();
        WorldgenBlocks.register(modBus);
        WorldgenFeatures.register(modBus);
        GeodeFeatures.register(modBus);
        IndustrialFeatures.register(modBus);
        WorldgenStructures.register(modBus);
        WorldgenPlacements.register(modBus);
        WorldgenCarvers.register(modBus);
        WorldgenBiomes.register(modBus);
        WorldgenTerrain.register(modBus);
        LOGGER.info("{} loaded", NAME);
    }
}
