package net.quiwh.cwiworldgen.terrain.reference.noise.function;

import java.util.function.Function;

import com.mojang.serialization.Codec;

import com.mojang.serialization.MapCodec;
import net.quiwh.cwiworldgen.terrain.reference.registries.CFHBuiltInRegistries;

public interface CurveFunction {
    public static final Codec<CurveFunction> CODEC = CFHBuiltInRegistries.CURVE_FUNCTION_TYPE.byNameCodec().dispatch(CurveFunction::codec, Function.identity());
	
	float apply(float f);
	
	MapCodec<? extends CurveFunction> codec();
}
