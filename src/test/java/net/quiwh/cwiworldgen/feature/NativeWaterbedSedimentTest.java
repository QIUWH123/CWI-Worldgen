package net.quiwh.cwiworldgen.feature;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
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
import net.quiwh.cwiworldgen.terrain.WaterbedMaterials;

/** Small actual-block fixtures; no reference-terrain contexts or neighboring chunk loads. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeWaterbedSedimentTest {
    private static final int WATER_TOP = 64;

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void profile13WorldspaceMasksKeepActualBedSafetyAndRepeatability(GameTestHelper helper) {
        int profileDifferences = 0, placed = 0;
        for (ChunkPos position : List.of(new ChunkPos(-1, -1), new ChunkPos(0, -1))) {
            Fixture fixture = fixture(helper, position, true);
            Fixture reversed = fixture(helper, position, true);
            for (Fixture target : List.of(fixture, reversed)) {
                // Excluded structure footprint, dry shore, unsupported cover and a protected ore.
                for (int y = target.floor(2, 1) + 1; y <= WATER_TOP; y++)
                    target.chunk.setBlockState(target.pos(2, y, 1), Blocks.AIR.defaultBlockState(), false);
                target.chunk.setBlockState(target.pos(3, target.floor(3, 1) - 1, 1), Blocks.AIR.defaultBlockState(), false);
                target.chunk.setBlockState(target.pos(4, target.floor(4, 1) - 1, 1), Blocks.DIAMOND_ORE.defaultBlockState(), false);
                prime(target.chunk);
            }
            var before = snapshot(fixture);
            int[] surface = heights(fixture, Heightmap.Types.WORLD_SURFACE_WG);
            int[] floor = heights(fixture, Heightmap.Types.OCEAN_FLOOR_WG);
            var lookup = (WaterbedSedimentFeature.ColumnLookup) (x, z) -> {
                if (x < 0 || x >= 16 || z < 0 || z >= 16)
                    throw new AssertionError("Profile13 queried an unowned column");
                return new WaterbedSedimentFeature.TerrainColumn(x >= 2, true, .35);
            };
            WaterbedSedimentFeature.placeChunk(fixture.chunk, 42L, lookup, 13);
            WaterbedSedimentFeature.placeChunk(reversed.chunk, 42L, lookup, 13);
            helper.assertTrue(snapshot(fixture).equals(snapshot(reversed)), "Profile13 actual blocks are not repeatable");
            var settled = snapshot(fixture);
            WaterbedSedimentFeature.placeChunk(fixture.chunk, 42L, lookup, 13);
            helper.assertTrue(snapshot(fixture).equals(settled), "Profile13 bed pass is not idempotent");
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                BlockPos top = fixture.pos(x, fixture.floor(x, z), z);
                boolean excluded = x < 2 || z == 1 && x >= 2 && x <= 4;
                if (excluded) {
                    for (int y = 45; y <= 72; y++) helper.assertTrue(
                            fixture.chunk.getBlockState(fixture.pos(x, y, z)) == before.get(fixture.pos(x, y, z)),
                            "Profile13 changed a dry, unsupported, protected or excluded column");
                    continue;
                }
                int expected = WaterbedMaterials.material(42L, top.getX(), top.getZ(), true, .35, 13);
                BlockState actualTop = fixture.chunk.getBlockState(top);
                int actual = kind(actualTop, fixture.soil);
                helper.assertTrue(actual == expected, "Profile13 production adapter selected a legacy or chunk-local material");
                if (actual == WaterbedMaterials.ROCK) helper.assertTrue(
                        x < 8 ? actualTop.is(Blocks.STONE) || actualTop.is(Blocks.COBBLESTONE) : actualTop.is(Blocks.TUFF),
                        "Profile13 crossed the local stone/tuff host boundary");
                if (actual == WaterbedMaterials.GRAVEL || actual == WaterbedMaterials.SAND)
                    helper.assertTrue(!fixture.chunk.getBlockState(top.below()).isAir(),
                            "Profile13 introduced unsupported falling sediment");
                profileDifferences += expected != WaterbedMaterials.material(42L, top.getX(), top.getZ(), true, .35) ? 1 : 0;
                placed += actual != WaterbedMaterials.SOIL ? 1 : 0;
                for (int y = fixture.floor(x, z) + 1; y <= WATER_TOP; y++) helper.assertTrue(
                        fixture.chunk.getBlockState(fixture.pos(x, y, z)) == before.get(fixture.pos(x, y, z)),
                        "Profile13 material pass edited water");
                int depth = WaterbedMaterials.depth(42L, top.getX(), top.getZ(), expected);
                for (int y = 45; y < fixture.floor(x, z) - depth + 1; y++) helper.assertTrue(
                        fixture.chunk.getBlockState(fixture.pos(x, y, z)) == before.get(fixture.pos(x, y, z)),
                        "Profile13 exceeded its existing shallow material-depth bound");
            }
            helper.assertTrue(java.util.Arrays.equals(surface, heights(fixture, Heightmap.Types.WORLD_SURFACE_WG))
                            && java.util.Arrays.equals(floor, heights(fixture, Heightmap.Types.OCEAN_FLOOR_WG)),
                    "Profile13 seabed materials changed terrain or water heights");
        }
        helper.assertTrue(placed > 200 && profileDifferences > 100,
                "Profile13 native positive fixtures did not exercise the new worldspace mask");
        System.out.println("CWI_WATERBED_PROFILE13=placed:" + placed + ",different_from12:" + profileDifferences
                + ",water_height:unchanged,depth:bounded,protected:unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void loadedCanonicalFeaturesReachSurfaceBiomesBeforeMinerals(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        for (String material : List.of("sand", "gravel", "clay", "claystone")) {
            var feature = registry.get(ResourceLocation.parse("cwi:disks/" + material));
            helper.assertTrue(feature != null && feature.feature() instanceof IrregularSedimentFeature,
                    "Canonical sediment ID still loads a vanilla disk for " + material);
        }
        var biomeRegistry = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        for (String name : List.of("forsaken_land", "smoldering_barren", "deep_ocean", "molten_caves")) {
            var biome = biomeRegistry.get(ResourceLocation.parse("cwi:" + name));
            var raw = biome.getGenerationSettings().features()
                    .get(net.minecraft.world.level.levelgen.GenerationStep.Decoration.RAW_GENERATION.ordinal());
            long count = raw.stream().filter(holder -> holder.unwrapKey().orElseThrow().location()
                    .equals(ResourceLocation.parse("cwi:waterbed_sediments"))).count();
            helper.assertTrue(count == (name.equals("molten_caves") ? 0 : 1),
                    "Waterbed pass is missing, duplicated or active in an underground biome: " + name);
        }
        System.out.println("CWI_WATERBED_LOADED=canonical_patches:4,surface_biomes:3,stage:RAW_GENERATION");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void riverAndOceanBedsHaveCoherentStoneGravelSandAndClayDistricts(GameTestHelper helper) {
        int[] counts = new int[5];
        int transitions = 0, neighbors = 0;
        for (long seed : new long[]{0L, 42L, -7L, 123456789L}) {
            for (boolean ocean : new boolean[]{false, true}) {
                for (ChunkPos position : List.of(new ChunkPos(-17, 9), new ChunkPos(33, -20))) {
                    Fixture fixture = fixture(helper, position, false);
                    place(fixture, seed, ocean, ocean ? .18 : .7);
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                        int kind = kind(fixture.chunk.getBlockState(fixture.pos(x, fixture.floor(x, z), z)), fixture.soil);
                        helper.assertTrue(kind >= 0, "Waterbed finishing introduced an unrelated material");
                        counts[kind]++;
                        if (x > 0) {
                            transitions += kind == kind(fixture.chunk.getBlockState(fixture.pos(x - 1,
                                    fixture.floor(x - 1, z), z)), fixture.soil) ? 0 : 1;
                            neighbors++;
                        }
                        if (z > 0) {
                            transitions += kind == kind(fixture.chunk.getBlockState(fixture.pos(x,
                                    fixture.floor(x, z - 1), z - 1)), fixture.soil) ? 0 : 1;
                            neighbors++;
                        }
                    }
                }
            }
        }
        int total = 16 * 256;
        helper.assertTrue(total - counts[0] > total * .4,
                "Actual submerged beds remain dominated by depleted dirt instead of varied sediment and rock");
        for (int kind = 1; kind < counts.length; kind++) helper.assertTrue(counts[kind] > 0,
                "Actual-block river/ocean fixtures omitted material class " + kind);
        helper.assertTrue(transitions < neighbors * .55,
                "Neighboring waterbed materials became per-block confetti rather than coherent districts");
        System.out.println("CWI_WATERBED_MATERIALS=columns:" + total + ",dirt:" + counts[0]
                + ",rock:" + counts[1] + ",gravel:" + counts[2] + ",sand:" + counts[3]
                + ",clay:" + counts[4] + ",transitions:" + transitions + ",neighbors:" + neighbors);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void slopedBedsKeepWaterHeightSupportAndLocalRockWithoutCrossChunkWrites(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new ChunkPos(-3, -2), true);
        var before = snapshot(fixture);
        int[] surface = heights(fixture, Heightmap.Types.WORLD_SURFACE_WG);
        int[] floor = heights(fixture, Heightmap.Types.OCEAN_FLOOR_WG);
        helper.assertTrue(place(fixture, 42L, false, .85), "Submerged sloped fixture received no sediment changes");
        int changed = 0;
        for (var entry : snapshot(fixture).entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState original = before.get(pos), actual = entry.getValue();
            int localX = pos.getX() & 15, localZ = pos.getZ() & 15;
            int top = fixture.floor(localX, localZ);
            if (actual == original) continue;
            changed++;
            helper.assertTrue(new ChunkPos(pos).equals(fixture.chunk.getPos())
                            && pos.getY() <= top && pos.getY() >= top - 4,
                    "Waterbed edit changed water, terrain height, deep geology or a neighboring chunk at " + pos);
            helper.assertTrue(!actual.isAir() && actual.getFluidState().isEmpty(),
                    "Waterbed finishing left unsupported air or fluid in its existing solid bed");
            helper.assertTrue(actual.isFaceSturdy(fixture.chunk, pos, net.minecraft.core.Direction.UP)
                            && !fixture.chunk.getBlockState(pos.below()).isAir(),
                    "Waterbed finishing created an unsupported sediment or rock block");
            if (actual.is(Blocks.STONE) || actual.is(Blocks.TUFF)) {
                helper.assertTrue(actual.is(localX < 8 ? Blocks.STONE : Blocks.TUFF),
                        "Waterbed rock crossed the underlying stone/tuff geology boundary");
            }
            if (actual.is(Blocks.COBBLESTONE)) helper.assertTrue(localX < 8,
                    "Worn stone replaced the local tuff geology");
        }
        helper.assertTrue(changed > 0, "Positive waterbed fixture changed no actual blocks");
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            for (int y = fixture.floor(x, z) + 1; y <= WATER_TOP; y++) {
                BlockPos pos = fixture.pos(x, y, z);
                helper.assertTrue(fixture.chunk.getBlockState(pos) == before.get(pos)
                                && fixture.chunk.getBlockState(pos).is(Blocks.WATER),
                        "River sediment replaced vanilla water or altered its source state at " + pos);
            }
            helper.assertTrue(surface[z * 16 + x] == fixture.chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z)
                            && floor[z * 16 + x] == fixture.chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z),
                    "Material-only bed finishing changed its water or ocean-floor heightmap");
        }
        System.out.println("CWI_WATERBED_SLOPE=changed_blocks:" + changed + ",water_height:unchanged,geology:local");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void dryCoveredIneligibleAndProtectedBedsStayUntouched(GameTestHelper helper) {
        Fixture dry = fixture(helper, ChunkPos.ZERO, false);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            for (int y = dry.floor(x, z) + 1; y <= WATER_TOP; y++)
                dry.chunk.setBlockState(dry.pos(x, y, z), Blocks.AIR.defaultBlockState(), false);
        prime(dry.chunk);
        var dryBefore = snapshot(dry);
        helper.assertTrue(!place(dry, 42L, false, .8) && snapshot(dry).equals(dryBefore),
                "Aquatic sediment spread onto dry land");

        Fixture ineligible = fixture(helper, ChunkPos.ZERO, false);
        var ineligibleBefore = snapshot(ineligible);
        helper.assertTrue(!WaterbedSedimentFeature.placeChunk(ineligible.chunk, 42L,
                        (x, z) -> new WaterbedSedimentFeature.TerrainColumn(false, false, .8))
                        && snapshot(ineligible).equals(ineligibleBefore),
                "Aquatic sediment entered a terrain column excluded by its production adapter");

        Fixture covered = fixture(helper, ChunkPos.ZERO, false);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            covered.chunk.setBlockState(covered.pos(x, 70, z), Blocks.STONE_BRICKS.defaultBlockState(), false);
        prime(covered.chunk);
        var coveredBefore = snapshot(covered);
        helper.assertTrue(!place(covered, 42L, false, .8) && snapshot(covered).equals(coveredBefore),
                "Aquatic surface decoration entered covered pools or a structure floor");

        Fixture protectedBed = fixture(helper, ChunkPos.ZERO, false);
        BlockState deposit = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:hematite_deposit")).defaultBlockState();
        helper.assertTrue(!deposit.isAir(), "Required compound deposit is absent from the native fixture registry");
        List<BlockState> protectedStates = List.of(Blocks.DIAMOND_ORE.defaultBlockState(), deposit,
                Blocks.CHEST.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState());
        Map<BlockPos, BlockState> protectedPositions = new HashMap<>();
        for (int i = 0; i < protectedStates.size(); i++) for (int depth = 0; depth <= 2; depth++) {
            int x = 2 + i * 3, z = 3 + depth * 3;
            BlockPos pos = protectedBed.pos(x, protectedBed.floor(x, z) - depth, z);
            protectedBed.chunk.setBlockState(pos, protectedStates.get(i), false);
            protectedPositions.put(pos, protectedStates.get(i));
        }
        int unsupportedX = 1, unsupportedZ = 13;
        BlockPos unsupported = protectedBed.pos(unsupportedX, protectedBed.floor(unsupportedX, unsupportedZ), unsupportedZ);
        protectedBed.chunk.setBlockState(unsupported.below(), Blocks.AIR.defaultBlockState(), false);
        prime(protectedBed.chunk);
        place(protectedBed, 42L, false, .8);
        for (var entry : protectedPositions.entrySet()) helper.assertTrue(
                protectedBed.chunk.getBlockState(entry.getKey()) == entry.getValue(),
                "Waterbed sediment changed an ore, compound deposit, container or crafted block at " + entry.getKey());
        helper.assertTrue(protectedBed.chunk.getBlockState(unsupported) == protectedBed.soil
                        && protectedBed.chunk.getBlockState(unsupported.below()).isAir(),
                "Waterbed sediment accepted unsupported cover or filled a cave void");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void negativeCoordinateChunksAreSeededRepeatableAndOrderIndependent(GameTestHelper helper) {
        Fixture westFirst = fixture(helper, new ChunkPos(-1, -1), true);
        Fixture eastSecond = fixture(helper, new ChunkPos(0, -1), true);
        Fixture eastFirst = fixture(helper, new ChunkPos(0, -1), true);
        Fixture westSecond = fixture(helper, new ChunkPos(-1, -1), true);
        place(westFirst, -987654321L, false, .55);
        place(eastSecond, -987654321L, false, .55);
        place(eastFirst, -987654321L, false, .55);
        place(westSecond, -987654321L, false, .55);
        helper.assertTrue(snapshot(westFirst).equals(snapshot(westSecond))
                        && snapshot(eastFirst).equals(snapshot(eastSecond)),
                "Waterbed material depends on chunk order or negative-coordinate traversal");
        var settled = snapshot(westFirst);
        place(westFirst, -987654321L, false, .55);
        helper.assertTrue(snapshot(westFirst).equals(settled),
                "Repeating the same aquatic material pass changes settled natural bed blocks");
        Fixture otherSeed = fixture(helper, new ChunkPos(-1, -1), true);
        place(otherSeed, 123456789L, false, .55);
        helper.assertTrue(!snapshot(otherSeed).equals(settled),
                "Different world seeds reused the same aquatic material layout");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void exposedCoastsAndDryRiverMarginsRemainUnchanged(GameTestHelper helper) {
        int checked = 0;
        for (long seed : new long[]{0L, 42L, -7L, 123456789L}) {
            for (boolean ocean : new boolean[]{false, true}) {
                Fixture shore = fixture(helper, new ChunkPos(-17, 9), true);
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                    for (int y = shore.floor(x, z) + 1; y <= WATER_TOP; y++)
                        shore.chunk.setBlockState(shore.pos(x, y, z), Blocks.AIR.defaultBlockState(), false);
                prime(shore.chunk);
                var before = snapshot(shore);
                int[] floor = heights(shore, Heightmap.Types.OCEAN_FLOOR_WG);
                int[] surface = heights(shore, Heightmap.Types.WORLD_SURFACE_WG);
                helper.assertTrue(!place(shore, seed, ocean, .45),
                        "Aquatic finishing recolored exposed land marked as a water candidate");
                helper.assertTrue(snapshot(shore).equals(before),
                        "Dry coast or river margin acquired a sand-and-stone overlay");
                helper.assertTrue(java.util.Arrays.equals(floor, heights(shore, Heightmap.Types.OCEAN_FLOOR_WG))
                                && java.util.Arrays.equals(surface, heights(shore, Heightmap.Types.WORLD_SURFACE_WG)),
                        "Rejecting dry shores changed terrain heightmaps");
                checked += 256;
            }
        }
        System.out.println("CWI_DRY_SHORE_PRESERVED=columns:" + checked + ",blocks:unchanged,height:unchanged");
        helper.succeed();
    }

    private static boolean place(Fixture fixture, long seed, boolean ocean, double energy) {
        return WaterbedSedimentFeature.placeChunk(fixture.chunk, seed, (x, z) -> {
            if (x < 0 || x >= 16 || z < 0 || z >= 16)
                throw new AssertionError("Aquatic bed pass requested an unowned chunk column");
            return new WaterbedSedimentFeature.TerrainColumn(true, ocean, energy);
        });
    }

    private static Fixture fixture(GameTestHelper helper, ChunkPos position, boolean sloped) {
        var chunk = new ProtoChunk(position, UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        BlockState soil = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:depleted_dirt")).defaultBlockState();
        helper.assertTrue(!soil.isAir(), "Depleted dirt is absent from the native fixture registry");
        Fixture fixture = new Fixture(chunk, soil, sloped);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int floor = fixture.floor(x, z);
            for (int y = 48; y <= floor; y++) chunk.setBlockState(fixture.pos(x, y, z),
                    y >= floor - 2 ? soil : (x < 8 ? Blocks.STONE : Blocks.TUFF).defaultBlockState(), false);
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
        int[] result = new int[256];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            result[z * 16 + x] = fixture.chunk.getHeight(type, x, z);
        return result;
    }

    private static Map<BlockPos, BlockState> snapshot(Fixture fixture) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 45; y <= 72; y++) {
            BlockPos pos = fixture.pos(x, y, z);
            result.put(pos, fixture.chunk.getBlockState(pos));
        }
        return result;
    }

    private static int kind(BlockState state, BlockState soil) {
        if (state == soil) return 0;
        if (state.is(Blocks.STONE) || state.is(Blocks.TUFF) || state.is(Blocks.COBBLESTONE)) return 1;
        if (state.is(Blocks.GRAVEL)) return 2;
        if (state.is(Blocks.SAND)) return 3;
        if (state.is(Blocks.CLAY)) return 4;
        return -1;
    }

    private record Fixture(ProtoChunk chunk, BlockState soil, boolean sloped) {
        int floor(int x, int z) { return 56 + (sloped ? (x + z) / 8 : 0); }
        BlockPos pos(int x, int y, int z) { return new BlockPos(chunk.getPos().getBlockX(x), y, chunk.getPos().getBlockZ(z)); }
    }
}
