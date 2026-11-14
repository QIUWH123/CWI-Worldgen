package net.quiwh.cwiworldgen.terrain.reference.data.preset;

import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.ClimateSettings;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.Preset;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.WorldSettings;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;

public class PresetClimateNoise {
	public static final ResourceKey<Noise> BIOME_EDGE_SHAPE = createKey("biome_edge_shape");
	
	public static void bootstrap(Preset preset, BootstrapContext<Noise> ctx) {
		WorldSettings worldSettings = preset.world();
		WorldSettings.Properties properties = worldSettings.properties;
		
		ClimateSettings climateSettings = preset.climate();
		ClimateSettings.BiomeNoise biomeEdgeShape = climateSettings.biomeEdgeShape;
		
		ctx.register(BIOME_EDGE_SHAPE, biomeEdgeShape.build(0));
	}

	private static ResourceKey<Noise> createKey(String name) {
		return PresetNoiseData.createKey("climate/" + name);
	}
}
