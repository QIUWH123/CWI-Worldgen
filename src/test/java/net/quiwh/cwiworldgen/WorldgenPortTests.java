package net.quiwh.cwiworldgen;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks actual loaded registries, DataFixer template loading, and new chunks. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class WorldgenPortTests {
    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("cwi", name);
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void biomesAndFeatureStages(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        String[] names = {"forsaken_land", "sandy_caves", "glowshroom_caves", "typical_caves", "deep_ocean",
                "dripstone_caves", "smoldering_barren", "spider_caves", "molten_caves", "echo_caves"};
        for (String name : names) {
            var biome = registry.getOptional(id(name)).orElseThrow(() -> new AssertionError("Missing biome " + name));
            helper.assertTrue(biome.getGenerationSettings().features().size() <= 11, "Invalid stage count in " + name);
        }
        var sandy = registry.get(id("sandy_caves")).getGenerationSettings().features();
        helper.assertTrue(sandy.get(8).stream().anyMatch(f -> f.is(ResourceKey.create(Registries.PLACED_FEATURE,
                ResourceLocation.withDefaultNamespace("spring_water")))), "Sandy springs not in fluid-springs stage");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 1200)
    public static void structurePalettesAndDataFixer(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        var resources = level.getServer().getResourceManager().listResources("structure", name ->
                name.getNamespace().equals("cwi") && name.getPath().endsWith(".nbt"));
        helper.assertTrue(resources.size() == 32, "Expected 32 preserved CWI templates, got " + resources.size());
        Set<String> failures = new HashSet<>();
        for (var entry : resources.entrySet()) {
            CompoundTag root;
            try (var stream = entry.getValue().open()) {
                root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
            }
            if (root.contains("palette")) checkPalette(root.getList("palette", Tag.TAG_COMPOUND), entry.getKey().toString(), failures);
            for (Tag palette : root.getList("palettes", Tag.TAG_LIST)) checkPalette((ListTag) palette, entry.getKey().toString(), failures);
            ResourceLocation template = ResourceLocation.fromNamespaceAndPath("cwi", entry.getKey().getPath()
                    .substring("structure/".length()).replaceFirst("\\.nbt$", ""));
            helper.assertTrue(level.getStructureManager().get(template).isPresent(), "Native DFU failed loading " + template);
        }
        helper.assertTrue(failures.isEmpty(), "Invalid template states: " + failures);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void everyBiomeGenerates(GameTestHelper helper) {
        String[] names = {"forsaken_land", "sandy_caves", "glowshroom_caves", "typical_caves", "deep_ocean",
                "dripstone_caves", "smoldering_barren", "spider_caves", "molten_caves", "echo_caves"};
        for (String name : names) {
            ServerLevel world = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "test_" + name)));
            helper.assertTrue(world != null, "Missing fixed-biome test dimension " + name);
            helper.assertTrue(world.getChunk(16, -16, ChunkStatus.FULL, true) != null, "Generation failed in " + name);
            System.out.println("CWI_WORLDGEN_BIOME_CHUNK=" + name);
        }
        helper.succeed();
    }

    private static void checkPalette(ListTag palette, String template, Set<String> failures) {
        for (Tag tag : palette) {
            CompoundTag state = (CompoundTag) tag;
            ResourceLocation name = ResourceLocation.parse(state.getString("Name"));
            if (!BuiltInRegistries.BLOCK.containsKey(name)) {
                failures.add(template + " missing " + name);
                continue;
            }
            var block = BuiltInRegistries.BLOCK.get(name);
            CompoundTag properties = state.getCompound("Properties");
            for (String key : properties.getAllKeys()) {
                var property = block.getStateDefinition().getProperty(key);
                if (property == null || property.getValue(properties.getString(key)).isEmpty())
                    failures.add(name + " invalid " + key + "=" + properties.getString(key));
            }
        }
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void freshWastelandChunks(GameTestHelper helper) {
        ServerLevel world = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "wasteland_test")));
        helper.assertTrue(world != null, "Isolated wasteland dimension was not loaded");
        helper.assertTrue(world.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator, "Expected noise terrain");
        Set<String> biomes = new HashSet<>();
        for (int[] sample : new int[][] {{0, 0}, {24, -16}, {-32, 20}}) {
            var chunk = world.getChunk(sample[0], sample[1], ChunkStatus.FULL, true);
            helper.assertTrue(chunk != null, "Fresh chunk generation failed");
            for (int y = -48; y <= 80; y += 32) {
                String biome = world.getBiome(new BlockPos(sample[0] * 16 + 8, y, sample[1] * 16 + 8))
                        .unwrapKey().orElseThrow().location().toString();
                helper.assertTrue(biome.startsWith("cwi:"), "Unexpected wasteland biome " + biome);
                biomes.add(biome);
            }
        }
        helper.assertTrue(!biomes.isEmpty(), "No biome samples");
        System.out.println("CWI_WORLDGEN_FRESH_CHUNKS=" + biomes);
        helper.succeed();
    }
}
