package net.quiwh.cwiworldgen.feature;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedBlockStateProvider;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

/** Actual bed blocks after eligibility clipping, rather than only a nominal noise mask. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeFineSedimentTest {
    private static final int WATER_TOP = 64;

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void tinyFineComponentsAreRemovedIncludingDiagonalAndVerticalStacks(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new ChunkPos(-1, -1), false);
        fine(fixture, 2, 2, Blocks.SAND.defaultBlockState(), 1);
        fine(fixture, 4, 4, Blocks.CLAY.defaultBlockState(), 5);
        // Diagonal contact does not create a continuous horizontal lens.
        fine(fixture, 7, 7, Blocks.SAND.defaultBlockState(), 2);
        fine(fixture, 8, 8, Blocks.SAND.defaultBlockState(), 2);
        fine(fixture, 11, 3, Blocks.CLAY.defaultBlockState(), 2);
        fine(fixture, 12, 3, Blocks.CLAY.defaultBlockState(), 2);
        fine(fixture, 13, 3, Blocks.CLAY.defaultBlockState(), 2);
        prime(fixture.chunk);
        var before = snapshot(fixture);
        int[] floor = heights(fixture, Heightmap.Types.OCEAN_FLOOR_WG);
        int[] surface = heights(fixture, Heightmap.Types.WORLD_SURFACE_WG);
        helper.assertTrue(WaterbedSedimentFeature.cleanupChunk(fixture.chunk, eligible(true, .35)),
                "Tiny exposed fine-sediment fragments received no correction");
        for (var entry : before.entrySet()) {
            BlockState actual = fixture.chunk.getBlockState(entry.getKey());
            if (!fine(entry.getValue())) {
                helper.assertTrue(actual == entry.getValue(), "Fine cleanup changed water, host rock or unrelated soil");
                continue;
            }
            helper.assertTrue(!fine(actual) && actual.isFaceSturdy(fixture.chunk, entry.getKey(), Direction.UP),
                    "One-column, diagonal or three-column fine-sediment fragments survived");
            helper.assertTrue(!fixture.chunk.getBlockState(entry.getKey().below()).isAir(),
                    "Removing a small fine lens introduced unsupported falling material");
        }
        assertHeights(helper, fixture, floor, surface);
        var settled = snapshot(fixture);
        helper.assertTrue(!WaterbedSedimentFeature.cleanupChunk(fixture.chunk, eligible(true, .35))
                        && snapshot(fixture).equals(settled), "Fine cleanup is not idempotent");
        System.out.println("CWI_FINE_FRAGMENTS=single:true,vertical_stack:true,diagonal:true,three_columns:true,water_unchanged:true");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void coherentFineLensesSurviveOnFlatAndSlopingBeds(GameTestHelper helper) {
        for (boolean sloped : new boolean[]{false, true}) {
            Fixture fixture = fixture(helper, new ChunkPos(0, -1), sloped);
            // This crosses a one-block bed step when the sloping fixture is used.
            for (int x = 4; x <= 7; x++) fine(fixture, x, 3, Blocks.SAND.defaultBlockState(), 3);
            for (int x = 10; x <= 11; x++) for (int z = 10; z <= 11; z++)
                fine(fixture, x, z, Blocks.CLAY.defaultBlockState(), 3);
            prime(fixture.chunk);
            var before = snapshot(fixture);
            helper.assertTrue(!WaterbedSedimentFeature.cleanupChunk(fixture.chunk, eligible(true, .35))
                            && snapshot(fixture).equals(before),
                    "A connected four-column fine lens was erased or fragmented on a " + (sloped ? "sloping" : "flat") + " bed");
        }
        System.out.println("CWI_FINE_COHERENT=flat_preserved:true,slope_overlap_preserved:true,min_columns:4");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void fineCleanupPreservesDryRoofedExcludedAndUnsupportedColumns(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new ChunkPos(-3, 2), false);
        for (int x : new int[]{2, 4, 6, 8, 10, 12}) fine(fixture, x, 3, Blocks.SAND.defaultBlockState(), 1);
        for (int y = 57; y <= WATER_TOP; y++)
            fixture.chunk.setBlockState(fixture.pos(2, y, 3), Blocks.AIR.defaultBlockState(), false);
        fixture.chunk.setBlockState(fixture.pos(4, 67, 3), Blocks.STONE_BRICKS.defaultBlockState(), false);
        fixture.chunk.setBlockState(fixture.pos(8, 55, 3), Blocks.AIR.defaultBlockState(), false);
        fixture.chunk.setBlockState(fixture.pos(10, 55, 3), Blocks.DIAMOND_ORE.defaultBlockState(), false);
        fixture.chunk.setBlockState(fixture.pos(12, 55, 3), Blocks.CHEST.defaultBlockState(), false);
        fixture.chunk.setBlockState(fixture.pos(14, 56, 3), Blocks.STONE_BRICKS.defaultBlockState(), false);
        prime(fixture.chunk);
        var before = snapshot(fixture);
        var columns = (WaterbedSedimentFeature.ColumnLookup) (x, z) -> {
            owned(x, z);
            return new WaterbedSedimentFeature.TerrainColumn(!(x == 6 && z == 3), true, .35);
        };
        WaterbedSedimentFeature.cleanupChunk(fixture.chunk, columns);
        helper.assertTrue(snapshot(fixture).equals(before),
                "Fine cleanup touched dry land, a covered pool, an excluded footprint, an ore/container or unsupported cover");
        System.out.println("CWI_FINE_GUARDS=dry:true,roof:true,excluded:true,unsupported:true,ore:true,container:true,built:true");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void lateDecorationRoofProtectsFineLensWhenWorldgenMapsAreStale(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new ChunkPos(-2, -3), false);
        // FEATURES keeps final maps current, while WG maps still describe pre-decoration ground.
        Heightmap.primeHeightmaps(fixture.chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.WORLD_SURFACE));
        fine(fixture, 4, 3, Blocks.CLAY.defaultBlockState(), 2);
        fixture.chunk.setBlockState(fixture.pos(4, WATER_TOP + 3, 3), Blocks.STONE_BRICKS.defaultBlockState(), false);
        helper.assertTrue(fixture.chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, 4, 3) == WATER_TOP
                        && fixture.chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 4, 3) == WATER_TOP + 3,
                "Late-roof regression did not retain stale WG and current final heightmaps");
        var before = snapshot(fixture);
        WaterbedSedimentFeature.cleanupChunk(fixture.chunk, eligible(true, .35));
        helper.assertTrue(snapshot(fixture).equals(before),
                "Late fine cleanup used stale WG heightmaps and changed sediment under a decoration roof");
        System.out.println("CWI_FINE_LATE_ROOF=wg_stale:true,final_map_current:true,roofed_lens_unchanged:true");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void fullFineFeatureKeepsVariedConnectedLensesInsideNegativeOwnerEdges(GameTestHelper helper) {
        verifyFullFineFeature(helper, "fine_sediments_wasteland_test", 14);
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void currentMountainProfileRetainsSupportedFineFeatureAtOwnerEdges(GameTestHelper helper) {
        verifyFullFineFeature(helper, "wasteland_test", 18);
    }

    private static void verifyFullFineFeature(GameTestHelper helper, String dimension, int profile) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:" + dimension)));
        helper.assertTrue(level != null, "Sediment fixture dimension is absent: " + dimension);
        var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null && terrain.generationVersion() == profile,
                "Full fine feature fixture did not select saved profile " + profile);
        var soil = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:depleted_dirt"));
        var config = new DiskConfiguration(RuleBasedBlockStateProvider.simple(Blocks.CLAY),
                BlockPredicate.matchesBlocks(soil, Blocks.GRAVEL, Blocks.SAND, Blocks.CLAY), ConstantInt.of(1), 4);
        var feature = new IrregularSedimentFeature(DiskConfiguration.CODEC);
        var owner = new ChunkPos(-2, -3);
        var areas = new HashSet<Integer>();
        int checked = 0;
        for (int seed = 0; seed < 16; seed++) {
            Fixture fixture = fixture(helper, owner, false);
            var before = snapshot(fixture);
            int[] floors = heights(fixture, Heightmap.Types.OCEAN_FLOOR_WG);
            int[] surfaces = heights(fixture, Heightmap.Types.WORLD_SURFACE_WG);
            BlockPos origin = fixture.pos((seed & 1) == 0 ? 0 : 15, WATER_TOP, (seed & 2) == 0 ? 0 : 15);
            helper.assertTrue(feature.place(config, new SedimentFixtureRegion(level, fixture.chunk),
                            level.getChunkSource().getGenerator(), RandomSource.create(seed), origin),
                    "Full fine feature rejected a supported wet owner-edge fixture");
            int area = 0;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                if (fixture.chunk.getBlockState(fixture.pos(x, fixture.floor(x, z), z)).is(Blocks.CLAY)) area++;
                for (int y = fixture.floor(x, z) + 1; y <= WATER_TOP; y++) helper.assertTrue(
                        fixture.chunk.getBlockState(fixture.pos(x, y, z)) == before.get(fixture.pos(x, y, z)),
                        "Full fine feature changed water at an owner edge");
            }
            helper.assertTrue(area > 1, "Minimum-radius fine feature produced a single-column lens");
            areas.add(area);
            WaterbedSedimentFeature.cleanupChunk(fixture.chunk, eligible(true, .35));
            int[] retained = new int[2];
            assertComponents(helper, fixture, retained);
            helper.assertTrue(retained[1] > 1, "Minimum-radius fine feature left no usable clay patch after cleanup");
            assertHeights(helper, fixture, floors, surfaces);
            for (var entry : snapshot(fixture).entrySet()) if (entry.getValue() != before.get(entry.getKey())) {
                helper.assertTrue(new ChunkPos(entry.getKey()).equals(owner)
                                && entry.getKey().getY() >= fixture.floor(entry.getKey().getX() & 15, entry.getKey().getZ() & 15) - 4
                                && entry.getKey().getY() <= fixture.floor(entry.getKey().getX() & 15, entry.getKey().getZ() & 15),
                        "Full fine feature changed an unowned column or exceeded its shallow bed bound");
            }
            checked++;
        }
        helper.assertTrue(areas.size() > 1, "Full fine feature produces equal-size lenses for every seed");
        System.out.println("CWI_FINE_FULL_FEATURE=profile:" + profile + ",seeds:" + checked + ",areas:" + areas
                + ",negative_owner:true,all_edges:true,owner_only:true,water_height_unchanged:true");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void profile14GeneratedBedFinesStayConnectedAcrossSeedsAndNegativeOwners(GameTestHelper helper) {
        int[] fineCounts = new int[2];
        int checked = 0;
        for (long seed : new long[]{0L, 42L, 123456789L, -987654321L}) {
            for (ChunkPos owner : List.of(new ChunkPos(-1, -1), new ChunkPos(0, -1),
                    new ChunkPos(-17, 9), new ChunkPos(33, -20))) {
                for (boolean ocean : new boolean[]{true, false}) {
                    Fixture fixture = fixture(helper, owner, true);
                    Fixture repeat = fixture(helper, owner, true);
                    var before = snapshot(fixture);
                    int[] floor = heights(fixture, Heightmap.Types.OCEAN_FLOOR_WG);
                    int[] surface = heights(fixture, Heightmap.Types.WORLD_SURFACE_WG);
                    var columns = eligible(ocean, .18);
                    for (Fixture target : List.of(fixture, repeat)) {
                        WaterbedSedimentFeature.placeChunk(target.chunk, seed, columns, 14);
                        WaterbedSedimentFeature.cleanupChunk(target.chunk, columns);
                    }
                    helper.assertTrue(snapshot(fixture).equals(snapshot(repeat)),
                            "Profile14 fine-sediment placement depends on previous owner processing");
                    assertComponents(helper, fixture, fineCounts);
                    assertHeights(helper, fixture, floor, surface);
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                        for (int y = fixture.floor(x, z) + 1; y <= WATER_TOP; y++) helper.assertTrue(
                                fixture.chunk.getBlockState(fixture.pos(x, y, z)) == before.get(fixture.pos(x, y, z)),
                                "Profile14 fine-sediment finishing changed source water");
                    }
                    var settled = snapshot(fixture);
                    WaterbedSedimentFeature.cleanupChunk(fixture.chunk, columns);
                    helper.assertTrue(snapshot(fixture).equals(settled), "Profile14 cleanup changed settled fines on a second pass");
                    checked += 256;
                }
            }
        }
        helper.assertTrue(fineCounts[0] > 0 && fineCounts[1] > 0,
                "No positive sand/clay lenses survived the generated-bed fixtures");
        System.out.println("CWI_FINE_PROFILE14=columns:" + checked + ",sand:" + fineCounts[0] + ",clay:" + fineCounts[1]
                + ",tiny_components:0,deterministic:true,water_height_unchanged:true");
        helper.succeed();
    }

    private static void assertComponents(GameTestHelper helper, Fixture fixture, int[] counts) {
        boolean[] seen = new boolean[256];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int index = z * 16 + x;
            BlockState material = fixture.chunk.getBlockState(fixture.pos(x, fixture.floor(x, z), z));
            if (seen[index] || !fine(material)) continue;
            var queue = new ArrayDeque<Integer>();
            queue.add(index);
            seen[index] = true;
            int area = 0;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst(), cx = current & 15, cz = current >> 4;
                area++;
                for (int d = 0; d < 4; d++) {
                    int nx = cx + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int nz = cz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16 || seen[nz * 16 + nx]) continue;
                    BlockState next = fixture.chunk.getBlockState(fixture.pos(nx, fixture.floor(nx, nz), nz));
                    if (next.getBlock() != material.getBlock() || !overlap(fixture, cx, cz, nx, nz, material)) continue;
                    seen[nz * 16 + nx] = true;
                    queue.add(nz * 16 + nx);
                }
            }
            helper.assertTrue(area >= 4, "Actual profile14 bed retains a fine-sediment fragment of " + area + " columns");
            counts[material.is(Blocks.CLAY) ? 1 : 0] += area;
        }
    }

    private static boolean overlap(Fixture fixture, int ax, int az, int bx, int bz, BlockState material) {
        int at = fixture.floor(ax, az), bt = fixture.floor(bx, bz);
        int ab = at, bb = bt;
        for (int d = 1; d < 5 && fixture.chunk.getBlockState(fixture.pos(ax, at - d, az)).getBlock() == material.getBlock(); d++) ab--;
        for (int d = 1; d < 5 && fixture.chunk.getBlockState(fixture.pos(bx, bt - d, bz)).getBlock() == material.getBlock(); d++) bb--;
        return Math.max(ab, bb) <= Math.min(at, bt);
    }

    private static void assertHeights(GameTestHelper helper, Fixture fixture, int[] floor, int[] surface) {
        helper.assertTrue(Arrays.equals(floor, heights(fixture, Heightmap.Types.OCEAN_FLOOR_WG))
                        && Arrays.equals(surface, heights(fixture, Heightmap.Types.WORLD_SURFACE_WG)),
                "Fine cleanup changed terrain or water heightmaps");
    }

    private static WaterbedSedimentFeature.ColumnLookup eligible(boolean ocean, double energy) {
        return (x, z) -> {
            owned(x, z);
            return new WaterbedSedimentFeature.TerrainColumn(true, ocean, energy);
        };
    }

    private static void owned(int x, int z) {
        if (x < 0 || x >= 16 || z < 0 || z >= 16) throw new AssertionError("Fine cleanup queried an unowned column");
    }

    private static boolean fine(BlockState state) { return state.is(Blocks.SAND) || state.is(Blocks.CLAY); }

    private static void fine(Fixture fixture, int x, int z, BlockState material, int depth) {
        for (int d = 0; d < depth; d++) fixture.chunk.setBlockState(fixture.pos(x, fixture.floor(x, z) - d, z), material, false);
    }

    private static Fixture fixture(GameTestHelper helper, ChunkPos owner, boolean sloped) {
        var chunk = new ProtoChunk(owner, UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        BlockState soil = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:depleted_dirt")).defaultBlockState();
        helper.assertTrue(!soil.isAir(), "Required depleted-dirt registration is absent");
        Fixture fixture = new Fixture(chunk, sloped);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int floor = fixture.floor(x, z);
            for (int y = 44; y <= floor; y++) chunk.setBlockState(fixture.pos(x, y, z),
                    y >= floor - 2 ? soil : Blocks.STONE.defaultBlockState(), false);
            for (int y = floor + 1; y <= WATER_TOP; y++)
                chunk.setBlockState(fixture.pos(x, y, z), Blocks.WATER.defaultBlockState(), false);
        }
        prime(chunk);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        return fixture;
    }

    private static void prime(ProtoChunk chunk) {
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
    }

    private static int[] heights(Fixture fixture, Heightmap.Types type) {
        int[] heights = new int[256];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) heights[z * 16 + x] = fixture.chunk.getHeight(type, x, z);
        return heights;
    }

    private static Map<BlockPos, BlockState> snapshot(Fixture fixture) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 44; y <= 72; y++) {
            BlockPos pos = fixture.pos(x, y, z);
            result.put(pos, fixture.chunk.getBlockState(pos));
        }
        return result;
    }

    private record Fixture(ProtoChunk chunk, boolean sloped) {
        int floor(int x, int z) { return 56 + (sloped ? (x + z) / 8 : 0); }
        BlockPos pos(int x, int y, int z) { return new BlockPos(chunk.getPos().getBlockX(x), y, chunk.getPos().getBlockZ(z)); }
    }

    private static final class SedimentFixtureRegion extends WorldGenRegion {
        private final ChunkAccess chunk;

        SedimentFixtureRegion(ServerLevel level, ChunkAccess chunk) {
            super(level, null, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), chunk);
            this.chunk = chunk;
        }

        @Override public boolean hasChunk(int x, int z) { return chunk.getPos().equals(new ChunkPos(x, z)); }
        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            if (hasChunk(x, z)) return chunk;
            if (required) throw new AssertionError("Fine-sediment feature escaped its owner");
            return null;
        }
    }
}
