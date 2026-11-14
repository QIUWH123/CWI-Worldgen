package net.quiwh.cwiworldgen.terrain;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Loads Production Rules And Builds Real Noise Chunks Without Neighbor Generation. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeBiomeGeologyIntegrationTest {
    private static final Map<String, Expected> EXPECTED = expected();
    private static final Set<String> SOILS = Set.of("kubejs:depleted_dirt", "kubejs:ashen_depleted_dirt", "minecraft:gravel");
    private static final Set<String> ORDINARY = Set.of("typical_caves", "spider_caves", "deep_ocean");

    @GameTest(template = "empty")
    public static void loadedProductionRulePrecedesDeepslateAndPreservesCarverAndOreTags(GameTestHelper helper) {
        var generator = generator(level(helper));
        JsonObject encoded = encodedRule(generator);
        List<JsonObject> leaves = new ArrayList<>();
        leaves(encoded, leaves);
        int geology = -1;
        int cliffs = -1;
        int bedrock = -1;
        int lastSoil = -1;
        int deepslate = -1;
        int count = 0;
        for (int i = 0; i < leaves.size(); i++) {
            JsonObject leaf = leaves.get(i);
            String type = leaf.get("type").getAsString();
            helper.assertTrue(!type.equals("cwi_worldgen:reference_strata"), "Loaded production rule still uses global strata");
            if (type.equals("cwi_worldgen:biome_rocks")) { geology = i; count++; }
            if (type.equals("cwi_worldgen:cliff_rocks")) cliffs = i;
            if (leaf.has("result_state")) {
                String block = leaf.getAsJsonObject("result_state").get("Name").getAsString();
                if (block.equals("minecraft:bedrock")) bedrock = i;
                if (SOILS.contains(block)) lastSoil = i;
                if (block.equals("minecraft:deepslate")) deepslate = i;
            }
        }
        helper.assertTrue(count == 1 && bedrock >= 0 && lastSoil > bedrock && cliffs > lastSoil
                        && geology > cliffs && deepslate > geology,
                "Loaded surface precedence must be bedrock, soil, local cliffs, biome geology, fallback deepslate: " + leaves);
        BiomeRockRule rule = loadedRule(generator);
        helper.assertTrue(rule.profiles().size() == EXPECTED.size(), "Loaded geology omitted a production profile");
        for (var profile : rule.profiles()) {
            Expected expected = EXPECTED.get(profile.biome().getPath());
            helper.assertTrue(expected != null && profile.minY() == expected.minY && profile.maxY() == expected.maxY,
                    "Loaded production geology has unexpected biome or height bounds: " + profile);
            Set<String> rocks = new HashSet<>();
            for (var rock : profile.rocks()) {
                rocks.add(id(rock.state()));
                helper.assertTrue(rock.state().is(BlockTags.OVERWORLD_CARVER_REPLACEABLES),
                        "Native carvers cannot replace new host rock " + id(rock.state()));
                helper.assertTrue(rock.state().is(BlockTags.STONE_ORE_REPLACEABLES)
                                || rock.state().is(BlockTags.DEEPSLATE_ORE_REPLACEABLES),
                        "Native ore targets lost new host rock " + id(rock.state()));
            }
            helper.assertTrue(rocks.equals(expected.rocks), "Loaded production rock palette changed: " + profile);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void nativeProductionSurfaceBuildReachesEveryPaletteBelowZeroAndPreservesProtectedStates(GameTestHelper helper) throws Exception {
        ServerLevel level = level(helper);
        var generator = generator(level);
        var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null, "Production native rule has no bound reference terrain engine");
        var sampler = loadedRule(generator).sampler(terrain.seed());
        Map<String, List<ChunkPos>> candidates = candidates(helper, level, generator, sampler);
        Metrics metrics = new Metrics();
        Set<ChunkPos> built = new HashSet<>();
        for (var entry : candidates.entrySet()) {
            if (metrics.reached(entry.getKey())) continue;
            for (ChunkPos pos : entry.getValue()) {
                if (!built.add(pos)) continue;
                helper.assertTrue(built.size() <= 48, "Native geology sampling exceeded its 48-chunk bound");
                buildAndCheck(helper, level, generator, pos, metrics);
                if (metrics.reached(entry.getKey())) break;
            }
            helper.assertTrue(metrics.reached(entry.getKey()),
                    "Native production surface never produced required biome/material " + entry.getKey() + ": " + metrics.materials);
        }
        helper.assertTrue(metrics.bedrock > 0 && metrics.water > 0 && metrics.protectedStates > 100,
                "Native fixture omitted bedrock, water, or protected-state preservation");
        helper.assertTrue(metrics.soils.containsAll(Set.of("kubejs:depleted_dirt", "kubejs:ashen_depleted_dirt")),
                "Native surface build lost either authored land soil: " + metrics.soils);
        helper.assertTrue(metrics.ashenCaps > 0 && metrics.depletedBelowAshen > 0,
                "Native soil check omitted exposed ashen caps with depleted subsoil");
        helper.assertTrue(metrics.borderColumns > 0 && metrics.negativeChunks > 0,
                "Native samples omitted stored-biome borders or negative chunk coordinates");
        JsonObject report = metrics.json();
        report.addProperty("seed", level.getSeed());
        report.addProperty("chunks", built.size());
        report.addProperty("configured_carvers_ores_structures_and_decorators_run", false);
        writeReport(report);
        System.out.println("CWI_BIOME_GEOLOGY_NATIVE=" + report);
        helper.succeed();
    }

    private static Map<String, List<ChunkPos>> candidates(GameTestHelper helper, ServerLevel level,
            NoiseBasedChunkGenerator generator, BiomeRockRule.Sampler sampler) {
        Map<String, List<ChunkPos>> result = new LinkedHashMap<>();
        for (var entry : EXPECTED.entrySet()) for (String rock : entry.getValue().rocks) {
            result.put(entry.getKey() + "/" + rock, new ArrayList<>());
        }
        for (String biome : ORDINARY) result.put(biome, new ArrayList<>());
        var state = level.getChunkSource().randomState();
        int searched = 0;
        search: for (int radius = 0; radius <= 96; radius++) {
            for (int gz = -radius; gz <= radius; gz++) for (int gx = -radius; gx <= radius; gx++) {
                if (Math.abs(gx) != radius && Math.abs(gz) != radius) continue;
                int x = gx * 64 + 8;
                int z = gz * 64 + 8;
                searched++;
                for (int y : new int[] {-48, -32, -16, -8, 16, 32, 64, 96}) {
                    var biome = generator.getBiomeSource().getNoiseBiome(x >> 2, y >> 2, z >> 2, state.sampler())
                            .unwrapKey().orElseThrow().location();
                    String name = biome.getPath();
                    String target = name;
                    BlockState material = sampler.materialAt(biome, x, y, z);
                    if (material != null) {
                        if (name.endsWith("_caves") && y >= 0) continue;
                        target += "/" + id(material);
                    }
                    List<ChunkPos> list = result.get(target);
                    ChunkPos pos = new ChunkPos(x >> 4, z >> 4);
                    if (list != null && list.size() < 8 && !list.contains(pos)) list.add(pos);
                }
                if (result.values().stream().allMatch(list -> list.size() >= 4)) break search;
            }
        }
        for (var entry : result.entrySet()) helper.assertTrue(!entry.getValue().isEmpty(),
                "Seeded native biome search did not find " + entry.getKey() + " after " + searched + " columns");
        System.out.println("CWI_BIOME_GEOLOGY_SEARCH_COLUMNS=" + searched);
        return result;
    }

    private static void buildAndCheck(GameTestHelper helper, ServerLevel level, NoiseBasedChunkGenerator generator,
            ChunkPos pos, Metrics metrics) {
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var state = level.getChunkSource().randomState();
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, registry, null);
        complete(level, generator.createBiomes(state, Blender.empty(), level.structureManager(), chunk));
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        complete(level, generator.fillFromNoise(Blender.empty(), state, level.structureManager(), chunk));
        chunk.setPersistedStatus(ChunkStatus.NOISE);
        var region = new IsolatedRegion(level, chunk);
        var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        var cliffBands = new CliffBands(terrain.seed());
        ReferenceRiverSurface.place(chunk, region.getBiomeManager(), terrain);
        List<BlockState> sentinels = List.of(Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.BEDROCK.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(),
                BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:depleted_dirt")).defaultBlockState());
        for (int i = 0; i < sentinels.size(); i++) chunk.setBlockState(new BlockPos(pos.getBlockX(i), -32, pos.getBlockZ(1)), sentinels.get(i), false);
        int minY = chunk.getMinBuildHeight();
        int height = chunk.getHeight();
        BlockState[] before = new BlockState[height * 256];
        BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = minY; y < minY + height; y++) {
            before[index(x, y - minY, z, height)] = chunk.getBlockState(block.set(pos.getBlockX(x), y, pos.getBlockZ(z)));
        }
        generator.buildSurface(chunk, new WorldGenerationContext(generator, level), state,
                level.structureManager(), region.getBiomeManager(), registry, Blender.empty());
        if (pos.x < 0 || pos.z < 0) metrics.negativeChunks++;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int globalX = pos.getBlockX(x), globalZ = pos.getBlockZ(z);
            var exposure = CliffBands.exposureAt(globalX, globalZ,
                    (sampleX, sampleZ) -> terrain.levels().scale(terrain.sample(sampleX, sampleZ).height));
            Set<String> columnBiomes = new HashSet<>();
            for (int y = minY; y < minY + height; y++) {
                block.set(pos.getBlockX(x), y, pos.getBlockZ(z));
                BlockState old = before[index(x, y - minY, z, height)];
                BlockState actual = chunk.getBlockState(block);
                String rock = id(actual);
                String biome = chunk.getNoiseBiome(block.getX() >> 2, y >> 2, block.getZ() >> 2)
                        .unwrapKey().orElseThrow().location().getPath();
                columnBiomes.add(biome);
                if (!old.is(Blocks.STONE)) {
                    helper.assertTrue(old == actual, "Native surface replaced protected " + id(old) + " at " + block);
                    if (!old.isAir()) metrics.protectedStates++;
                    if (old.is(Blocks.WATER)) metrics.water++;
                    continue;
                }
                if (actual.is(Blocks.BEDROCK)) { metrics.bedrock++; continue; }
                if (SOILS.contains(rock)) {
                    metrics.soils.add(rock);
                    if (rock.equals("kubejs:ashen_depleted_dirt")) {
                        helper.assertTrue(chunk.getBlockState(block.above()).isAir(),
                                "Grass-like ashen dirt is buried or submerged at " + block);
                        String below = id(chunk.getBlockState(block.below()));
                        helper.assertTrue(!SOILS.contains(below) || below.equals("kubejs:depleted_dirt"),
                                "Soil below an ashen cap must be depleted dirt at " + block + ": " + below);
                        metrics.ashenCaps++;
                        if (below.equals("kubejs:depleted_dirt")) metrics.depletedBelowAshen++;
                    }
                    continue;
                }
                Expected expected = EXPECTED.get(biome);
                BlockState cliff = CliffRockRule.material(cliffBands.materialAt("cwi:" + biome,
                        block.getX(), y, block.getZ(), exposure));
                if (cliff != null) {
                    helper.assertTrue(actual == cliff, "Native cliff rule disagrees with local exposure at " + block + ": " + rock);
                    continue;
                }
                if (expected != null && y >= expected.minY && y <= expected.maxY) {
                    helper.assertTrue(expected.rocks.contains(rock),
                            "Native surface bypassed palette for stored " + biome + " at " + block + ": " + rock);
                    if (!biome.endsWith("_caves") || y < 0) metrics.materials.merge(biome + "/" + rock, 1L, Long::sum);
                } else {
                    helper.assertTrue(actual.is(Blocks.STONE) || actual.is(Blocks.DEEPSLATE),
                            "Special geology escaped stored biome/actual height at " + block + " biome=" + biome + ": " + rock);
                    if (ORDINARY.contains(biome) && y > minY + 1 && y <= 0) {
                        helper.assertTrue(actual.is(Blocks.DEEPSLATE), "Ordinary deep cave lost fallback deepslate at " + block);
                        metrics.materials.merge(biome, 1L, Long::sum);
                    } else if (biome.equals("deep_ocean") && y >= 8 && actual.is(Blocks.STONE)) {
                        metrics.materials.merge(biome, 1L, Long::sum);
                    }
                }
            }
            if (columnBiomes.size() >= 3) metrics.borderColumns++;
        }
        JsonObject sample = new JsonObject();
        sample.addProperty("chunk_x", pos.x);
        sample.addProperty("chunk_z", pos.z);
        metrics.samples.add(sample);
    }

    private static int index(int x, int y, int z, int height) { return (x * 16 + z) * height + y; }

    private static ServerLevel level(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        helper.assertTrue(level != null, "Missing production-equivalent wasteland dimension");
        return level;
    }

    private static NoiseBasedChunkGenerator generator(ServerLevel level) {
        return (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
    }

    private static JsonObject encodedRule(NoiseBasedChunkGenerator generator) {
        return SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE,
                generator.generatorSettings().value().surfaceRule()).getOrThrow().getAsJsonObject();
    }

    private static BiomeRockRule loadedRule(NoiseBasedChunkGenerator generator) {
        List<JsonObject> leaves = new ArrayList<>();
        leaves(encodedRule(generator), leaves);
        JsonObject encoded = leaves.stream().filter(leaf -> leaf.get("type").getAsString().equals("cwi_worldgen:biome_rocks"))
                .findFirst().orElseThrow(() -> new AssertionError("Production noise settings did not load biome geology"));
        return (BiomeRockRule) SurfaceRules.RuleSource.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
    }

    private static void leaves(JsonObject rule, List<JsonObject> result) {
        String type = rule.get("type").getAsString();
        if (type.equals("minecraft:sequence")) {
            for (JsonElement child : rule.getAsJsonArray("sequence")) leaves(child.getAsJsonObject(), result);
        } else if (type.equals("minecraft:condition")) {
            leaves(rule.getAsJsonObject("then_run"), result);
        } else result.add(rule);
    }

    private static String id(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }

    private static void complete(ServerLevel level, CompletableFuture<?> future) {
        level.getServer().managedBlock(() -> { level.getChunkSource().pollTask(); return future.isDone(); });
        future.join();
    }

    private static void writeReport(JsonObject report) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        while (project.getParent() != null && !Files.isRegularFile(project.resolve("build.gradle"))) project = project.getParent();
        Path output = project.resolve("build/terrain-preview/biome-geology-native.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n", StandardCharsets.UTF_8);
    }

    private static Map<String, Expected> expected() {
        Map<String, Expected> result = new LinkedHashMap<>();
        result.put("molten_caves", new Expected(-60, -8, Set.of("kubejs:darkslate")));
        result.put("glowshroom_caves", new Expected(-32, 40, Set.of("kubejs:gneiss", "kubejs:schist", "kubejs:phyllite", "minecraft:tuff")));
        result.put("sandy_caves", new Expected(-12, 48, Set.of("kubejs:quartzite", "kubejs:claystone", "kubejs:shale")));
        result.put("dripstone_caves", new Expected(-48, 32, Set.of("create:limestone", "minecraft:calcite", "kubejs:claystone")));
        result.put("echo_caves", new Expected(-60, -24, Set.of("kubejs:gneiss", "kubejs:schist", "kubejs:shale", "create:limestone")));
        result.put("forsaken_land", new Expected(8, 383, Set.of("minecraft:stone", "minecraft:tuff")));
        result.put("smoldering_barren", new Expected(8, 383, Set.of("minecraft:stone", "minecraft:tuff")));
        return result;
    }

    private record Expected(int minY, int maxY, Set<String> rocks) {}

    private static final class Metrics {
        final Map<String, Long> materials = new LinkedHashMap<>();
        final Set<String> soils = new HashSet<>();
        final JsonArray samples = new JsonArray();
        long protectedStates;
        long water;
        long bedrock;
        long ashenCaps;
        long depletedBelowAshen;
        int borderColumns;
        int negativeChunks;

        boolean reached(String target) { return materials.getOrDefault(target, 0L) > 16; }

        JsonObject json() {
            JsonObject result = new JsonObject();
            result.add("native_material_blocks", new GsonBuilder().create().toJsonTree(materials));
            result.add("land_soils", new GsonBuilder().create().toJsonTree(soils));
            result.addProperty("preserved_non_default_solid_or_fluid_blocks", protectedStates);
            result.addProperty("preserved_water_blocks", water);
            result.addProperty("bedrock_blocks", bedrock);
            result.addProperty("exposed_ashen_caps", ashenCaps);
            result.addProperty("ashen_caps_with_depleted_subsoil", depletedBelowAshen);
            result.addProperty("columns_crossing_stored_biome_boundaries", borderColumns);
            result.addProperty("negative_coordinate_chunks", negativeChunks);
            result.add("samples", samples);
            return result;
        }
    }

    private static final class IsolatedRegion extends WorldGenRegion {
        private final ServerLevel level;
        private final ChunkAccess chunk;

        IsolatedRegion(ServerLevel level, ChunkAccess chunk) {
            super(level, null, null, chunk);
            this.level = level;
            this.chunk = chunk;
        }

        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }

        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            if (x == chunk.getPos().x && z == chunk.getPos().z) return chunk;
            if (required) throw new AssertionError("Surface test requested unrelated mutable chunk " + x + "," + z);
            return null;
        }

        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
            return level.getUncachedNoiseBiome(x, y, z);
        }
    }

    private NativeBiomeGeologyIntegrationTest() {}
}
