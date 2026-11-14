package net.quiwh.cwiworldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.ReferenceGenerationHeight;
import net.quiwh.cwiworldgen.terrain.ReferenceTerrainDensity;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeGenerationHeightTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void overriddenAquiferDependenciesKeepFullHeightFallback(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "wasteland_test")));
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        NoiseGeneratorSettings original = generator.generatorSettings().value();
        NoiseRouter r = original.noiseRouter();
        var preliminaryOnly = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(),
                r.lavaNoise(), r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
                DensityFunctions.constant(1.), r.finalDensity(), r.veinToggle(), r.veinRidged(), r.veinGap());
        var changedPreliminary = new NoiseGeneratorSettings(original.noiseSettings(), original.defaultBlock(), original.defaultFluid(),
                preliminaryOnly, original.surfaceRule(), original.spawnTarget(), original.seaLevel(), original.disableMobGeneration(),
                original.isAquifersEnabled(), original.oreVeinsEnabled(), original.useLegacyRandomSource());
        helper.assertTrue(!ReferenceGenerationHeight.supports(changedPreliminary), "Overridden preliminary surface remained eligible");
        var changedGrid = new NoiseGeneratorSettings(new NoiseSettings(-64, 448, 4, 2), original.defaultBlock(), original.defaultFluid(),
                r, original.surfaceRule(), original.spawnTarget(), original.seaLevel(), original.disableMobGeneration(),
                original.isAquifersEnabled(), original.oreVeinsEnabled(), original.useLegacyRandomSource());
        helper.assertTrue(!ReferenceGenerationHeight.supports(changedGrid), "Unsupported sparse noise grid remained eligible");
        var router = new NoiseRouter(r.barrierNoise(), DensityFunctions.constant(.5), DensityFunctions.zero(),
                r.lavaNoise(), r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
                DensityFunctions.constant(1.), r.finalDensity(), r.veinToggle(), r.veinRidged(), r.veinGap());
        var settings = new NoiseGeneratorSettings(original.noiseSettings(), original.defaultBlock(), original.defaultFluid(),
                router, original.surfaceRule(), original.spawnTarget(), original.seaLevel(), original.disableMobGeneration(),
                original.isAquifersEnabled(), original.oreVeinsEnabled(), original.useLegacyRandomSource());
        boolean supported = ReferenceGenerationHeight.supports(settings);
        helper.assertTrue(!supported, "High preliminary surfaces and aquifers were incorrectly treated as bounded terrain");
        int height = ReferenceGenerationHeight.height(settings.noiseSettings(), settings,
                ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain(),
                new ChunkPos(1, 1), Blender.empty(), level.structureManager(), supported);
        helper.assertTrue(height == settings.noiseSettings().height(), "Unsupported aquifers truncated noise filling");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void overriddenSurfaceDependenciesKeepFullHeightFallback(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "wasteland_test")));
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        NoiseGeneratorSettings original = generator.generatorSettings().value();
        var encoded = DensityFunction.HOLDER_HELPER_CODEC.encodeStart(
                net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()),
                original.noiseRouter().finalDensity()).getOrThrow().getAsJsonObject();
        var base = encoded.getAsJsonObject("argument1");
        var range = base.getAsJsonObject("argument1").getAsJsonObject("argument")
                .getAsJsonObject("argument2");
        // Equivalent to overriding the named surface dependency while keeping
        // every outer wrapper and climate marker in the router unchanged.
        range.add("input", new JsonPrimitive(1.));
        range.add("when_in_range", new JsonPrimitive(1.));
        DensityFunction changed = DensityFunction.HOLDER_HELPER_CODEC.parse(
                net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), encoded).getOrThrow();
        NoiseRouter r = original.noiseRouter();
        var router = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(),
                r.lavaNoise(), r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
                r.initialDensityWithoutJaggedness(), changed, r.veinToggle(), r.veinRidged(), r.veinGap());
        var settings = new NoiseGeneratorSettings(original.noiseSettings(), original.defaultBlock(), original.defaultFluid(),
                router, original.surfaceRule(), original.spawnTarget(), original.seaLevel(), original.disableMobGeneration(),
                original.isAquifersEnabled(), original.oreVeinsEnabled(), original.useLegacyRandomSource());
        boolean supported = ReferenceGenerationHeight.supports(settings);
        helper.assertTrue(!supported, "Overridden surface dependency was incorrectly treated as bounded terrain");
        int height = ReferenceGenerationHeight.height(settings.noiseSettings(), settings,
                ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain(),
                new ChunkPos(1, 1), Blender.empty(), level.structureManager(), supported);
        helper.assertTrue(height == settings.noiseSettings().height(), "Unsupported dependency truncated noise filling");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void boundedNoiseFillMatchesFullHeightBlockForBlock(GameTestHelper helper) throws Exception {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "wasteland_test")));
        helper.assertTrue(level != null, "Missing wasteland test dimension");
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        helper.assertTrue(ReferenceGenerationHeight.supports(settings), "Canonical surface graph is not eligible for height optimization");
        NoiseRouter r = settings.noiseRouter();
        NoiseRouter fullRouter = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(),
                r.fluidLevelSpreadNoise(), r.lavaNoise(), r.temperature(), r.vegetation(), r.continents(),
                r.erosion(), r.depth(), r.ridges(), r.initialDensityWithoutJaggedness(),
                DensityFunctions.add(r.finalDensity(), DensityFunctions.zero()), r.veinToggle(), r.veinRidged(), r.veinGap());
        NoiseGeneratorSettings fullSettings = new NoiseGeneratorSettings(settings.noiseSettings(), settings.defaultBlock(),
                settings.defaultFluid(), fullRouter, settings.surfaceRule(), settings.spawnTarget(), settings.seaLevel(),
                settings.disableMobGeneration(), settings.isAquifersEnabled(), settings.oreVeinsEnabled(), settings.useLegacyRandomSource());
        helper.assertTrue(!ReferenceGenerationHeight.supports(fullSettings), "Unsupported graph did not select full-range fallback");
        var fullGenerator = new NoiseBasedChunkGenerator(generator.getBiomeSource(), Holder.direct(fullSettings));
        var noises = level.registryAccess().lookupOrThrow(Registries.NOISE);
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        JsonArray samples = new JsonArray();
        int reduced = 0;
        for (long seed : new long[] {0L, 42L}) {
            RandomState bounded = RandomState.create(seed == 42L ? stressedAquifers(settings) : settings, noises, seed);
            RandomState full = RandomState.create(seed == 42L ? stressedAquifers(fullSettings) : fullSettings, noises, seed);
            try {
                ChunkPos[] positions = seed == 0L
                        ? new ChunkPos[] {new ChunkPos(1, 1), new ChunkPos(-2, -2), new ChunkPos(7, 7),
                                new ChunkPos(-96, -59), new ChunkPos(70, -126),
                                new ChunkPos(204, 264), new ChunkPos(200, 262)}
                        : new ChunkPos[] {new ChunkPos(1, 1), new ChunkPos(-2, -2), new ChunkPos(7, 7),
                                new ChunkPos(484, 123), new ChunkPos(132, -3), new ChunkPos(133, -3),
                                new ChunkPos(310, 395), new ChunkPos(338, -81), new ChunkPos(193, 208)};
                for (ChunkPos pos : positions) {
                    System.out.println("CWI_HEIGHT_COMPARE_START seed=" + seed + " chunk=" + pos);
                    int depth = ReferenceGenerationHeight.height(settings.noiseSettings(), settings,
                            ((TerrainRandomState) (Object) bounded).cwiWorldgen$terrain(), pos,
                            Blender.empty(), level.structureManager(), true);
                    if (depth < settings.noiseSettings().height()) reduced++;
                    ChunkAccess a = new ProtoChunk(pos, UpgradeData.EMPTY, level, biomes, null);
                    ChunkAccess b = new ProtoChunk(pos, UpgradeData.EMPTY, level, biomes, null);
                    var boundedFill = generator.fillFromNoise(Blender.empty(), bounded, level.structureManager(), a);
                    level.getServer().managedBlock(() -> {
                        level.getChunkSource().pollTask();
                        return boundedFill.isDone();
                    });
                    boundedFill.join();
                    var fullFill = fullGenerator.fillFromNoise(Blender.empty(), full, level.structureManager(), b);
                    level.getServer().managedBlock(() -> {
                        level.getChunkSource().pollTask();
                        return fullFill.isDone();
                    });
                    fullFill.join();
                    var block = new BlockPos.MutableBlockPos();
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                            block.set(pos.getBlockX(x), y, pos.getBlockZ(z));
                            helper.assertTrue(a.getBlockState(block).equals(b.getBlockState(block)),
                                    "Height optimization changed generated material at " + block + " seed=" + seed);
                        }
                    }
                    JsonObject sample = new JsonObject();
                    sample.addProperty("seed", seed);
                    sample.addProperty("chunk_x", pos.x);
                    sample.addProperty("chunk_z", pos.z);
                    sample.addProperty("full_height", settings.noiseSettings().height());
                    sample.addProperty("bounded_height", depth);
                    sample.addProperty("stressed_aquifer_channels", seed == 42L);
                    samples.add(sample);
                    System.out.println("CWI_HEIGHT_COMPARE_PASS=" + sample);
                }
            } finally {
                ((TerrainRandomState) (Object) bounded).cwiWorldgen$terrain().close();
                ((TerrainRandomState) (Object) full).cwiWorldgen$terrain().close();
            }
        }
        helper.assertTrue(reduced > 0, "Optimization never reduced the noise-fill range");
        Path output = Path.of("../terrain-preview/reference-height-optimization.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(samples) + "\n");
        helper.succeed();
    }

    private static NoiseGeneratorSettings stressedAquifers(NoiseGeneratorSettings settings) {
        NoiseRouter r = settings.noiseRouter();
        var router = new NoiseRouter(DensityFunctions.constant(100.), DensityFunctions.constant(.5), DensityFunctions.zero(),
                r.lavaNoise(), r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
                r.initialDensityWithoutJaggedness(), r.finalDensity(), r.veinToggle(), r.veinRidged(), r.veinGap());
        return new NoiseGeneratorSettings(settings.noiseSettings(), settings.defaultBlock(), settings.defaultFluid(),
                router, settings.surfaceRule(), settings.spawnTarget(), settings.seaLevel(), settings.disableMobGeneration(),
                settings.isAquifersEnabled(), settings.oreVeinsEnabled(), settings.useLegacyRandomSource());
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void pinnedDensityReadersMatchDetachedSamplesAcrossTileBoundaries(GameTestHelper helper) {
        try (var terrain = new ReferenceTerrain(42L)) {
            var density = new ReferenceTerrainDensity.Seeded(terrain, ReferenceTerrainDensity.Field.HEIGHT, true);
            for (int[] p : new int[][] {{-1, -1}, {0, 0}, {15, 15}, {16, 16}, {127, 127}, {128, 128}, {-129, -129}, {-128, -128}}) {
                double expected = terrain.sample(p[0], p[1]).height;
                double actual = density.compute(new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(p[0], 80, p[1]));
                helper.assertTrue(actual == expected, "Pinned reader sampled the wrong owning chunk at " + p[0] + "," + p[1]);
            }
        }
        helper.succeed();
    }
}
