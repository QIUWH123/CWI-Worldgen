package net.quiwh.cwiworldgen.terrain.reference.registries;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Registry;
import net.minecraft.core.MappedRegistry;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.domain.Domain;
import net.quiwh.cwiworldgen.terrain.reference.noise.function.CurveFunction;
/** Private codec tables, deliberately absent from Minecraft's global registry graph. */
public final class CFHBuiltInRegistries {
 public static final Registry<MapCodec<? extends Noise>> NOISE_TYPE = new MappedRegistry<>(CFHRegistries.NOISE_TYPE,Lifecycle.stable());
 public static final Registry<MapCodec<? extends Domain>> DOMAIN_TYPE = new MappedRegistry<>(CFHRegistries.DOMAIN_TYPE,Lifecycle.stable());
 public static final Registry<MapCodec<? extends CurveFunction>> CURVE_FUNCTION_TYPE = new MappedRegistry<>(CFHRegistries.CURVE_FUNCTION_TYPE,Lifecycle.stable());
}
