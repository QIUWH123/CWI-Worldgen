package net.quiwh.cwiworldgen.feature;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

/** Real configured-feature dispatch on detached owner chunks, including translated burnt roots. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeDepletedDirtTreeTest {
    private static final int GROUND = 128;

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void publicStandingTreesRejectEveryNonDepletedSubstrateWithoutWrites(GameTestHelper helper) {
        var level = level(helper);
        var owner = dryOwner(helper, level);
        String[] rejected = {"minecraft:stone", "minecraft:granite", "minecraft:deepslate",
                "kubejs:schist", "kubejs:cobbled_granite", "kubejs:granite_gravel",
                "minecraft:gravel", "minecraft:dirt", "minecraft:coarse_dirt", "kubejs:ash_dirt"};
        int checks = 0;
        for (String family : new String[]{"ash_log", "burnt_log"}) {
            var configured = configured(helper, level, family);
            for (String id : rejected) for (int seed = 0; seed < 4; seed++) {
                var ground = block(helper, id);
                var chunk = fixture(level, owner, ground);
                var before = snapshot(chunk);
                boolean placed = configured.place(new TreeRegion(level, chunk, family),
                        level.getChunkSource().getGenerator(), RandomSource.create(seed), center(owner));
                helper.assertTrue(!placed && before.equals(snapshot(chunk)),
                        "Profile21 " + family + " planted on " + id + " or changed rejected native terrain");
                checks++;
            }
        }
        System.out.println("CWI_DEPLETED_TREE_REJECTION=public_families:2,substrates:10,seeds:4,atomic_checks:" + checks);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void depletedSoilsRetainOriginalShapesRngAndNativeGround(GameTestHelper helper) {
        var level = level(helper);
        var owner = dryOwner(helper, level);
        int accepted = 0;
        for (String family : new String[]{"ash_log", "burnt_log"}) {
            var configured = configured(helper, level, family);
            Block log = block(helper, "kubejs:" + family).getBlock();
            for (String soil : new String[]{"kubejs:depleted_dirt", "kubejs:ashen_depleted_dirt"})
                for (int seed = 0; seed < 16; seed++) {
                    var surface = block(helper, soil);
                    var actual = fixture(level, owner, surface);
                    var oracle = fixture(level, owner, surface);
                    var actualRandom = RandomSource.create(seed);
                    var oracleRandom = RandomSource.create(seed);
                    boolean placed = configured.place(new TreeRegion(level, actual, family),
                            level.getChunkSource().getGenerator(), actualRandom, center(owner));
                    boolean expected = family.equals("burnt_log")
                            ? Feature.TREE.place((TreeConfiguration) configured.config(), new TreeRegion(level, oracle, family),
                                    level.getChunkSource().getGenerator(), oracleRandom, center(owner))
                            : SafeDeadwoodFeature.placeChunk(oracle, center(owner), oracleRandom,
                                    (SafeDeadwoodFeature.Config) configured.config(), pos -> true, 20);
                    helper.assertTrue(placed && expected && !logs(actual, log).isEmpty()
                                    && logs(actual, log).equals(logs(oracle, log))
                                    && actualRandom.nextLong() == oracleRandom.nextLong(),
                            "Depleted-soil restriction changed accepted " + family + " shape or RNG on " + soil);
                    helper.assertTrue(actual.getBlockState(center(owner).below()).equals(surface),
                            "Tree replaced its native depleted soil");
                    accepted++;
                }
        }
        System.out.println("CWI_DEPLETED_TREE_ACCEPTANCE=public_families:2,depleted_soils:2,seeds:16,shape_rng_ground_checks:" + accepted);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void translatedBurntRootsRecheckActualDestinationSoilAtomically(GameTestHelper helper) {
        var level = level(helper);
        var owner = dryOwner(helper, level);
        var configured = configured(helper, level, "burnt_log");
        var tree = (TreeConfiguration) configured.config();
        var burnt = block(helper, "kubejs:burnt_log").getBlock();
        var soil = block(helper, "kubejs:depleted_dirt");
        int translated = 0;
        for (int seed = 0; seed < 32; seed++) {
            BlockPos base = new BlockPos((seed & 1) == 0 ? owner.getMinBlockX() : owner.getMaxBlockX(),
                    GROUND + 1, (seed & 2) == 0 ? owner.getMinBlockZ() : owner.getMaxBlockZ());
            var oracle = new NeighborRegion(level, owner, soil);
            var oracleRandom = RandomSource.create(seed);
            helper.assertTrue(Feature.TREE.place(tree, oracle, level.getChunkSource().getGenerator(), oracleRandom, base),
                    "Unbounded vanilla border oracle rejected depleted soil");
            var shape = oracle.logs(burnt);
            int minX = shape.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow();
            int maxX = shape.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow();
            int minZ = shape.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow();
            int maxZ = shape.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow();
            int dx = Math.max(0, owner.getMinBlockX() - minX) + Math.min(0, owner.getMaxBlockX() - maxX);
            int dz = Math.max(0, owner.getMinBlockZ() - minZ) + Math.min(0, owner.getMaxBlockZ() - maxZ);
            if (dx == 0 && dz == 0) continue;
            translated++;
            BlockPos shifted = base.offset(dx, 0, dz);
            var rejected = fixture(level, owner, Blocks.STONE.defaultBlockState());
            rejected.setBlockState(base.below(), soil, false);
            var before = snapshot(rejected);
            helper.assertTrue(!configured.place(new TreeRegion(level, rejected, "burnt_log"),
                            level.getChunkSource().getGenerator(), RandomSource.create(seed), base)
                            && before.equals(snapshot(rejected)),
                    "Burnt tree moved from depleted dirt onto a stone destination or left partial logs");
            var accepted = fixture(level, owner, Blocks.STONE.defaultBlockState());
            accepted.setBlockState(base.below(), soil, false);
            accepted.setBlockState(shifted.below(), soil, false);
            var actualRandom = RandomSource.create(seed);
            helper.assertTrue(configured.place(new TreeRegion(level, accepted, "burnt_log"),
                            level.getChunkSource().getGenerator(), actualRandom, base),
                    "Burnt tree rejected a depleted-soil destination");
            Map<BlockPos, BlockState> expected = new LinkedHashMap<>();
            shape.forEach((pos, state) -> expected.put(pos.offset(dx, 0, dz), state));
            helper.assertTrue(expected.equals(logs(accepted, burnt))
                            && actualRandom.nextLong() == oracleRandom.nextLong()
                            && accepted.getBlockState(shifted.below()).equals(soil),
                    "Border support check changed whole-tree translation, native soil or accepted RNG");
        }
        helper.assertTrue(translated > 0, "No crossing burnt fork was exercised");
        System.out.println("CWI_DEPLETED_TREE_BORDER=seeds:32,translated:" + translated + ",stone_destination:rejected,depleted_destination:accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void savedStandingProfilesAndOtherSceneryRetainRockSupport(GameTestHelper helper) {
        var level = level(helper);
        var owner = dryOwner(helper, level);
        var configured = configured(helper, level, "ash_log");
        var ash = (SafeDeadwoodFeature.Config) configured.config();
        for (int profile = 1; profile <= 20; profile++) {
            var chunk = fixture(level, owner, Blocks.STONE.defaultBlockState());
            helper.assertTrue(SafeDeadwoodFeature.placeChunk(chunk, center(owner), RandomSource.create(42), ash,
                            pos -> true, profile), "Saved standing profile " + profile + " lost its rock-support rule");
        }
        var burnt = (TreeConfiguration) configured(helper, level, "burnt_log").config();
        var oldBurnt = fixture(level, owner, Blocks.STONE.defaultBlockState());
        helper.assertTrue(OriginalBurntTreePlacement.placeChecked(new TreeRegion(level, oldBurnt, "burnt_log"),
                        level.getChunkSource().getGenerator(), oldBurnt, RandomSource.create(42), center(owner), burnt, pos -> true),
                "Saved original burnt-tree path lost native rock support");
        for (var kind : new SafeDeadwoodFeature.Kind[]{SafeDeadwoodFeature.Kind.FALLEN, SafeDeadwoodFeature.Kind.COVER}) {
            var chunk = fixture(level, owner, Blocks.STONE.defaultBlockState());
            var config = new SafeDeadwoodFeature.Config(Blocks.OAK_LOG.defaultBlockState(), kind, 1, 1, false);
            helper.assertTrue(SafeDeadwoodFeature.placeChunk(chunk, center(owner), RandomSource.create(42), config,
                            pos -> true, 21), "Standing-tree soil restriction leaked into " + kind);
        }
        System.out.println("CWI_DEPLETED_TREE_SAVED=saved_ash_profiles:1-20,saved_burnt:retained,fallen_cover:retained");
        helper.succeed();
    }

    private static ServerLevel level(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        helper.assertTrue(level != null, "Missing isolated profile21 test dimension");
        var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null && terrain.generationVersion() == 21,
                "Public tree dispatch requires the actual profile21 production dimension");
        return level;
    }

    private static ChunkPos dryOwner(GameTestHelper helper, ServerLevel level) {
        var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
        for (int attempt = 0; attempt < 32; attempt++) {
            var owner = new ChunkPos(attempt * 8, 64);
            var reader = terrain.prepareChunk(owner.x, owner.z);
            boolean dry = true;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                var cell = reader.getCell(x, z);
                dry &= cell.terrain != null && !cell.terrain.isRiver() && !cell.terrain.isLake()
                        && !cell.terrain.isWetland() && !cell.terrain.isSubmerged()
                        && !cell.terrain.isDeepOcean() && !cell.terrain.isShallowOcean();
            }
            if (dry) return owner;
        }
        throw new AssertionError("No bounded dry tree-dispatch fixture within 32 owner chunks");
    }

    private static ConfiguredFeature<?, ?> configured(GameTestHelper helper, ServerLevel level, String family) {
        var configured = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceLocation.parse("cwi:natures/" + family));
        helper.assertTrue(configured != null, "Missing actual configured " + family);
        return configured;
    }

    private static BlockState block(GameTestHelper helper, String name) {
        var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(name)).defaultBlockState();
        helper.assertTrue(!state.isAir(), "Missing native substrate/material " + name);
        return state;
    }

    private static BlockPos center(ChunkPos owner) {
        return new BlockPos(owner.getMiddleBlockX(), GROUND + 1, owner.getMiddleBlockZ());
    }

    private static ProtoChunk fixture(ServerLevel level, ChunkPos owner, BlockState surface) {
        var chunk = new ProtoChunk(owner, UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = GROUND - 3; y <= GROUND; y++)
            chunk.setBlockState(new BlockPos(owner.getBlockX(x), y, owner.getBlockZ(z)),
                    y == GROUND - 3 ? Blocks.STONE.defaultBlockState() : surface, false);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        return chunk;
    }

    private static Map<BlockPos, BlockState> snapshot(ChunkAccess chunk) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        var owner = chunk.getPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = GROUND - 3; y <= GROUND + 12; y++) {
            var pos = new BlockPos(owner.getBlockX(x), y, owner.getBlockZ(z));
            result.put(pos, chunk.getBlockState(pos));
        }
        return result;
    }

    private static Map<BlockPos, BlockState> logs(ChunkAccess chunk, Block log) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        snapshot(chunk).forEach((pos, state) -> { if (state.is(log)) result.put(pos, state); });
        return result;
    }

    private static class TreeRegion extends WorldGenRegion {
        protected final ChunkAccess chunk;
        private final Holder<Biome> biome;
        TreeRegion(ServerLevel level, ChunkAccess chunk, String family) {
            super(level, null, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), chunk);
            this.chunk = chunk;
            biome = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(ResourceKey.create(
                    Registries.BIOME, ResourceLocation.parse(family.equals("burnt_log") ? "cwi:smoldering_barren" : "cwi:forsaken_land")));
        }
        @Override public Holder<Biome> getBiome(BlockPos pos) { return biome; }
        @Override public boolean hasChunk(int x, int z) { return chunk.getPos().equals(new ChunkPos(x, z)); }
        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            if (hasChunk(x, z)) return chunk;
            if (required) throw new AssertionError("Tree escaped its owning chunk");
            return null;
        }
    }

    private static final class NeighborRegion extends TreeRegion {
        private final Map<Long, ChunkAccess> chunks = new LinkedHashMap<>();
        NeighborRegion(ServerLevel level, ChunkPos owner, BlockState soil) {
            super(level, fixture(level, owner, soil), "burnt_log");
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                var candidate = fixture(level, new ChunkPos(owner.x + dx, owner.z + dz), soil);
                chunks.put(candidate.getPos().toLong(), candidate);
            }
        }
        @Override public boolean hasChunk(int x, int z) { return chunks.containsKey(ChunkPos.asLong(x, z)); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            var found = chunks.get(ChunkPos.asLong(x, z));
            if (found == null && required) throw new AssertionError("Vanilla tree oracle exceeded its 3x3 fixture");
            return found;
        }
        Map<BlockPos, BlockState> logs(Block log) {
            Map<BlockPos, BlockState> result = new LinkedHashMap<>();
            chunks.values().forEach(part -> result.putAll(NativeDepletedDirtTreeTest.logs(part, log)));
            return result;
        }
    }
}
