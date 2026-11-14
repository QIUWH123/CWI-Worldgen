package net.quiwh.cwiworldgen.terrain.reference.data.preset;
import net.minecraft.core.HolderGetter;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.quiwh.cwiworldgen.terrain.reference.registries.CFHRegistries;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noises;
/** Only the ground and climate-edge noise holders are required by the terrain engine. */
public final class PresetNoiseData {
 public static Noise getNoise(HolderGetter<Noise> lookup,ResourceKey<Noise> key) {
  return new Noises.HolderHolder(lookup.getOrThrow(key));
 }
 public static Noise registerAndWrap(BootstrapContext<Noise> context,ResourceKey<Noise> key,Noise noise) {
  return new Noises.HolderHolder(context.register(key,noise));
 }
 public static ResourceKey<Noise> createKey(String path) {
  return ResourceKey.create(CFHRegistries.NOISE,ResourceLocation.fromNamespaceAndPath("cwi_worldgen","reference/"+path));
 }
}
