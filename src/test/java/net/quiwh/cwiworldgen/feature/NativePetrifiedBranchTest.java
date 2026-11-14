package net.quiwh.cwiworldgen.feature;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativePetrifiedBranchTest {
    private static final int MIN_Y = -60;
    private static final int MAX_Y = -8;
    private static final int ROOT_Y = -24;

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void disconnectedLegacyWoodBecomesConnectedWithinItsSuccessfulSampleBudget(GameTestHelper helper) {
        int reproduced = 0, expectedTotal = 0, writtenTotal = 0;
        Set<Set<BlockPos>> shapes = new HashSet<>();
        for (int maximum : new int[]{8, 16}) for (int seed = 0; seed < 32; seed++) {
            Fixture legacy = fixture(helper, maximum);
            Fixture modern = fixture(helper, maximum);
            Fixture repeat = fixture(helper, maximum);
            helper.assertTrue(legacy(legacy, seed), "Flat native legacy ceiling rejected all branch samples");
            var expected = wood(legacy);
            if (!allCeilingAnchored(legacy, expected.keySet())) reproduced++;
            helper.assertTrue(modern(modern, seed, modern.allowed()) && modern(repeat, seed, repeat.allowed()),
                    "A clear natural ceiling rejected a bounded connected branch");
            var actual = wood(modern);
            helper.assertTrue(actual.size() == expected.size()
                            && count(actual, modern.porous()) == count(expected, legacy.porous()),
                    "Reconnecting sampled branches increased/decreased the successful wood or porous budget");
            helper.assertTrue(snapshot(modern).equals(snapshot(repeat)), "Cave branch changed with repeated generation order");
            helper.assertTrue(allCeilingAnchored(modern, actual.keySet()), "Modern branch contains unsupported wood components");
            assertAttachments(helper, modern);
            for (BlockPos pos : actual.keySet()) helper.assertTrue(modern.allowed().test(pos)
                            && Math.abs(pos.getX() - modern.origin().getX()) <= 1
                            && Math.abs(pos.getZ() - modern.origin().getZ()) <= 1
                            && pos.getY() >= ROOT_Y - maximum && pos.getY() <= ROOT_Y + 1,
                    "Connected wood escaped the original 3×3 configured-length envelope");
            shapes.add(new HashSet<>(actual.keySet()));
            expectedTotal += expected.size();
            writtenTotal += actual.size();
        }
        helper.assertTrue(reproduced > 0, "Legacy fixture did not reproduce detached petrified wood");
        helper.assertTrue(shapes.size() > 20, "Connected branches collapsed into a single fixed shape");
        System.out.println("CWI_PETRIFIED_BRANCH_BUDGET=legacy_successful_sample_count:" + expectedTotal
                + ",written_wood:" + writtenTotal + ",legacy_detached_cases:" + reproduced
                + ",seeded_shapes:" + shapes.size() + ",same_porous_budget:true");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void failedPorousCandidatesCannotCreatePhantomAmber(GameTestHelper helper) {
        boolean reproduced = false;
        for (int seed = 0; seed < 512 && !reproduced; seed++) {
            Fixture legacy = fixture(helper, 8);
            Fixture modern = fixture(helper, 8);
            for (Fixture target : new Fixture[]{legacy, modern}) {
                // Porous candidates sampled in these solid walls fail placement,
                // but the old code still records them as amber attachment sources.
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) continue;
                    for (int depth = 0; depth <= 8; depth++)
                        target.set(target.origin().offset(x, -depth, z), Blocks.STONE.defaultBlockState());
                }
            }
            legacy(legacy, seed);
            if (!phantomAmber(legacy)) continue;
            reproduced = true;
            modern(modern, seed, modern.allowed());
            helper.assertTrue(!phantomAmber(modern), "Failed porous candidates still act as phantom amber sources");
            if (!wood(modern).isEmpty()) {
                helper.assertTrue(allCeilingAnchored(modern, wood(modern).keySet()), "Narrow-cave branch lost ceiling support");
                assertAttachments(helper, modern);
            }
            System.out.println("CWI_PETRIFIED_PHANTOM=seed:" + seed + ",legacy_unsupported_amber:true,modern_phantom_amber:false");
        }
        helper.assertTrue(reproduced, "Solid-wall fixture did not reproduce legacy phantom porous attachments");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void insufficientProtectedSpaceAndOwnerBoundsRejectAtomically(GameTestHelper helper) {
        for (int guard = 0; guard < 5; guard++) {
            Fixture fixture = fixture(helper, 16);
            Predicate<BlockPos> allowed = fixture.allowed();
            if (guard == 0) fixture.set(fixture.origin().above(), Blocks.CHEST.defaultBlockState());
            if (guard == 1) fixture.set(fixture.origin().above(), Blocks.BEDROCK.defaultBlockState());
            if (guard == 2) fixture.set(fixture.origin(), Blocks.DIAMOND_ORE.defaultBlockState());
            if (guard == 3) {
                Predicate<BlockPos> outer = allowed;
                allowed = pos -> outer.test(pos) && !pos.equals(fixture.origin());
            }
            if (guard == 4) {
                // The protected plane disconnects the large sampled lower volume
                // from the only real ceiling support. No neighboring writes can
                // bypass it, and bridging may not inflate the original budget.
                Predicate<BlockPos> outer = allowed;
                allowed = pos -> outer.test(pos) && pos.getY() != ROOT_Y - 1;
            }
            var before = snapshot(fixture);
            helper.assertTrue(!modern(fixture, 42, allowed) && snapshot(fixture).equals(before),
                    "Rejected cave branch left partial wood, roots or amber: " + guard);
        }
        Fixture edge = fixture(helper, 16);
        var before = snapshot(edge);
        helper.assertTrue(!PetrifiedBranchPlacement.placeChecked(edge.world(), edge.pos(0, ROOT_Y, 8),
                        RandomSource.create(42), edge.config(), edge.allowed()) && snapshot(edge).equals(before),
                "A branch clipped its 3×3 envelope across a negative owning-chunk edge");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void submergedAmberUsesPlacedPorousSupportAndCorrectWaterlogging(GameTestHelper helper) {
        int amber = 0;
        for (int seed = 0; seed < 128; seed++) {
            Fixture fixture = fixture(helper, 8);
            for (int x = 4; x <= 12; x++) for (int z = 4; z <= 12; z++) for (int y = ROOT_Y - 12; y <= ROOT_Y; y++)
                fixture.set(fixture.pos(x, y, z), Blocks.WATER.defaultBlockState());
            fixture.set(fixture.origin(), Blocks.AIR.defaultBlockState());
            helper.assertTrue(modern(fixture, seed, fixture.allowed()), "A water-filled natural cave rejected a supported branch");
            helper.assertTrue(allCeilingAnchored(fixture, wood(fixture).keySet()), "Submerged branch contains floating logs");
            for (var entry : snapshot(fixture).entrySet()) {
                helper.assertTrue(!entry.getValue().is(fixture.rootBody().getBlock()) && !entry.getValue().is(fixture.rootHead().getBlock()),
                        "Dry hanging roots were placed into water");
                if (!entry.getValue().is(fixture.amber().getBlock())) continue;
                amber++;
                helper.assertTrue(entry.getValue().getValue(AmethystClusterBlock.WATERLOGGED),
                        "Submerged amber discarded source water");
            }
            assertAttachments(helper, fixture);
        }
        helper.assertTrue(amber > 0, "Submerged fixture exercised no actual amber attachments");
        helper.succeed();
    }

    private static boolean legacy(Fixture fixture, long seed) {
        var feature = new PetrifiedBranchFeature(PalettedConfiguration.codec(PetrifiedBranchConfig.CODEC,
                "petrified_log", "porous_petrified_log", "petrified_roots_plant", "petrified_roots", "amber_cluster"));
        return feature.place(fixture.config(), fixture.world(), fixture.world().getLevel().getChunkSource().getGenerator(),
                RandomSource.create(seed), fixture.origin());
    }

    private static boolean modern(Fixture fixture, long seed, Predicate<BlockPos> allowed) {
        return PetrifiedBranchPlacement.placeChecked(fixture.world(), fixture.origin(), RandomSource.create(seed), fixture.config(), allowed);
    }

    private static int count(Map<BlockPos, BlockState> blocks, BlockState requested) {
        return (int) blocks.values().stream().filter(state -> state.is(requested.getBlock())).count();
    }

    private static Map<BlockPos, BlockState> wood(Fixture fixture) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (var entry : snapshot(fixture).entrySet())
            if (entry.getValue().is(fixture.log().getBlock()) || entry.getValue().is(fixture.porous().getBlock()))
                result.put(entry.getKey(), entry.getValue());
        return result;
    }

    private static boolean allCeilingAnchored(Fixture fixture, Set<BlockPos> wood) {
        var remaining = new HashSet<>(wood);
        var pending = new ArrayDeque<BlockPos>();
        for (BlockPos pos : wood) if (fixture.chunk().getBlockState(pos.above()).is(Blocks.STONE)) {
            remaining.remove(pos);
            pending.add(pos);
        }
        while (!pending.isEmpty()) {
            BlockPos current = pending.remove();
            for (Direction direction : Direction.values()) if (remaining.remove(current.relative(direction))) pending.add(current.relative(direction));
        }
        return !wood.isEmpty() && remaining.isEmpty();
    }

    private static boolean phantomAmber(Fixture fixture) {
        for (var entry : snapshot(fixture).entrySet()) if (entry.getValue().is(fixture.amber().getBlock())) {
            Direction facing = entry.getValue().getValue(AmethystClusterBlock.FACING);
            if (!fixture.chunk().getBlockState(entry.getKey().relative(facing.getOpposite())).is(fixture.porous().getBlock())) return true;
        }
        return false;
    }

    private static void assertAttachments(GameTestHelper helper, Fixture fixture) {
        for (var entry : snapshot(fixture).entrySet()) {
            BlockState state = entry.getValue();
            BlockPos pos = entry.getKey();
            if (state.is(fixture.amber().getBlock())) {
                Direction facing = state.getValue(AmethystClusterBlock.FACING);
                BlockPos support = pos.relative(facing.getOpposite());
                helper.assertTrue(fixture.chunk().getBlockState(support).is(fixture.porous().getBlock())
                                && state.canSurvive(fixture.world(), pos), "Amber is detached from actual final porous wood");
            }
            if (state.is(fixture.rootBody().getBlock()) || state.is(fixture.rootHead().getBlock())) {
                helper.assertTrue(state.canSurvive(fixture.world(), pos), "Petrified roots cannot survive beneath their actual support");
                BlockPos support = pos.above();
                if (fixture.chunk().getBlockState(support).is(fixture.rootBody().getBlock())) support = support.above();
                helper.assertTrue(fixture.chunk().getBlockState(support).is(fixture.log().getBlock())
                                || fixture.chunk().getBlockState(support).is(fixture.porous().getBlock()),
                        "Petrified roots did not originate beneath actual final wood");
            }
            if (state.is(fixture.log().getBlock()) || state.is(fixture.porous().getBlock())
                    || state.is(fixture.rootBody().getBlock()) || state.is(fixture.rootHead().getBlock()) || state.is(fixture.amber().getBlock()))
                helper.assertTrue(fixture.allowed().test(pos), "Cave branch decoration escaped its owner or cave band");
        }
    }

    private static Fixture fixture(GameTestHelper helper, int maximum) {
        ChunkPos owner = new ChunkPos(-4, 2);
        var chunk = new ProtoChunk(owner, UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        Map<String, BlockState> states = new HashMap<>();
        for (String key : new String[]{"petrified_log", "porous_petrified_log", "petrified_roots_plant", "petrified_roots", "amber_cluster"}) {
            BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("cwi:" + key)).defaultBlockState();
            helper.assertTrue(!state.isAir(), "Required Core-owned branch palette is absent: " + key);
            states.put(key, state);
        }
        var config = new PalettedConfiguration<>(new PetrifiedBranchConfig(maximum / 2, maximum), new PaletteConfiguration(states));
        var fixture = new Fixture(chunk, new BranchFixtureRegion(helper.getLevel(), chunk), config);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = ROOT_Y + 1; y <= MAX_Y; y++)
            fixture.set(fixture.pos(x, y, z), Blocks.STONE.defaultBlockState());
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        return fixture;
    }

    private static Map<BlockPos, BlockState> snapshot(Fixture fixture) {
        var result = new HashMap<BlockPos, BlockState>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = MIN_Y; y <= MAX_Y; y++) {
            BlockPos pos = fixture.pos(x, y, z);
            result.put(pos, fixture.chunk().getBlockState(pos));
        }
        return result;
    }

    private record Fixture(ProtoChunk chunk, BranchFixtureRegion world, PalettedConfiguration<PetrifiedBranchConfig> config) {
        BlockPos pos(int x, int y, int z) { return new BlockPos(chunk.getPos().getBlockX(x), y, chunk.getPos().getBlockZ(z)); }
        BlockPos origin() { return pos(8, ROOT_Y, 8); }
        void set(BlockPos position, BlockState state) { chunk.setBlockState(position, state, false); }
        BlockState log() { return config.palette().state("petrified_log"); }
        BlockState porous() { return config.palette().state("porous_petrified_log"); }
        BlockState rootBody() { return config.palette().state("petrified_roots_plant"); }
        BlockState rootHead() { return config.palette().state("petrified_roots"); }
        BlockState amber() { return config.palette().state("amber_cluster"); }
        Predicate<BlockPos> allowed() {
            return pos -> new ChunkPos(pos).equals(chunk.getPos()) && pos.getY() >= MIN_Y && pos.getY() <= MAX_Y;
        }
    }

    private static final class BranchFixtureRegion extends WorldGenRegion {
        private final ChunkAccess chunk;

        BranchFixtureRegion(ServerLevel level, ChunkAccess chunk) {
            super(level, null, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), chunk);
            this.chunk = chunk;
        }

        @Override public boolean hasChunk(int x, int z) { return chunk.getPos().equals(new ChunkPos(x, z)); }
        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            if (hasChunk(x, z)) return chunk;
            if (required) throw new AssertionError("Petrified branch escaped its bounded owner");
            return null;
        }
    }
}
