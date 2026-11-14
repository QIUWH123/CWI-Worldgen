package net.quiwh.cwiworldgen.terrain.reference.biome;

import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noises;

public interface BiomeParameter {
	float min();
	
	float max();
	
	default float mid() {
		return (this.min() + this.max()) / 2.0F;
	}
	
	default Noise source() {
		return Noises.constant(this.mid());
	}
}
