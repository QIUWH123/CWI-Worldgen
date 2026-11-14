package net.quiwh.cwiworldgen.terrain.reference.noise.domain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.quiwh.cwiworldgen.terrain.reference.noise.NoiseUtil;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise.Visitor;

record DirectionWarp(Noise direction, Noise strength) implements Domain {
	public static final MapCodec<DirectionWarp> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Noise.HOLDER_HELPER_CODEC.fieldOf("direction").forGetter(DirectionWarp::direction),
		Noise.HOLDER_HELPER_CODEC.fieldOf("strength").forGetter(DirectionWarp::strength)
	).apply(instance, DirectionWarp::new));
	
	@Override
	public float getOffsetX(float x, float z, int seed) {
        float angle = this.direction.compute(x, z, seed) * 6.2831855F;
        return NoiseUtil.sin(angle) * this.strength.compute(x, z, seed);
	}

	@Override
	public float getOffsetZ(float x, float z, int seed) {
        float angle = this.direction.compute(x, z, seed) * 6.2831855F;
        return NoiseUtil.cos(angle) * this.strength.compute(x, z, seed);
	}

	@Override
	public Domain mapAll(Visitor visitor) {
		return new DirectionWarp(this.direction.mapAll(visitor), this.strength.mapAll(visitor));
	}

	@Override
	public MapCodec<DirectionWarp> codec() {
		return CODEC;
	}
}
