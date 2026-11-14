package net.quiwh.cwiworldgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Follow real world creation through datapack-dimension precedence. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeWorldSelectionTest {
    @BeforeBatch(batch = "defaultBatch")
    public static void enableNaturalStructuresInDisposableGameTestWorld(ServerLevel level) throws ReflectiveOperationException {
        var server = level.getServer();
        if (!(server instanceof GameTestServer)) {
            throw new IllegalStateException("Structure verification setup is restricted to GameTestServer");
        }
        // Vanilla GameTestServer hardcodes WorldOptions(0L, false, false), which
        // makes ChunkStatusTasks skip natural structure starts in every test
        // dimension. There is no public setter; change only this disposable
        // test server's immutable options before any test requests new chunks.
        var worldData = server.getWorldData();
        var options = PrimaryLevelData.class.getDeclaredField("worldOptions");
        options.setAccessible(true);
        var enabledOptions = worldData.worldGenOptions().withStructures(true);
        options.set(worldData, enabledOptions);
        // Each ServerLevel's StructureManager captured the original immutable
        // options during construction. Keep that decoration gate consistent.
        var managerOptions = StructureManager.class.getDeclaredField("worldOptions");
        managerOptions.setAccessible(true);
        for (var world : server.getAllLevels()) {
            managerOptions.set(world.structureManager(), enabledOptions);
            if (!world.structureManager().shouldGenerateStructures()) {
                throw new IllegalStateException("Test world's structure manager still disables natural structures");
            }
        }
        if (!worldData.worldGenOptions().generateStructures() || worldData.worldGenOptions().seed() != 0L) {
            throw new IllegalStateException("Natural structure fixture must use enabled structures and deterministic seed zero");
        }
        System.out.println("CWI_NATIVE_WORLD_OPTIONS=seed=0 generate_structures=true");
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 1200)
    public static void normalPresetSurvivesDimensionBake(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        // The live LEVEL_STEM registry has already been baked, so reload the
        // datapack-only layer exactly as WorldLoader does before world creation.
        var dimensions = RegistryDataLoader.load(server.getResourceManager(),
                server.registries().getAccessForLoading(RegistryLayer.DIMENSIONS),
                RegistryDataLoader.DIMENSION_REGISTRIES).registryOrThrow(Registries.LEVEL_STEM);
        var presets = helper.getLevel().registryAccess().registryOrThrow(Registries.WORLD_PRESET);
        var structures = helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (var presetId : new ResourceLocation[]{ResourceLocation.withDefaultNamespace("normal"),
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "wasteland")}) {
            var preset = presets.getOptional(presetId).orElseThrow();
            var expected = preset.overworld().orElseThrow().generator().getBiomeSource().possibleBiomes();
            var generator = preset.createWorldDimensions().bake(dimensions).dimensions()
                    .getOptional(LevelStem.OVERWORLD).orElseThrow().generator();
            var baked = generator.getBiomeSource().possibleBiomes();
            helper.assertTrue(expected.size() == 10, presetId + " must select all ten wasteland biomes");
            helper.assertTrue(baked.equals(expected), "Datapack dimension overrides selected " + presetId + ": expected "
                    + expected.stream().map(b -> b.unwrapKey().orElseThrow().location().toString()).sorted().toList()
                    + ", actual " + baked.stream().map(b -> b.unwrapKey().orElseThrow().location().toString()).sorted().toList());
            helper.assertTrue(baked.stream().allMatch(b -> b.unwrapKey().orElseThrow().location().getNamespace().equals("cwi")),
                    "Baked world must use the canonical CWI biome IDs");
            for (String region : new String[]{"forsaken_land", "smoldering_barren"}) {
                for (String size : new String[]{"sml", "mid", "lrg"}) {
                    var id = ResourceLocation.fromNamespaceAndPath("cwi", region + "/" + size);
                    var structure = structures.getOptional(id).orElseThrow();
                    helper.assertTrue(baked.stream().anyMatch(structure.biomes()::contains),
                            "Structure " + id + " has no eligible biome in baked " + presetId);
                }
            }
            System.out.println("CWI_WORLD_CREATION_BAKE=" + presetId + " biomes=" + baked.size()
                    + " eligible_structure_families=6");
        }
        helper.succeed();
    }

    private NativeWorldSelectionTest() {}
}
