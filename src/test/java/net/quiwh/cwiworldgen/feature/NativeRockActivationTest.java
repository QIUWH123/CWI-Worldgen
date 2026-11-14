package net.quiwh.cwiworldgen.feature;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeRockActivationTest {
    @GameTest(template = "empty")
    public static void loadedBiomesRunRockOutcropsAfterErosionAndBeforeLakesOresAndStructures(GameTestHelper helper) {
        var biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        for (String name : List.of("forsaken_land", "smoldering_barren", "deep_ocean", "typical_caves", "sandy_caves",
                "glowshroom_caves", "dripstone_caves", "spider_caves", "molten_caves", "echo_caves")) {
            var biome = biomes.get(ResourceLocation.fromNamespaceAndPath("cwi", name));
            helper.assertTrue(biome != null, "Missing canonical biome " + name);
            var stages = biome.getGenerationSettings().features();
            List<String> raw = new ArrayList<>();
            stages.get(GenerationStep.Decoration.RAW_GENERATION.ordinal()).forEach(holder ->
                    raw.add(holder.unwrapKey().orElseThrow().location().toString()));
            helper.assertTrue(raw.stream().filter("cwi:terrain_surface_exposure"::equals).count() == 1,
                    "Erosion must run exactly once in " + name + ": " + raw);
            boolean surface = name.equals("forsaken_land") || name.equals("smoldering_barren");
            if (surface) {
                helper.assertTrue(raw.stream().filter("cwi:terrain_rock_outcrops"::equals).count() == 1,
                        "Rock outcrops are not active exactly once in loaded surface biome " + name);
                helper.assertTrue(raw.indexOf("cwi:terrain_surface_exposure") < raw.indexOf("cwi:terrain_rock_outcrops"),
                        "Rock outcrops run before erosion in " + name);
            } else {
                helper.assertTrue(!raw.contains("cwi:terrain_rock_outcrops"), "Rock outcrops leaked into " + name);
            }
            stages.forEach(stage -> stage.forEach(holder -> {
                String id = holder.unwrapKey().orElseThrow().location().toString();
                helper.assertTrue(!id.equals("cwi:natures/pebbles") && !id.startsWith("cwi:outcrops/"),
                        "Removed repetitive decoration is active in " + name + ": " + id);
                if (name.equals("forsaken_land")) helper.assertTrue(!id.equals("cwi:structures/ashstone_place"),
                        "Legacy ashstone blob still runs alongside the small native ash outcrop");
                if (name.equals("smoldering_barren")) helper.assertTrue(
                        !id.equals("cwi:structures/dark_ash_stone_large")
                                && !id.equals("cwi:structures/dark_ash_stone_small"),
                        "Legacy dark ash blob still runs alongside the shallow connected outcrop");
            }));
            if (name.equals("deep_ocean") || name.equals("typical_caves")) helper.assertTrue(
                    stages.stream().flatMap(stage -> stage.stream()).anyMatch(holder -> holder.unwrapKey()
                            .orElseThrow().location().toString().equals("cwi:structures/ashstone_place")),
                    "Surface ash changes removed the legacy cave/ocean feature in " + name);
        }
        helper.succeed();
    }
}
