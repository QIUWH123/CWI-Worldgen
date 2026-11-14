package net.quiwh.cwiworldgen.terrain.reference.noise.module;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

record Multiply(Noise input1, Noise input2, boolean legacyBounds) implements Noise {
	public static final MapCodec<Multiply> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Noise.HOLDER_HELPER_CODEC.fieldOf("input1").forGetter(Multiply::input1),
		Noise.HOLDER_HELPER_CODEC.fieldOf("input2").forGetter(Multiply::input2),
		Codec.BOOL.optionalFieldOf("legacy_bounds", false).forGetter(Multiply::legacyBounds)
	).apply(instance, Multiply::new));

	Multiply(Noise input1, Noise input2) {
		this(input1, input2, false);
	}
	
	@Override
	public float compute(float x, float z, int seed) {
		float input1 = this.input1.compute(x, z, seed);
		return input1 != 0.0F ? input1 * this.input2.compute(x, z, seed) : 0.0F;
	}

	@Override
	public float minValue() {
		if (this.legacyBounds) return this.input1.minValue() * this.input2.minValue();
		return Math.min(Math.min(this.input1.minValue() * this.input2.minValue(), this.input1.minValue() * this.input2.maxValue()),
				Math.min(this.input1.maxValue() * this.input2.minValue(), this.input1.maxValue() * this.input2.maxValue()));
	}

	@Override
	public float maxValue() {
		if (this.legacyBounds) return this.input1.maxValue() * this.input2.maxValue();
		return Math.max(Math.max(this.input1.minValue() * this.input2.minValue(), this.input1.minValue() * this.input2.maxValue()),
				Math.max(this.input1.maxValue() * this.input2.minValue(), this.input1.maxValue() * this.input2.maxValue()));
	}

	@Override
	public Noise mapAll(Visitor visitor) {
		return visitor.apply(new Multiply(this.input1.mapAll(visitor), this.input2.mapAll(visitor), this.legacyBounds));
	}

	@Override
	public MapCodec<Multiply> codec() {
		return CODEC;
	}
}
