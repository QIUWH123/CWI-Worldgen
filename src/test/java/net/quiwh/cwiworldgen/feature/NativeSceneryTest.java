package net.quiwh.cwiworldgen.feature;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.placement.SceneryDistrictFilter;
import net.quiwh.cwiworldgen.placement.SceneryClusters;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeSceneryTest {
    @GameTest(template = "empty")
    public static void worldSpaceSceneryKeepsSavedProfilesAndRemovesChunkBorderStrips(GameTestHelper helper) {
        var biome = ResourceLocation.parse("cwi:forsaken_land");
        var profile = new SceneryDistrictFilter.Profile(biome, .8, 1, 3, 1, 3, 3);
        long oldCount = 0, newCount = 0, edgeCount = 0;
        long[] binsX = new long[16], binsZ = new long[16];
        for (long seed : new long[] {0, 19, 42, -987654321}) {
            for (int x = -24; x <= 24; x++) for (int z = -20; z <= 20; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                var legacy = SceneryDistrictFilter.candidates(seed, 371, chunk, profile);
                oldCount += legacy.size();
                for (int version = 1; version <= 4; version++) helper.assertTrue(legacy.equals(
                        SceneryDistrictFilter.candidates(seed, 371, chunk, profile, version)),
                        "A saved generation profile changed scenery");
                var next = SceneryDistrictFilter.candidates(seed, 371, chunk, profile, 5);
                helper.assertTrue(next.equals(SceneryDistrictFilter.candidates(seed, 371, chunk, profile, 5)),
                        "World-space scenery depends on generation order");
                newCount += next.size();
                for (var position : next) {
                    helper.assertTrue(new ChunkPos(position).equals(chunk), "Scenery candidate escaped owning chunk");
                    helper.assertTrue(SceneryClusters.richness(seed, position.getX(), position.getZ()) > 0,
                            "A world-space member filled a quiet district");
                    helper.assertTrue(SceneryClusters.richness(seed, position.getX(), position.getZ())
                            == SceneryDistrictFilter.richness(seed, position.getX(), position.getZ()),
                            "New scenery lost the established shared clearing field");
                    binsX[position.getX() & 15]++;
                    binsZ[position.getZ() & 15]++;
                    if ((position.getX() & 15) == 0 || (position.getX() & 15) == 15
                            || (position.getZ() & 15) == 0 || (position.getZ() & 15) == 15) edgeCount++;
                }
            }
        }
        double ratio = newCount / (double) oldCount;
        helper.assertTrue(ratio > .85 && ratio < 1.15, "World-space groups changed candidate density by more than15%");
        for (long[] bins : List.of(binsX, binsZ)) {
            double mean = newCount / 16.;
            for (long count : bins) helper.assertTrue(count > mean * .65 && count < mean * 1.4,
                    "Scenery still has periodic chunk-center or border bias");
        }
        helper.assertTrue(edgeCount > 500, "Chunk borders still have artificial scenery-free strips");
        System.out.println("CWI_SCENERY_WORLD_NATIVE=old=" + oldCount + " new=" + newCount
                + " ratio=" + ratio + " edge_candidates=" + edgeCount);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void coherentSceneryHasSeededClearingsAndBiomeContrast(GameTestHelper helper) {
        var biome = ResourceLocation.parse("cwi:forsaken_land");
        var rich = new SceneryDistrictFilter.Profile(biome, .8, 1, 3, 1, 3, 3);
        var sparse = new SceneryDistrictFilter.Profile(biome, .35, 1, 2, 1, 2, 2);
        int richCount = 0, sparseCount = 0, quiet = 0, changedSeeds = 0, closePairs = 0;
        for (int x = -32; x <= 32; x++) for (int z = -16; z <= 16; z++) {
            ChunkPos chunk = new ChunkPos(x, z);
            var first = SceneryDistrictFilter.candidates(19, 371, chunk, rich);
            helper.assertTrue(first.equals(SceneryDistrictFilter.candidates(19, 371, chunk, rich)),
                    "Scenery changed with cache/generation order");
            if (!first.equals(SceneryDistrictFilter.candidates(20, 371, chunk, rich))) changedSeeds++;
            richCount += first.size();
            sparseCount += SceneryDistrictFilter.candidates(19, 371, chunk, sparse).size();
            if (SceneryDistrictFilter.richness(19, chunk.getMiddleBlockX(), chunk.getMiddleBlockZ()) == 0) {
                quiet++;
                helper.assertTrue(first.isEmpty(), "A shared clearing contains scenery");
            }
            for (var pos : first) helper.assertTrue(new ChunkPos(pos).equals(chunk), "Scenery candidate escaped owner");
            for (int i = 1; i < first.size(); i++) if (first.get(i).distSqr(first.get(i - 1)) <= 50) closePairs++;
        }
        helper.assertTrue(quiet > 50 && richCount > 300 && changedSeeds > 200,
                "Missing quiet districts, positive coverage or world seed variation");
        helper.assertTrue(richCount > sparseCount * 2 && closePairs > 100,
                "Biome profiles or compact groups lost their contrast");
        double greatestStep = 0;
        for (int x = -512; x < 512; x++) greatestStep = Math.max(greatestStep,
                Math.abs(SceneryDistrictFilter.richness(19, x, -113) - SceneryDistrictFilter.richness(19, x + 1, -113)));
        helper.assertTrue(greatestStep < .03, "Scenery mask has a discontinuity at a cell boundary");
        System.out.println("CWI_SCENERY_NATIVE=rich=" + richCount + " sparse=" + sparseCount
                + " clearings=" + quiet + " seed_changes=" + changedSeeds + " close_pairs=" + closePairs);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void safeFallenLogsKeepTheirAxisSupportAndOwningChunk(GameTestHelper helper) {
        var config = config(SafeDeadwoodFeature.Kind.FALLEN, 4, 6, false);
        Set<Direction.Axis> axes = new HashSet<>();
        Set<Integer> sizes = new HashSet<>();
        for (int seed = 0; seed < 24; seed++) {
            ChunkPos owner = new ChunkPos(-2, 3);
            var first = fixture(helper, owner);
            var second = fixture(helper, owner);
            BlockPos origin = new BlockPos(owner.getBlockX(8), 129, owner.getBlockZ(8));
            helper.assertTrue(place(first, origin, seed, config) && place(second, origin, seed, config),
                    "Supported fallen log was rejected");
            var changes = logs(first);
            helper.assertTrue(changes.equals(logs(second)), "Log shape is not deterministic");
            helper.assertTrue(changes.size() >= 4 && changes.size() <= 6 && changes.containsKey(origin),
                    "Fallen log lost its configured length or origin");
            sizes.add(changes.size());
            for (var entry : changes.entrySet()) {
                BlockPos position = entry.getKey();
                axes.add(entry.getValue().getValue(RotatedPillarBlock.AXIS));
                helper.assertTrue(new ChunkPos(position).equals(owner)
                        && first.getBlockState(position.below()).is(Blocks.DIRT), "Fallen log is unsupported or crossed a border");
                helper.assertTrue(first.getHeight(Heightmap.Types.WORLD_SURFACE_WG, position.getX(), position.getZ()) == 129,
                        "Deadwood left a stale heightmap");
            }
        }
        helper.assertTrue(axes.equals(Set.of(Direction.Axis.X, Direction.Axis.Z)) && sizes.size() == 3,
                "Fallen logs lost direction or length variation");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void deadwoodRejectsWholeProtectedOrUnsupportedFootprints(GameTestHelper helper) {
        var config = config(SafeDeadwoodFeature.Kind.FALLEN, 6, 6, false);
        BlockPos origin = new BlockPos(8, 129, 8);
        var accepted = fixture(helper, ChunkPos.ZERO);
        helper.assertTrue(place(accepted, origin, 7, config), "Could not establish positive log footprint");
        BlockPos member = logs(accepted).keySet().stream().filter(pos -> !pos.equals(origin)).findFirst().orElseThrow();
        var deposit = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:hematite_deposit")).defaultBlockState();
        helper.assertTrue(!deposit.isAir(), "Native ore deposit fixture is missing");
        for (BlockState protectedState : List.of(Blocks.DIAMOND_ORE.defaultBlockState(), deposit,
                Blocks.STONE_BRICKS.defaultBlockState(), Blocks.CHEST.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState())) {
            for (BlockPos position : List.of(origin, member, member.below())) {
                var chunk = fixture(helper, ChunkPos.ZERO);
                chunk.setBlockState(position, protectedState, false);
                var before = snapshot(chunk);
                helper.assertTrue(!place(chunk, origin, 7, config), "Scenery accepted an obstructed/protected footprint");
                helper.assertTrue(before.equals(snapshot(chunk)), "Rejected scenery left a partial log");
            }
        }
        var unsupported = fixture(helper, ChunkPos.ZERO);
        unsupported.setBlockState(member.below(), Blocks.AIR.defaultBlockState(), false);
        helper.assertTrue(!place(unsupported, origin, 7, config) && logs(unsupported).isEmpty(),
                "Fallen log bridged unsupported ground");
        var district = fixture(helper, ChunkPos.ZERO);
        helper.assertTrue(!SafeDeadwoodFeature.placeChunk(district, origin, RandomSource.create(7), config,
                pos -> !pos.equals(member)) && logs(district).isEmpty(), "Whole footprint did not respect a protected district/structure");
        for (int x : List.of(0, 15)) for (int z : List.of(0, 15)) {
            int rejections = 0;
            for (int seed = 0; seed < 32; seed++) {
                var edge = fixture(helper, ChunkPos.ZERO);
                boolean placed = place(edge, new BlockPos(x, 129, z), seed, config);
                if (!placed) { rejections++; helper.assertTrue(logs(edge).isEmpty(), "Border rejection left fragments"); }
                for (BlockPos pos : logs(edge).keySet()) helper.assertTrue(new ChunkPos(pos).equals(ChunkPos.ZERO),
                        "Log escaped its owner at a chunk corner");
            }
            helper.assertTrue(rejections > 5, "Outward-pointing border logs were clipped instead of rejected");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void standingWoodIncludesStumpsAndForksWithAtomicClearance(GameTestHelper helper) {
        var config = config(SafeDeadwoodFeature.Kind.STANDING, 1, 7, true);
        Set<Integer> heights = new HashSet<>();
        boolean fork = false;
        for (int seed = 0; seed < 64; seed++) {
            var chunk = fixture(helper, ChunkPos.ZERO);
            BlockPos origin = new BlockPos(8, 129, 8);
            helper.assertTrue(place(chunk, origin, seed, config), "Clear supported standing wood rejected");
            var changes = logs(chunk);
            heights.add(changes.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow() - 128);
            fork |= changes.keySet().stream().anyMatch(pos -> pos.getX() != 8 || pos.getZ() != 8);
            for (BlockState state : changes.values()) helper.assertTrue(state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y,
                    "Standing stump has a horizontal axis");
        }
        helper.assertTrue(heights.contains(1) && heights.contains(7) && fork, "Missing short stumps, tall trunks or fork variation");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void loadedBurntTrunksKeepVisibleHeightOnSlopedRockAndAshSupports(GameTestHelper helper) {
        var config = loadedBurntConfig(helper);
        BlockState ashen = registeredBlock(helper, "kubejs:ashen_depleted_dirt");
        BlockState ashStone = registeredBlock(helper, "kubejs:ash_stone");
        BlockState darkAshStone = registeredBlock(helper, "kubejs:dark_ash_stone");
        int accepted = 0;
        for (BlockState support : List.of(Blocks.STONE.defaultBlockState(), Blocks.TUFF.defaultBlockState(), ashen, ashStone, darkAshStone)) {
            for (int groundY : List.of(128, 256)) {
                Set<Integer> heights = new HashSet<>();
                for (int seed = 0; seed < 24; seed++) {
                    var chunk = fixture(helper, ChunkPos.ZERO, groundY, support, true);
                    BlockPos base = new BlockPos(8, groundY + 1, 8);
                    helper.assertTrue(place(chunk, base, seed, config),
                            "Loaded burnt trunk rejected dry natural support or an elevated slope: " + support + " y=" + groundY);
                    var changes = wood(chunk, config.state(), groundY + 1, groundY + 9);
                    int height = changes.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow() - groundY;
                    helper.assertTrue(height >= 5 && height <= 7 && changes.containsKey(base),
                            "Loaded burnt trunk lost its visible 5–7 block height");
                    helper.assertTrue(changes.size() == height + 2 && chunk.getBlockState(base.below()) == support,
                            "Loaded burnt trunk lost its fork or changed its natural support");
                    for (var entry : changes.entrySet()) helper.assertTrue(
                            new ChunkPos(entry.getKey()).equals(ChunkPos.ZERO)
                                    && entry.getValue().getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y,
                            "Loaded burnt trunk escaped its owner or changed axis");
                    heights.add(height);
                    accepted++;
                }
                helper.assertTrue(heights.equals(Set.of(5, 6, 7)), "Loaded burnt trunks lost height variation");
            }
        }
        var blocked = fixture(helper, ChunkPos.ZERO, 128, ashStone, true);
        BlockPos base = new BlockPos(8, 129, 8);
        blocked.setBlockState(base.above(3), Blocks.STONE_BRICKS.defaultBlockState(), false);
        var before = snapshot(blocked);
        helper.assertTrue(!place(blocked, base, 7, config) && before.equals(snapshot(blocked)),
                "Accepting ash-stone support allowed a protected obstruction or left a partial burnt trunk");
        System.out.println("CWI_BURNT_SUPPORT_NATIVE=accepted=" + accepted
                + " supports=stone,tuff,ashen_depleted_dirt,ash_stone,dark_ash_stone heights=5,6,7 ground_y=128,256 sloped=true atomic=true");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void registeredBurntPlacementChainPlacesOnGeneratedSmolderingTerrain(GameTestHelper helper) {
        var config = loadedBurntConfig(helper);
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:natural_geometry_wasteland_test")));
        helper.assertTrue(level != null, "Missing current-generation wasteland test dimension");
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var state = level.getChunkSource().randomState();
        var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null && terrain.generationVersion() >= 5, "Burnt placement did not use world-space scenery");
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var placedId = ResourceLocation.parse("cwi:natures/burnt_log_place");
        var placed = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).get(placedId);
        helper.assertTrue(placed != null && placed.feature().unwrapKey().orElseThrow().location()
                        .equals(ResourceLocation.parse("cwi:natures/burnt_log")),
                "Registered burnt placement does not retain the public configured-feature path");
        var smoldering = biomes.get(ResourceLocation.parse("cwi:smoldering_barren"));
        helper.assertTrue(smoldering.getGenerationSettings().features().stream().anyMatch(stage -> stage.stream()
                        .anyMatch(holder -> holder.unwrapKey().orElseThrow().location().equals(placedId))),
                "Smoldering biome does not invoke its registered burnt placement");
        int tested = 0, candidates = 0, placedChunks = 0, trunks = 0;
        search: for (int tileZ = -8; tileZ <= 8; tileZ++) for (int tileX = -8; tileX <= 8; tileX++) {
            var raw = terrain.sampleRaw(tileX * 128 + 64, tileZ * 128 + 64);
            if (raw.height < .4F || raw.terrain.isSubmerged() || raw.terrain.isWetland()) continue;
            for (int dz = 0; dz < 8; dz++) for (int dx = 0; dx < 8; dx++) {
                ChunkPos owner = new ChunkPos(tileX * 8 + dx, tileZ * 8 + dz);
                var reader = terrain.prepareChunk(owner.x, owner.z);
                var center = reader.getCell(8, 8);
                int previewY = terrain.levels().scale(center.height);
                if (center.terrain.isSubmerged() || center.terrain.isWetland()
                        || SceneryClusters.richness(level.getSeed(), owner.getMiddleBlockX(), owner.getMiddleBlockZ()) <= 0
                        || !level.getUncachedNoiseBiome(owner.getMiddleBlockX() >> 2, previewY >> 2,
                                owner.getMiddleBlockZ() >> 2).is(ResourceLocation.parse("cwi:smoldering_barren"))) continue;
                var chunk = new ProtoChunk(owner, UpgradeData.EMPTY, level, biomes, null);
                complete(level, generator.createBiomes(state, Blender.empty(), level.structureManager(), chunk));
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                complete(level, generator.fillFromNoise(Blender.empty(), state, level.structureManager(), chunk));
                chunk.setPersistedStatus(ChunkStatus.NOISE);
                var region = new IsolatedSceneryRegion(level, chunk);
                generator.buildSurface(chunk, new WorldGenerationContext(generator, level), state,
                        level.structureManager(), region.getBiomeManager(), biomes, Blender.empty());
                chunk.setPersistedStatus(ChunkStatus.FEATURES);
                long placementSeed = 971L + tested;
                var context = new PlacementContext(region, generator, Optional.of(placed));
                var random = RandomSource.create(placementSeed);
                List<BlockPos> origins = List.of(owner.getWorldPosition());
                for (var modifier : placed.placement()) origins = origins.stream()
                        .flatMap(position -> modifier.getPositions(context, random, position)).toList();
                candidates += origins.size();
                boolean success = placed.placeWithBiomeCheck(region, generator, RandomSource.create(placementSeed), owner.getWorldPosition());
                int chunkTrunks = 0;
                for (BlockPos origin : origins) {
                    int height = 0;
                    while (height < 9 && chunk.getBlockState(origin.above(height)).is(config.state().getBlock())) height++;
                    // A nearby fork may occupy a candidate's column without that candidate placing a trunk.
                    if (height < 5) continue;
                    helper.assertTrue(height <= 7 && region.getBiome(origin).is(ResourceLocation.parse("cwi:smoldering_barren"))
                                    && chunk.getBlockState(origin.below()).isFaceSturdy(chunk, origin.below(), Direction.UP),
                            "Public burnt placement lost its height, Smoldering biome or natural ground support");
                    chunkTrunks++;
                }
                helper.assertTrue(success == (chunkTrunks > 0), "Public placement result disagrees with actual visible burnt trunks");
                trunks += chunkTrunks;
                placedChunks += success ? 1 : 0;
                tested++;
                if (placedChunks >= 2 || tested >= 64) break search;
            }
        }
        System.out.println("CWI_BURNT_PUBLIC_NATIVE=generated_chunks=" + tested + " candidates=" + candidates
                + " placed_chunks=" + placedChunks + " visible_trunks=" + trunks + " height=5..7");
        helper.assertTrue(placedChunks >= 2 && trunks >= 2,
                "Registered burnt placement produced no repeatable visible coverage on generated Smoldering terrain");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void currentStandingPlacementReachesFormerQuietDistrictsOnGeneratedGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:water_landscape_wasteland_test")));
        helper.assertTrue(level != null, "Missing current wasteland dimension");
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var state = level.getChunkSource().randomState();
        var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null && terrain.generationVersion() == 10,
                "Current standing-tree coverage fixture did not use profile10");
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        int generated = 0, candidates = 0, trunks = 0;
        Set<String> placedBiomes = new HashSet<>();
        for (String biomeName : List.of("forsaken_land", "smoldering_barren")) {
            boolean burnt = biomeName.equals("smoldering_barren");
            String configuredName = burnt ? "burnt_log" : "ash_log";
            long salt = burnt ? 612509 : 156791;
            var profile = new SceneryDistrictFilter.Profile(ResourceLocation.parse("cwi:" + biomeName),
                    burnt ? .28 : .48, 1, 2, 1, 2, burnt ? 2 : 3);
            var placed = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE)
                    .get(ResourceLocation.parse("cwi:natures/" + configuredName + "_place"));
            helper.assertTrue(placed != null && (burnt || placed.feature().value().config() instanceof SafeDeadwoodFeature.Config),
                    "Missing registered current standing-tree placement");
            var config = burnt ? loadedBurntConfig(helper) : (SafeDeadwoodFeature.Config) placed.feature().value().config();
            boolean found = false;
            search: for (int radius = 0; radius <= 64; radius++) for (int cz = -radius; cz <= radius; cz++)
                for (int cx = -radius; cx <= radius; cx++) {
                    if (Math.max(Math.abs(cx), Math.abs(cz)) != radius) continue;
                    ChunkPos owner = new ChunkPos(cx, cz);
                    int centerX = owner.getMiddleBlockX(), centerZ = owner.getMiddleBlockZ();
                    if (SceneryClusters.richness(level.getSeed(), centerX, centerZ) > 0
                            || SceneryDistrictFilter.candidates(level.getSeed(), salt, owner, profile, 10, true).isEmpty()) continue;
                    var center = terrain.sampleRaw(centerX, centerZ);
                    int previewY = terrain.levels().scale(center.height);
                    if (center.terrain.isSubmerged() || center.terrain.isWetland()
                            || !level.getUncachedNoiseBiome(centerX >> 2, previewY >> 2, centerZ >> 2)
                                    .is(ResourceLocation.parse("cwi:" + biomeName))) continue;
                    var chunk = new ProtoChunk(owner, UpgradeData.EMPTY, level, biomes, null);
                    complete(level, generator.createBiomes(state, Blender.empty(), level.structureManager(), chunk));
                    chunk.setPersistedStatus(ChunkStatus.BIOMES);
                    complete(level, generator.fillFromNoise(Blender.empty(), state, level.structureManager(), chunk));
                    chunk.setPersistedStatus(ChunkStatus.NOISE);
                    var region = new IsolatedSceneryRegion(level, chunk);
                    generator.buildSurface(chunk, new WorldGenerationContext(generator, level), state,
                            level.structureManager(), region.getBiomeManager(), biomes, Blender.empty());
                    chunk.setPersistedStatus(ChunkStatus.FEATURES);
                    var context = new PlacementContext(region, generator, Optional.of(placed));
                    var random = RandomSource.create(971L + generated);
                    List<BlockPos> origins = List.of(owner.getWorldPosition());
                    for (var modifier : placed.placement()) origins = origins.stream()
                            .flatMap(position -> modifier.getPositions(context, random, position)).toList();
                    candidates += origins.size();
                    boolean success = placed.placeWithBiomeCheck(region, generator,
                            RandomSource.create(971L + generated), owner.getWorldPosition());
                    generated++;
                    if (success) for (BlockPos origin : origins) {
                        int height = 0;
                        while (height < 9 && chunk.getBlockState(origin.above(height)).is(config.state().getBlock())) height++;
                        if (height < (burnt ? 5 : 3)) continue;
                        helper.assertTrue(height <= 7 && chunk.getBlockState(origin.below()).isFaceSturdy(chunk, origin.below(), Direction.UP),
                                "Current registered tree lost visible height or surface support");
                        trunks++; found = true;
                    }
                    if (found) { placedBiomes.add(biomeName); break search; }
                    helper.assertTrue(generated < 32, "Current quiet-district tree candidates repeatedly failed actual placement");
                }
            helper.assertTrue(found, "No registered standing tree survived in a formerly excluded " + biomeName + " district");
        }
        helper.assertTrue(placedBiomes.size() == 2 && trunks >= 2,
                "Current generation did not restore both surface biome tree families");
        System.out.println("CWI_QUIET_DISTRICT_TREE_NATIVE=generated_chunks:" + generated + ",candidates:" + candidates
                + ",trunks:" + trunks + ",biomes:" + placedBiomes + ",old_richness:zero");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void modernStandingAshKeepsVisibleTrunksWhileSavedStumpsRemainFrozen(GameTestHelper helper) {
        var configured = helper.getLevel().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceLocation.parse("cwi:natures/ash_log"));
        helper.assertTrue(configured != null && configured.config() instanceof SafeDeadwoodFeature.Config,
                "Missing loaded ash-log configuration");
        var config = (SafeDeadwoodFeature.Config) configured.config();
        Set<Integer> modernHeights = new HashSet<>(), savedHeights = new HashSet<>();
        for (int seed = 0; seed < 64; seed++) {
            for (int version : List.of(9, 10)) {
                var chunk = fixture(helper, ChunkPos.ZERO, 128, registeredBlock(helper, "kubejs:depleted_dirt"), true);
                BlockPos origin = new BlockPos(8, 129, 8);
                helper.assertTrue(SafeDeadwoodFeature.placeChunk(chunk, origin, RandomSource.create(seed), config,
                        position -> true, version), "Standing ash rejected supported sloping depleted soil");
                int height = 0;
                while (height < 9 && chunk.getBlockState(origin.above(height)).is(config.state().getBlock())) height++;
                (version == 10 ? modernHeights : savedHeights).add(height);
            }
        }
        helper.assertTrue(modernHeights.equals(Set.of(3, 4, 5, 6, 7)) && savedHeights.equals(Set.of(1, 2, 3, 4, 5, 6, 7)),
                "Modern visible trunks or frozen saved stumps lost their configured height distribution");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void surfaceHabitatCacheIsImmutableBoundedAndSeedLocal(GameTestHelper helper){
        var cell=new net.quiwh.cwiworldgen.terrain.reference.cell.Cell();
        cell.height=.5F;cell.gradient=.25F;cell.riverMask=.75F;
        try(var first=new net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain(42,12);
                var second=new net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain(43,12)){
            var expected=first.surfaceHabitat(-17,-16,cell);
            helper.assertTrue(expected.slope()==.25&&expected.elevation()==128&&expected.waterProximity()==.25,"Habitat does not use completed native cell");
            var refined=expected.withRockResistance(.9);
            helper.assertTrue(expected.rockResistance()==.5&&refined.rockResistance()==.9,"Actual support mutated cached habitat");
            boolean different=false;
            for(int x=-2048;x<=0;x++){
                var sample=first.surfaceHabitat(x,-16,cell);
                if(x>-32&&!sample.equals(second.surfaceHabitat(x,-16,cell)))different=true;
            }
            helper.assertTrue(first.cachedSurfaceHabitats()<=1024&&different,"Habitat cache is unbounded or shared across seeds");
            helper.assertTrue(expected.equals(first.surfaceHabitat(-17,-16,cell)),"Habitat eviction or traversal changed context");
        }
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void profile12HabitatPreservesStandingShapesAndFeatureRandomStream(GameTestHelper helper){
        var harsh=new net.quiwh.cwiworldgen.terrain.SurfaceHabitat(1,350,1,0,1);
        var config=config(SafeDeadwoodFeature.Kind.STANDING,1,7,true);
        for(int seed=0;seed<64;seed++){
            var before=fixture(helper,ChunkPos.ZERO);var after=fixture(helper,ChunkPos.ZERO);
            var oldRandom=RandomSource.create(seed);var newRandom=RandomSource.create(seed);
            BlockPos origin=new BlockPos(8,129,8);
            boolean oldPlaced=SafeDeadwoodFeature.placeChunk(before,origin,oldRandom,config,pos->true,11);
            boolean newPlaced=SafeDeadwoodFeature.placeChunk(after,origin,newRandom,config,pos->true,12,harsh,42);
            helper.assertTrue(oldPlaced==newPlaced&&snapshot(before).equals(snapshot(after)),"Surface habitat removed or reshaped standing tree");
            helper.assertTrue(oldRandom.nextLong()==newRandom.nextLong(),"Standing habitat changed feature RNG stream");
        }
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void leafShelterUsesOnlySupportedStandingTrunksWithinOwnerRadiusFour(GameTestHelper helper){
        var chunk=fixture(helper,ChunkPos.ZERO);
        BlockPos origin=new BlockPos(8,129,8);
        for(int y=129;y<=132;y++)chunk.setBlockState(new BlockPos(10,y,8),Blocks.OAK_LOG.defaultBlockState(),false);
        Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG,Heightmap.Types.OCEAN_FLOOR_WG));
        helper.assertTrue(SafeDeadwoodFeature.hasStandingTrunkNearby(chunk,origin),"Actually placed supported trunk did not shelter nearby leaves");
        helper.assertTrue(!SafeDeadwoodFeature.hasStandingTrunkNearby(chunk,new BlockPos(5,129,8)),"Leaf shelter exceeded radius4");
        for(int y=129;y<=132;y++)chunk.setBlockState(new BlockPos(10,y,8),Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS,Direction.Axis.X),false);
        helper.assertTrue(!SafeDeadwoodFeature.hasStandingTrunkNearby(chunk,origin),"Fallen wood was mistaken for standing trunk");
        for(int y=129;y<=132;y++)chunk.setBlockState(new BlockPos(10,y,8),Blocks.OAK_LOG.defaultBlockState(),false);
        chunk.setBlockState(new BlockPos(10,128,8),Blocks.AIR.defaultBlockState(),false);
        helper.assertTrue(!SafeDeadwoodFeature.hasStandingTrunkNearby(chunk,origin),"Unsupported wood was mistaken for successfully placed trunk");
        // An out-of-owner search cannot wrap a column into the opposite edge of this ProtoChunk.
        helper.assertTrue(!SafeDeadwoodFeature.hasStandingTrunkNearby(chunk,new BlockPos(-2,129,8)),"Leaf shelter read a wrapped neighboring chunk column");
        helper.succeed();
    }

    private static SafeDeadwoodFeature.Config loadedBurntConfig(GameTestHelper helper) {
        var configured = helper.getLevel().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceLocation.parse("cwi_worldgen:legacy/burnt_log"));
        helper.assertTrue(configured != null && configured.feature() instanceof SafeDeadwoodFeature
                        && configured.config() instanceof SafeDeadwoodFeature.Config,
                "Saved-profile burnt-log feature does not load its original safe deadwood configuration");
        var config = (SafeDeadwoodFeature.Config) configured.config();
        helper.assertTrue(config.state().is(registeredBlock(helper, "kubejs:burnt_log").getBlock())
                        && config.kind() == SafeDeadwoodFeature.Kind.STANDING && config.minSize() == 5
                        && config.maxSize() == 7 && config.fork(), "Loaded burnt-log configuration lost original visible height or fork");
        return config;
    }

    private static BlockState registeredBlock(GameTestHelper helper, String name) {
        BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(name)).defaultBlockState();
        helper.assertTrue(!state.isAir(), "Missing native block fixture " + name);
        return state;
    }

    private static Map<BlockPos, BlockState> wood(ProtoChunk chunk, BlockState material, int minY, int maxY) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = minY; y <= maxY; y++) {
            BlockPos pos = new BlockPos(chunk.getPos().getBlockX(x), y, chunk.getPos().getBlockZ(z));
            BlockState state = chunk.getBlockState(pos);
            if (state.is(material.getBlock())) result.put(pos, state);
        }
        return result;
    }

    private static void complete(ServerLevel level, CompletableFuture<?> future) {
        level.getServer().managedBlock(() -> {
            level.getChunkSource().pollTask();
            return future.isDone();
        });
        future.join();
    }

    private static final class IsolatedSceneryRegion extends WorldGenRegion {
        private final ServerLevel level;
        private final ChunkAccess chunk;

        IsolatedSceneryRegion(ServerLevel level, ChunkAccess chunk) {
            // ConfiguredFeature enters Feature.place, whose public adapter checks
            // ensureCanWrite against the actual generation step before placement.
            // Chunk reads remain isolated below, so the superclass cache is unused.
            super(level, null, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), chunk);
            this.level = level;
            this.chunk = chunk;
        }

        @Override public boolean hasChunk(int x, int z) { return x == chunk.getPos().x && z == chunk.getPos().z; }

        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }

        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            if (x == chunk.getPos().x && z == chunk.getPos().z) return chunk;
            if (required) throw new AssertionError("Burnt placement requested neighboring mutable chunk " + x + "," + z);
            return null;
        }

        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
            return level.getUncachedNoiseBiome(x, y, z);
        }
    }

    private static SafeDeadwoodFeature.Config config(SafeDeadwoodFeature.Kind kind, int min, int max, boolean fork) {
        return new SafeDeadwoodFeature.Config(Blocks.OAK_LOG.defaultBlockState(), kind, min, max, fork);
    }
    private static boolean place(ProtoChunk chunk, BlockPos origin, long seed, SafeDeadwoodFeature.Config config) {
        return SafeDeadwoodFeature.placeChunk(chunk, origin, RandomSource.create(seed), config, position -> true);
    }
    private static ProtoChunk fixture(GameTestHelper helper, ChunkPos owner) {
        return fixture(helper, owner, 128, Blocks.DIRT.defaultBlockState(), false);
    }
    private static ProtoChunk fixture(GameTestHelper helper, ChunkPos owner, int groundY, BlockState support, boolean sloped) {
        var chunk = new ProtoChunk(owner, UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int top = groundY + (sloped ? Math.floorDiv(x - 8, 3) : 0);
            for (int y = top - 4; y <= top; y++)
                chunk.setBlockState(new BlockPos(owner.getBlockX(x), y, owner.getBlockZ(z)), support, false);
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        return chunk;
    }
    private static Map<BlockPos, BlockState> snapshot(ProtoChunk chunk) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 127; y <= 138; y++) {
            var position = new BlockPos(chunk.getPos().getBlockX(x), y, chunk.getPos().getBlockZ(z));
            result.put(position, chunk.getBlockState(position));
        }
        return result;
    }
    private static Map<BlockPos, BlockState> logs(ProtoChunk chunk) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        snapshot(chunk).forEach((pos, state) -> { if (state.is(Blocks.OAK_LOG)) result.put(pos, state); });
        return result;
    }
    private NativeSceneryTest() {}
}
