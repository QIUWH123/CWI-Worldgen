package net.quiwh.cwiworldgen;

import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarverDebugSettings;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.TerrainClimateSampler;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import org.apache.commons.lang3.mutable.MutableBoolean;

/** Exercise the native sampler, replacement call and Boat.tick injection paths. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeReferenceHooksTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 1200)
    public static void spawnSamplerUsesItsOwnContinentAndKeepsVanillaHeightResolution(GameTestHelper helper) {
        ServerLevel level = wasteland(helper);
        RandomState state = level.getChunkSource().randomState();
        ReferenceTerrain terrain = terrain(state);
        helper.assertTrue(terrain != null, "Wasteland has no reference terrain");
        helper.assertTrue(((TerrainClimateSampler) (Object) state.sampler()).cwiWorldgen$terrain() == terrain,
                "Spawn sampler was not bound to its own RandomState terrain");
        BlockPos first = state.sampler().findSpawnPosition();
        BlockPos center = terrain.spawnSearchCenter();
        helper.assertTrue(first.getY() == 0, "Climate hook overrode vanilla safe-height resolution");
        helper.assertTrue(Math.abs(first.getX() - center.getX()) <= 2560
                        && Math.abs(first.getZ() - center.getZ()) <= 2560,
                "Source's two radial passes escaped their continent-centered search bounds");

        var lookup = level.registryAccess().asGetterLookup();
        for (String dimension : List.of("nether", "end")) {
            RandomState foreign = RandomState.create(lookup, settingsKey(dimension), 42L);
            Climate.Sampler sampler = foreign.sampler();
            helper.assertTrue(((TerrainClimateSampler) (Object) sampler).cwiWorldgen$terrain() == null,
                    dimension + " sampler inherited the Overworld spawn context");
            BlockPos vanilla = sampler.spawnTarget().isEmpty() ? BlockPos.ZERO
                    : Climate.findSpawnPosition(sampler.spawnTarget(), sampler);
            helper.assertTrue(sampler.findSpawnPosition().equals(vanilla),
                    dimension + " spawn hook changed vanilla behavior");
        }
        helper.assertTrue(first.equals(state.sampler().findSpawnPosition()),
                "Other dimension searches changed the existing world's spawn result");

        // Empty targets normally bypass Climate.findSpawnPosition. The bound
        // sampler must still use the source preset's chosen search center.
        Climate.Sampler unbound = Climate.empty();
        helper.assertTrue(unbound.findSpawnPosition().equals(BlockPos.ZERO), "Unbound sampler changed vanilla origin");
        ((TerrainClimateSampler) (Object) unbound).cwiWorldgen$bindTerrain(terrain);
        helper.assertTrue(unbound.findSpawnPosition().equals(center), "Bound sampler ignored its own continent center");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void nativeCarverProtectsWaterSourcesOnlyForReferenceTerrain(GameTestHelper helper) {
        ServerLevel level = wasteland(helper);
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var lookup = level.registryAccess().asGetterLookup();
        RandomState cwi = level.getChunkSource().randomState();
        RandomState nether = RandomState.create(lookup, settingsKey("nether"), 42L);
        RandomState end = RandomState.create(lookup, settingsKey("end"), 42L);
        BlockState[] states = {
                Blocks.WATER.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1),
                Blocks.STONE.defaultBlockState(), Blocks.LAVA.defaultBlockState()
        };
        System.out.println("CWI_CARVER_WATER_SOURCE=" + states[0].getFluidState().isSource()
                + " minecraft_water_tag=" + states[0].getFluidState().is(FluidTags.WATER));
        var config = new CarverConfiguration(1F, ConstantHeight.of(VerticalAnchor.absolute(32)),
                ConstantFloat.of(1F), VerticalAnchor.absolute(-64), CarverDebugSettings.DEFAULT,
                HolderSet.direct(Blocks.WATER.builtInRegistryHolder(), Blocks.OAK_FENCE.builtInRegistryHolder(),
                        Blocks.STONE.builtInRegistryHolder(), Blocks.LAVA.builtInRegistryHolder()));
        var probe = new ReplacementProbe();
        for (RandomState state : List.of(cwi, nether, end, cwi)) {
            boolean reference = terrain(state) != null;
            var context = new CarvingContext(generator, level.registryAccess(), level, null,
                    state, generator.generatorSettings().value().surfaceRule());
            var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
            var chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, level, biomes, null);
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos(8, 32, 8);
            for (int index = 0; index < states.length; index++) {
                chunk.setBlockState(position, states[index], false);
                boolean carved = probe.replace(context, config, chunk, position);
                boolean protectedSource = reference && index < 2;
                helper.assertTrue(carved != protectedSource,
                        "Native replacement result changed for state " + index + ", CWI=" + reference
                                + ", source=" + states[index].getFluidState().isSource()
                                + ", water_tag=" + states[index].getFluidState().is(FluidTags.WATER)
                                + ", debug=" + config.debugSettings.isDebugMode());
                helper.assertTrue(protectedSource ? chunk.getBlockState(position).equals(states[index])
                                : chunk.getBlockState(position).isAir(),
                        "Carver changed a protected water source or failed an allowed replacement");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void nativeRiverBoatsLiftAndSyncWithoutBiomeOrDimensionLeaks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_legacy_test")));
        helper.assertTrue(level != null, "Missing legacy river-boat compatibility dimension");
        ReferenceTerrain terrain = terrain(level.getChunkSource().randomState());
        BlockPos river = findColumn(helper, terrain, true);
        BlockPos land = findColumn(helper, terrain, false);
        int y = level.getMaxBuildHeight() - 12;
        Boat empty = tickSubmergedBoat(helper, level, river.atY(y), false, false);
        helper.assertTrue(empty.getDeltaMovement().y == .2, "Empty river boat did not receive source 0.2 lift");
        var dirty = empty.getEntityData().packDirty();
        helper.assertTrue(dirty != null && dirty.stream().anyMatch(value ->
                        value.serializer() == EntityDataSerializers.BOOLEAN && Boolean.TRUE.equals(value.value())),
                "River terrain classification was not serialized for client prediction");
        Boat mirror = new Boat(EntityType.BOAT, level);
        mirror.getEntityData().assignValues(dirty);
        var mirrored = mirror.getEntityData().getNonDefaultValues();
        helper.assertTrue(mirrored != null && mirrored.stream().anyMatch(value ->
                        value.serializer() == EntityDataSerializers.BOOLEAN && Boolean.TRUE.equals(value.value())),
                "Client entity-data assignment lost the terrain river flag");
        Boat occupied = tickSubmergedBoat(helper, level, river.atY(y), true, false);
        helper.assertTrue(occupied.getDeltaMovement().y == .3, "Occupied river boat did not receive source 0.3 lift");
        Boat flowing = tickSubmergedBoat(helper, level, river.atY(y), false, true);
        helper.assertTrue(flowing.getDeltaMovement().y == .2, "Flowing underwater river boat did not receive source lift");
        Boat nonRiver = tickSubmergedBoat(helper, level, land.atY(y), false, false);
        helper.assertTrue(nonRiver.getDeltaMovement().y != .2,
                "An artificial pond outside terrain rivers received river buoyancy");

        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null && terrain(nether.getChunkSource().randomState()) == null,
                "Nether isolation check did not use a vanilla terrain dimension");
        Boat foreign = tickSubmergedBoat(helper, nether, new BlockPos(8, nether.getMaxBuildHeight() - 12, 8), false, false);
        helper.assertTrue(foreign.getDeltaMovement().y != .2, "Nether boat received CWI river lift");
        helper.succeed();
    }

    private static Boat tickSubmergedBoat(GameTestHelper helper, ServerLevel level, BlockPos position,
            boolean occupied, boolean flowing) {
        BlockState water = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, flowing ? 1 : 0);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 3; dy++) {
            level.setBlock(position.offset(dx, dy, dz), water, 2);
        }
        Boat boat = new Boat(EntityType.BOAT, level);
        boat.setPos(position.getX() + .5, position.getY(), position.getZ() + .5);
        boat.setDeltaMovement(.1, -.1, .05);
        if (occupied) {
            var passenger = EntityType.ARMOR_STAND.create(level);
            helper.assertTrue(passenger != null && passenger.startRiding(boat, true), "Could not mount native boat passenger");
        }
        boat.tick();
        helper.assertTrue(boat.getDeltaMovement().x > 0 && boat.getDeltaMovement().z > 0,
                "Buoyancy destroyed horizontal boat motion");
        boat.ejectPassengers();
        return boat;
    }

    private static BlockPos findColumn(GameTestHelper helper, ReferenceTerrain terrain, boolean river) {
        int checkedTiles = 0;
        for (int x = -2040; x <= 2056; x += 128) for (int z = -2040; z <= 2056; z += 128) {
            if (terrain.sampleRaw(x, z).terrain.isRiver() != river) continue;
            if (terrain.sample(x, z).terrain.isRiver() == river) return new BlockPos(x, 0, z);
            if (++checkedTiles >= 16) throw new AssertionError("Sixteen terrain candidates changed river classification after filtering");
        }
        helper.assertTrue(false, "No " + (river ? "river" : "non-river") + " column in bounded terrain search");
        throw new AssertionError("Unreachable");
    }

    private static ServerLevel wasteland(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "wasteland_test")));
        helper.assertTrue(level != null, "Missing wasteland test dimension");
        return level;
    }

    private static ReferenceTerrain terrain(RandomState state) {
        return ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
    }

    private static ResourceKey<NoiseGeneratorSettings> settingsKey(String name) {
        return ResourceKey.create(Registries.NOISE_SETTINGS, ResourceLocation.withDefaultNamespace(name));
    }

    private static final class ReplacementProbe extends WorldCarver<CarverConfiguration> {
        ReplacementProbe() { super(CarverConfiguration.CODEC.codec()); }

        boolean replace(CarvingContext context, CarverConfiguration config, ChunkAccess chunk,
                BlockPos.MutableBlockPos position) {
            var aquifer = Aquifer.createDisabled((x, y, z) -> new Aquifer.FluidStatus(-64, Blocks.LAVA.defaultBlockState()));
            return super.carveBlock(context, config, chunk, ignored -> null,
                    new CarvingMask(chunk.getHeight(), chunk.getMinBuildHeight()), position,
                    new BlockPos.MutableBlockPos(), aquifer, new MutableBoolean());
        }

        @Override public boolean carve(CarvingContext context, CarverConfiguration config, ChunkAccess chunk,
                Function<BlockPos, Holder<Biome>> biomes, RandomSource random, Aquifer aquifer,
                ChunkPos origin, CarvingMask mask) { throw new UnsupportedOperationException(); }

        @Override public boolean isStartChunk(CarverConfiguration config, RandomSource random) { return true; }
    }

    private NativeReferenceHooksTest() {}
}
