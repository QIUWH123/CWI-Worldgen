package net.quiwh.cwiworldgen.terrain.reference.platform;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
/** Direct registration is confined to private reference-codec tables. */
public final class RegistryUtil {
 public static <T> void register(Registry<T> registry,String name,T value) {
  Registry.register(registry,ResourceLocation.fromNamespaceAndPath("cwi_worldgen",name),value);
 }
}
