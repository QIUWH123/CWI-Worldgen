package net.quiwh.cwiworldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.structure.TerrainCheckedJigsawStructure;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverSurface;
import net.quiwh.cwiworldgen.terrain.ReferenceTerrainDensity;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverColumn;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.ContinentalHydrology;

/** Exercises the wasteland structure codec, real template pieces and safe weathering. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeWastelandStructureTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void terrainSurveyRejectsSteepAndFloodedFootprints(GameTestHelper helper) {
        var bounds = new BoundingBox(0, 0, 0, 19, 20, 23);
        helper.assertTrue(TerrainCheckedJigsawStructure.acceptsTerrain(bounds,
                (x, z) -> 70, (x, z) -> 70, 5, 1), "Flat dry terrain rejected");
        helper.assertTrue(!TerrainCheckedJigsawStructure.acceptsTerrain(bounds,
                (x, z) -> x == 19 && z == 23 ? 82 : 70,
                (x, z) -> x == 19 && z == 23 ? 82 : 70, 5, 1), "Far corner slope was missed");
        helper.assertTrue(!TerrainCheckedJigsawStructure.acceptsTerrain(bounds,
                (x, z) -> 70, (x, z) -> 66, 5, 1), "Submerged footprint accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void terrainSurveySeesElevatedRiverWaterBeforeSurfacePlacement(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "wasteland_test")));
        helper.assertTrue(level != null, "Missing production-equivalent wasteland test world");
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var state = level.getChunkSource().randomState();
        var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null, "Wasteland noise router has no reference terrain");
        Levels levels = terrain.levels();
        BlockPos river = null;
        int filteredCandidates = 0;
        // Sparse raw screening is cheap. At most four interior tiles are then filtered.
        for (int z = -4064; z <= 4064 && river == null && filteredCandidates < 4; z += 64) {
            for (int x = -4064; x <= 4064 && river == null && filteredCandidates < 4; x += 64) {
                Cell raw = terrain.sampleRaw(x, z);
                if (!hasDeepElevatedWater(raw, levels)) continue;
                int rawWaterY = elevatedWaterHeight(raw, levels);
                boolean waterfall = false;
                for (int direction = 0; direction < 4; direction++) {
                    Cell neighbor = terrain.sampleRaw(x + (direction == 2 ? 1 : direction == 3 ? -1 : 0),
                            z + (direction == 0 ? -1 : direction == 1 ? 1 : 0));
                    if ((neighbor.terrain.isRiver() || neighbor.terrain.isLake() || neighbor.terrain.isWetland())
                            && neighbor.riverWaterLevel > 0 && rawWaterY - elevatedWaterHeight(neighbor, levels) > 1)
                        waterfall = true;
                }
                if (waterfall) continue;
                Cell cell = terrain.sample(x, z);
                filteredCandidates++;
                if (hasDeepElevatedWater(cell, levels)) river = new BlockPos(x, 0, z);
            }
        }
        helper.assertTrue(river != null, "No elevated river fixture found within four filtered tiles");
        int x = river.getX(), z = river.getZ();
        var column = ReferenceTerrainDensity.withAccurateTerrain(() -> generator.getBaseColumn(x, z, level, state));
        var chunk = new ProtoChunk(new ChunkPos(river), UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        var pos = new BlockPos.MutableBlockPos();
        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
            chunk.setBlockState(pos.set(x, y, z), column.getBlock(y), false);
        }
        int beforeSurface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        int beforeFloor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
        helper.assertTrue(beforeSurface - beforeFloor <= 1, "Fixture already had deep vanilla sea-level water");
        var warmBiome = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(
                ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("cwi", "forsaken_land")));
        ReferenceRiverSurface.place(chunk, new BiomeManager((qx, qy, qz) -> warmBiome, level.getSeed()), terrain);
        Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(
                Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
        int afterSurface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        int afterFloor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
        helper.assertTrue(afterSurface - afterFloor > 1 && afterSurface > generator.getSeaLevel(),
                "Original river overlay did not create elevated deep water at the fixture");
        var generation = new Structure.GenerationContext(level.registryAccess(), generator, generator.getBiomeSource(),
                state, level.getStructureManager(), level.getSeed(), new ChunkPos(river), level, ignored -> true);
        helper.assertTrue(!TerrainCheckedJigsawStructure.acceptsTerrain(generation,
                new BoundingBox(x, level.getMinBuildHeight(), z, x, level.getMaxBuildHeight() - 1, z), 5, 1),
                "Structure survey accepted a column flooded by later reference river placement");
        System.out.println("CWI_REFERENCE_RIVER_SURVEY=" + river + " water=" + afterSurface + " floor=" + afterFloor);
        helper.succeed();
    }

    private static boolean hasDeepElevatedWater(Cell cell, Levels levels) {
        if (!(cell.terrain.isRiver() || cell.terrain.isLake() || cell.terrain.isWetland()) || cell.riverWaterLevel <= 0)
            return false;
        int groundY = levels.scale(cell.height);
        int waterY = elevatedWaterHeight(cell, levels);
        return groundY >= levels.waterLevel && waterY > levels.waterLevel && waterY - groundY > 2;
    }

    private static int elevatedWaterHeight(Cell cell, Levels levels) {
        return ReferenceRiverColumn.waterTableY(cell, levels);
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 1200)
    public static void wastelandRuinsGenerateOnFlatTerrain(GameTestHelper helper) {
        var level = helper.getLevel();
        var generator = level.getChunkSource().getGenerator();
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        int index = 0;
        for (String region : new String[]{"forsaken_land", "smoldering_barren"}) {
            for (String size : new String[]{"sml", "mid", "lrg"}) {
                var id = ResourceLocation.fromNamespaceAndPath("cwi", region + "/" + size);
                var structure = registry.getOptional(id).orElseThrow(() -> new AssertionError("Missing " + id));
                helper.assertTrue(structure instanceof TerrainCheckedJigsawStructure, "Wasteland ruins lost terrain validation");
                var start = structure.generate(level.registryAccess(), generator, generator.getBiomeSource(),
                        level.getChunkSource().randomState(), level.getStructureManager(), level.getSeed(),
                        new ChunkPos(900 + index++ * 8, 900), 0, level, ignored -> true);
                helper.assertTrue(start.isValid() && !start.getPieces().isEmpty(), "Flat terrain rejected " + id);
                helper.assertTrue(start.getPieces().stream().allMatch(piece -> piece instanceof PoolElementStructurePiece),
                        "Wasteland ruin has no actual pool templates: " + id);
                System.out.println("CWI_WASTELAND_JIGSAW=" + id + " pieces=" + start.getPieces().size());
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void smallRuinsAreNaturallyLocatableInWasteland(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "wasteland_test")));
        helper.assertTrue(level != null, "Missing production-equivalent wasteland test world");
        helper.assertTrue(level.getServer().getWorldData().worldGenOptions().generateStructures(),
                "Natural structure verification requires enabled world structure generation");
        var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var ruins = HolderSet.direct(java.util.List.of("forsaken_land", "smoldering_barren").stream()
                .map(region -> structures.getHolderOrThrow(ResourceKey.create(Registries.STRUCTURE,
                        ResourceLocation.fromNamespaceAndPath("cwi", region + "/sml")))).toList());
        var located = level.getChunkSource().getGenerator().findNearestMapStructure(
                level, ruins, BlockPos.ZERO, 12, false);
        helper.assertTrue(located != null, "No natural small CWI ruin could be located around the test world's origin");
        var pos = located.getFirst();
        var chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS, true);
        var start = chunk.getStartForStructure(located.getSecond().value());
        helper.assertTrue(start != null && start.isValid() && !start.getPieces().isEmpty(),
                "Located CWI ruin has no naturally generated start");
        System.out.println("CWI_WASTELAND_NATURAL_RUIN=" + located.getSecond().unwrapKey().orElseThrow().location()
                + " position=" + pos + " pieces=" + start.getPieces().size());
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void weatheringPreservesMachinesAndBlockEntityData(GameTestHelper helper) {
        var level = helper.getLevel();
        var registry = level.registryAccess().registryOrThrow(Registries.PROCESSOR_LIST);
        int weathered = 0;
        for (String region : new String[]{"forsaken_land", "smoldering_barren"}) {
            var id = ResourceLocation.fromNamespaceAndPath("cwi", region + "_weathering");
            var processors = registry.getOptional(id).orElseThrow(() -> new AssertionError("Missing " + id)).list();
            for (int i = 0; i < 256; i++) {
                BlockPos pos = helper.absolutePos(new BlockPos(i % 16, 5, i / 16));
                var source = new StructureTemplate.StructureBlockInfo(pos, Blocks.STONE_BRICKS.defaultBlockState(), null);
                var result = source;
                for (var processor : processors) result = processor.processBlock(level, BlockPos.ZERO, BlockPos.ZERO,
                        source, result, new StructurePlaceSettings());
                helper.assertTrue(result != null && !result.state().isAir(), "Weathering deleted masonry");
                if (!result.state().equals(source.state())) weathered++;
                var nbt = new CompoundTag();
                nbt.putString("CustomName", "Protected apparatus");
                var machine = new StructureTemplate.StructureBlockInfo(pos, Blocks.CHEST.defaultBlockState(), nbt);
                var protectedResult = machine;
                for (var processor : processors) protectedResult = processor.processBlock(level, BlockPos.ZERO,
                        BlockPos.ZERO, machine, protectedResult, new StructurePlaceSettings());
                helper.assertTrue(protectedResult != null && protectedResult.state().equals(machine.state())
                        && nbt.equals(protectedResult.nbt()), "Weathering changed a block entity");
            }
        }
        helper.assertTrue(weathered > 0 && weathered < 200, "Weathering was absent or excessive: " + weathered);
        helper.succeed();
    }

    private NativeWastelandStructureTest() {}
}
