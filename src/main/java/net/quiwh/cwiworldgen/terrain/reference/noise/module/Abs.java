package net.quiwh.cwiworldgen.terrain.reference.noise.module;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record Abs(Noise input) implements Noise {
	public static final MapCodec<Abs> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Noise.HOLDER_HELPER_CODEC.fieldOf("input").forGetter(Abs::input)
	).apply(instance, Abs::new));
	
	@Override
	public float compute(float x, float z, int seed) {
		return Math.abs(this.input.compute(x, z, seed));
	}

	@Override
	public float minValue() {
		float min = this.input.minValue();
		float max = this.input.maxValue();
		return min <= 0.0F && max >= 0.0F ? 0.0F : Math.min(Math.abs(min), Math.abs(max));
	}

	@Override
	public float maxValue() {
		return Math.max(Math.abs(this.input.minValue()), Math.abs(this.input.maxValue()));
	}

	@Override
	public Noise mapAll(Visitor visitor) {
		return visitor.apply(new Abs(this.input.mapAll(visitor)));
	}

	@Override
	public MapCodec<Abs> codec() {
		return CODEC;
	}
}
