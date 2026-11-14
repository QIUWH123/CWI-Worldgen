package net.quiwh.cwiworldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Block-state codecs silently accept unknown properties; check source data explicitly. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeResourceTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void productionOverworldUsesWastelandBiomes(GameTestHelper helper) throws Exception {
        var resource = helper.getLevel().getServer().getResourceManager().getResourceOrThrow(
                ResourceLocation.withDefaultNamespace("dimension/overworld.json"));
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            LevelStem stem = LevelStem.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE,
                    helper.getLevel().registryAccess()), JsonParser.parseReader(reader)).getOrThrow();
            helper.assertTrue(stem.generator() instanceof NoiseBasedChunkGenerator, "Production Overworld lost its noise generator");
            var biomes = stem.generator().getBiomeSource().possibleBiomes();
            helper.assertTrue(biomes.size() == 10, "Production climate source should contain all ten reachable biomes");
            helper.assertTrue(biomes.stream().allMatch(biome -> biome.unwrapKey().orElseThrow().location().getNamespace().equals("cwi")),
                    "Production Overworld includes unexpected biomes");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void worldgenStatesHaveValidProperties(GameTestHelper helper) throws Exception {
        var resources = helper.getLevel().getServer().getResourceManager().listResources("worldgen", name ->
                name.getPath().endsWith(".json") && (name.getNamespace().equals("cwi")
                        || name.getNamespace().equals("cwi_worldgen")
                        || name.equals(ResourceLocation.withDefaultNamespace("worldgen/noise_settings/overworld.json"))));
        Set<String> failures = new HashSet<>();
        int states = 0;
        for (var entry : resources.entrySet()) {
            try (var reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                states += checkStates(JsonParser.parseReader(reader), entry.getKey().toString(), failures);
            }
        }
        helper.assertTrue(states > 100, "Expected substantial worldgen block-state coverage, got " + states);
        helper.assertTrue(failures.isEmpty(), "Invalid worldgen states: " + failures);
        System.out.println("CWI_WORLDGEN_VALIDATED_STATES=" + states);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void preparedTagScriptsRestoreVanillaAndPackWaterTags(GameTestHelper helper) {
        for (var fluid : new net.minecraft.world.level.material.Fluid[]{
                net.minecraft.world.level.material.Fluids.WATER, net.minecraft.world.level.material.Fluids.FLOWING_WATER}) {
            helper.assertTrue(fluid.defaultFluidState().is(net.minecraft.tags.FluidTags.WATER),
                    "Pack tag script failed to restore vanilla water: " + BuiltInRegistries.FLUID.getKey(fluid));
            var common = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.FLUID,
                    ResourceLocation.parse("c:water"));
            helper.assertTrue(fluid.defaultFluidState().is(common), "Pack tag script failed to restore common water");
        }
        var distilled = BuiltInRegistries.FLUID.get(ResourceLocation.parse("kubejs:distilled_water"));
        var packWater = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.FLUID,
                ResourceLocation.parse("cwi:water"));
        helper.assertTrue(distilled != net.minecraft.world.level.material.Fluids.EMPTY
                && distilled.defaultFluidState().is(packWater), "Pack helper closure did not finish its fluid tags");
        System.out.println("CWI_PREPARED_WATER_TAGS=vanilla,common,pack");
        helper.succeed();
    }

    private static int checkStates(JsonElement element, String resource, Set<String> failures) {
        int count = 0;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) count += checkStates(child, resource, failures);
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("Name")) {
                count++;
                ResourceLocation id = ResourceLocation.parse(object.get("Name").getAsString());
                if (!BuiltInRegistries.BLOCK.containsKey(id)) {
                    failures.add(resource + " missing " + id);
                } else if (object.has("Properties")) {
                    var block = BuiltInRegistries.BLOCK.get(id);
                    for (var entry : object.getAsJsonObject("Properties").entrySet()) {
                        var property = block.getStateDefinition().getProperty(entry.getKey());
                        if (property == null || property.getValue(entry.getValue().getAsString()).isEmpty()) {
                            failures.add(resource + " " + id + " invalid " + entry.getKey() + "=" + entry.getValue());
                        }
                    }
                }
            }
            for (JsonElement child : object.asMap().values()) count += checkStates(child, resource, failures);
        }
        return count;
    }
}
