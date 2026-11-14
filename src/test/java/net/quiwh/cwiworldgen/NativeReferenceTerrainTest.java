package net.quiwh.cwiworldgen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource;
import net.quiwh.cwiworldgen.terrain.ReferenceTerrainDensity;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;

/** Bounded native checks of the supplied terrain math, seed ownership and generated materials. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeReferenceTerrainTest {
    private static final int[][] POINTS = {{0, 0}, {3, 7}, {17, 31}, {63, 64}, {90, 109}, {127, 127}};
    private static final ResourceLocation DEPLETED = ResourceLocation.parse("kubejs:depleted_dirt");
    private static final ResourceLocation ASHEN = ResourceLocation.parse("kubejs:ashen_depleted_dirt");

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void heightOffsetMatchesOriginalFloatQuantization(GameTestHelper helper) {
        float[] heights = {0F, 1F / 256F, Math.nextDown(.25F), .25F, .5F, 1F, -1F / 256F};
        double[] expected = {-.9959375858306885, -.9881250858306885, -.5037500858306885,
                -.4959375858306885, .0040624141693115234, 1.0040624141693115, -1.0037500858306885};
        for (int index = 0; index < heights.length; index++) {
            double actual = ReferenceTerrainDensity.heightOffset(heights[index]);
            helper.assertTrue(Double.doubleToLongBits(actual) == Double.doubleToLongBits(expected[index]),
                    "Original ClampToNearestUnit/OFFSET changed at height " + heights[index] + ": " + actual);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void sourceHeightsAreStableAcrossSeedsCacheAndQueryOrder(GameTestHelper helper) throws Exception {
        JsonObject report = new JsonObject();
        JsonArray samples = new JsonArray();
        // All points fit in one tile. Three contexts therefore generate only three tiles.
        try (var first = new ReferenceTerrain(0L); var other = new ReferenceTerrain(42L);
                var repeated = new ReferenceTerrain(0L); var highBits = new ReferenceTerrain(1L << 32)) {
            Cell[] raw = new Cell[POINTS.length];
            Cell[] accurate = new Cell[POINTS.length];
            for (int index = 0; index < POINTS.length; index++) {
                int x = POINTS[index][0], z = POINTS[index][1];
                raw[index] = first.sampleRaw(x, z);
                other.sampleRaw(x, z);
                sameCell(helper, raw[index], first.sampleRaw(x, z), "raw cross-seed query " + index);
                sameCell(helper, raw[index], highBits.sampleRaw(x, z), "original low-32-bit seed " + index);
                accurate[index] = first.sample(x, z);
            }

            boolean changedSeed = false;
            for (int index = POINTS.length - 1; index >= 0; index--) {
                int x = POINTS[index][0], z = POINTS[index][1];
                Cell foreign = other.sample(x, z);
                changedSeed |= Float.floatToIntBits(foreign.height) != Float.floatToIntBits(accurate[index].height);
                sameCell(helper, accurate[index], repeated.sample(x, z), "reversed cold-context query " + index);
                sameCell(helper, accurate[index], first.sample(x, z), "warm query after another world " + index);
                sameCell(helper, raw[index], first.sampleRaw(x, z), "raw sampling after tile generation " + index);
                JsonObject sample = cellJson(accurate[index], x, z);
                sample.addProperty("raw_height", raw[index].height);
                sample.addProperty("other_seed_height", foreign.height);
                samples.add(sample);
            }
            helper.assertTrue(changedSeed, "The original terrain engine reused seed 0 heights for seed 42");
            Cell detached = first.sample(0, 0);
            detached.height = -999F;
            detached.gradient = -999F;
            sameCell(helper, accurate[0], first.sample(0, 0), "mutating a returned sample changed its cached tile");
        }
        report.addProperty("complete_tiles_generated", 3);
        report.addProperty("minecraft_chunks_requested", 0);
        report.add("samples", samples);
        writeReport("reference-terrain-determinism.json", report);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void terrainEngineBindsOnlyToSelectedNoiseRouters(GameTestHelper helper) {
        var lookup = helper.getLevel().registryAccess().asGetterLookup();
        RandomState overworld = RandomState.create(lookup, settingsKey("overworld"), 42L);
        RandomState nether = RandomState.create(lookup, settingsKey("nether"), 42L);
        RandomState end = RandomState.create(lookup, settingsKey("end"), 42L);
        helper.assertTrue((Object) overworld instanceof TerrainRandomState,
                "RandomState terrain accessor mixin is missing");
        ReferenceTerrain terrain = ((TerrainRandomState) (Object) overworld).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null, "Selected Overworld router did not bind its original terrain engine");
        helper.assertTrue(((TerrainRandomState) (Object) nether).cwiWorldgen$terrain() == null,
                "Nether created an Overworld terrain engine");
        helper.assertTrue(((TerrainRandomState) (Object) end).cwiWorldgen$terrain() == null,
                "End created an Overworld terrain engine");
        helper.assertTrue(runtimeTerrain(helper, wasteland(helper)) != null,
                "The actual wasteland dimension did not receive its terrain engine");
        terrain.close();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void chunkBiomeSamplerPreservesBoundedCaveDistrictSeed(GameTestHelper helper) throws Exception {
        ServerLevel level = wasteland(helper);
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var source = (BoundedCaveBiomeSource) generator.getBiomeSource();
        RandomState state = level.getChunkSource().randomState();
        Climate.Sampler raw = state.sampler();
        BlockPos interior = findCaveInterior(helper, source, raw);
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        var chunk = new SamplerNoiseChunk(state, settings, interior.getX() >> 4, interior.getZ() >> 4);
        Climate.Sampler accurate = chunk.climate(state, settings);

        var fingerprint = BoundedCaveBiomeSource.class.getDeclaredMethod("fingerprint", Climate.Sampler.class);
        fingerprint.setAccessible(true);
        long rawFingerprint = (long) fingerprint.invoke(source, raw);
        long accurateFingerprint = (long) fingerprint.invoke(source, accurate);
        helper.assertTrue(rawFingerprint == accurateFingerprint,
                "NoiseChunk's filtered terrain changed the bounded-cave district seed");

        int compared = 0, bounded = 0;
        int originX = interior.getX() & ~15, originZ = interior.getZ() & ~15;
        for (int x = originX; x < originX + 16; x += 4) {
            for (int z = originZ; z < originZ + 16; z += 4) {
                var context = new DensityFunction.SinglePointContext(x, -32, z);
                // Stay well below both terrain surfaces: this isolates district identity
                // from intentional raw-versus-eroded differences at the surface gate.
                if (raw.depth().compute(context) <= .5 || accurate.depth().compute(context) <= .5) continue;
                String before = biome(source, raw, x, -32, z);
                String after = biome(source, accurate, x, -32, z);
                helper.assertTrue(before.equals(after), "NoiseChunk changed underground district at " + x + "," + z
                        + " from " + before + " to " + after);
                compared++;
                if (isSpecialCave(before)) bounded++;
            }
        }
        helper.assertTrue(compared > 0 && bounded > 0,
                "Sampler regression did not compare actual bounded regions underground");
        JsonObject report = new JsonObject();
        report.addProperty("raw_sampler_fingerprint", Long.toUnsignedString(rawFingerprint));
        report.addProperty("chunk_sampler_fingerprint", Long.toUnsignedString(accurateFingerprint));
        report.addProperty("common_underground_quarts_compared", compared);
        report.addProperty("bounded_region_quarts_compared", bounded);
        report.addProperty("chunk_x", interior.getX() >> 4);
        report.addProperty("chunk_z", interior.getZ() >> 4);
        writeReport("reference-terrain-cave-samplers.json", report);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void generatedSurfaceKeepsGentleSoilAndExposesSteepNativeRock(GameTestHelper helper) throws Exception {
        ServerLevel level = wasteland(helper);
        ReferenceTerrain terrain = runtimeTerrain(helper, level);
        long started = System.nanoTime();
        var shortlisted = shortlistTiles(terrain);
        JsonArray tileReports = new JsonArray();
        List<ChunkCandidate> candidates = new ArrayList<>();
        // Raw queries choose the three tiles; only these tiles get the full erosion pass.
        for (TileCandidate selected : shortlisted.subList(0, 3)) {
            var tile = terrain.getTile(selected.x, selected.z);
            int steepTotal = 0, gentleTotal = 0;
            for (int chunkX = selected.x * 8; chunkX < selected.x * 8 + 8; chunkX++) {
                for (int chunkZ = selected.z * 8; chunkZ < selected.z * 8 + 8; chunkZ++) {
                    int steep = 0, gentle = 0;
                    for (int x = chunkX * 16; x < chunkX * 16 + 16; x++) {
                        for (int z = chunkZ * 16; z < chunkZ * 16 + 16; z++) {
                            Cell cell = tile.lookup(x, z);
                            if (!dry(cell)) continue;
                            if (cell.gradient > .75F) steep++;
                            if (cell.gradient < .25F && cell.height < .9F) gentle++;
                        }
                    }
                    steepTotal += steep;
                    gentleTotal += gentle;
                    candidates.add(new ChunkCandidate(chunkX, chunkZ, steep, gentle));
                }
            }
            JsonObject tileReport = new JsonObject();
            tileReport.addProperty("tile_x", selected.x);
            tileReport.addProperty("tile_z", selected.z);
            tileReport.addProperty("raw_shortlist_score", selected.score);
            tileReport.addProperty("filtered_steep_columns", steepTotal);
            tileReport.addProperty("filtered_gentle_columns", gentleTotal);
            tileReports.add(tileReport);
        }
        var steep = candidates.stream().filter(c -> c.steep > 0)
                .sorted(Comparator.comparingInt(ChunkCandidate::steep).reversed()).toList();
        var gentle = candidates.stream().filter(c -> c.gentle > 0)
                .sorted(Comparator.comparingInt(ChunkCandidate::gentle).reversed()).toList();
        helper.assertTrue(!steep.isEmpty() && !gentle.isEmpty(),
                "Three shortlisted original terrain tiles lacked steep land or gentle benches");
        var requested = new LinkedHashMap<Long, ChunkCandidate>();
        for (int index = 0; index < 6; index++) {
            if (index < steep.size()) addChunk(requested, steep.get(index));
            if (index < gentle.size()) addChunk(requested, gentle.get(index));
        }

        int steepRock = 0, gentleSoil = 0, deepSoil = 0, deepAir = 0, chunks = 0;
        JsonArray chunkReports = new JsonArray();
        for (ChunkCandidate candidate : requested.values()) {
            ChunkAccess chunk = level.getChunk(candidate.x, candidate.z, ChunkStatus.FEATURES, true);
            helper.assertTrue(chunk != null, "Fresh source-terrain chunk generation failed");
            SurfaceMetrics metrics = inspect(chunk, terrain);
            steepRock += metrics.steepRock;
            gentleSoil += metrics.gentleSoil;
            deepSoil += metrics.deepSoil;
            deepAir += metrics.deepAir;
            chunks++;
            JsonObject chunkReport = metrics.json();
            chunkReport.addProperty("chunk_x", candidate.x);
            chunkReport.addProperty("chunk_z", candidate.z);
            chunkReports.add(chunkReport);
            if (steepRock > 0 && gentleSoil > 0 && deepAir > 0) break;
        }
        JsonObject report = new JsonObject();
        report.addProperty("world_seed", level.getSeed());
        report.addProperty("shortlisted_complete_tiles", 3);
        report.addProperty("requested_feature_chunks", chunks);
        report.addProperty("maximum_feature_chunk_requests", 12);
        report.addProperty("steep_surface_native_rock_columns", steepRock);
        report.addProperty("gentle_surface_depleted_soil_columns", gentleSoil);
        report.addProperty("depleted_soil_blocks_at_or_below_y32", deepSoil);
        report.addProperty("deep_cave_air_blocks_examined", deepAir);
        report.addProperty("elapsed_ms", (System.nanoTime() - started) / 1_000_000L);
        report.add("tile_shortlist", tileReports);
        report.add("chunks", chunkReports);
        writeReport("reference-terrain-generated-materials.json", report);
        helper.assertTrue(steepRock > 0, "No native rock was exposed on original steep terrain in " + chunks + " chunks");
        helper.assertTrue(gentleSoil > 0, "Original gentle benches lost their depleted soil in " + chunks + " chunks");
        helper.assertTrue(deepAir > 0, "Surface-material check did not inspect any deep caves");
        helper.assertTrue(deepSoil == 0, "Surface soil leaked into deep caves: " + deepSoil + " blocks at/below Y32");
        helper.succeed();
    }

    private static List<TileCandidate> shortlistTiles(ReferenceTerrain terrain) {
        List<TileCandidate> result = new ArrayList<>();
        for (int tileX = -12; tileX <= 12; tileX += 4) {
            for (int tileZ = -12; tileZ <= 12; tileZ += 4) {
                float score = 0F;
                for (int dx : new int[] {16, 64, 112}) for (int dz : new int[] {16, 64, 112}) {
                    int x = (tileX << 7) + dx, z = (tileZ << 7) + dz;
                    Cell center = terrain.sampleRaw(x, z);
                    if (!dry(center)) continue;
                    score = Math.max(score, Math.abs(center.height - terrain.sampleRaw(x + 4, z).height)
                            + Math.abs(center.height - terrain.sampleRaw(x, z + 4).height));
                }
                result.add(new TileCandidate(tileX, tileZ, score));
            }
        }
        result.sort(Comparator.comparingDouble(TileCandidate::score).reversed());
        return result;
    }

    private static SurfaceMetrics inspect(ChunkAccess chunk, ReferenceTerrain terrain) {
        int steepRock = 0, gentleSoil = 0, deepSoil = 0, deepAir = 0;
        JsonArray examples = new JsonArray();
        var pos = new BlockPos.MutableBlockPos();
        for (int localX = 0; localX < 16; localX++) for (int localZ = 0; localZ < 16; localZ++) {
            int x = chunk.getPos().getBlockX(localX), z = chunk.getPos().getBlockZ(localZ);
            Cell cell = terrain.sample(x, z);
            int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
            BlockState state = chunk.getBlockState(pos.set(x, surfaceY, z));
            boolean actualSteepRock = dry(cell) && cell.gradient > .75F && state.is(BlockTags.BASE_STONE_OVERWORLD);
            boolean actualGentleSoil = dry(cell) && cell.gradient < .25F && cell.height < .9F && soil(state);
            if (actualSteepRock) steepRock++;
            if (actualGentleSoil) gentleSoil++;
            if ((actualSteepRock || actualGentleSoil) && examples.size() < 12) {
                JsonObject example = cellJson(cell, x, z);
                example.addProperty("surface_y", surfaceY);
                example.addProperty("surface_block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                examples.add(example);
            }
            for (int y = chunk.getMinBuildHeight(); y <= Math.min(32, surfaceY - 24); y++) {
                BlockState deep = chunk.getBlockState(pos.set(x, y, z));
                if (soil(deep)) deepSoil++;
                if (deep.isAir()) deepAir++;
            }
        }
        return new SurfaceMetrics(steepRock, gentleSoil, deepSoil, deepAir, examples);
    }

    private static boolean dry(Cell cell) {
        return cell.height > .27F && !cell.terrain.isSubmerged() && !cell.terrain.isWetland()
                && cell.terrain != TerrainType.VOLCANO_PIPE;
    }

    private static boolean soil(BlockState state) {
        ResourceLocation name = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return name.equals(DEPLETED) || name.equals(ASHEN);
    }

    private static void addChunk(LinkedHashMap<Long, ChunkCandidate> result, ChunkCandidate chunk) {
        result.putIfAbsent(((long) chunk.x << 32) ^ (chunk.z & 0xFFFFFFFFL), chunk);
    }

    private static BlockPos findCaveInterior(GameTestHelper helper, BiomeSource source, Climate.Sampler sampler) {
        for (int x = -896; x <= 896; x += 16) for (int z = -896; z <= 896; z += 16) {
            var point = new DensityFunction.SinglePointContext(x, -32, z);
            if (sampler.depth().compute(point) <= .6) continue;
            String name = biome(source, sampler, x, -32, z);
            if (!isSpecialCave(name)) continue;
            if (name.equals(biome(source, sampler, x - 8, -32, z))
                    && name.equals(biome(source, sampler, x + 8, -32, z))
                    && name.equals(biome(source, sampler, x, -32, z - 8))
                    && name.equals(biome(source, sampler, x, -32, z + 8))) return new BlockPos(x, -32, z);
        }
        helper.assertTrue(false, "Could not find a deep bounded-cave interior in the sparse sampler search");
        throw new AssertionError("Unreachable");
    }

    private static String biome(BiomeSource source, Climate.Sampler sampler, int x, int y, int z) {
        return source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), sampler)
                .unwrapKey().orElseThrow().location().toString();
    }

    private static boolean isSpecialCave(String name) {
        return name.endsWith("_caves") && !name.equals("cwi:typical_caves");
    }

    private static ServerLevel wasteland(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "wasteland_test")));
        helper.assertTrue(level != null, "Missing fresh wasteland test dimension");
        return level;
    }

    private static ReferenceTerrain runtimeTerrain(GameTestHelper helper, ServerLevel level) {
        RandomState state = level.getChunkSource().randomState();
        helper.assertTrue((Object) state instanceof TerrainRandomState, "Runtime terrain accessor is missing");
        ReferenceTerrain terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null, "Runtime wasteland router has no original terrain context");
        return terrain;
    }

    private static ResourceKey<NoiseGeneratorSettings> settingsKey(String name) {
        return ResourceKey.create(Registries.NOISE_SETTINGS, ResourceLocation.withDefaultNamespace(name));
    }

    private static void sameCell(GameTestHelper helper, Cell expected, Cell actual, String context) {
        int[] before = bits(expected), after = bits(actual);
        helper.assertTrue(Arrays.equals(before, after) && expected.terrain.getName().equals(actual.terrain.getName()),
                "Original terrain cell changed during " + context + ": " + Arrays.toString(before)
                        + " versus " + Arrays.toString(after));
    }

    private static int[] bits(Cell cell) {
        return new int[] {Float.floatToIntBits(cell.height), Float.floatToIntBits(cell.gradient),
                Float.floatToIntBits(cell.heightErosion), Float.floatToIntBits(cell.sediment),
                Float.floatToIntBits(cell.erosion), Float.floatToIntBits(cell.weirdness),
                Float.floatToIntBits(cell.waterTable), Float.floatToIntBits(cell.riverWaterLevel)};
    }

    private static JsonObject cellJson(Cell cell, int x, int z) {
        JsonObject result = new JsonObject();
        result.addProperty("x", x);
        result.addProperty("z", z);
        result.addProperty("height", cell.height);
        result.addProperty("height_bits", Float.floatToIntBits(cell.height));
        result.addProperty("original_gradient", cell.gradient);
        result.addProperty("terrain", cell.terrain.getName());
        return result;
    }

    private static void writeReport(String filename, JsonObject report) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        while (project.getParent() != null && !Files.isRegularFile(project.resolve("build.gradle"))) project = project.getParent();
        Path folder = project.resolve("build/terrain-preview");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(filename), new GsonBuilder().setPrettyPrinting().create().toJson(report)
                + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("CWI_REFERENCE_TERRAIN_REPORT=" + folder.resolve(filename));
        System.out.println("CWI_REFERENCE_TERRAIN_METRICS=" + report);
    }

    private record TileCandidate(int x, int z, float score) {}
    private record ChunkCandidate(int x, int z, int steep, int gentle) {}
    private record SurfaceMetrics(int steepRock, int gentleSoil, int deepSoil, int deepAir, JsonArray examples) {
        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("steep_native_rock_columns", steepRock);
            result.addProperty("gentle_depleted_soil_columns", gentleSoil);
            result.addProperty("deep_depleted_soil_blocks", deepSoil);
            result.addProperty("deep_air_blocks", deepAir);
            result.add("column_examples", examples);
            return result;
        }
    }

    /** Uses the same full-chunk constructor/wrapping path as Minecraft's BIOMES stage. */
    private static final class SamplerNoiseChunk extends NoiseChunk {
        private static final DensityFunctions.BeardifierOrMarker NO_STRUCTURES = new DensityFunctions.BeardifierOrMarker() {
            @Override public double compute(DensityFunction.FunctionContext context) { return 0; }
            @Override public double minValue() { return 0; }
            @Override public double maxValue() { return 0; }
        };

        SamplerNoiseChunk(RandomState state, NoiseGeneratorSettings settings, int chunkX, int chunkZ) {
            super(16 / settings.noiseSettings().getCellWidth(), state, chunkX * 16, chunkZ * 16,
                    settings.noiseSettings(), NO_STRUCTURES, settings,
                    (x, y, z) -> new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid()), Blender.empty());
        }

        Climate.Sampler climate(RandomState state, NoiseGeneratorSettings settings) {
            return cachedClimateSampler(state.router(), settings.spawnTarget());
        }
    }
}
