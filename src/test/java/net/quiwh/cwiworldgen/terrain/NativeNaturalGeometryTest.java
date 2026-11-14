package net.quiwh.cwiworldgen.terrain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.quiwh.cwiworldgen.hydrology.DirectedReach;
import net.quiwh.cwiworldgen.hydrology.current.CurrentFieldBuilder;
import net.quiwh.cwiworldgen.hydrology.watershed.WatershedSample;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

/** Profile 8 is verified through its real production router, finished tiles and generated blocks. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeNaturalGeometryTest {
    private record Fixture(BlockPos center, BlockPos leftBank, BlockPos rightBank) {}

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void naturalGeometryRetainsSupportedRiverBlocksJoinsAndDirectedCurrent(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:natural_geometry_wasteland_test")));
        helper.assertTrue(level != null, "Missing profile-8 production geometry test dimension");
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var settings = generator.generatorSettings().value();
        helper.assertTrue(TerrainGenerationProfile.fromRouter(settings.noiseRouter()) == 8
                        && ReferenceGenerationHeight.supports(settings),
                "Natural-geometry dimension must bind profile 8 and its generation-height optimization");
        var fullSettings = fullRange(settings);
        var fullGenerator = new NoiseBasedChunkGenerator(generator.getBiomeSource(), Holder.direct(fullSettings));
        helper.assertTrue(!ReferenceGenerationHeight.supports(fullSettings),
                "Independent full-height geometry control did not bypass height optimization");
        var noises = level.registryAccess().lookupOrThrow(Registries.NOISE);
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        int comparisons = 0, reduced = 0, joins = 0;
        for (long seed : new long[]{0L, 42L}) {
            var bounded = RandomState.create(settings, noises, seed);
            var full = RandomState.create(fullSettings, noises, seed);
            var terrain = ((TerrainRandomState) (Object) bounded).cwiWorldgen$terrain();
            var controlTerrain = ((TerrainRandomState) (Object) full).cwiWorldgen$terrain();
            try {
                helper.assertTrue(terrain != null && controlTerrain != null
                                && terrain.generationVersion() == 8 && controlTerrain.generationVersion() == 8,
                        "Geometry block comparison used another saved profile");
                Fixture fixture = findSupportedCrossSection(terrain);
                joins += verifyJoinsAndCurrent(helper, terrain, fixture, seed);
                Map<Long, ProtoChunk> owned = new LinkedHashMap<>();
                for (var position : List.of(fixture.center, fixture.leftBank, fixture.rightBank)) {
                    var chunkPos = new ChunkPos(position);
                    if (owned.containsKey(chunkPos.toLong())) continue;
                    int height = ReferenceGenerationHeight.height(settings.noiseSettings(), settings,
                            terrain, chunkPos, Blender.empty(), level.structureManager(), true);
                    if (height < settings.noiseSettings().height()) reduced++;
                    var a = new ProtoChunk(chunkPos, UpgradeData.EMPTY, level, biomes, null);
                    var b = new ProtoChunk(chunkPos, UpgradeData.EMPTY, level, biomes, null);
                    complete(level, generator.createBiomes(bounded, Blender.empty(), level.structureManager(), a));
                    complete(level, fullGenerator.createBiomes(full, Blender.empty(), level.structureManager(), b));
                    a.setPersistedStatus(ChunkStatus.BIOMES);
                    b.setPersistedStatus(ChunkStatus.BIOMES);
                    complete(level, generator.fillFromNoise(Blender.empty(), bounded, level.structureManager(), a));
                    complete(level, fullGenerator.fillFromNoise(Blender.empty(), full, level.structureManager(), b));
                    a.setPersistedStatus(ChunkStatus.NOISE);
                    b.setPersistedStatus(ChunkStatus.NOISE);
                    var block = new BlockPos.MutableBlockPos();
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                            block.set(chunkPos.getBlockX(x), y, chunkPos.getBlockZ(z));
                            helper.assertTrue(a.getBlockState(block).equals(b.getBlockState(block)),
                                    "Profile-8 height optimization changed generated material at " + block + " seed=" + seed);
                        }
                    owned.put(chunkPos.toLong(), a);
                    comparisons++;
                    System.out.println("CWI_NATURAL_GEOMETRY_HEIGHT=seed:" + seed + ",chunk:" + chunkPos
                            + ",bounded_height:" + height + ",full_height:" + settings.noiseSettings().height());
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
                            "Profile-8 post-carver river gasket was not idempotent");
                    for (var neighbor : region.neighbors.values()) for (var section : neighbor.getSections())
                        helper.assertTrue(section.hasOnlyAir(), "Profile-8 terrain wrote into an unowned neighboring chunk");
                }
                var column = ReferenceRiverColumn.sample(terrain, fixture.center.getX(), fixture.center.getZ());
                var wetChunk = owned.get(new ChunkPos(fixture.center).toLong());
                for (int y = column.sourceMinY(); y <= column.sourceMaxY(); y++) {
                    var position = new BlockPos(fixture.center.getX(), y, fixture.center.getZ());
                    var water = wetChunk.getBlockState(position);
                    helper.assertTrue(water.is(Blocks.WATER) && water.getFluidState().isSource(),
                            "Profile-8 production river lost source water at " + position);
                }
                var top = new BlockPos(fixture.center.getX(), column.waterY(), fixture.center.getZ());
                helper.assertTrue(wetChunk.getBlockState(top.above()).isAir(),
                        "Profile-8 finished river retained a solid roof above surface water");
                var bed = new BlockPos(fixture.center.getX(), column.solidTopY(), fixture.center.getZ());
                helper.assertTrue(!wetChunk.getBlockState(bed).isAir()
                                && wetChunk.getBlockState(bed).getFluidState().isEmpty(),
                        "Profile-8 finished river lost its solid supporting bed");
                for (var bank : List.of(fixture.leftBank, fixture.rightBank)) {
                    var chunk = owned.get(new ChunkPos(bank).toLong());
                    var state = chunk.getBlockState(bank);
                    helper.assertTrue(!state.isAir() && state.getFluidState().isEmpty(),
                            "Profile-8 generated dry bank lost its solid retaining column at " + bank);
                }
                System.out.println("CWI_NATURAL_GEOMETRY_BLOCKS=seed:" + seed + ",center:" + fixture.center
                        + ",left_bank:" + fixture.leftBank + ",right_bank:" + fixture.rightBank
                        + ",source_min:" + column.sourceMinY() + ",source_max:" + column.sourceMaxY()
                        + ",unowned_neighbor_writes:0");
            } finally {
                if (terrain != null) terrain.close();
                if (controlTerrain != null) controlTerrain.close();
            }
        }
        helper.assertTrue(comparisons >= 2 && comparisons <= 6 && reduced >= 2 && joins >= 32,
                "Profile-8 geometry missed positive block, optimized-height or shared-join coverage");
        helper.succeed();
    }

    private static Fixture findSupportedCrossSection(ReferenceTerrain terrain) {
        int candidates = 0;
        for (var reach : terrain.hydrologySnapshot(0, 0).reaches()) {
            var point = reach.points().get(reach.points().size() / 2);
            int x = (int) Math.round(point.x()), z = (int) Math.round(point.z());
            var sample = terrain.watershedSample(x, z);
            if (!ordinaryWet(sample) || sample.reachId() != reach.id() || sample.width() < 4
                    || sample.waterY() <= terrain.levels().waterY + 4) continue;
            var center = ReferenceRiverColumn.sample(terrain, x, z);
            if (!center.hasWater() || center.waterY() - center.solidTopY() < 2
                    || center.transition() != ReferenceRiverColumn.Transition.NONE
                    || center.sourceMinY() > center.sourceMaxY()) continue;
            BlockPos left = dryBank(terrain, x, z, sample, -1, center.waterY());
            BlockPos right = dryBank(terrain, x, z, sample, 1, center.waterY());
            if (left != null && right != null)
                return new Fixture(new BlockPos(x, center.waterY(), z), left, right);
            if (++candidates >= 64) break;
        }
        throw new AssertionError("No real profile-8 accepted river supplied stable deep water and two supported dry banks");
    }

    private static BlockPos dryBank(ReferenceTerrain terrain, int x, int z,
            WatershedSample center, int side, int waterY) {
        for (double distance = center.width() * 1.15; distance <= center.width() * 2.5 + 4; distance += 2) {
            int bx = (int) Math.round(x - center.tangentZ() * distance * side);
            int bz = (int) Math.round(z + center.tangentX() * distance * side);
            var column = ReferenceRiverColumn.sample(terrain, bx, bz);
            var sample = terrain.watershedSample(bx, bz);
            if (!column.hasWater() && column.groundY() >= waterY && sample.originalGroundY() >= waterY
                    && column.retainingTopY() >= waterY && !sample.basin() && !sample.island())
                return new BlockPos(bx, column.retainingTopY(), bz);
        }
        return null;
    }

    private static boolean ordinaryWet(WatershedSample sample) {
        return sample.valid() && sample.wet() && !sample.basin() && !sample.island() && !sample.dryCrest()
                && Math.abs(sample.distance()) <= sample.width();
    }

    private static int verifyJoinsAndCurrent(GameTestHelper helper, ReferenceTerrain terrain, Fixture fixture, long seed) {
        Map<Long, DirectedReach> reaches = new LinkedHashMap<>();
        for (var reach : terrain.hydrologySnapshot(0, 0).reaches()) reaches.put(reach.id(), reach);
        var wetReaches = new HashSet<Long>();
        for (var reach : reaches.values()) {
            var point = reach.points().get(reach.points().size() / 2);
            var sample = terrain.watershedSample((int) Math.round(point.x()), (int) Math.round(point.z()));
            if (ordinaryWet(sample) && sample.reachId() == reach.id()) wetReaches.add(reach.id());
        }
        int joins = 0;
        for (var reach : reaches.values()) {
            if (!wetReaches.contains(reach.id())) continue;
            var last = reach.points().getLast();
            var joint = terrain.watershedSample((int) Math.round(last.x()), (int) Math.round(last.z()));
            if (!ordinaryWet(joint)) continue;
            for (long id : reach.downstreamIds()) {
                if (!wetReaches.contains(id)) continue;
                var downstream = reaches.get(id);
                helper.assertTrue(downstream != null && last.equals(downstream.points().getFirst()),
                        "Profile-8 native river has a geometric seam at a shared drainage junction");
                double previous = Double.POSITIVE_INFINITY;
                // The final incoming samples approach and first receiving samples leave the shared
                // point through the same column arbitration used by generation and currents.
                var approach = new ArrayList<>(reach.points().subList(Math.max(0, reach.points().size() - 9), reach.points().size()));
                approach.addAll(downstream.points().subList(1, Math.min(9, downstream.points().size())));
                int wet = 0;
                for (var point : approach) {
                    var sample = terrain.watershedSample((int) Math.round(point.x()), (int) Math.round(point.z()));
                    if (!ordinaryWet(sample)) continue;
                    helper.assertTrue(Math.floor(sample.waterY()) <= Math.floor(previous),
                            "Profile-8 shared native join raises projected downstream water");
                    helper.assertTrue(sample.width() <= 28 && sample.originalGroundY() - sample.waterY() <= 32
                                    && sample.waterY() <= Math.max(terrain.levels().waterY, sample.originalGroundY()) + 2,
                            "Profile-8 shared native join loses width/incision/bank-support bounds");
                    previous = sample.waterY(); wet++;
                }
                helper.assertTrue(wet >= 3, "Profile-8 shared junction check lacked actual accepted wet samples");
                if (++joins >= 32) break;
            }
            if (joins >= 32) break;
        }
        helper.assertTrue(joins >= 16, "Profile-8 native topology lacked enough shared wet junctions");
        int x = fixture.center.getX(), z = fixture.center.getZ();
        var query = terrain.queryHydrology(x, z);
        var sample = terrain.watershedSample(x, z);
        helper.assertTrue(query.validDirection() && query.reachId() == sample.reachId()
                        && query.tangentX() * sample.tangentX() + query.tangentZ() * sample.tangentZ() > .99999,
                "Profile-8 directed query disagrees with generated wet-channel geometry");
        var field = CurrentFieldBuilder.build(terrain, new ChunkPos(fixture.center), 1);
        var current = field.column(x & 15, fixture.center.getY(), z & 15);
        helper.assertTrue(current.active() && current.contains(fixture.center.getY())
                        && current.directionX() * query.tangentX() + current.directionZ() * query.tangentZ() > .999,
                "Profile-8 completed current field lost its wet surface interval or downstream heading");
        helper.assertTrue(!field.column(x & 15, fixture.center.getY() + 1, z & 15).plannedWater(),
                "Profile-8 planned current leaked above generated surface water");
        System.out.println("CWI_NATURAL_GEOMETRY_CURRENT=seed:" + seed + ",joins:" + joins
                + ",active_columns:" + field.activeColumns() + ",center_heading:" + current.heading());
        return joins;
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
            if (status.isAfter(ChunkStatus.EMPTY)) throw new AssertionError("Profile-8 geometry requested a generated neighbor");
            return neighbors.computeIfAbsent(key, ignored -> new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY,
                    level, level.registryAccess().registryOrThrow(Registries.BIOME), null));
        }
        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
            return generator.getBiomeSource().getNoiseBiome(x, y, z, state.sampler());
        }
    }
}
