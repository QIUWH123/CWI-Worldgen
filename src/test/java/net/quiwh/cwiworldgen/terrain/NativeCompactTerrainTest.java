package net.quiwh.cwiworldgen.terrain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;

/** Profile 3 uses the production graph and actual blocks, independently of profile 2's fixtures. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeCompactTerrainTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void compactMountainAndRiverBlocksMatchFullHeightAndRetainWater(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:compact_wasteland_test")));
        helper.assertTrue(level != null, "Missing production-profile compact terrain test dimension");
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var settings = generator.generatorSettings().value();
        helper.assertTrue(TerrainGenerationProfile.fromRouter(settings.noiseRouter()) == 3
                        && ReferenceGenerationHeight.supports(settings),
                "Compact production router did not retain generation-height optimization");
        var fullSettings = fullRange(settings);
        var fullGenerator = new NoiseBasedChunkGenerator(generator.getBiomeSource(), Holder.direct(fullSettings));
        helper.assertTrue(!ReferenceGenerationHeight.supports(fullSettings),
                "Independent full-height control failed to bypass optimization");
        var noises = level.registryAccess().lookupOrThrow(Registries.NOISE);
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        int comparisons = 0, reduced = 0;
        for (long seed : new long[]{0L, 42L}) {
            var bounded = RandomState.create(settings, noises, seed);
            var full = RandomState.create(fullSettings, noises, seed);
            var terrain = ((TerrainRandomState) (Object) bounded).cwiWorldgen$terrain();
            var controlTerrain = ((TerrainRandomState) (Object) full).cwiWorldgen$terrain();
            try {
                helper.assertTrue(terrain != null && controlTerrain != null
                                && terrain.generationVersion() == 3 && controlTerrain.generationVersion() == 3,
                        "Compact block comparison accidentally used another saved terrain profile");
                // Real mountain positives from the four-seed compact terrain measurement.
                int mountainX = seed == 0L ? 0 : 1472, mountainZ = seed == 0L ? -384 : 0;
                var base = new Cell();
                terrain.sampleBaseInto(base, mountainX, mountainZ);
                helper.assertTrue(base.terrain.isMountain() && base.height * 256 > 125,
                        "Compact positive fixture no longer samples actual mountain terrain");
                BlockPos wet = findDeepWetColumn(terrain);
                var positions = new ChunkPos[]{new ChunkPos(mountainX >> 4, mountainZ >> 4), new ChunkPos(wet)};
                Map<Long, ProtoChunk> owned = new LinkedHashMap<>();
                for (var position : positions) {
                    int depth = ReferenceGenerationHeight.height(settings.noiseSettings(), settings,
                            terrain, position, Blender.empty(), level.structureManager(), true);
                    if (depth < settings.noiseSettings().height()) reduced++;
                    var a = new ProtoChunk(position, UpgradeData.EMPTY, level, biomes, null);
                    var b = new ProtoChunk(position, UpgradeData.EMPTY, level, biomes, null);
                    complete(level, generator.createBiomes(bounded, Blender.empty(), level.structureManager(), a));
                    complete(level, fullGenerator.createBiomes(full, Blender.empty(), level.structureManager(), b));
                    a.setPersistedStatus(ChunkStatus.BIOMES);
                    b.setPersistedStatus(ChunkStatus.BIOMES);
                    complete(level, generator.fillFromNoise(Blender.empty(), bounded, level.structureManager(), a));
                    complete(level, fullGenerator.fillFromNoise(Blender.empty(), full, level.structureManager(), b));
                    a.setPersistedStatus(ChunkStatus.NOISE);
                    b.setPersistedStatus(ChunkStatus.NOISE);
                    var block = new BlockPos.MutableBlockPos();
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                            block.set(position.getBlockX(x), y, position.getBlockZ(z));
                            helper.assertTrue(a.getBlockState(block).equals(b.getBlockState(block)),
                                    "Compact height optimization changed generated material at " + block + " seed=" + seed);
                        }
                    }
                    owned.put(position.toLong(), a);
                    comparisons++;
                    System.out.println("CWI_COMPACT_HEIGHT_COMPARE=seed:" + seed + ",chunk:" + position
                            + ",bounded_height:" + depth + ",full_height:" + settings.noiseSettings().height());
                }
                for (var chunk : owned.values()) {
                    var region = new OwnedRegion(level, chunk, owned, generator, bounded, seed);
                    var manager = new BiomeManager(region, BiomeManager.obfuscateSeed(seed));
                    generator.buildSurface(chunk, new WorldGenerationContext(generator, level), bounded,
                            level.structureManager(), manager, biomes, Blender.empty());
                    chunk.setPersistedStatus(ChunkStatus.SURFACE);
                    generator.applyCarvers(region, seed, bounded, manager, level.structureManager(), chunk, GenerationStep.Carving.AIR);
                    chunk.setPersistedStatus(ChunkStatus.CARVERS);
                    helper.assertTrue(ReferenceRiverGasket.place(chunk, bounded, seed) == 0,
                            "Compact post-carver water gasket was not idempotent");
                    for (var neighbor : region.neighbors.values()) for (var section : neighbor.getSections())
                        helper.assertTrue(section.hasOnlyAir(), "Compact terrain wrote into an unowned neighboring chunk");
                }
                var column = ReferenceRiverColumn.sample(terrain, wet.getX(), wet.getZ());
                var wetChunk = owned.get(new ChunkPos(wet).toLong());
                var top = new BlockPos(wet.getX(), column.waterY(), wet.getZ());
                helper.assertTrue(wetChunk.getBlockState(top).is(Blocks.WATER)
                                && wetChunk.getBlockState(top.below()).is(Blocks.WATER)
                                && wetChunk.getBlockState(top.above()).isAir(),
                        "Compact production river lost its actual source-water interval or retained a solid roof at " + top);
                System.out.println("CWI_COMPACT_RIVER_BLOCKS=seed:" + seed + ",x:" + wet.getX() + ",z:" + wet.getZ()
                        + ",water_y:" + column.waterY() + ",water_depth:" + (column.waterY() - column.solidTopY())
                        + ",unowned_neighbor_writes:0");
            } finally {
                if (terrain != null) terrain.close();
                if (controlTerrain != null) controlTerrain.close();
            }
        }
        helper.assertTrue(comparisons == 4 && reduced >= 2,
                "Compact profile did not exercise both seeds, mountain/river chunks and reduced filling");
        helper.succeed();
    }

    private static BlockPos findDeepWetColumn(ReferenceTerrain terrain) {
        int candidates = 0;
        for (var reach : terrain.hydrologySnapshot(0, 0).reaches()) {
            var midpoint = reach.points().get(reach.points().size() / 2);
            int x = (int) Math.round(midpoint.x()), z = (int) Math.round(midpoint.z());
            var sample = terrain.watershedSample(x, z);
            if (!sample.wet() || sample.basin() || sample.island() || sample.dryCrest()
                    || Math.abs(sample.distance()) > sample.width()) continue;
            var column = ReferenceRiverColumn.sample(terrain, x, z);
            if (column.hasWater() && column.waterY() - column.solidTopY() >= 2
                    && column.transition() == ReferenceRiverColumn.Transition.NONE
                    && column.sourceMinY() <= column.waterY() && column.waterY() <= column.sourceMaxY())
                return new BlockPos(x, column.waterY(), z);
            if (++candidates >= 64) break;
        }
        throw new AssertionError("No real compact watershed reach supplied a deep, stable projected water column");
    }

    private static NoiseGeneratorSettings fullRange(NoiseGeneratorSettings settings) {
        NoiseRouter r = settings.noiseRouter();
        var full = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(),
                r.lavaNoise(), r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
                r.initialDensityWithoutJaggedness(), DensityFunctions.add(r.finalDensity(), DensityFunctions.zero()),
                r.veinToggle(), r.veinRidged(), r.veinGap());
        return new NoiseGeneratorSettings(settings.noiseSettings(), settings.defaultBlock(), settings.defaultFluid(),
                full, settings.surfaceRule(), settings.spawnTarget(), settings.seaLevel(), settings.disableMobGeneration(),
                settings.isAquifersEnabled(), settings.oreVeinsEnabled(), settings.useLegacyRandomSource());
    }

    private static void complete(ServerLevel level, CompletableFuture<?> future) {
        level.getServer().managedBlock(() -> { level.getChunkSource().pollTask(); return future.isDone(); });
        future.join();
    }

    private static final class OwnedRegion extends WorldGenRegion {
        private final ServerLevel level;
        private final Map<Long, ProtoChunk> owned;
        private final NoiseBasedChunkGenerator generator;
        private final RandomState state;
        private final long seed;
        private final Map<Long, ProtoChunk> neighbors = new LinkedHashMap<>();
        OwnedRegion(ServerLevel level, ChunkAccess center, Map<Long, ProtoChunk> owned,
                NoiseBasedChunkGenerator generator, RandomState state, long seed) {
            super(level, null, null, center);
            this.level = level; this.owned = owned; this.generator = generator; this.state = state; this.seed = seed;
        }
        @Override public long getSeed() { return seed; }
        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            long key = ChunkPos.asLong(x, z);
            var existing = owned.get(key);
            if (existing != null) return existing;
            if (status.isAfter(ChunkStatus.EMPTY)) throw new AssertionError("Compact terrain requested a generated neighbor");
            return neighbors.computeIfAbsent(key, ignored -> new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY,
                    level, level.registryAccess().registryOrThrow(Registries.BIOME), null));
        }
        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
            return generator.getBiomeSource().getNoiseBiome(x, y, z, state.sampler());
        }
    }
}
