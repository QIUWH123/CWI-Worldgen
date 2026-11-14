package net.quiwh.cwiworldgen.feature.geode;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
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
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.feature.geode.config.GeodeCrystalSpikeConfig;

/** Actual block transactions, including a legacy early-valid/later-missing ceiling. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeCrystalSpikePlacementTest {
    private static final int MIN_Y = -60;
    private static final int MAX_Y = -24;
    private static final int ROOT_Y = -40;

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void laterMissingCeilingDoesNotLeaveEarlierSubstrateBloom(GameTestHelper helper) {
        for (boolean echo : new boolean[]{false, true}) {
            Fixture legacy = fixture(helper, echo, CaveSurface.CEILING, false);
            Fixture modern = fixture(helper, echo, CaveSurface.CEILING, false);
            for (Fixture target : new Fixture[]{legacy, modern}) {
                // The first disk column finds a real roof; the next column has no
                // ceiling inside the legacy ten-block search. The center remains
                // a valid configured-feature origin throughout this reproducer.
                target.set(target.origin(), target.substrate());
                target.set(target.origin().offset(-1, 2, 0), target.substrate());
            }
            var before = snapshot(modern);
            var positions = new HashSet<BlockPos>();
            boolean legacyResult = echo
                    ? new EchoCrystalSpikeFeature(GeodeCrystalSpikeConfig.CODEC).placeSpike(legacy.world(),
                            legacy.origin(), 1, 4, 0, positions, Direction.UP, RandomSource.create(42))
                    : new CrystalSpikeFeature(GeodeCrystalSpikeConfig.CODEC).placeSpike(legacy.world(),
                            legacy.origin(), 1, 4, 0, positions, Direction.UP, RandomSource.create(42));
            helper.assertTrue(!legacyResult && legacy.chunk().getBlockState(legacy.origin().offset(-1, 2, 0)) == legacy.bloom(),
                    "Partial-bloom regression fixture did not reproduce a successful early ceiling before later failure");
            helper.assertTrue(!place(modern, 42, 1, modern.allowed()) && snapshot(modern).equals(before),
                    "A failed ceiling spike left substrate or crystal edits behind");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void supportedSpikesUseVariedDeterministicAzimuthsAndValidClusters(GameTestHelper helper) {
        Set<BlockPos> tips = new HashSet<>();
        int clusters = 0;
        for (boolean echo : new boolean[]{false, true}) for (CaveSurface surface : CaveSurface.values()) {
            for (int seed = 0; seed < 32; seed++) {
                long shapeSeed = seed * 0x9e3779b97f4a7c15L;
                Fixture actual = fixture(helper, echo, surface, true);
                Fixture repeat = fixture(helper, echo, surface, true);
                // Water around an upward spike exercises attachment waterlogging.
                if (surface == CaveSurface.FLOOR && echo) for (Fixture target : new Fixture[]{actual, repeat})
                    for (int x = 2; x <= 13; x++) for (int z = 2; z <= 13; z++) for (int y = ROOT_Y + 1; y <= ROOT_Y + 10; y++)
                        target.set(target.pos(x, y, z), Blocks.WATER.defaultBlockState());
                helper.assertTrue(place(actual, shapeSeed, 3, actual.allowed()) && place(repeat, shapeSeed, 3, repeat.allowed()),
                        "A fully supported flat cave spike unexpectedly failed");
                helper.assertTrue(snapshot(actual).equals(snapshot(repeat)), "Spike orientation or decoration is not seeded deterministically");
                Set<BlockPos> crystals = crystals(actual);
                assertConnectedAndAnchored(helper, actual, crystals);
                int tipY = ROOT_Y + (surface == CaveSurface.FLOOR ? 7 : -7);
                for (BlockPos crystal : crystals) if (crystal.getY() == tipY)
                    tips.add(new BlockPos(crystal.getX() - actual.origin().getX(), 0, crystal.getZ() - actual.origin().getZ()));
                for (var entry : snapshot(actual).entrySet()) {
                    if (!entry.getValue().is(Blocks.AMETHYST_CLUSTER)) continue;
                    clusters++;
                    BlockState state = entry.getValue();
                    Direction facing = state.getValue(AmethystClusterBlock.FACING);
                    BlockPos support = entry.getKey().relative(facing.getOpposite());
                    helper.assertTrue(crystals.contains(support) && actual.chunk().getBlockState(support)
                                    .isFaceSturdy(actual.chunk(), support, facing), "Cluster has no supporting crystal face");
                    helper.assertTrue(state.getValue(AmethystClusterBlock.WATERLOGGED)
                                    == (surface == CaveSurface.FLOOR && echo), "Cluster waterlogging disagrees with its original fluid");
                }
            }
        }
        helper.assertTrue(tips.size() >= 12, "Crystal lean still collapses into the old four fixed directions");
        helper.assertTrue(clusters > 0, "No attached clusters were exercised by the successful spike fixtures");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void obstructionsBoundsAndProtectedSubstrateRejectWithoutEdits(GameTestHelper helper) {
        for (boolean echo : new boolean[]{false, true}) for (CaveSurface surface : CaveSurface.values()) {
            int growth = surface == CaveSurface.FLOOR ? 1 : -1;
            for (int guard = 0; guard < 5; guard++) {
                Fixture fixture = fixture(helper, echo, surface, true);
                Predicate<BlockPos> allowed = fixture.allowed();
                if (guard == 0) fixture.set(fixture.origin().above(growth * 3), Blocks.BEDROCK.defaultBlockState());
                if (guard == 1) fixture.set(fixture.origin().offset(1, 0, 0), Blocks.CHEST.defaultBlockState());
                if (guard == 2) fixture.set(fixture.origin().offset(1, 0, 0), Blocks.DIAMOND_ORE.defaultBlockState());
                if (guard == 3) {
                    Predicate<BlockPos> outer = allowed;
                    allowed = pos -> outer.test(pos) && pos.getY() != ROOT_Y + growth * 4;
                }
                if (guard == 4) {
                    Predicate<BlockPos> outer = allowed;
                    BlockPos protectedRoot = fixture.origin().offset(-2, 0, 0);
                    allowed = pos -> outer.test(pos) && !pos.equals(protectedRoot);
                }
                var before = snapshot(fixture);
                helper.assertTrue(!place(fixture, 42, 3, allowed) && snapshot(fixture).equals(before),
                        "A blocked, ore/container, cave-band or structure-protected spike changed blocks: " + guard);
            }
            Fixture border = fixture(helper, echo, surface, true);
            BlockPos edge = border.pos(0, ROOT_Y, 8);
            var before = snapshot(border);
            helper.assertTrue(!CrystalSpikePlacement.placeChecked(border.world(), border.config(), RandomSource.create(42),
                            edge, 3, 30, border.substrateTest(), border.bloom(), border.allowed())
                            && snapshot(border).equals(before), "Owner-edge spike wrote a neighboring chunk or left partial edits");
        }
        helper.succeed();
    }

    private static boolean place(Fixture fixture, long seed, int radius, Predicate<BlockPos> allowed) {
        return CrystalSpikePlacement.placeChecked(fixture.world(), fixture.config(), RandomSource.create(seed),
                fixture.origin(), radius, 30, fixture.substrateTest(), fixture.bloom(), allowed);
    }

    private static Set<BlockPos> crystals(Fixture fixture) {
        Set<BlockPos> crystals = new HashSet<>();
        for (var entry : snapshot(fixture).entrySet()) if (entry.getValue() == fixture.config().crystal_state
                && (fixture.surface() == CaveSurface.FLOOR ? entry.getKey().getY() > ROOT_Y : entry.getKey().getY() < ROOT_Y))
            crystals.add(entry.getKey());
        return crystals;
    }

    private static void assertConnectedAndAnchored(GameTestHelper helper, Fixture fixture, Set<BlockPos> crystals) {
        helper.assertTrue(!crystals.isEmpty(), "Successful spike placed no crystal blocks");
        var pending = new ArrayDeque<BlockPos>();
        var remaining = new HashSet<>(crystals);
        BlockPos first = crystals.iterator().next();
        remaining.remove(first);
        pending.add(first);
        boolean anchored = false;
        while (!pending.isEmpty()) {
            BlockPos current = pending.remove();
            helper.assertTrue(fixture.allowed().test(current), "Crystal escaped the owner or approved cave height band");
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (remaining.remove(next)) pending.add(next);
                if (!crystals.contains(next) && fixture.allowed().test(next)) {
                    BlockState state = fixture.chunk().getBlockState(next);
                    if ((state == fixture.substrate() || state == fixture.bloom())
                            && state.isFaceSturdy(fixture.chunk(), next, direction.getOpposite())) anchored = true;
                }
            }
        }
        helper.assertTrue(remaining.isEmpty() && anchored, "Spike contains detached crystal components or has no solid cave support");
    }

    private static Fixture fixture(GameTestHelper helper, boolean echo, CaveSurface surface, boolean filled) {
        ChunkPos owner = new ChunkPos(-2, -3);
        var chunk = new ProtoChunk(owner, UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        BlockState substrate = (echo ? Blocks.STONE : Blocks.NETHERRACK).defaultBlockState();
        BlockState bloom = (echo ? Blocks.SCULK : Blocks.BLACKSTONE).defaultBlockState();
        var config = new GeodeCrystalSpikeConfig((echo ? Blocks.SCULK : Blocks.QUARTZ_BLOCK).defaultBlockState(),
                Blocks.AMETHYST_CLUSTER.defaultBlockState(), ConstantInt.of(2), surface);
        var fixture = new Fixture(chunk, new SpikeFixtureRegion(helper.getLevel(), chunk), substrate, bloom, config, surface);
        if (filled) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            for (int y = MIN_Y; y <= MAX_Y; y++)
                if (surface == CaveSurface.FLOOR ? y <= ROOT_Y : y >= ROOT_Y) fixture.set(fixture.pos(x, y, z), substrate);
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

    private record Fixture(ProtoChunk chunk, SpikeFixtureRegion world, BlockState substrate, BlockState bloom,
            GeodeCrystalSpikeConfig config, CaveSurface surface) {
        BlockPos pos(int x, int y, int z) { return new BlockPos(chunk.getPos().getBlockX(x), y, chunk.getPos().getBlockZ(z)); }
        BlockPos origin() { return pos(8, ROOT_Y, 8); }
        void set(BlockPos pos, BlockState state) { chunk.setBlockState(pos, state, false); }
        Predicate<BlockState> substrateTest() { return state -> state == substrate; }
        Predicate<BlockPos> allowed() {
            return pos -> new ChunkPos(pos).equals(chunk.getPos()) && pos.getY() >= MIN_Y && pos.getY() <= MAX_Y;
        }
    }

    private static final class SpikeFixtureRegion extends WorldGenRegion {
        private final ChunkAccess chunk;

        SpikeFixtureRegion(ServerLevel level, ChunkAccess chunk) {
            super(level, null, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), chunk);
            this.chunk = chunk;
        }

        @Override public boolean hasChunk(int x, int z) { return chunk.getPos().equals(new ChunkPos(x, z)); }
        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            if (hasChunk(x, z)) return chunk;
            if (required) throw new AssertionError("Crystal-spike placement escaped its owning chunk");
            return null;
        }
    }
}
