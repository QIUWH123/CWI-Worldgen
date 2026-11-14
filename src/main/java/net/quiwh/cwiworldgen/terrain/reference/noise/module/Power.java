package net.quiwh.cwiworldgen.terrain.reference.noise.module;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.quiwh.cwiworldgen.terrain.reference.noise.NoiseUtil;

record Power(Noise input, float power) implements Noise {
	public static final MapCodec<Power> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Noise.HOLDER_HELPER_CODEC.fieldOf("input").forGetter(Power::input),
		Codec.FLOAT.fieldOf("power").forGetter(Power::power)
	).apply(instance, Power::new));
	
	@Override
	public float compute(float x, float z, int seed) {
		return NoiseUtil.pow(this.input.compute(x, z, seed), this.power);
	}

	@Override
	public float minValue() {
		return this.bound(true);
	}

	@Override
	public float maxValue() {
		return this.bound(false);
	}

	private float bound(boolean minimum) {
		float min = this.input.minValue();
		float max = this.input.maxValue();
		float lower = NoiseUtil.pow(min, this.power);
		float upper = NoiseUtil.pow(max, this.power);
		boolean spansZero = min <= 0.0F && max >= 0.0F;
		boolean evenInteger = this.power == Math.rint(this.power) && this.power % 2.0F == 0.0F;
		if (spansZero && this.power > 0.0F && evenInteger && minimum) {
			return 0.0F;
		}
		if (spansZero && this.power < 0.0F) {
			if (!minimum) {
				return Float.POSITIVE_INFINITY;
			}
			if (min < 0.0F && !evenInteger && this.power == Math.rint(this.power)) {
				return Float.NEGATIVE_INFINITY;
			}
		}
		return minimum ? Math.min(lower, upper) : Math.max(lower, upper);
	}

	@Override
	public Noise mapAll(Visitor visitor) {
		return visitor.apply(new Power(this.input.mapAll(visitor), this.power));
	}

	@Override
	public MapCodec<Power> codec() {
		return CODEC;
	}
}
