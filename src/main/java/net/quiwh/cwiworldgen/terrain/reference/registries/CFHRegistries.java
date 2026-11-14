package net.quiwh.cwiworldgen.terrain.reference.registries;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.domain.Domain;
import net.quiwh.cwiworldgen.terrain.reference.noise.function.CurveFunction;
public final class CFHRegistries {
 public static final ResourceKey<Registry<MapCodec<? extends Noise>>> NOISE_TYPE = key("noise_type");
 public static final ResourceKey<Registry<MapCodec<? extends Domain>>> DOMAIN_TYPE = key("domain_type");
 public static final ResourceKey<Registry<MapCodec<? extends CurveFunction>>> CURVE_FUNCTION_TYPE = key("curve_function_type");
 public static final ResourceKey<Registry<Noise>> NOISE = key("noise");
 private static <T> ResourceKey<Registry<T>> key(String path) {
  return ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "reference/" + path));
 }
}
