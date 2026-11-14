package net.quiwh.cwiworldgen.feature;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BlobFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.ForkingTrunkPlacer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

/** Catches replacing the author's public vanilla tree with a same-height custom fork. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeOriginalBurntTreeTest {
    @GameTest(template = "empty")
    public static void publicBurntFeatureUsesOriginalVanillaTrunkAndAirFoliage(GameTestHelper helper) {
        var id = ResourceLocation.parse("cwi:natures/burnt_log");
        var configured = helper.getLevel().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).get(id);
        helper.assertTrue(configured != null && configured.feature() == Feature.TREE
                        && configured.config() instanceof TreeConfiguration,
                "Public burnt_log must load the author's minecraft:tree, not a synthetic deadwood fork");
        var tree = (TreeConfiguration) configured.config();
        helper.assertTrue(tree.trunkPlacer instanceof ForkingTrunkPlacer
                        && tree.foliagePlacer instanceof BlobFoliagePlacer,
                "Original burnt tree lost vanilla forking trunk or blob air-foliage placer");
        helper.assertTrue(tree.decorators.isEmpty() && tree.rootPlacer.isEmpty() && tree.ignoreVines && !tree.forceDirt,
                "Original burnt tree changed decorators, roots or dirt/vine options");
        var origin = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        for (int seed = 0; seed < 32; seed++) {
            var random = RandomSource.create(seed);
            var expected = RandomSource.create(seed);
            int height = 5 + expected.nextInt(3) + expected.nextInt(1);
            helper.assertTrue(tree.trunkPlacer.getTreeHeight(random) == height
                            && random.nextLong() == expected.nextLong(),
                    "Original forking tree height or RNG contract changed");
            helper.assertTrue(tree.foliageProvider.getState(random, origin).is(Blocks.AIR)
                            && tree.foliagePlacer.foliageHeight(random, height, tree) == 3
                            && tree.foliagePlacer.foliageRadius(random, height - 3) == 2,
                    "Original burnt tree must retain radius2/height3 air foliage, without invented leaves");
            helper.assertTrue(BuiltInRegistries.BLOCK.getKey(tree.trunkProvider.getState(random, origin).getBlock())
                            .equals(ResourceLocation.parse("kubejs:burnt_log"))
                            && BuiltInRegistries.BLOCK.getKey(tree.dirtProvider.getState(random, origin).getBlock())
                            .equals(ResourceLocation.parse("kubejs:depleted_dirt")),
                    "Original burnt tree material providers changed");
        }
        var placed = helper.getLevel().registryAccess().registryOrThrow(Registries.PLACED_FEATURE)
                .get(ResourceLocation.parse("cwi:natures/burnt_log_place"));
        helper.assertTrue(placed != null && placed.feature().value() == configured,
                "Public burnt placement bypasses the restored original configured feature");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void originalTreeProducesBentVariableForksRatherThanFixedParallelProngs(GameTestHelper helper) {
        var configured = helper.getLevel().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceLocation.parse("cwi:natures/burnt_log"));
        helper.assertTrue(configured != null && configured.feature() == Feature.TREE,
                "Cannot inspect original tree: public feature is still synthetic deadwood");
        var tree = (TreeConfiguration) configured.config();
        var log = tree.trunkProvider.getState(RandomSource.create(0), BlockPos.ZERO).getBlock();
        int bent = 0, longBranches = 0;
        Set<Set<BlockPos>> shapes = new HashSet<>();
        for (int seed = 0; seed < 32; seed++) {
            var chunk = new ProtoChunk(ChunkPos.ZERO, UpgradeData.EMPTY, helper.getLevel(),
                    helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                for (int y = 125; y <= 128; y++) chunk.setBlockState(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), false);
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
            var region = new TreeFixtureRegion(helper.getLevel(), chunk);
            helper.assertTrue(Feature.TREE.place(tree, region, helper.getLevel().getChunkSource().getGenerator(),
                    RandomSource.create(seed), new BlockPos(8, 129, 8)), "Original vanilla tree rejected clear supported fixture");
            Set<BlockPos> shape = new HashSet<>();
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 129; y <= 138; y++) {
                var p = new BlockPos(x, y, z);
                if (chunk.getBlockState(p).is(log)) shape.add(p.offset(-8, -129, -8));
            }
            int maxY = shape.stream().mapToInt(BlockPos::getY).max().orElseThrow();
            helper.assertTrue(maxY >= 4 && maxY <= 6 && shape.contains(BlockPos.ZERO), "Original tree lost supported 5-7-block height");
            if (!shape.contains(new BlockPos(0, maxY, 0))) bent++;
            if (shape.stream().anyMatch(p -> Math.abs(p.getX()) + Math.abs(p.getZ()) >= 2)) longBranches++;
            shapes.add(shape);
        }
        helper.assertTrue(bent > 0 && longBranches > 0 && shapes.size() >= 16,
                "Burnt trees reverted to uniform straight trunks with fixed two-block side prongs");
        System.out.println("CWI_ORIGINAL_BURNT_SHAPES=seeds:32,unique:" + shapes.size() + ",bent:" + bent + ",long_branches:" + longBranches);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void atomicRestorationMatchesVanillaShapeAndRngAndRejectsWholeObstructedTrees(GameTestHelper helper) {
        var configured = helper.getLevel().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceLocation.parse("cwi:natures/burnt_log"));
        helper.assertTrue(configured != null && configured.feature() == Feature.TREE,
                "Original tree is missing from public configuration");
        var config = (TreeConfiguration) configured.config();
        var generator = helper.getLevel().getChunkSource().getGenerator();
        BlockPos base = new BlockPos(8, 129, 8);
        var burnt = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:burnt_log"));
        int rejectedBranches = 0;
        for (int seed = 0; seed < 32; seed++) {
            var original = fixture(helper, Blocks.STONE.defaultBlockState());
            var restored = fixture(helper, Blocks.STONE.defaultBlockState());
            var originalRandom = RandomSource.create(seed); var restoredRandom = RandomSource.create(seed);
            helper.assertTrue(Feature.TREE.place(config, new TreeFixtureRegion(helper.getLevel(), original), generator,
                            originalRandom, base), "Original tree fixture failed");
            helper.assertTrue(OriginalBurntTreePlacement.placeChecked(helper.getLevel(), generator, restored,
                            restoredRandom, base, config, position -> true), "Safe restoration rejected complete original tree");
            Map<BlockPos, BlockState> originalLogs = new LinkedHashMap<>(), restoredLogs = new LinkedHashMap<>();
            snapshot(original).forEach((pos, state) -> { if (state.is(burnt)) originalLogs.put(pos, state); });
            snapshot(restored).forEach((pos, state) -> { if (state.is(burnt)) restoredLogs.put(pos, state); });
            helper.assertTrue(originalLogs.equals(restoredLogs) && originalRandom.nextLong() == restoredRandom.nextLong(),
                    "Recorder changed actual vanilla trunk geometry or foliage/trunk RNG sequence");
            helper.assertTrue(restored.getBlockState(base.below()).is(Blocks.STONE), "Restoration replaced native rock support with dirt");
            var branch = originalLogs.keySet().stream().filter(p -> p.getX() != base.getX() || p.getZ() != base.getZ()).findFirst();
            if (branch.isPresent()) {
                var blocked = fixture(helper, Blocks.STONE.defaultBlockState());
                blocked.setBlockState(branch.get(), Blocks.STONE_BRICKS.defaultBlockState(), false);
                var before = snapshot(blocked);
                helper.assertTrue(!OriginalBurntTreePlacement.placeChecked(helper.getLevel(), generator, blocked,
                                RandomSource.create(seed), base, config, position -> true) && before.equals(snapshot(blocked)),
                        "Blocked vanilla branch was silently clipped or left a partial tree");
                rejectedBranches++;
            }
        }
        helper.assertTrue(rejectedBranches > 0, "No original branch obstruction was exercised");
        for (String name : new String[]{"kubejs:depleted_dirt", "kubejs:ashen_depleted_dirt", "kubejs:ash_dirt",
                "kubejs:ash_stone", "kubejs:dark_ash_stone", "kubejs:granite_gravel", "kubejs:cobbled_granite"}) {
            BlockState support = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(name)).defaultBlockState();
            helper.assertTrue(!support.isAir(), "Missing native support fixture " + name);
            for (int seed = 0; seed < 8; seed++) {
                var original = fixture(helper, support); var restored = fixture(helper, support);
                var vanillaRandom = RandomSource.create(seed); var restoredRandom = RandomSource.create(seed);
                helper.assertTrue(Feature.TREE.place(config, new TreeFixtureRegion(helper.getLevel(), original), generator,
                                vanillaRandom, base), "Vanilla original tree rejected native support " + name);
                helper.assertTrue(OriginalBurntTreePlacement.placeChecked(helper.getLevel(), generator, restored,
                                restoredRandom, base, config, position -> true), "Atomic original tree rejected stable native support " + name);
                for (var entry : snapshot(original).entrySet()) if (entry.getKey().getY() >= base.getY())
                    helper.assertTrue(entry.getValue().equals(restored.getBlockState(entry.getKey())),
                            "Restoration changed original tree over native support " + name);
                helper.assertTrue(vanillaRandom.nextLong() == restoredRandom.nextLong()
                                && restored.getBlockState(base.below()).equals(support),
                        "Native support changed tree RNG or was replaced by the dirt provider: " + name);
            }
        }
        var protectedChunk = fixture(helper, Blocks.STONE.defaultBlockState()); var before = snapshot(protectedChunk);
        helper.assertTrue(!OriginalBurntTreePlacement.placeChecked(helper.getLevel(), generator, protectedChunk,
                        RandomSource.create(42), base, config, position -> false) && before.equals(snapshot(protectedChunk)),
                "Protected terrain accepted or partially placed original tree");
        var hollow = fixture(helper, Blocks.GRAVEL.defaultBlockState());
        hollow.setBlockState(base.below(3), Blocks.AIR.defaultBlockState(), false); before = snapshot(hollow);
        helper.assertTrue(!OriginalBurntTreePlacement.placeChecked(helper.getLevel(), generator, hollow,
                        RandomSource.create(42), base, config, position -> true) && before.equals(snapshot(hollow)),
                "Original tree accepted unstable falling support");
        var flooded = fixture(helper, Blocks.STONE.defaultBlockState());
        flooded.setBlockState(base, Blocks.WATER.defaultBlockState(), false);
        Heightmap.primeHeightmaps(flooded, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
        before = snapshot(flooded);
        helper.assertTrue(!OriginalBurntTreePlacement.placeChecked(helper.getLevel(), generator, flooded,
                        RandomSource.create(42), base, config, position -> true) && before.equals(snapshot(flooded)),
                "Original tree accepted submerged support");
        System.out.println("CWI_ORIGINAL_BURNT_PARITY=seeds:32,shape_and_rng:exact,ground:preserved,blocked_branches:"
                + rejectedBranches);
        helper.succeed();
    }

    private static ProtoChunk fixture(GameTestHelper helper, BlockState surface) {
        var chunk = new ProtoChunk(ChunkPos.ZERO, UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 125; y <= 128; y++)
            chunk.setBlockState(new BlockPos(x, y, z), y == 125 ? Blocks.STONE.defaultBlockState() : surface, false);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
        return chunk;
    }
    private static Map<BlockPos, BlockState> snapshot(ChunkAccess chunk) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 125; y <= 140; y++) {
            var pos = new BlockPos(x, y, z); result.put(pos, chunk.getBlockState(pos));
        }
        return result;
    }

    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void publicConfiguredFeatureDispatchPreservesLegacyAndRunsOriginalOnlyForProfile13(GameTestHelper helper) {
        String[] dimensions = {"wasteland_legacy_test", "coordinated_wasteland_test", "scenery_shapes_wasteland_test"};
        int[] versions = {1, 12, 13};
        for (int index = 0; index < dimensions.length; index++) {
            var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse("cwi_worldgen:" + dimensions[index])));
            helper.assertTrue(level != null, "Missing burnt dispatch fixture " + dimensions[index]);
            var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
            helper.assertTrue(terrain != null && terrain.generationVersion() == versions[index], "Wrong saved/current dispatch profile");
            ChunkPos owner = null;
            for (int attempt = 0; attempt < 32; attempt++) {
                var candidate = new ChunkPos(attempt * 8, 64);
                var reader = terrain.prepareChunk(candidate.x, candidate.z);
                boolean dry = true;
                for (int x = 4; x <= 12; x++) for (int z = 4; z <= 12; z++) {
                    var cell = reader.getCell(x, z);
                    dry &= cell.terrain != null && !cell.terrain.isRiver() && !cell.terrain.isLake()
                            && !cell.terrain.isWetland() && !cell.terrain.isSubmerged()
                            && !cell.terrain.isDeepOcean() && !cell.terrain.isShallowOcean();
                }
                if (dry) { owner = candidate; break; }
            }
            helper.assertTrue(owner != null, "No bounded dry dispatch fixture");
            var registry = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
            var configured = registry.get(ResourceLocation.parse("cwi:natures/burnt_log"));
            var legacy = registry.get(ResourceLocation.parse("cwi_worldgen:legacy/burnt_log"));
            for (int seed = 0; seed < 8; seed++) {
                var actual = dispatchFixture(level, owner); var expected = dispatchFixture(level, owner);
                var actualRegion = new DispatchRegion(level, actual); var expectedRegion = new DispatchRegion(level, expected);
                var actualRandom = RandomSource.create(seed); var expectedRandom = RandomSource.create(seed);
                BlockPos base = new BlockPos(owner.getMiddleBlockX(), 129, owner.getMiddleBlockZ());
                boolean placed = configured.place(actualRegion, level.getChunkSource().getGenerator(), actualRandom, base);
                boolean wanted = versions[index] < 13
                        ? legacy.place(expectedRegion, level.getChunkSource().getGenerator(), expectedRandom, base)
                        : Feature.TREE.place((TreeConfiguration) configured.config(), expectedRegion,
                                level.getChunkSource().getGenerator(), expectedRandom, base);
                helper.assertTrue(placed && wanted, "Public configured burnt feature rejected clear native dispatch fixture");
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 129; y <= 140; y++) {
                    var pos = new BlockPos(owner.getBlockX(x), y, owner.getBlockZ(z));
                    helper.assertTrue(actual.getBlockState(pos).equals(expected.getBlockState(pos)),
                            "Public dispatch changed saved or original tree blocks in profile " + versions[index]);
                }
                helper.assertTrue(actual.getBlockState(base.below()).is(Blocks.STONE)
                                && actualRandom.nextLong() == expectedRandom.nextLong(),
                        "Public dispatch changed native support or saved/original RNG in profile " + versions[index]);
            }
        }
        System.out.println("CWI_PUBLIC_BURNT_DISPATCH=profiles:1,12,13,seeds_each:8,blocks_rng_support:PASS");
        helper.succeed();
    }

    private static ProtoChunk dispatchFixture(ServerLevel level, ChunkPos owner) {
        var chunk = new ProtoChunk(owner, UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 125; y <= 128; y++)
            chunk.setBlockState(new BlockPos(owner.getBlockX(x), y, owner.getBlockZ(z)), Blocks.STONE.defaultBlockState(), false);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        return chunk;
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void originalBorderForksTranslateWholeShapeIntoOwnerWithExactRngAndAtomicRejection(GameTestHelper helper) {
        var configured = helper.getLevel().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceLocation.parse("cwi:natures/burnt_log"));
        var config = (TreeConfiguration) configured.config();
        var generator = helper.getLevel().getChunkSource().getGenerator();
        var burnt = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:burnt_log"));
        int translated = 0;
        for (int seed = 0; seed < 32; seed++) {
            BlockPos base = new BlockPos((seed & 1) == 0 ? 0 : 15, 129, (seed & 2) == 0 ? 0 : 15);
            // Only the vanilla oracle has neighbors. Production receives a strict owner-only region.
            var vanilla = new NeighborTreeRegion(helper.getLevel());
            var vanillaRandom = RandomSource.create(seed); var restoredRandom = RandomSource.create(seed);
            helper.assertTrue(Feature.TREE.place(config, vanilla, generator, vanillaRandom, base), "Vanilla border fixture failed");
            var original = vanilla.logs(burnt);
            int minX = original.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow();
            int maxX = original.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow();
            int minZ = original.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow();
            int maxZ = original.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow();
            int dx = Math.max(0, -minX) + Math.min(0, 15 - maxX);
            int dz = Math.max(0, -minZ) + Math.min(0, 15 - maxZ);
            Map<BlockPos, BlockState> expected = new LinkedHashMap<>();
            original.forEach((pos, state) -> expected.put(pos.offset(dx, 0, dz), state));
            BlockPos shiftedBase = base.offset(dx, 0, dz);
            var restored = fixture(helper, Blocks.STONE.defaultBlockState());
            helper.assertTrue(OriginalBurntTreePlacement.placeChecked(new TreeFixtureRegion(helper.getLevel(), restored), generator,
                            restored, restoredRandom, base, config, position -> true),
                    "Complete vanilla fork was rejected solely because its source root is at an owner edge");
            Map<BlockPos, BlockState> actual = new LinkedHashMap<>();
            snapshot(restored).forEach((pos, state) -> { if (state.is(burnt)) actual.put(pos, state); });
            long expectedNext = vanillaRandom.nextLong();
            helper.assertTrue(expected.equals(actual) && expectedNext == restoredRandom.nextLong()
                            && actual.containsKey(shiftedBase)
                            && actual.keySet().stream().allMatch(pos -> new ChunkPos(pos).equals(ChunkPos.ZERO)),
                    "Owner-contained restoration clipped, deformed or rerolled the original tree");
            helper.assertTrue(restored.getBlockState(shiftedBase.below()).is(Blocks.STONE), "Translation replaced native support");
            if (dx == 0 && dz == 0) continue;
            translated++;
            for (BlockState obstacle : new BlockState[]{Blocks.STONE_BRICKS.defaultBlockState(), Blocks.WATER.defaultBlockState()}) {
                var blocked = fixture(helper, Blocks.STONE.defaultBlockState());
                // Keep the terrain heightmap: this is an occupied target, not a new supported surface.
                blocked.setBlockState(shiftedBase, obstacle, false);
                var before = snapshot(blocked); var blockedRandom = RandomSource.create(seed);
                helper.assertTrue(!OriginalBurntTreePlacement.placeChecked(new TreeFixtureRegion(helper.getLevel(), blocked), generator,
                                blocked, blockedRandom, base, config, position -> true) && before.equals(snapshot(blocked))
                                && blockedRandom.nextLong() == expectedNext,
                        "Blocked translated root changed RNG, retried another site or left partial logs");
            }
            var protectedChunk = fixture(helper, Blocks.STONE.defaultBlockState());
            var protectedBefore = snapshot(protectedChunk);
            helper.assertTrue(!OriginalBurntTreePlacement.placeChecked(new TreeFixtureRegion(helper.getLevel(), protectedChunk), generator,
                            protectedChunk, RandomSource.create(seed), base, config, position -> !position.equals(shiftedBase))
                            && protectedBefore.equals(snapshot(protectedChunk)),
                    "Protected translated root was ignored or left a partial tree");
            var raised = fixture(helper, Blocks.STONE.defaultBlockState());
            raised.setBlockState(shiftedBase, Blocks.STONE.defaultBlockState(), false);
            raised.setBlockState(shiftedBase.above(), Blocks.STONE.defaultBlockState(), false);
            Heightmap.primeHeightmaps(raised, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
            var raisedRandom = RandomSource.create(seed);
            helper.assertTrue(OriginalBurntTreePlacement.placeChecked(new TreeFixtureRegion(helper.getLevel(), raised), generator,
                            raised, raisedRandom, base, config, position -> true), "Translated tree did not anchor on actual raised support");
            Map<BlockPos, BlockState> raisedExpected = new LinkedHashMap<>(), raisedActual = new LinkedHashMap<>();
            expected.forEach((pos, state) -> raisedExpected.put(pos.above(2), state));
            snapshot(raised).forEach((pos, state) -> { if (state.is(burnt)) raisedActual.put(pos, state); });
            helper.assertTrue(raisedExpected.equals(raisedActual) && raisedRandom.nextLong() == expectedNext
                            && raised.getBlockState(shiftedBase.above()).is(Blocks.STONE),
                    "Raised support changed relative vanilla shape, RNG or native ground");
        }
        helper.assertTrue(translated > 0, "No crossing vanilla fork was exercised");
        System.out.println("CWI_BURNT_BORDER_ROOTS=seeds:32,translated_trees:" + translated
                + ",relative_shape_rng:exact,owner_only:true,blocked_root:atomic,actual_support:anchored");
        helper.succeed();
    }

    private static final class NeighborTreeRegion extends WorldGenRegion {
        private final Map<Long, ChunkAccess> chunks = new LinkedHashMap<>();
        NeighborTreeRegion(ServerLevel level) {
            super(level, null, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), dispatchFixture(level, ChunkPos.ZERO));
            for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                var chunk = dispatchFixture(level, new ChunkPos(x, z)); chunks.put(chunk.getPos().toLong(), chunk);
            }
        }
        @Override public boolean hasChunk(int x, int z) { return chunks.containsKey(ChunkPos.asLong(x, z)); }
        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            var chunk = chunks.get(ChunkPos.asLong(x, z));
            if (chunk == null && required) throw new AssertionError("Vanilla oracle exceeded its 3 x 3 fixture");
            return chunk;
        }
        Map<BlockPos, BlockState> logs(net.minecraft.world.level.block.Block burnt) {
            Map<BlockPos, BlockState> result = new LinkedHashMap<>();
            for (var chunk : chunks.values()) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = 129; y <= 140; y++) {
                var pos = new BlockPos(chunk.getPos().getBlockX(x), y, chunk.getPos().getBlockZ(z));
                var state = chunk.getBlockState(pos); if (state.is(burnt)) result.put(pos, state);
            }
            return result;
        }
    }

    private static final class DispatchRegion extends TreeFixtureRegion {
        private final Holder<Biome> biome;
        DispatchRegion(ServerLevel level, ChunkAccess chunk) {
            super(level, chunk);
            biome = level.registryAccess().registryOrThrow(Registries.BIOME)
                    .getHolderOrThrow(ResourceKey.create(Registries.BIOME, ResourceLocation.parse("cwi:smoldering_barren")));
        }
        @Override public Holder<Biome> getBiome(BlockPos pos) { return biome; }
    }

    private static class TreeFixtureRegion extends WorldGenRegion {
        private final ChunkAccess chunk;
        TreeFixtureRegion(ServerLevel level, ChunkAccess chunk) {
            super(level, null, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), chunk);
            this.chunk = chunk;
        }
        @Override public boolean hasChunk(int x, int z) { return chunk.getPos().equals(new ChunkPos(x, z)); }
        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            if (hasChunk(x, z)) return chunk;
            if (required) throw new AssertionError("Original-tree fixture escaped its owner");
            return null;
        }
    }
}
