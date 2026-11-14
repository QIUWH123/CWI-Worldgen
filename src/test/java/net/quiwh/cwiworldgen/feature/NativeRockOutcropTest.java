package net.quiwh.cwiworldgen.feature;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
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
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.Terrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainCategory;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeRockOutcropTest {
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void outcropsUseNativeRockWithBuriedSupportedConnectedRelief(GameTestHelper helper) {
        var fixture = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
        long seed = findSeed(helper, fixture);
        var changes = changed(fixture);
        Set<BlockPos> footprint = new HashSet<>();
        boolean buried = false;
        boolean relief = false;
        for (var entry : changes.entrySet()) {
            BlockPos pos = entry.getKey();
            helper.assertTrue(entry.getValue().is(Blocks.GRANITE), "Outcrop invented an unrelated rock palette");
            helper.assertTrue(pos.getX() > 0 && pos.getX() < 15 && pos.getZ() > 0 && pos.getZ() < 15,
                    "Contained footprint touched a chunk edge");
            int surface = fixture.surface(pos.getX(), pos.getZ());
            buried |= pos.getY() < surface;
            relief |= pos.getY() > surface;
            footprint.add(new BlockPos(pos.getX(), 0, pos.getZ()));
            if (pos.getY() > surface) helper.assertTrue(fixture.chunk.getBlockState(pos.below()).is(Blocks.GRANITE),
                    "Exposed rock has an air gap below it");
        }
        helper.assertTrue(buried && relief, "Expected partly buried foundations and above-ground relief");
        helper.assertTrue(footprint.size() > 7 && footprint.size() < 90, "Outcrop became pebbles or a full surface patch");
        helper.assertTrue(connected(footprint), "Joined lobes left detached rock speckles");
        helper.assertTrue(connectedVoxels(changes.keySet()), "Native relief is not face-connected in three dimensions");
        for (BlockPos column : footprint) {
            int top = fixture.surface(column.getX(), column.getZ());
            while (!fixture.chunk.getBlockState(column.atY(top + 1)).isAir()) top++;
            helper.assertTrue(fixture.chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, column.getX(), column.getZ()) == top
                            && fixture.chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, column.getX(), column.getZ()) == top,
                    "Raised rock left a stale worldgen heightmap");
        }
        helper.assertTrue(fixture.cell.gradient == .5F && fixture.cell.height == .7F,
                "Feature mutated its published terrain cell");
        System.out.println("CWI_ROCK_OUTCROP_NATIVE=seed=" + seed + " footprint=" + footprint.size()
                + " blocks=" + changes.size());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void localTuffIsLargerAndMoreFrequentWhilePlainsStayOpen(GameTestHelper helper) {
        int stoneCount = 0;
        int tuffCount = 0;
        int plainCount = 0;
        int cliffCount = 0;
        int stoneArea = 0;
        int tuffArea = 0;
        for (int seed = 0; seed < 192; seed++) {
            var stone = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
            if (place(stone, seed)) {
                stoneCount++;
                stoneArea += footprint(changed(stone)).size();
            }
            var tuff = fixture(helper, ChunkPos.ZERO, Blocks.TUFF.defaultBlockState(), false, .5F);
            if (place(tuff, seed)) {
                tuffCount++;
                tuffArea += footprint(changed(tuff)).size();
                for (BlockState state : changed(tuff).values()) helper.assertTrue(state.is(Blocks.TUFF),
                        "Local tuff exposure introduced an unrelated palette");
            }
            var plains = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, 0);
            if (place(plains, seed)) plainCount++;
            var cliff = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, 0);
            for (int x = 10; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 129; y <= 136; y++) {
                cliff.chunk.setBlockState(new BlockPos(x, y, z), y < 134 ? Blocks.GRANITE.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), false);
            }
            Heightmap.primeHeightmaps(cliff.chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
            if (ReferenceRockOutcropFeature.placeChunk(cliff.chunk, seed, 63,
                    (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(cliff.cell, x >= 10 ? 136 : 128, true))) cliffCount++;
        }
        helper.assertTrue(stoneCount > 5 && stoneCount < 50, "Slope outcrops lost their sparse placement");
        helper.assertTrue(tuffCount > stoneCount * 1.6, "Real local tuff did not get more frequent exposures");
        helper.assertTrue(tuffArea / (double) tuffCount > stoneArea / (double) stoneCount * 1.5,
                "Tuff formations were not larger than ordinary native outcrops");
        helper.assertTrue(plainCount * 3 < stoneCount, "Plains did not stay substantially more open than slopes");
        helper.assertTrue(cliffCount > stoneCount && cliffCount > plainCount * 3,
                "Actual cliff bases were not favored over flat ground and ordinary slopes");
        System.out.println("CWI_ROCK_OUTCROP_FREQUENCY=192 seeds stone=" + stoneCount + " tuff=" + tuffCount
                + " plains=" + plainCount + " cliff=" + cliffCount + " mean_area=" + stoneArea / (double) stoneCount
                + "/" + tuffArea / (double) tuffCount);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void oneFormationPreservesTheLocalGeologyBoundary(GameTestHelper helper) {
        boolean crossedBoundary = false;
        for (int seed = 0; seed < 128 && !crossedBoundary; seed++) {
            var fixture = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
            for (int x = 8; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 112; y <= 125; y++) {
                fixture.chunk.setBlockState(new BlockPos(x, y, z), Blocks.TUFF.defaultBlockState(), false);
            }
            var before = snapshot(fixture);
            if (!place(fixture, seed)) continue;
            Set<BlockState> exposed = new HashSet<>();
            for (var entry : snapshot(fixture).entrySet()) {
                if (entry.getValue() == before.get(entry.getKey())) continue;
                BlockState expected = entry.getKey().getX() >= 8 ? Blocks.TUFF.defaultBlockState() : Blocks.GRANITE.defaultBlockState();
                helper.assertTrue(entry.getValue() == expected, "Formation spread tuff or another rock across its native geology boundary");
                exposed.add(entry.getValue());
            }
            crossedBoundary = exposed.size() == 2;
        }
        helper.assertTrue(crossedBoundary, "No seeded formation exercised a local geology boundary");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void wetTerrainAndNonSurfaceBiomesRejectTheWholeFormation(GameTestHelper helper) throws Exception {
        var dry = fixture(helper, ChunkPos.ZERO, Blocks.TUFF.defaultBlockState(), false, .5F);
        long seed = findSeed(helper, dry);
        var constructor = Terrain.class.getDeclaredConstructor(int.class, String.class, TerrainCategory.class);
        constructor.setAccessible(true);
        Terrain lake = constructor.newInstance(-1, "outcrop_test_lake", TerrainCategory.LAKE);
        for (Terrain terrain : List.of(TerrainType.RIVER, lake, TerrainType.WETLAND, TerrainType.DEEP_OCEAN,
                TerrainType.SHALLOW_OCEAN, TerrainType.VOLCANO_PIPE)) {
            var wet = fixture(helper, ChunkPos.ZERO, Blocks.TUFF.defaultBlockState(), false, .5F);
            wet.cell.terrain = terrain;
            helper.assertTrue(!place(wet, seed) && changed(wet).isEmpty(), "Outcrop entered excluded terrain " + terrain);
        }
        var otherBiome = fixture(helper, ChunkPos.ZERO, Blocks.TUFF.defaultBlockState(), false, .5F);
        helper.assertTrue(!ReferenceRockOutcropFeature.placeChunk(otherBiome.chunk, seed, 63,
                (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(otherBiome.cell, 128, false)),
                "Outcrop accepted a non-surface biome");
        helper.assertTrue(changed(otherBiome).isEmpty(), "Biome rejection modified ground");
        var partial = fixture(helper, ChunkPos.ZERO, Blocks.TUFF.defaultBlockState(), false, .5F);
        BlockPos member = changed(dry).keySet().iterator().next();
        helper.assertTrue(!ReferenceRockOutcropFeature.placeChunk(partial.chunk, seed, 63,
                (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(partial.cell, 128,
                        x != member.getX() || z != member.getZ())),
                "Formation bridged an excluded biome column");
        helper.assertTrue(changed(partial).isEmpty(), "Rejected footprint left scattered fragments");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void oresStructuresFluidsAndUnsupportedGroundRemainUntouched(GameTestHelper helper) {
        var dry = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
        long seed = findSeed(helper, dry);
        BlockPos member = footprint(changed(dry)).iterator().next().atY(128);
        BlockState deposit = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:hematite_deposit")).defaultBlockState();
        helper.assertTrue(!deposit.isAir(), "Required native compound deposit is not registered");
        for (BlockState protectedState : List.of(Blocks.DIAMOND_ORE.defaultBlockState(), deposit,
                Blocks.CHEST.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState())) {
            for (int dy : List.of(0, -1)) {
                var fixture = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
                BlockPos protectedPos = member.offset(0, dy, 0);
                fixture.chunk.setBlockState(protectedPos, protectedState, false);
                var before = snapshot(fixture);
                helper.assertTrue(!place(fixture, seed), "Outcrop accepted an ore or protected state");
                helper.assertTrue(before.equals(snapshot(fixture)), "Rejected outcrop modified protected terrain");
            }
        }
        for (BlockState obstruction : List.of(Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
                Blocks.DEAD_BUSH.defaultBlockState())) {
            var fixture = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
            fixture.chunk.setBlockState(member.above(), obstruction, false);
            var before = snapshot(fixture);
            helper.assertTrue(!place(fixture, seed) && before.equals(snapshot(fixture)), "Outcrop replaced fluid or decoration");
        }
        var unsupported = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
        unsupported.chunk.setBlockState(member.below(3), Blocks.AIR.defaultBlockState(), false);
        var before = snapshot(unsupported);
        helper.assertTrue(!place(unsupported, seed) && before.equals(snapshot(unsupported)), "Foundation bridged a cave opening");
        var submerged = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
        helper.assertTrue(!ReferenceRockOutcropFeature.placeChunk(submerged.chunk, seed, 140,
                (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(submerged.cell, 128, true)),
                "Outcrop accepted ground below sea level");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void missingShallowGeologyNeverFallsBackToInventedStone(GameTestHelper helper) {
        var fixture = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
        long seed = findSeed(helper, fixture);
        var deep = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 116; y <= 128; y++) {
            deep.chunk.setBlockState(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), false);
        }
        var before = snapshot(deep);
        helper.assertTrue(!place(deep, seed) && before.equals(snapshot(deep)),
                "Feature invented stone without shallow native rock");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void geometryVariesDeterministicallyAndKeepsNegativeChunkOwnership(GameTestHelper helper) {
        Set<Set<BlockPos>> shapes = new HashSet<>();
        int successful = 0;
        for (int seed = 0; seed < 128; seed++) {
            var first = fixture(helper, new ChunkPos(-3, 4), Blocks.TUFF.defaultBlockState(), true, .5F);
            var repeated = fixture(helper, new ChunkPos(-3, 4), Blocks.TUFF.defaultBlockState(), true, .5F);
            boolean placed = place(first, seed);
            helper.assertTrue(placed == place(repeated, seed) && snapshot(first).equals(snapshot(repeated)),
                    "Identical seeded chunks changed with invocation order");
            if (!placed) continue;
            successful++;
            var changes = changed(first);
            shapes.add(footprint(changes));
            for (BlockPos pos : changes.keySet()) helper.assertTrue(new ChunkPos(pos).equals(first.chunk.getPos())
                    && (pos.getX() & 15) > 0 && (pos.getX() & 15) < 15
                    && (pos.getZ() & 15) > 0 && (pos.getZ() & 15) < 15,
                    "Formation crossed the owning negative-coordinate chunk boundary");
            helper.assertTrue(connected(footprint(changes)), "Sloped outcrop lost its connected footprint");
        }
        helper.assertTrue(successful > 12 && shapes.size() > 10, "Seeded formations collapsed to a fixed boulder shape");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void smolderingUsesDarkAshWithShallowConnectedVariedNativeGeometry(GameTestHelper helper) {
        BlockState darkAsh = registered(helper, "kubejs:dark_ash_stone");
        Set<Set<BlockPos>> shapes = new HashSet<>();
        int placed = 0;
        for (int seed = 0; seed < 128; seed++) {
            var nativeRock = fixture(helper, ChunkPos.ZERO, Blocks.TUFF.defaultBlockState(), true, .5F);
            var smoldering = fixture(helper, ChunkPos.ZERO, Blocks.TUFF.defaultBlockState(), true, .5F);
            boolean expectedPlacement = place(nativeRock, seed);
            boolean actualPlacement = place(smoldering, seed, ReferenceRockOutcropFeature.BiomeStyle.SMOLDERING);
            helper.assertTrue(expectedPlacement == actualPlacement, "Dark ash substitution changed native candidate frequency");
            if (!actualPlacement) continue;
            placed++;
            var changes = changed(smoldering);
            helper.assertTrue(footprint(changes).equals(footprint(changed(nativeRock))),
                    "Dark ash substitution changed native outcrop geometry");
            helper.assertTrue(changed(nativeRock).keySet().stream().anyMatch(pos ->
                            pos.getY() < nativeRock.surface(pos.getX(), pos.getZ()) - 1),
                    "Ordinary stone/tuff lost its deeper foundation relative to dark ash");
            for (BlockPos column : footprint(changes)) helper.assertTrue(
                    smoldering.chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, column.getX(), column.getZ())
                            == nativeRock.chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, column.getX(), column.getZ()),
                    "Dark ash substitution changed native exposed relief");
            shapes.add(footprint(changes));
            helper.assertTrue(connectedVoxels(changes.keySet()), "Smoldering formation has detached dark ash fragments");
            for (var entry : changes.entrySet()) {
                BlockPos pos = entry.getKey();
                helper.assertTrue(entry.getValue() == darkAsh, "Smoldering formation retained an ordinary rock block");
                helper.assertTrue(pos.getY() >= smoldering.surface(pos.getX(), pos.getZ()) - 1,
                        "Smoldering formation dug a deep underground root");
            }
        }
        helper.assertTrue(placed > 12 && shapes.size() > 10, "Dark ash formations lost their varied sparse native geometry");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void shallowDarkAshNeverLeavesDetachedFragmentsAcrossAbruptLedges(GameTestHelper helper) {
        registered(helper, "kubejs:dark_ash_stone");
        int slopeCandidates = 0;
        int rejected = 0;
        for (int seed = 0; seed < 192; seed++) {
            var stepped = fixture(helper, ChunkPos.ZERO, Blocks.TUFF.defaultBlockState(), false, .5F);
            for (int x = 8; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 129; y <= 136; y++) {
                stepped.chunk.setBlockState(new BlockPos(x, y, z), y < 134 ? Blocks.TUFF.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), false);
            }
            Heightmap.primeHeightmaps(stepped.chunk,
                    EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
            var before = snapshot(stepped);
            boolean placed = ReferenceRockOutcropFeature.placeChunk(stepped.chunk, seed, 63,
                    (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(stepped.cell, x >= 8 ? 136 : 128,
                            ReferenceRockOutcropFeature.BiomeStyle.SMOLDERING));
            if (placed) {
                Set<BlockPos> actual = new HashSet<>();
                for (var entry : snapshot(stepped).entrySet()) if (entry.getValue() != before.get(entry.getKey())) actual.add(entry.getKey());
                helper.assertTrue(connectedVoxels(actual), "Dark ash crossed an abrupt ledge as detached fragments");
            } else {
                rejected++;
                helper.assertTrue(before.equals(snapshot(stepped)), "Disconnected dark ash was only partially rejected");
            }
            var flat = fixture(helper, ChunkPos.ZERO, Blocks.TUFF.defaultBlockState(), false, .5F);
            if (place(flat, seed, ReferenceRockOutcropFeature.BiomeStyle.SMOLDERING)) slopeCandidates++;
        }
        helper.assertTrue(slopeCandidates > 20 && rejected > 150,
                "Abrupt ledge fixture did not exercise shallow-formation rejection");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void forsakenAshChunksAreSmallRareConnectedAndAverageSixteenBlocks(GameTestHelper helper) {
        BlockState ash = registered(helper, "kubejs:ash_stone");
        int chunks = 0;
        int volume = 0;
        Set<Integer> volumes = new HashSet<>();
        Set<Set<BlockPos>> shapes = new HashSet<>();
        for (int seed = 0; seed < 1024; seed++) {
            var fixture = fixture(helper, new ChunkPos(-3, 4), Blocks.GRANITE.defaultBlockState(), false, 0);
            if (!place(fixture, seed, ReferenceRockOutcropFeature.BiomeStyle.FORSAKEN)) continue;
            Map<BlockPos, BlockState> ashChanges = new HashMap<>();
            for (var entry : changed(fixture).entrySet()) if (entry.getValue() == ash) ashChanges.put(entry.getKey(), ash);
            if (ashChanges.isEmpty()) continue;
            chunks++;
            volume += ashChanges.size();
            volumes.add(ashChanges.size());
            shapes.add(footprint(ashChanges));
            helper.assertTrue(ashChanges.size() >= 12 && ashChanges.size() <= 20,
                    "Forsaken ash chunk volume fell outside 12-20 total blocks: " + ashChanges.size());
            helper.assertTrue(connectedVoxels(ashChanges.keySet()), "Ash chunk is not face-connected in three dimensions");
            Set<BlockPos> area = footprint(ashChanges);
            int minX = area.stream().mapToInt(BlockPos::getX).min().orElseThrow();
            int maxX = area.stream().mapToInt(BlockPos::getX).max().orElseThrow();
            int minZ = area.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
            int maxZ = area.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
            helper.assertTrue(maxX - minX <= 4 && maxZ - minZ <= 4, "Ash chunk spread into a broad surface sheet");
            boolean exposed = false;
            for (BlockPos pos : ashChanges.keySet()) {
                int top = fixture.surface(pos.getX() & 15, pos.getZ() & 15);
                exposed |= pos.getY() > top;
                helper.assertTrue(pos.getY() >= top - 1 && pos.getY() <= top + 2,
                        "Ash chunk exceeded its shallow root or exposed cap");
                helper.assertTrue(new ChunkPos(pos).equals(fixture.chunk.getPos())
                                && (pos.getX() & 15) > 0 && (pos.getX() & 15) < 15
                                && (pos.getZ() & 15) > 0 && (pos.getZ() & 15) < 15,
                        "Ash chunk left its owning negative-coordinate chunk");
            }
            helper.assertTrue(exposed, "Ash chunk was buried entirely below the surface");
            var repeated = fixture(helper, fixture.chunk.getPos(), Blocks.GRANITE.defaultBlockState(), false, 0);
            helper.assertTrue(place(repeated, seed, ReferenceRockOutcropFeature.BiomeStyle.FORSAKEN)
                            && snapshot(repeated).equals(snapshot(fixture)), "Ash chunk changed under the same world seed");
        }
        double mean = volume / (double) chunks;
        helper.assertTrue(chunks >= 25 && chunks <= 80, "Forsaken ash chunks lost their roughly one-in-twenty frequency: " + chunks);
        helper.assertTrue(Math.abs(mean - 16) <= .8 && volumes.size() >= 6 && shapes.size() > 15,
                "Accepted ash chunks lost their volume or shape variation: mean=" + mean + " volumes=" + volumes);
        System.out.println("CWI_FORSAKEN_ASH_CHUNKS=1024 seeds accepted=" + chunks + " mean_volume=" + mean + " volumes=" + volumes);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void ashChunksRejectProtectedColumnsAndBiomeBoundariesAtomically(GameTestHelper helper) {
        BlockState ash = registered(helper, "kubejs:ash_stone");
        long seed = -1;
        Set<BlockPos> members = Set.of();
        for (int candidate = 0; candidate < 512; candidate++) {
            var dry = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, 0);
            if (!place(dry, candidate, ReferenceRockOutcropFeature.BiomeStyle.FORSAKEN)) continue;
            var changes = changed(dry);
            if (changes.values().stream().allMatch(state -> state == ash)) {
                seed = candidate;
                members = changes.keySet();
                break;
            }
        }
        helper.assertTrue(seed >= 0 && !members.isEmpty(), "No isolated Forsaken ash candidate exercised protection guards");
        BlockPos member = members.iterator().next().atY(128);
        for (BlockState obstruction : List.of(Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.CHEST.defaultBlockState(),
                Blocks.STONE_BRICKS.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState())) {
            var blocked = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, 0);
            blocked.chunk.setBlockState(member, obstruction, false);
            var before = snapshot(blocked);
            helper.assertTrue(!place(blocked, seed, ReferenceRockOutcropFeature.BiomeStyle.FORSAKEN)
                            && before.equals(snapshot(blocked)), "Rejected ash chunk modified protected terrain");
        }
        var boundary = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, 0);
        helper.assertTrue(!ReferenceRockOutcropFeature.placeChunk(boundary.chunk, seed, 63,
                        (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(boundary.cell, 128,
                                x == member.getX() && z == member.getZ() ? ReferenceRockOutcropFeature.BiomeStyle.SMOLDERING
                                        : ReferenceRockOutcropFeature.BiomeStyle.FORSAKEN))
                        && changed(boundary).isEmpty(), "Ash chunk crossed a surface biome boundary");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void ordinaryAshBouldersHaveLumpyVariedCapsAndOneToThreeBlockRoots(GameTestHelper helper) {
        BlockState ash = registered(helper, "kubejs:ash_stone");
        int accepted = 0, volume = 0;
        Set<Integer> burial = new HashSet<>(), caps = new HashSet<>();
        Set<Set<BlockPos>> shapes = new HashSet<>();
        for (int seed = 0; seed < 512; seed++) {
            var fixture = fixture(helper, new ChunkPos(-3, 4), Blocks.GRANITE.defaultBlockState(), false, 0);
            boolean placed = ReferenceRockOutcropFeature.placeChunk(fixture.chunk, seed, 63,
                    (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(fixture.cell, 128,
                            ReferenceRockOutcropFeature.BiomeStyle.FORSAKEN), 10);
            if (!placed) continue;
            Map<BlockPos, BlockState> ashChanges = new HashMap<>();
            for (var entry : changed(fixture).entrySet()) if (entry.getValue() == ash) ashChanges.put(entry.getKey(), ash);
            if (ashChanges.isEmpty()) continue;
            accepted++; volume += ashChanges.size(); shapes.add(Set.copyOf(ashChanges.keySet()));
            helper.assertTrue(ashChanges.size() >= 12 && ashChanges.size() <= 20
                            && connectedVoxels(ashChanges.keySet()), "Ordinary ash boulder lost its small connected volume");
            for (BlockPos column : footprint(ashChanges)) {
                int min = ashChanges.keySet().stream().filter(pos -> pos.getX() == column.getX() && pos.getZ() == column.getZ())
                        .mapToInt(BlockPos::getY).min().orElseThrow();
                int max = ashChanges.keySet().stream().filter(pos -> pos.getX() == column.getX() && pos.getZ() == column.getZ())
                        .mapToInt(BlockPos::getY).max().orElseThrow();
                burial.add(128 - min); caps.add(max - 128);
                helper.assertTrue(min >= 125 && min <= 127 && max >= 129 && max <= 131,
                        "Ordinary ash root or cap exceeded1–3 block range");
                helper.assertTrue(fixture.chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                                column.getX(), column.getZ()) == max,
                        "Ordinary ash boulder left a stale surface heightmap");
            }
            var repeated = fixture(helper, fixture.chunk.getPos(), Blocks.GRANITE.defaultBlockState(), false, 0);
            helper.assertTrue(ReferenceRockOutcropFeature.placeChunk(repeated.chunk, seed, 63,
                            (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(repeated.cell, 128,
                                    ReferenceRockOutcropFeature.BiomeStyle.FORSAKEN), 10)
                            && snapshot(fixture).equals(snapshot(repeated)), "Ordinary ash boulder is nondeterministic");
        }
        helper.assertTrue(accepted >= 12 && accepted <= 50 && Math.abs(volume / (double) accepted - 16) < 1.2,
                "Ordinary ash boulders lost sparse placement or approximately16block mean size");
        helper.assertTrue(burial.equals(Set.of(1, 2, 3)) && caps.equals(Set.of(1, 2, 3)) && shapes.size() >= 12,
                "Ordinary ash boulders lost lumpy cap, burial or shape variation");
        System.out.println("CWI_ASH_BOULDER_NATIVE=accepted:" + accepted + ",mean_volume:" + volume / (double) accepted
                + ",burial:" + burial + ",caps:" + caps + ",shapes:" + shapes.size());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void profileTwelveOutcropsFollowActualHostContactsThroughCustomWeatheredCover(GameTestHelper helper) {
        BlockState shale = registered(helper, "kubejs:shale");
        boolean exercised = false;
        for (int seed = 0; seed < 128 && !exercised; seed++) {
            var fixture = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                String name = x < 8 ? "granite" : "shale";
                for (int y = 112; y <= 125; y++) fixture.chunk.setBlockState(new BlockPos(x, y, z),
                        x < 8 ? Blocks.GRANITE.defaultBlockState() : shale, false);
                fixture.chunk.setBlockState(new BlockPos(x, 127, z), registered(helper, "kubejs:cobbled_" + name), false);
                fixture.chunk.setBlockState(new BlockPos(x, 128, z), registered(helper, "kubejs:" + name + "_gravel"), false);
            }
            var before = snapshot(fixture);
            boolean placed = ReferenceRockOutcropFeature.placeChunk(fixture.chunk, seed, 63,
                    (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(fixture.cell, 128, true), 12);
            if (!placed) continue;
            Set<BlockPos> changes = new HashSet<>();
            boolean graniteSeen = false, shaleSeen = false;
            for (var entry : snapshot(fixture).entrySet()) {
                if (entry.getValue().equals(before.get(entry.getKey()))) continue;
                BlockPos pos = entry.getKey();
                BlockState expected = pos.getX() < 8 ? Blocks.GRANITE.defaultBlockState() : shale;
                helper.assertTrue(entry.getValue().equals(expected), "Outcrop crossed an actual registered host contact at " + pos);
                graniteSeen |= pos.getX() < 8;
                shaleSeen |= pos.getX() >= 8;
                changes.add(pos);
            }
            helper.assertTrue(!changes.isEmpty() && connectedVoxels(changes), "Weathered cover yielded a detached or empty outcrop");
            exercised = graniteSeen && shaleSeen;
        }
        helper.assertTrue(exercised, "No outcrop crossed the actual granite/shale contact through custom gravel and cobble");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void profilesOneThroughElevenRetainLegacyNativeOutcropBlocks(GameTestHelper helper) {
        var candidate = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
        long seed = findSeed(helper, candidate);
        var expected = snapshot(candidate);
        for (int version = 1; version <= 11; version++) {
            var actual = fixture(helper, ChunkPos.ZERO, Blocks.GRANITE.defaultBlockState(), false, .5F);
            ReferenceRockOutcropFeature.placeChunk(actual.chunk, seed, 63,
                    (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(actual.cell, 128, true), version);
            helper.assertTrue(snapshot(actual).equals(expected), "Profile " + version + " changed legacy native outcrop blocks");
        }
        helper.succeed();
    }

    private static BlockState registered(GameTestHelper helper, String id) {
        BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
        helper.assertTrue(!state.isAir(), "Required outcrop material is not registered: " + id);
        return state;
    }

    private static Fixture fixture(GameTestHelper helper, ChunkPos pos, BlockState rock, boolean slope, float gradient) {
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        Cell cell = new Cell();
        cell.terrain = TerrainType.HILLS;
        cell.height = .7F;
        cell.gradient = gradient;
        Fixture fixture = new Fixture(chunk, cell, rock, slope);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int top = fixture.surface(x, z);
            for (int y = 112; y <= top; y++) chunk.setBlockState(new BlockPos(pos.getBlockX(x), y, pos.getBlockZ(z)),
                    y < top - 2 ? rock : Blocks.DIRT.defaultBlockState(), false);
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        return fixture;
    }

    private static boolean place(Fixture fixture, long seed) {
        return ReferenceRockOutcropFeature.placeChunk(fixture.chunk, seed, 63,
                (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(fixture.cell, fixture.surface(x, z), true));
    }

    private static boolean place(Fixture fixture, long seed, ReferenceRockOutcropFeature.BiomeStyle biome) {
        return ReferenceRockOutcropFeature.placeChunk(fixture.chunk, seed, 63,
                (x, z) -> new ReferenceRockOutcropFeature.TerrainColumn(fixture.cell, fixture.surface(x, z), biome));
    }

    private static long findSeed(GameTestHelper helper, Fixture fixture) {
        for (long seed = 0; seed < 256; seed++) if (place(fixture, seed)) return seed;
        helper.fail("No outcrop in 256 deterministic dry-slope candidates");
        return -1;
    }

    private static Map<BlockPos, BlockState> changed(Fixture fixture) {
        Map<BlockPos, BlockState> changes = new HashMap<>();
        for (var entry : snapshot(fixture).entrySet()) {
            BlockPos pos = entry.getKey();
            int top = fixture.surface(pos.getX() & 15, pos.getZ() & 15);
            BlockState original = pos.getY() > top ? Blocks.AIR.defaultBlockState()
                    : pos.getY() < top - 2 ? fixture.rock : Blocks.DIRT.defaultBlockState();
            if (entry.getValue() != original) changes.put(pos, entry.getValue());
        }
        return changes;
    }

    private static Map<BlockPos, BlockState> snapshot(Fixture fixture) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        ChunkPos pos = fixture.chunk.getPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 116; y <= 142; y++) {
            BlockPos block = new BlockPos(pos.getBlockX(x), y, pos.getBlockZ(z));
            result.put(block, fixture.chunk.getBlockState(block));
        }
        return result;
    }

    private static Set<BlockPos> footprint(Map<BlockPos, BlockState> changes) {
        Set<BlockPos> result = new HashSet<>();
        for (BlockPos pos : changes.keySet()) result.add(pos.atY(0));
        return result;
    }

    private static boolean connected(Set<BlockPos> footprint) {
        Set<BlockPos> remaining = new HashSet<>(footprint);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos first = remaining.iterator().next();
        remaining.remove(first);
        queue.add(first);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.remove();
            for (BlockPos neighbor : List.of(pos.north(), pos.south(), pos.east(), pos.west())) {
                if (remaining.remove(neighbor)) queue.add(neighbor);
            }
        }
        return remaining.isEmpty();
    }

    private static boolean connectedVoxels(Set<BlockPos> voxels) {
        if (voxels.isEmpty()) return false;
        Set<BlockPos> remaining = new HashSet<>(voxels);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos first = remaining.iterator().next();
        remaining.remove(first);
        queue.add(first);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.remove();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = pos.relative(direction);
                if (remaining.remove(neighbor)) queue.add(neighbor);
            }
        }
        return remaining.isEmpty();
    }

    private record Fixture(ProtoChunk chunk, Cell cell, BlockState rock, boolean slope) {
        int surface(int localX, int localZ) { return 128 + (slope ? localX / 2 : 0); }
    }
}
