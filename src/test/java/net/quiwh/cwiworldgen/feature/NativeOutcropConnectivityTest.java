package net.quiwh.cwiworldgen.feature;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;

/** Actual native outcrop writes across a ledge, including saved-profile behavior. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeOutcropConnectivityTest {
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void nativeOutcropsRejectDisconnectedAbruptLedgesAtomically(GameTestHelper helper) {
        for (BlockState rock : new BlockState[]{Blocks.GRANITE.defaultBlockState(), Blocks.TUFF.defaultBlockState()}) {
            boolean reproduced = false;
            for (int seed = 0; seed < 256 && !reproduced; seed++) {
                Fixture legacy = fixture(helper, new ChunkPos(-2, 3), rock, -12);
                Map<BlockPos, BlockState> before = snapshot(legacy);
                if (!place(legacy, seed, 15)) continue;
                Set<BlockPos> changed = changed(before, snapshot(legacy));
                if (connected(changed)) continue;
                reproduced = true;
                // Profile 15 supplies the actual pre-fix reproducer, not a nominal shape assertion.
                // Every horizontal neighbor crosses the same large height gap,
                // so translation cannot accidentally move the modern candidate
                // away from the actual disconnected-column regression.
                Fixture modern = fixture(helper, new ChunkPos(-2, 3), rock, -12);
                helper.assertTrue(!place(modern, seed, 16), "Disconnected native formation survived an abrupt ledge");
                helper.assertTrue(snapshot(modern).equals(before), "Rejected native outcrop left partial blocks");
                assertHeights(helper, modern);
                for (int version = 13; version <= 15; version++) {
                    Fixture saved = fixture(helper, new ChunkPos(-2, 3), rock, -12);
                    helper.assertTrue(place(saved, seed, version)
                                    && snapshot(saved).equals(snapshot(legacy)),
                            "Saved profile " + version + " changed the historical ledge formation");
                }
                System.out.println("CWI_NATIVE_OUTCROP_LEDGE=rock:" + rock + ",seed:" + seed
                        + ",legacy_disconnected:true,modern_atomic_rejection:true");
            }
            helper.assertTrue(reproduced, "Abrupt ledge did not reproduce detached " + rock + " components");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void connectedNativeOutcropsRetainTheirMaterialBudgetAndShape(GameTestHelper helper) {
        int successes = 0;
        for (BlockState rock : new BlockState[]{Blocks.GRANITE.defaultBlockState(), Blocks.TUFF.defaultBlockState()}) {
            for (int step : new int[]{0, 1}) {
                for (int seed = 0; seed < 64; seed++) {
                    Fixture legacy = fixture(helper, ChunkPos.ZERO, rock, step);
                    Fixture modern = fixture(helper, ChunkPos.ZERO, rock, step);
                    Map<BlockPos, BlockState> before = snapshot(modern);
                    boolean oldPlaced = place(legacy, seed, 15);
                    boolean newPlaced = place(modern, seed, 16);
                    if (oldPlaced && newPlaced) helper.assertTrue(normalized(legacy, changed(before, snapshot(legacy)))
                                    .equals(normalized(modern, changed(before, snapshot(modern)))),
                            "Translating a connected native outcrop changed its palette, volume or shape");
                    for (int version = 13; version <= 14; version++) {
                        Fixture saved = fixture(helper, ChunkPos.ZERO, rock, step);
                        helper.assertTrue(place(saved, seed, version) == oldPlaced && snapshot(saved).equals(snapshot(legacy)),
                                "Saved native profile changed its exact selection or block fixture");
                    }
                    if (newPlaced) {
                        successes++;
                        Set<BlockPos> changed = changed(before, snapshot(modern));
                        helper.assertTrue(connected(changed), "Gentle terrain yielded detached rock components");
                        for (BlockPos pos : changed) {
                            helper.assertTrue(modern.chunk.getBlockState(pos).is(rock.getBlock()),
                                    "Native connectivity correction changed the host material");
                            helper.assertTrue(modern.chunk.getBlockState(pos.below()).isFaceSturdy(
                                    modern.chunk, pos.below(), Direction.UP), "Native outcrop lost solid support");
                        }
                    }
                    assertHeights(helper, modern);
                }
            }
        }
        helper.assertTrue(successes > 12, "Connected native fixture did not exercise enough accepted formations");
        System.out.println("CWI_NATIVE_OUTCROP_CONNECTED=accepted:" + successes
                + ",shape_palette_budget:unchanged,support:solid");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void compactFootprintsCanSelectEveryWholeOwnerAnchor(GameTestHelper helper) {
        for (int[] offsets : new int[][]{{-1, 1}, {-2, 0}, {0, 2}, {-5, 5}}) {
            var random = RandomSource.create(42);
            Set<Integer> anchors = new HashSet<>();
            for (int attempt = 0; attempt < 8192; attempt++) {
                int anchor = ReferenceRockOutcropFeature.ownedAnchor(random, offsets[0], offsets[1]);
                helper.assertTrue(anchor + offsets[0] >= 0 && anchor + offsets[1] <= 15,
                        "Exact footprint bounds selected an anchor requiring clipping or a neighbor write");
                anchors.add(anchor);
            }
            for (int anchor = -offsets[0]; anchor <= 15 - offsets[1]; anchor++)
                helper.assertTrue(anchors.contains(anchor), "A feasible whole-footprint anchor remains excluded by a center grid");
            helper.assertTrue(anchors.size() == 16 - offsets[1] + offsets[0], "Footprint sampler selected infeasible anchors");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void actualTranslatedNativeAndAshFormationsReachEveryNegativeOwnerEdge(GameTestHelper helper) {
        BlockState ash = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:ash_stone")).defaultBlockState();
        helper.assertTrue(!ash.isAir(), "Required ordinary ash stone is not registered");
        for (boolean ashOnly : new boolean[]{false, true}) {
            int edges = 0, accepted = 0;
            boolean protectedChecked = false;
            BlockState rock = (ashOnly ? Blocks.GRANITE : Blocks.TUFF).defaultBlockState();
            for (int seed = 0; seed < 4096 && (edges != 15 || accepted < 16); seed++) {
                Fixture fixture = fixture(helper, new ChunkPos(-3, -4), rock, 0);
                Map<BlockPos, BlockState> before = snapshot(fixture);
                var style = ashOnly ? ReferenceRockOutcropFeature.BiomeStyle.FORSAKEN : ReferenceRockOutcropFeature.BiomeStyle.NATIVE;
                ReferenceRockOutcropFeature.placeChunk(fixture.chunk, seed, 63,
                        (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(fixture.cell, 128, style), 16);
                Set<BlockPos> actual = changed(before, snapshot(fixture));
                if (ashOnly) actual.removeIf(pos -> fixture.chunk.getBlockState(pos) != ash);
                if (actual.isEmpty()) continue;
                accepted++;
                helper.assertTrue(connected(actual), "Translated formation contains disconnected or clipped fragments");
                if (ashOnly) helper.assertTrue(actual.size() >= 12 && actual.size() <= 20,
                        "Translating ordinary ash changed its approved 12–20-block volume budget");
                for (BlockPos pos : actual) {
                    helper.assertTrue(new ChunkPos(pos).equals(fixture.chunk.getPos()), "Formation escaped its negative owning chunk");
                    if ((pos.getX() & 15) == 0) edges |= 1;
                    if ((pos.getX() & 15) == 15) edges |= 2;
                    if ((pos.getZ() & 15) == 0) edges |= 4;
                    if ((pos.getZ() & 15) == 15) edges |= 8;
                    if (ashOnly) helper.assertTrue(pos.getY() >= 125 && pos.getY() <= 131,
                            "Translating ordinary ash changed its 1–3-block burial or relief");
                }
                assertHeights(helper, fixture);
                if (!ashOnly && !protectedChecked) {
                    protectedChecked = true;
                    Fixture blocked = fixture(helper, fixture.chunk.getPos(), rock, 0);
                    BlockPos protectedPos = actual.stream().filter(pos -> (pos.getX() & 15) < 6 || (pos.getX() & 15) > 9
                            || (pos.getZ() & 15) < 6 || (pos.getZ() & 15) > 9).findFirst().orElseThrow().atY(128);
                    blocked.chunk.setBlockState(protectedPos, Blocks.CHEST.defaultBlockState(), false);
                    var blockedBefore = snapshot(blocked);
                    helper.assertTrue(!place(blocked, seed, 16) && snapshot(blocked).equals(blockedBefore),
                            "Translated final footprint ignored a protected block or left partial writes");
                    Fixture excluded = fixture(helper, fixture.chunk.getPos(), rock, 0);
                    var excludedBefore = snapshot(excluded);
                    helper.assertTrue(!ReferenceRockOutcropFeature.placeChunk(excluded.chunk, seed, 63,
                                    (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(excluded.cell, 128,
                                            x == (protectedPos.getX() & 15) && z == (protectedPos.getZ() & 15)
                                                    ? ReferenceRockOutcropFeature.BiomeStyle.OTHER : style), 16)
                                    && snapshot(excluded).equals(excludedBefore),
                            "Translated formation used stale probe-biome eligibility instead of its final footprint");
                }
            }
            helper.assertTrue(edges == 15 && accepted >= 16,
                    "Actual " + (ashOnly ? "ash" : "native") + " formations still leave a guaranteed owner-edge gap");
        }
        helper.succeed();
    }

    private static Map<BlockPos, BlockState> normalized(Fixture fixture, Set<BlockPos> changed) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        if (changed.isEmpty()) return result;
        int minX = changed.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int minZ = changed.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        for (BlockPos pos : changed) result.put(new BlockPos(pos.getX() - minX,
                pos.getY() - fixture.surface(pos.getX() & 15, pos.getZ() & 15), pos.getZ() - minZ), fixture.chunk.getBlockState(pos));
        return result;
    }

    private static Fixture fixture(GameTestHelper helper, ChunkPos owner, BlockState rock, int step) {
        ProtoChunk chunk = new ProtoChunk(owner, UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        Cell cell = new Cell();
        cell.terrain = TerrainType.HILLS;
        cell.height = .7F;
        cell.gradient = .5F;
        Fixture fixture = new Fixture(chunk, cell, step);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int top = fixture.surface(x, z);
            for (int y = 112; y <= top; y++) chunk.setBlockState(
                    new BlockPos(owner.getBlockX(x), y, owner.getBlockZ(z)),
                    y < top - 2 ? rock : Blocks.DIRT.defaultBlockState(), false);
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        return fixture;
    }

    private static boolean place(Fixture fixture, long seed, int version) {
        return ReferenceRockOutcropFeature.placeChunk(fixture.chunk, seed, 63,
                (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(fixture.cell, fixture.surface(x, z),
                        ReferenceRockOutcropFeature.BiomeStyle.NATIVE), version);
    }

    private static Map<BlockPos, BlockState> snapshot(Fixture fixture) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        ChunkPos owner = fixture.chunk.getPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 112; y <= 147; y++) {
            BlockPos pos = new BlockPos(owner.getBlockX(x), y, owner.getBlockZ(z));
            blocks.put(pos, fixture.chunk.getBlockState(pos));
        }
        return blocks;
    }

    private static Set<BlockPos> changed(Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> after) {
        Set<BlockPos> result = new HashSet<>();
        for (var entry : after.entrySet()) if (!entry.getValue().equals(before.get(entry.getKey()))) result.add(entry.getKey());
        return result;
    }

    private static boolean connected(Set<BlockPos> blocks) {
        if (blocks.isEmpty()) return false;
        Set<BlockPos> remaining = new HashSet<>(blocks);
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        BlockPos first = remaining.iterator().next();
        remaining.remove(first);
        pending.add(first);
        while (!pending.isEmpty()) {
            BlockPos current = pending.remove();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (remaining.remove(neighbor)) pending.add(neighbor);
            }
        }
        return remaining.isEmpty();
    }

    private static void assertHeights(GameTestHelper helper, Fixture fixture) {
        ChunkPos owner = fixture.chunk.getPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int top = 147;
            while (top > 111 && fixture.chunk.getBlockState(new BlockPos(
                    owner.getBlockX(x), top, owner.getBlockZ(z))).isAir()) top--;
            helper.assertTrue(fixture.chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) == top
                            && fixture.chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) == top,
                    "Outcrop left an incorrect worldgen heightmap");
        }
    }

    private record Fixture(ProtoChunk chunk, Cell cell, int step) {
        int surface(int x, int z) { return 128 + (step < 0 ? ((x + z) & 1) * -step : x >= 8 ? step : 0); }
    }
}
