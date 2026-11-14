package net.quiwh.cwiworldgen.terrain.reference.data.preset;

import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.Preset;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.WorldSettings;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noises;

public class PresetTerrainTypeNoise {
	public static final ResourceKey<Noise> GROUND = PresetNoiseData.createKey("terrain/ground");
	
	public static void bootstrap(Preset preset, BootstrapContext<Noise> ctx) {
		WorldSettings worldSettings = preset.world();
		WorldSettings.Properties properties = worldSettings.properties;
		float seaLevel = properties.seaLevel;
		int terrainScaler = properties.terrainScaler();

		ctx.register(GROUND, Noises.constant(seaLevel / (float)terrainScaler));
	}
}
