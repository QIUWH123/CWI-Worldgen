package net.quiwh.cwiworldgen.terrain;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource;
import net.quiwh.cwiworldgen.hydrology.current.CurrentFieldBuilder;
import net.quiwh.cwiworldgen.hydrology.watershed.WatershedHydrology;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;

/** Profile22 integration checks against the distribution JAR and fresh detached chunks. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeClassicTerrainTest {
    private static final int PROFILE = 22;
    private static final long SEED = 0L;

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void compactProfileRoundtripsAndNormalOverworldDefaultsToTwentyTwo(GameTestHelper helper) {
        var level = level(helper);
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        helper.assertTrue(((BoundedCaveBiomeSource) generator.getBiomeSource()).generationVersion() == PROFILE
                        && TerrainGenerationProfile.fromRouter(generator.generatorSettings().value().noiseRouter()) == PROFILE,
                "Compact-range test dimension did not bind coherent profile 22");
        var normal = level.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS)
                .get(ResourceLocation.withDefaultNamespace("overworld"));
        helper.assertTrue(normal != null && TerrainGenerationProfile.fromRouter(normal.noiseRouter()) == PROFILE,
                "The normal Overworld registry default did not select profile 22");
        var terrainCodec = ReferenceTerrainDensity.CODEC.codec();
        var marker = terrainCodec.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"field\":\"height\",\"generation_version\":22}")).getOrThrow();
        helper.assertTrue(terrainCodec.parse(JsonOps.INSTANCE, terrainCodec.encodeStart(JsonOps.INSTANCE, marker)
                        .getOrThrow()).getOrThrow().generationVersion() == PROFILE,
                "Terrain marker lost profile 22 during save/reload");
        var caves = (BoundedCaveBiomeSource) generator.getBiomeSource();
        var caveCodec = BoundedCaveBiomeSource.CODEC.codec();
        var decodedCaves = caveCodec.parse(ops, caveCodec.encodeStart(ops, caves).getOrThrow()).getOrThrow();
        helper.assertTrue(decodedCaves.generationVersion() == PROFILE, "Cave source lost profile 22");
        var landscapeCodec = ReferenceLandscapeDensity.CODEC.codec();
        var landscape = new ReferenceLandscapeDensity(decodedCaves, PROFILE);
        var decodedLandscape = landscapeCodec.parse(ops, landscapeCodec.encodeStart(ops, landscape)
                .getOrThrow()).getOrThrow();
        helper.assertTrue(decodedLandscape.generationVersion() == PROFILE
                        && decodedLandscape.caves().generationVersion() == PROFILE,
                "Landscape marker lost the modern saved profile");
        var saved = ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow().getAsJsonObject();
        var decoded = (NoiseBasedChunkGenerator) ChunkGenerator.CODEC.parse(ops, saved).getOrThrow();
        helper.assertTrue(TerrainGenerationProfile.fromRouter(decoded.generatorSettings().value().noiseRouter()) == PROFILE
                        && ((BoundedCaveBiomeSource) decoded.getBiomeSource()).generationVersion() == PROFILE,
                "Whole generator save/reload lost profile 22");
        // Every saved terrain, cave and landscape identity survives loading under the new default.
        int savedProfiles = 0;
        for (int previousProfile = 1; previousProfile <= 21; previousProfile++) {
            var previousMarker = terrainCodec.parse(JsonOps.INSTANCE, JsonParser.parseString(
                    "{\"field\":\"height\",\"generation_version\":" + previousProfile + "}")).getOrThrow();
            helper.assertTrue(terrainCodec.parse(JsonOps.INSTANCE, terrainCodec.encodeStart(JsonOps.INSTANCE,
                            previousMarker).getOrThrow()).getOrThrow().generationVersion() == previousProfile,
                    "Terrain reload migrated saved profile " + previousProfile);
            var previous = saved.deepCopy();
            previous.addProperty("settings", "minecraft:overworld");
            previous.getAsJsonObject("biome_source").addProperty("generation_version", previousProfile);
            var legacy = (NoiseBasedChunkGenerator) ChunkGenerator.CODEC.parse(ops, previous).getOrThrow();
            var legacyCaves = (BoundedCaveBiomeSource) legacy.getBiomeSource();
            helper.assertTrue(TerrainGenerationProfile.fromRouter(legacy.generatorSettings().value().noiseRouter()) == previousProfile
                            && legacyCaves.generationVersion() == previousProfile,
                    "New defaults migrated saved generator profile " + previousProfile);
            helper.assertTrue(caveCodec.parse(ops, caveCodec.encodeStart(ops, legacyCaves).getOrThrow())
                            .getOrThrow().generationVersion() == previousProfile,
                    "Cave reload migrated saved profile " + previousProfile);
            var legacyLandscape = new ReferenceLandscapeDensity(legacyCaves, previousProfile);
            var restoredLandscape = landscapeCodec.parse(ops, landscapeCodec.encodeStart(ops, legacyLandscape)
                    .getOrThrow()).getOrThrow();
            helper.assertTrue(restoredLandscape.generationVersion() == previousProfile
                            && restoredLandscape.caves().generationVersion() == previousProfile,
                    "Landscape reload migrated saved profile " + previousProfile);
            var restoredGenerator = (NoiseBasedChunkGenerator) ChunkGenerator.CODEC.parse(ops,
                    ChunkGenerator.CODEC.encodeStart(ops, legacy).getOrThrow()).getOrThrow();
            helper.assertTrue(TerrainGenerationProfile.fromRouter(restoredGenerator.generatorSettings().value().noiseRouter()) == previousProfile
                            && ((BoundedCaveBiomeSource) restoredGenerator.getBiomeSource()).generationVersion() == previousProfile,
                    "Whole generator roundtrip migrated saved profile " + previousProfile);
            savedProfiles++;
        }
        helper.assertTrue(terrainCodec.parse(JsonOps.INSTANCE, JsonParser.parseString(
                        "{\"field\":\"height\",\"generation_version\":23}")).error().isPresent(),
                "An unimplemented terrain profile was accepted");
        var unsupportedCaves = caveCodec.encodeStart(ops, caves).getOrThrow().getAsJsonObject().deepCopy();
        unsupportedCaves.addProperty("generation_version", 23);
        helper.assertTrue(caveCodec.parse(ops, unsupportedCaves).error().isPresent(),
                "An unimplemented cave profile was accepted");
        var unsupportedLandscape = landscapeCodec.encodeStart(ops, landscape).getOrThrow().getAsJsonObject().deepCopy();
        unsupportedLandscape.addProperty("generation_version", 23);
        helper.assertTrue(landscapeCodec.parse(ops, unsupportedLandscape).error().isPresent(),
                "An unimplemented landscape profile was accepted");
        var unsupportedGenerator = saved.deepCopy();
        unsupportedGenerator.getAsJsonObject("biome_source").addProperty("generation_version", 23);
        helper.assertTrue(ChunkGenerator.CODEC.parse(ops, unsupportedGenerator).error().isPresent(),
                "An unimplemented generator profile was accepted");
        helper.assertTrue(savedProfiles == 21, "Saved-profile compatibility coverage is incomplete");
        System.out.println("CWI_COMPACT_PROFILE=default:22,terrain:22,caves:22,landscape:22,generator:22,saved_profiles:1..21,saved_profiles_roundtripped:"
                + savedProfiles + ",reject23:true");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void compactBaseRetainsModernLandformsAndLoadedFeatureStages(GameTestHelper helper) {
        try (var terrain = new ReferenceTerrain(SEED, PROFILE)) {
            var snapshot = terrain.hydrologySnapshot(0, 0);
            helper.assertTrue(terrain.generationVersion() == PROFILE
                            && snapshot.provider().equals(WatershedHydrology.PROVIDER)
                            && snapshot.version() == WatershedHydrology.VERSION,
                    "Compact base downgraded the public terrain or watershed policy");
            var modernLandforms = new RegionalLandforms(terrain.seed(), terrain.levels(), terrain::sampleBaseInto, PROFILE);
            int compared = 0, changed = 0;
            try {
                // Compare unchanged dry columns with the independent current landform consumer.
                for (int z = -384; z <= 384 && compared < 16; z += 128) {
                    for (int x = -384; x <= 384 && compared < 16; x += 128) {
                        var base = new Cell();
                        terrain.sampleBaseInto(base, x, z);
                        var expected = new Cell();
                        expected.copyFrom(base);
                        modernLandforms.apply(expected, x, z);
                        var actual = terrain.sampleRaw(x, z);
                        if (actual.cwiNaturalRiver || actual.cwiPondProtected || actual.cwiIsland) continue;
                        helper.assertTrue(Float.floatToRawIntBits(actual.height) == Float.floatToRawIntBits(expected.height),
                                "Generation and drainage planning disagree on modern landforms at " + x + "," + z);
                        compared++;
                        if (Float.floatToRawIntBits(base.height) != Float.floatToRawIntBits(expected.height)) changed++;
                    }
                }
            } finally { modernLandforms.close(); }
            helper.assertTrue(compared >= 4 && changed > 0,
                    "No positive modern landform relief survived on the classic base: compared=" + compared + ",changed=" + changed);
            System.out.println("CWI_COMPACT_LANDFORMS=profile:22,dry_columns:" + compared + ",changed_columns:" + changed);
        }
        var biomes = level(helper).registryAccess().registryOrThrow(Registries.BIOME);
        for (String name : List.of("forsaken_land", "smoldering_barren")) {
            var biome = biomes.get(ResourceLocation.fromNamespaceAndPath("cwi", name));
            helper.assertTrue(biome != null, "Missing current surface biome " + name);
            var stages = biome.getGenerationSettings().features();
            var raw = new ArrayList<String>();
            stages.get(GenerationStep.Decoration.RAW_GENERATION.ordinal()).forEach(holder ->
                    raw.add(holder.unwrapKey().orElseThrow().location().toString()));
            helper.assertTrue(raw.stream().filter("cwi:terrain_surface_exposure"::equals).count() == 1
                            && raw.stream().filter("cwi:terrain_rock_outcrops"::equals).count() == 1
                            && raw.stream().filter("cwi:waterbed_sediments"::equals).count() == 1
                            && raw.indexOf("cwi:terrain_surface_exposure") < raw.indexOf("cwi:terrain_rock_outcrops"),
                    "Compact range profile lost current erosion, rock or waterbed activation/order in " + name + ": " + raw);
            var late = new ArrayList<String>();
            stages.get(GenerationStep.Decoration.TOP_LAYER_MODIFICATION.ordinal()).forEach(holder ->
                    late.add(holder.unwrapKey().orElseThrow().location().toString()));
            helper.assertTrue(late.stream().filter("cwi:terrain_surface_cleanup"::equals).count() == 1,
                    "Compact range profile lost the current late surface cleanup in " + name);
        }
        System.out.println("CWI_COMPACT_FEATURES=surface_biomes:2,current_feature_activation:retained");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void modernRiverProjectionMatchesActualSurfaceAndCarverWater(GameTestHelper helper) {
        var level = level(helper);
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var state = RandomState.create(generator.generatorSettings().value(),
                level.registryAccess().lookupOrThrow(Registries.NOISE), SEED);
        var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null && terrain.generationVersion() == PROFILE,
                "Actual-block generation did not bind profile 22");
        try {
            BlockPos wet = findWet(terrain);
            var position = new ChunkPos(wet);
            var chunk = new ProtoChunk(position, UpgradeData.EMPTY, level,
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
            complete(level, generator.createBiomes(state, Blender.empty(), level.structureManager(), chunk));
            chunk.setPersistedStatus(ChunkStatus.BIOMES);
            complete(level, generator.fillFromNoise(Blender.empty(), state, level.structureManager(), chunk));
            chunk.setPersistedStatus(ChunkStatus.NOISE);
            Map<Long, ProtoChunk> owned = Map.of(position.toLong(), chunk);
            var region = new OwnedRegion(level, chunk, owned, generator, state);
            var manager = new BiomeManager(region, BiomeManager.obfuscateSeed(SEED));
            generator.buildSurface(chunk, new WorldGenerationContext(generator, level), state,
                    level.structureManager(), manager, level.registryAccess().registryOrThrow(Registries.BIOME), Blender.empty());
            chunk.setPersistedStatus(ChunkStatus.SURFACE);
            int surface = assertProjectedWater(helper, terrain, chunk, "SURFACE");
            generator.applyCarvers(region, SEED, state, manager, level.structureManager(), chunk, GenerationStep.Carving.AIR);
            chunk.setPersistedStatus(ChunkStatus.CARVERS);
            int carved = assertProjectedWater(helper, terrain, chunk, "CARVERS");
            helper.assertTrue(surface > 0 && carved == surface, "No positive stable projected wet columns survived both stages");
            helper.assertTrue(ReferenceRiverGasket.place(chunk, state, SEED) == 0,
                    "The post-carver water gasket was not idempotent");
            for (var neighbor : region.neighbors.values()) for (var section : neighbor.getSections())
                helper.assertTrue(section.hasOnlyAir(), "Compact range river generation wrote into an unowned neighboring chunk");
            System.out.println("CWI_COMPACT_RIVER_BLOCKS=profile:22,seed:0,chunk:" + position
                    + ",surface_columns:" + surface + ",carver_columns:" + carved + ",unowned_neighbor_writes:0");
        } finally { if (terrain != null) terrain.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void isolatedCurrentBuilderReconstructsGenerationSeedAndProfileTwentyTwo(GameTestHelper helper) {
        var level = level(helper);
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var state = RandomState.create(generator.generatorSettings().value(),
                level.registryAccess().lookupOrThrow(Registries.NOISE), SEED);
        var generated = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        helper.assertTrue(generated != null && generated.generationVersion() == PROFILE,
                "Generation context lost the current profile before current-field reconstruction");
        try (var isolated = new ReferenceTerrain(SEED, PROFILE)) {
            BlockPos wet = findWet(generated);
            var chunk = new ChunkPos(wet);
            var expected = CurrentFieldBuilder.build(generated, chunk, 1);
            var reconstructed = CurrentFieldBuilder.build(isolated, chunk, 1);
            var repeated = CurrentFieldBuilder.build(isolated, chunk, 1);
            helper.assertTrue(isolated.generationVersion() == PROFILE && isolated.seed() == generated.seed()
                            && expected.activeColumns() > 0 && reconstructed.activeColumns() > 0,
                    "Isolated worker reconstruction lost the profile, seed or directed current positive");
            helper.assertTrue(expected.layerCount() == reconstructed.layerCount(), "Reconstruction changed current layers");
            for (int layer = 0; layer < expected.layerCount(); layer++) for (int index = 0; index < 256; index++)
                helper.assertTrue(expected.packed(layer, index) == reconstructed.packed(layer, index)
                                && expected.packed(layer, index) == repeated.packed(layer, index),
                        "Current field differs between generation, isolated reconstruction or repeat at " + layer + "/" + index);
            var projected = ReferenceRiverColumn.sample(generated, wet.getX(), wet.getZ());
            var column = reconstructed.column(wet.getX() & 15, wet.getZ() & 15);
            helper.assertTrue(column.active() && column.maxY() == projected.waterY()
                            && column.minY() == projected.placementFloorY() + 1,
                    "Current metadata no longer describes the same generated wet interval");
            System.out.println("CWI_COMPACT_CURRENT=profile:22,seed:0,chunk:" + chunk
                    + ",active_columns:" + expected.activeColumns() + ",all_packed_columns_identical:true");
        } finally { if (generated != null) generated.close(); }
        helper.succeed();
    }

    private static int assertProjectedWater(GameTestHelper helper, ReferenceTerrain terrain, ProtoChunk chunk, String stage) {
        int count = 0;
        var position = chunk.getPos();
        var columns = new ReferenceRiverColumn.Reader(terrain, position.getMinBlockX(), position.getMinBlockZ(), 16, 16);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int worldX = position.getBlockX(x), worldZ = position.getBlockZ(z);
            var projected = columns.sample(worldX, worldZ);
            if (!projected.hasWater() || projected.transition() != ReferenceRiverColumn.Transition.NONE
                    || projected.waterY() - projected.solidTopY() < 2
                    || !terrain.sample(worldX, worldZ).terrain.isRiver()) continue;
            helper.assertTrue(projected.sourceMinY() <= projected.waterY() && projected.sourceMaxY() == projected.waterY(),
                    "Stable wet projection lost its source interval");
            for (int y = projected.sourceMinY(); y <= projected.sourceMaxY(); y++) {
                var block = new BlockPos(worldX, y, worldZ);
                helper.assertTrue(chunk.getBlockState(block).is(Blocks.WATER),
                        stage + " lost projected river water at " + block + " in " + projected);
            }
            var bed = new BlockPos(worldX, projected.solidTopY(), worldZ);
            helper.assertTrue(!chunk.getBlockState(bed).isAir() && chunk.getBlockState(bed).getFluidState().isEmpty(),
                    stage + " lost solid support below projected river water at " + bed);
            helper.assertTrue(chunk.getBlockState(new BlockPos(worldX, projected.waterY() + 1, worldZ)).isAir(),
                    stage + " retained a roof directly over projected river water");
            count++;
        }
        return count;
    }

    private static BlockPos findWet(ReferenceTerrain terrain) {
        int filteredCandidates = 0;
        for (int[] owner : new int[][]{{0, 0}, {6000, 6000}, {-6000, -6000}}) {
            for (var reach : terrain.hydrologySnapshot(owner[0], owner[1]).reaches()) {
                for (int fraction : new int[]{2, 1, 3}) {
                    var point = reach.points().get((reach.points().size() - 1) * fraction / 4);
                    int x = (int) Math.round(point.x()), z = (int) Math.round(point.z());
                    var raw = terrain.watershedSample(x, z);
                    if (!raw.wet() || raw.basin() || raw.island() || raw.dryCrest()
                            || Math.abs(raw.distance()) > raw.width()
                            || raw.waterY() <= terrain.levels().waterY + 4) continue;
                    var column = ReferenceRiverColumn.sample(terrain, x, z);
                    if (column.hasWater() && column.waterY() - column.solidTopY() >= 3
                            && column.transition() == ReferenceRiverColumn.Transition.NONE
                            && terrain.sample(x, z).terrain.isRiver() && terrain.queryHydrology(x, z).validDirection()
                            && column.sourceMinY() <= column.waterY() && column.sourceMaxY() == column.waterY())
                        return new BlockPos(x, column.waterY(), z);
                    if (++filteredCandidates >= 64) throw new AssertionError("No stable current-bearing river in 64 native wet candidates");
                }
            }
        }
        throw new AssertionError("No positive stable modern wet river found on the compact base");
    }

    private static ServerLevel level(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        helper.assertTrue(level != null, "Missing isolated compact-profile test dimension");
        return level;
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
        private final Map<Long, ProtoChunk> neighbors = new LinkedHashMap<>();
        OwnedRegion(ServerLevel level, ChunkAccess center, Map<Long, ProtoChunk> owned,
                NoiseBasedChunkGenerator generator, RandomState state) {
            super(level, null, null, center);
            this.level = level; this.owned = owned; this.generator = generator; this.state = state;
        }
        @Override public long getSeed() { return SEED; }
        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            long key = ChunkPos.asLong(x, z);
            var existing = owned.get(key);
            if (existing != null) return existing;
            if (status.isAfter(ChunkStatus.EMPTY)) throw new AssertionError("Compact range terrain requested a generated neighbor");
            return neighbors.computeIfAbsent(key, ignored -> new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY,
                    level, level.registryAccess().registryOrThrow(Registries.BIOME), null));
        }
        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
            return generator.getBiomeSource().getNoiseBiome(x, y, z, state.sampler());
        }
    }
}
