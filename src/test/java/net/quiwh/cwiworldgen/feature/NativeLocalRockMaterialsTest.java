package net.quiwh.cwiworldgen.feature;

import java.lang.reflect.InvocationTargetException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedBlockStateProvider;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.WaterbedMaterials;

/** Actual registered variants; reflection permits a behavioral RED before the new resolver exists. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeLocalRockMaterialsTest {
    @GameTest(template = "empty")
    public static void registeredFamiliesResolveRawCobbleGravelAndRejectProtectedBlocks(GameTestHelper helper) {
        for (String name : new String[]{"granite", "diorite", "andesite", "basalt", "deepslate", "claystone",
                "schist", "gneiss", "rhyolite", "shale", "conglomerate", "phyllite", "quartzite", "darkslate"}) {
            String raw = (java.util.Set.of("granite", "diorite", "andesite", "basalt", "deepslate").contains(name)
                    ? "minecraft:" : "kubejs:") + name;
            String cobble = name.equals("deepslate") ? "minecraft:cobbled_deepslate" : "kubejs:cobbled_" + name;
            BlockState rawState = required(helper, raw), cobbleState = required(helper, cobble);
            BlockState gravel = required(helper, "kubejs:" + name + "_gravel");
            helper.assertTrue(gravel.getBlock() instanceof FallingBlock, "Native registry must use actual falling gravel: " + name);
            for (BlockState source : new BlockState[]{rawState, cobbleState, gravel}) {
                Object family = invoke("family", new Class<?>[]{BlockState.class}, source);
                helper.assertTrue(family != null, "Registered family was not recognized: " + source);
                helper.assertTrue(component(family, "raw").equals(rawState)
                                && component(family, "cobble").equals(cobbleState)
                                && component(family, "gravel").equals(gravel),
                        "Family conversion crossed registered rock identity: " + source);
                helper.assertTrue((boolean) invoke("natural", new Class<?>[]{BlockState.class}, source),
                        "A registered rock family was excluded from shared natural substrates");
            }
        }
        for (BlockState protectedState : new BlockState[]{Blocks.DIAMOND_ORE.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(),
                required(helper, "kubejs:hematite_deposit")}) {
            helper.assertTrue(invoke("family", new Class<?>[]{BlockState.class}, protectedState) == null
                            && !(boolean) invoke("natural", new Class<?>[]{BlockState.class}, protectedState),
                    "Ore, deposit, crafted block or block entity entered natural rock resolver");
        }
        Object tuff = invoke("family", new Class<?>[]{BlockState.class}, Blocks.TUFF.defaultBlockState());
        helper.assertTrue(component(tuff, "raw").is(Blocks.TUFF) && component(tuff, "cobble").is(Blocks.TUFF)
                        && component(tuff, "gravel").is(Blocks.GRAVEL),
                "Missing optional tuff variants produced air or invented blocks instead of safe fallback");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void profileTwelveWaterbedsMatchHostContactsWithoutChangingMaterialOrDepthDistribution(GameTestHelper helper) {
        int gravelCount = 0, wornCount = 0, sandCount = 0, clayCount = 0;
        for (long seed : new long[]{0, 42, -7, 123456789}) {
            ProtoChunk chunk = bed(helper);
            var before = snapshot(chunk);
            placeBed(chunk, seed, 12);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int kind = WaterbedMaterials.material(seed, x, z, true, .55);
                BlockState actual = chunk.getBlockState(new BlockPos(x, 56, z));
                String name = x < 8 ? "granite" : "shale";
                BlockState expected = switch (kind) {
                    case WaterbedMaterials.ROCK -> WaterbedMaterials.rockWear(seed, x, z) > .66
                            ? required(helper, "kubejs:cobbled_" + name)
                            : required(helper, (x < 8 ? "minecraft:" : "kubejs:") + name);
                    case WaterbedMaterials.GRAVEL -> required(helper, "kubejs:" + name + "_gravel");
                    case WaterbedMaterials.SAND -> Blocks.SAND.defaultBlockState();
                    case WaterbedMaterials.CLAY -> Blocks.CLAY.defaultBlockState();
                    default -> Blocks.DIRT.defaultBlockState();
                };
                helper.assertTrue(actual.equals(expected), "Profile12 crossed local host contact or changed class at " + x + "," + z);
                int depth = WaterbedMaterials.depth(seed, x, z, kind);
                for (int y = 48; y <= 64; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState expectedLayer = kind != WaterbedMaterials.SOIL && y <= 56 && y > 56 - depth
                            ? expected : before.get(pos);
                    helper.assertTrue(chunk.getBlockState(pos).equals(expectedLayer),
                            "Local material conversion changed depth, water or deeper strata at " + pos);
                }
                gravelCount += kind == WaterbedMaterials.GRAVEL ? 1 : 0;
                wornCount += kind == WaterbedMaterials.ROCK && WaterbedMaterials.rockWear(seed, x, z) > .66 ? 1 : 0;
                sandCount += kind == WaterbedMaterials.SAND ? 1 : 0;
                clayCount += kind == WaterbedMaterials.CLAY ? 1 : 0;
            }
        }
        helper.assertTrue(gravelCount > 0 && wornCount > 0 && sandCount > 0 && clayCount > 0,
                "Fixture failed to exercise gravel, wear, vanilla sand and vanilla clay");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void profileElevenWaterbedMatchesLegacyEntryPoint(GameTestHelper helper) {
        for (long seed : new long[]{0, 42, -7}) {
            ProtoChunk old = bed(helper), gated = bed(helper);
            WaterbedSedimentFeature.placeChunk(old, seed, (x, z) -> new WaterbedSedimentFeature.TerrainColumn(true, true, .55));
            placeBed(gated, seed, 11);
            helper.assertTrue(snapshot(old).equals(snapshot(gated)), "Profile11 changed legacy aquatic blocks");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void profileTwelveBedsRejectProtectedAndHollowShallowColumnsAtomically(GameTestHelper helper) {
        for (BlockState blocker : new BlockState[]{Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.CHEST.defaultBlockState(),
                Blocks.STONE_BRICKS.defaultBlockState(), required(helper, "kubejs:hematite_deposit"), Blocks.AIR.defaultBlockState()}) {
            ProtoChunk chunk = bed(helper);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                chunk.setBlockState(new BlockPos(x, 55, z), blocker, false);
            prime(chunk);
            var before = snapshot(chunk);
            placeBed(chunk, 42, 12);
            helper.assertTrue(snapshot(chunk).equals(before), "Local gravel replaced a protected or hollow shallow column");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void laterClayLensAcceptsRegisteredGravelWithSturdySupport(GameTestHelper helper) {
        var level = helper.getLevel();
        var gravel = required(helper, "kubejs:granite_gravel");
        var config = new DiskConfiguration(RuleBasedBlockStateProvider.simple(Blocks.CLAY),
                BlockPredicate.matchesBlocks(Blocks.DIRT, Blocks.GRAVEL), ConstantInt.of(2), 2);
        BlockPos floor = helper.absolutePos(new BlockPos(4, 12, 4));
        for (int depth = 0; depth < 3; depth++) level.setBlock(floor.below(depth), gravel, 2);
        level.setBlock(floor.below(3), required(helper, "kubejs:cobbled_granite"), 2);
        for (int up = 1; up <= 4; up++) level.setBlock(floor.above(up), Blocks.WATER.defaultBlockState(), 2);
        Heightmap.primeHeightmaps(level.getChunkAt(floor), EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
        boolean changed = (boolean) invokeOn(IrregularSedimentFeature.class, "placeColumn",
                new Class<?>[]{WorldGenLevel.class, DiskConfiguration.class, RandomSource.class,
                        int.class, int.class, int.class, int.class}, level, config, RandomSource.create(42),
                floor.getX(), floor.getZ(), 3, 12);
        helper.assertTrue(changed, "Later configured gravel target did not accept the registered custom gravel");
        for (int depth = 0; depth < 3; depth++) helper.assertTrue(level.getBlockState(floor.below(depth)).is(Blocks.CLAY),
                "Later clay lens failed through registered local gravel");
        helper.assertTrue(level.getBlockState(floor.below(3)).equals(required(helper, "kubejs:cobbled_granite"))
                        && level.getBlockState(floor.above()).is(Blocks.WATER), "Clay lens modified its cobble support or water");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void genericCaveBouldersMatchHostButPreserveExplicitCrystalPaletteAndLegacyRandom(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(new BlockPos(4, 12, 4));
        // GameTest structure offsets need not align to chunks; keep this positive fixture inside its owner.
        BlockPos origin = new BlockPos((absolute.getX() & ~15) + 8, absolute.getY(), (absolute.getZ() & ~15) + 8);
        long legacyRandom = 0;
        for (int version : new int[]{11, 12}) {
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                level.setBlock(origin.offset(x, -1, z), required(helper, "kubejs:shale"), 2);
                for (int y = 0; y <= 3; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            var random = RandomSource.create(42);
            var config = new SmallBoulderConfiguration(
                    net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider.simple(Blocks.GRANITE),
                    java.util.Optional.empty(), 0);
            var feature = new SmallBoulderFeature(SmallBoulderConfiguration.CODEC);
            var context = new net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<>(java.util.Optional.empty(), level,
                    level.getChunkSource().getGenerator(), random, origin, config);
            helper.assertTrue((boolean) invokeOn(SmallBoulderFeature.class, "place",
                    new Class<?>[]{net.minecraft.world.level.levelgen.feature.FeaturePlaceContext.class, int.class},
                    feature, context, version), "Supported generic cave boulder was rejected");
            int blocks = 0;
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 2; y++) {
                BlockState actual = level.getBlockState(origin.offset(x, y, z));
                if (actual.isAir()) continue;
                helper.assertTrue(actual.equals(version == 11 ? Blocks.GRANITE.defaultBlockState() : required(helper, "kubejs:shale")),
                        "Generic cave boulder ignored its profile or actual local host");
                blocks++;
            }
            helper.assertTrue(blocks > 0, "Generic cave fixture placed no actual blocks");
            long after = random.nextLong();
            if (version == 11) legacyRandom = after;
            else helper.assertTrue(after == legacyRandom, "Local boulder palette consumed extra random draws");
        }
        BlockPos crystal = origin;
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 3; y++)
            level.setBlock(crystal.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        var config = new SmallBoulderConfiguration(
                net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider.simple(Blocks.AMETHYST_BLOCK),
                java.util.Optional.empty(), 0);
        var feature = new SmallBoulderFeature(SmallBoulderConfiguration.CODEC);
        var context = new net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<>(java.util.Optional.empty(), level,
                level.getChunkSource().getGenerator(), RandomSource.create(42), crystal, config);
        helper.assertTrue((boolean) invokeOn(SmallBoulderFeature.class, "place",
                new Class<?>[]{net.minecraft.world.level.levelgen.feature.FeaturePlaceContext.class, int.class}, feature, context, 12),
                "Explicit crystal boulder was rejected");
        helper.assertTrue(level.getBlockState(crystal).is(Blocks.AMETHYST_BLOCK), "Explicit crystal palette was remapped to local geology");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void resistanceUsesActualRegisteredRockAndWeatheredForm(GameTestHelper helper) {
        double granite = (double) invoke("resistance", new Class<?>[]{BlockState.class}, Blocks.GRANITE.defaultBlockState());
        double claystone = (double) invoke("resistance", new Class<?>[]{BlockState.class}, required(helper, "kubejs:claystone"));
        double gravel = (double) invoke("resistance", new Class<?>[]{BlockState.class}, required(helper, "kubejs:granite_gravel"));
        helper.assertTrue(granite > claystone && gravel < granite && gravel >= 0 && granite <= 1,
                "Actual rock resistance ignored pack hardness or weathered material form");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void habitatOutcropRetentionKeepsMostCandidatesAndFavorsExposedResistantGround(GameTestHelper helper) {
        try {
            Class<?> habitat = Class.forName("net.quiwh.cwiworldgen.terrain.SurfaceHabitat");
            var constructor = habitat.getConstructor(double.class, double.class, double.class, double.class, double.class);
            Object exposed = constructor.newInstance(1., 128., 0., 0., 1.);
            Object wetGrove = constructor.newInstance(0., 128., 1., 1., 0.);
            double high = (double) invokeOn(ReferenceRockOutcropFeature.class, "outcropChanceMultiplier", new Class<?>[]{habitat}, exposed);
            double low = (double) invokeOn(ReferenceRockOutcropFeature.class, "outcropChanceMultiplier", new Class<?>[]{habitat}, wetGrove);
            helper.assertTrue(low >= .85 && high <= 1.15 && high > low,
                    "Habitat weighting erased most outcrops or favored wet soft groves over exposed resistant rock");
        } catch (ReflectiveOperationException missing) { throw new AssertionError("Surface habitat contract missing", missing); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void profileTwelveCaveBouldersRejectUnownedMainAndDecoratorReadsBeforeMutation(GameTestHelper helper) {
        var chunk = new ProtoChunk(new ChunkPos(32, 32), UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            chunk.setBlockState(new BlockPos(512 + x, 31, 512 + z), Blocks.STONE.defaultBlockState(), false);
        var region = new OwnerBoundBoulderRegion(helper.getLevel(), chunk);
        var feature = new SmallBoulderFeature(SmallBoulderConfiguration.CODEC);
        var base = net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider.simple(Blocks.GRANITE);
        var ornament = net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider.simple(Blocks.AMETHYST_BLOCK);
        var contextClass = net.minecraft.world.level.levelgen.feature.FeaturePlaceContext.class;
        // x=0 crosses the main footprint; x=1 fits the main body but crosses decorator search.
        for (int localX : new int[]{0, 1}) {
            var origin = new BlockPos(512 + localX, 32, 520);
            var config = new SmallBoulderConfiguration(base, localX == 0 ? java.util.Optional.empty()
                    : java.util.Optional.of(ornament), localX == 0 ? 0 : 1);
            var context = new net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<>(java.util.Optional.empty(), region,
                    helper.getLevel().getChunkSource().getGenerator(), RandomSource.create(42), origin, config);
            helper.assertTrue(!(boolean) invokeOn(SmallBoulderFeature.class, "place",
                    new Class<?>[]{contextClass, int.class}, feature, context, 12),
                    "Profile12 boulder accepted unowned main/decorator access");
            helper.assertTrue(region.blockReads == 0 && region.writes == 0,
                    "Owner-bound rejection read or mutated terrain before full access validation");
        }
        var origin = new BlockPos(520, 32, 520);
        region.denied = origin.above();
        var denied = new SmallBoulderConfiguration(base, java.util.Optional.of(ornament), 1);
        var deniedContext = new net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<>(java.util.Optional.empty(), region,
                helper.getLevel().getChunkSource().getGenerator(), RandomSource.create(42), origin, denied);
        helper.assertTrue(!(boolean) invokeOn(SmallBoulderFeature.class, "place", new Class<?>[]{contextClass, int.class},
                        feature, deniedContext, 12) && region.blockReads == 0 && region.writes == 0,
                "Profile12 failed to reject a non-writable target before reading ground");
        region.denied = null;
        helper.assertTrue((boolean) invokeOn(SmallBoulderFeature.class, "place", new Class<?>[]{contextClass, int.class},
                        feature, deniedContext, 12) && region.writes > 0,
                "Fully owned supported body/decorator fixture did not exercise positive placement");
        for (int x = 512; x < 528; x++) for (int z = 512; z < 528; z++)
            helper.assertTrue(chunk.getBlockState(new BlockPos(x, 31, z)).is(Blocks.STONE),
                    "Owner-bound boulder modified its supporting ground");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unresolvedBoulderHostsRejectGenericRockAtomicallyButPreserveExplicitPalettes(GameTestHelper helper) {
        var generic = net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider.simple(Blocks.GRANITE);
        var crystal = net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider.simple(Blocks.AMETHYST_BLOCK);
        var mixed = new net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider(
                net.minecraft.util.random.SimpleWeightedRandomList.<BlockState>builder()
                        .add(Blocks.AMETHYST_BLOCK.defaultBlockState(), 1)
                        .add(Blocks.GRANITE.defaultBlockState(), 1).build());
        BlockPos origin = new BlockPos(520, 32, 520);
        long mixedSeed = -1;
        for (long seed = 0; seed < 100; seed++) {
            var probe = RandomSource.create(seed);
            probe.nextBoolean(); probe.nextBoolean();
            boolean firstCrystal = mixed.getState(probe, origin).is(Blocks.AMETHYST_BLOCK);
            boolean laterRock = false;
            for (int i = 1; i < 11; i++) laterRock |= mixed.getState(probe, origin).is(Blocks.GRANITE);
            if (firstCrystal && laterRock) { mixedSeed = seed; break; }
        }
        helper.assertTrue(mixedSeed >= 0, "Fixture must sample an explicit palette before unresolved generic rock");
        for (int scenario = 0; scenario < 4; scenario++) {
            var chunk = new ProtoChunk(new ChunkPos(32, 32), UpgradeData.EMPTY, helper.getLevel(),
                    helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
            // Natural sturdy support, with no identifiable rock within the shallow resolver limit.
            for (int x = 512; x < 528; x++) for (int z = 512; z < 528; z++) for (int y = 15; y <= 31; y++)
                chunk.setBlockState(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), false);
            var region = new OwnerBoundBoulderRegion(helper.getLevel(), chunk);
            var provider = scenario == 2 ? mixed : scenario == 3 ? crystal : generic;
            var config = new SmallBoulderConfiguration(provider, java.util.Optional.empty(), 0);
            var context = new net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<>(java.util.Optional.empty(), region,
                    helper.getLevel().getChunkSource().getGenerator(), RandomSource.create(mixedSeed), origin, config);
            int version = scenario == 0 ? 11 : 12;
            boolean placed = (boolean) invokeOn(SmallBoulderFeature.class, "place",
                    new Class<?>[]{net.minecraft.world.level.levelgen.feature.FeaturePlaceContext.class, int.class},
                    new SmallBoulderFeature(SmallBoulderConfiguration.CODEC), context, version);
            boolean expected = scenario == 0 || scenario == 3;
            helper.assertTrue(placed == expected, "Unresolved boulder host ignored generic/explicit palette boundary");
            helper.assertTrue(expected ? region.writes == 11 : region.writes == 0,
                    "Unresolved generic rock caused partial boulder placement or changed legacy/explicit shape");
            if (scenario == 3) helper.assertTrue(chunk.getBlockState(origin).is(Blocks.AMETHYST_BLOCK),
                    "Unresolved host remapped the explicit crystal palette");
        }
        helper.succeed();
    }

    /** A real native ProtoChunk with a region that fails any accidental neighbor read/write. */
    private static final class OwnerBoundBoulderRegion extends net.minecraft.server.level.WorldGenRegion {
        private final ProtoChunk chunk;
        private BlockPos denied;
        private int blockReads, writes;
        OwnerBoundBoulderRegion(net.minecraft.server.level.ServerLevel level, ProtoChunk chunk) {
            super(level, null, null, chunk);
            this.chunk = chunk;
        }
        @Override public boolean hasChunk(int x, int z) { return x == chunk.getPos().x && z == chunk.getPos().z; }
        @Override public boolean ensureCanWrite(BlockPos pos) {
            return hasChunk(pos.getX() >> 4, pos.getZ() >> 4) && !isOutsideBuildHeight(pos) && !pos.equals(denied);
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            if (!hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) throw new AssertionError("Boulder read an unowned neighbor: " + pos);
            blockReads++;
            return chunk.getBlockState(pos);
        }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
            if (!ensureCanWrite(pos)) throw new AssertionError("Boulder wrote outside permitted owner: " + pos);
            writes++;
            chunk.setBlockState(pos, state, false);
            return true;
        }
        @Override public ChunkAccess getChunk(int x, int z, net.minecraft.world.level.chunk.status.ChunkStatus status, boolean required) {
            if (hasChunk(x, z)) return chunk;
            if (required) throw new AssertionError("Boulder requested an unowned mutable chunk");
            return null;
        }
    }

    private static void placeBed(ProtoChunk chunk, long seed, int version) {
        invokeOn(WaterbedSedimentFeature.class, "placeChunk", new Class<?>[]{ChunkAccess.class, long.class,
                WaterbedSedimentFeature.ColumnLookup.class, int.class}, chunk, seed,
                (WaterbedSedimentFeature.ColumnLookup) (x, z) -> new WaterbedSedimentFeature.TerrainColumn(true, true, .55), version);
    }

    private static ProtoChunk bed(GameTestHelper helper) {
        var chunk = new ProtoChunk(ChunkPos.ZERO, UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            BlockState host = x < 8 ? Blocks.GRANITE.defaultBlockState() : required(helper, "kubejs:shale");
            for (int y = 48; y <= 56; y++) chunk.setBlockState(new BlockPos(x, y, z),
                    y >= 54 ? Blocks.DIRT.defaultBlockState() : host, false);
            for (int y = 57; y <= 64; y++) chunk.setBlockState(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState(), false);
        }
        prime(chunk);
        return chunk;
    }

    private static void prime(ProtoChunk chunk) {
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
    }
    private static Map<BlockPos, BlockState> snapshot(ProtoChunk chunk) {
        var result = new HashMap<BlockPos, BlockState>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 48; y <= 64; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            result.put(pos, chunk.getBlockState(pos));
        }
        return result;
    }
    private static BlockState required(GameTestHelper helper, String id) {
        BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
        helper.assertTrue(!state.isAir(), "Required registered material missing: " + id);
        return state;
    }
    private static BlockState component(Object family, String name) {
        return (BlockState) invokeOn(family.getClass(), name, new Class<?>[0], family);
    }
    private static Object invoke(String name, Class<?>[] signature, Object... args) {
        try { return invokeOn(Class.forName("net.quiwh.cwiworldgen.terrain.LocalRockMaterials"), name, signature, args); }
        catch (ClassNotFoundException missing) { throw new AssertionError("Local rock material resolver is absent", missing); }
    }
    private static Object invokeOn(Class<?> owner, String name, Class<?>[] signature, Object... args) {
        try {
            var method = owner.getDeclaredMethod(name, signature);
            method.setAccessible(true);
            return method.invoke(java.lang.reflect.Modifier.isStatic(method.getModifiers()) ? null : args[0],
                    java.lang.reflect.Modifier.isStatic(method.getModifiers()) ? args : java.util.Arrays.copyOfRange(args, 1, args.length));
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Error error) throw error;
            throw new AssertionError("Material operation failed", failed.getCause());
        } catch (ReflectiveOperationException missing) { throw new AssertionError("Missing local material behavior " + owner.getSimpleName() + "." + name, missing); }
    }
}
