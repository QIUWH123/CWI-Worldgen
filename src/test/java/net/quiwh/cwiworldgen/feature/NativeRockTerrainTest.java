package net.quiwh.cwiworldgen.feature;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeRockTerrainTest {
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void publicFeaturePlacesOnNativeInterpolatedTerrainAndRegionalGeology(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var state = level.getChunkSource().randomState();
        var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var biomeKeys = List.of(ResourceKey.create(Registries.BIOME, ResourceLocation.parse("cwi:smoldering_barren")),
                ResourceKey.create(Registries.BIOME, ResourceLocation.parse("cwi:forsaken_land")));
        var darkAshStoneId = ResourceLocation.parse("kubejs:dark_ash_stone");
        var ashStoneId = ResourceLocation.parse("kubejs:ash_stone");
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(darkAshStoneId)
                        && BuiltInRegistries.BLOCK.containsKey(ashStoneId),
                "Native biome outcrop materials are not registered");
        var darkAshStone = BuiltInRegistries.BLOCK.get(darkAshStoneId);
        var ashStone = BuiltInRegistries.BLOCK.get(ashStoneId);
        helper.assertTrue(darkAshStone != Blocks.AIR && ashStone != Blocks.AIR,
                "Native biome outcrop materials resolved to air");
        var erosion = new ReferenceSurfaceExposureFeature(NoneFeatureConfiguration.CODEC);
        var rocks = new ReferenceRockOutcropFeature(NoneFeatureConfiguration.CODEC);
        int tested = 0;
        int[] placed = new int[2];
        int[] raised = new int[2];
        int[] formationBlocks = new int[2];
        int aboveExactCell = 0;
        int matchingFormationBlocks = 0;
        int ashStoneBlocks = 0;
        search: for (int tileZ = -8; tileZ <= 8; tileZ++) for (int tileX = -8; tileX <= 8; tileX++) {
            var preview = terrain.sampleRaw(tileX * 128 + 64, tileZ * 128 + 64);
            if (preview.height < .4F || preview.terrain.isSubmerged() || preview.terrain.isWetland()) continue;
            for (int dz = 0; dz < 8; dz++) for (int dx = 0; dx < 8; dx++) {
                ChunkPos pos = new ChunkPos(tileX * 8 + dx, tileZ * 8 + dz);
                var reader = terrain.prepareChunk(pos.x, pos.z);
                var center = reader.getCell(8, 8);
                if (center.gradient < .18F || center.terrain.isSubmerged() || center.terrain.isWetland()) continue;
                var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, registry, null);
                complete(level, generator.createBiomes(state, Blender.empty(), level.structureManager(), chunk));
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                complete(level, generator.fillFromNoise(Blender.empty(), state, level.structureManager(), chunk));
                chunk.setPersistedStatus(ChunkStatus.NOISE);
                var region = new IsolatedRegion(level, chunk);
                generator.buildSurface(chunk, new WorldGenerationContext(generator, level), state,
                        level.structureManager(), region.getBiomeManager(), registry, Blender.empty());
                chunk.setPersistedStatus(ChunkStatus.FEATURES);
                var origin = pos.getWorldPosition();
                erosion.place(new FeaturePlaceContext<>(Optional.empty(), region, generator,
                        RandomSource.create(42), origin, NoneFeatureConfiguration.INSTANCE));
                int[] originalTop = new int[256];
                BlockState[] nativeRock = new BlockState[256];
                int minimumY = chunk.getMinBuildHeight();
                int maximumY = chunk.getMaxBuildHeight();
                BlockState[][] originalBlocks = new BlockState[256][maximumY - minimumY];
                BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                    int column = x * 16 + z;
                    originalTop[column] = y;
                    if (terrain.generationVersion() >= 12) {
                        var family = net.quiwh.cwiworldgen.terrain.LocalRockMaterials.find(chunk,
                                block.set(pos.getBlockX(x), y, pos.getBlockZ(z)), 12);
                        if (family != null) nativeRock[column] = family.raw();
                    }
                    for (int depth = 0; nativeRock[column] == null && depth <= 12; depth++) {
                        BlockState material = chunk.getBlockState(block.set(pos.getBlockX(x), y - depth, pos.getBlockZ(z)));
                        if (material.is(Blocks.STONE) || material.is(Blocks.TUFF) || material.is(Blocks.ANDESITE)) {
                            nativeRock[column] = material;
                            break;
                        }
                    }
                    for (int snapshotY = minimumY; snapshotY < maximumY; snapshotY++) {
                        originalBlocks[column][snapshotY - minimumY] = chunk.getBlockState(
                                block.set(pos.getBlockX(x), snapshotY, pos.getBlockZ(z)));
                    }
                    if (y > terrain.levels().scale(reader.getCell(x, z).height)) aboveExactCell++;
                }
                tested++;
                for (int biomeIndex = 0; biomeIndex < biomeKeys.size(); biomeIndex++) {
                    var biomeKey = biomeKeys.get(biomeIndex);
                    var biomeRegion = new IsolatedRegion(level, chunk, registry.getHolderOrThrow(biomeKey));
                    helper.assertTrue(biomeRegion.getBiome(origin.atY(originalTop[0])).is(biomeKey),
                            "Public outcrop test did not expose requested biome " + biomeKey.location());
                    boolean didPlace = rocks.place(new FeaturePlaceContext<>(Optional.empty(), biomeRegion, generator,
                            RandomSource.create(43), origin, NoneFeatureConfiguration.INSTANCE));
                    if (didPlace) placed[biomeIndex]++;
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                        int column = x * 16 + z;
                        int top = originalTop[column];
                        int expectedTop = top;
                        boolean changed = false;
                        for (int y = minimumY; y < maximumY; y++) {
                            BlockState actual = chunk.getBlockState(block.set(pos.getBlockX(x), y, pos.getBlockZ(z)));
                            if (actual == originalBlocks[column][y - minimumY]) continue;
                            changed = true;
                            helper.assertTrue(didPlace, "Public outcrop changed blocks while reporting no placement");
                            int maximumRootDepth = actual.is(darkAshStone) ? 1 : 3;
                            helper.assertTrue(y >= top - maximumRootDepth,
                                    "Public outcrop extended below its shallow foundation at " + block);
                            if (biomeIndex == 0) {
                                helper.assertTrue(actual.is(darkAshStone),
                                        "Smoldering outcrop did not use dark ash stone at " + block + ": " + actual);
                            } else {
                                boolean nativeMaterial = nativeRock[column] != null && actual == nativeRock[column];
                                helper.assertTrue(nativeMaterial || actual.is(ashStone),
                                        "Forsaken outcrop did not use native rock or ash stone at " + block + ": " + actual);
                                if (nativeMaterial) matchingFormationBlocks++;
                                if (actual.is(ashStone)) ashStoneBlocks++;
                            }
                            formationBlocks[biomeIndex]++;
                            expectedTop = Math.max(expectedTop, y);
                        }
                        if (!changed) continue;
                        if (expectedTop > top) raised[biomeIndex]++;
                        helper.assertTrue(chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) == expectedTop
                                        && chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) == expectedTop,
                                "Public outcrop left a stale worldgen heightmap at " + pos + " column " + x + "," + z);
                    }
                    // Reuse The Same Real Native Terrain To Exercise Both Public Biome Mappings.
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                        int column = x * 16 + z;
                        for (int y = minimumY; y < maximumY; y++) {
                            block.set(pos.getBlockX(x), y, pos.getBlockZ(z));
                            BlockState original = originalBlocks[column][y - minimumY];
                            if (chunk.getBlockState(block) != original) chunk.setBlockState(block, original, false);
                        }
                    }
                    Heightmap.primeHeightmaps(chunk,
                            EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
                }
                if ((placed[0] >= 2 && placed[1] >= 2) || tested >= 64) break search;
            }
        }
        System.out.println("CWI_ROCK_OUTCROP_REAL_TERRAIN=tested=" + tested + " placed=" + placed[0] + "/" + placed[1]
                + " raised_columns=" + raised[0] + "/" + raised[1] + " dark_ash_stone_blocks=" + formationBlocks[0]
                + " matching_native_rock_blocks=" + matchingFormationBlocks + " ash_stone_blocks=" + ashStoneBlocks
                + " interpolated_above_exact_cell=" + aboveExactCell);
        helper.assertTrue(placed[0] >= 2 && placed[1] >= 2 && raised[0] > 8 && raised[1] > 8
                        && formationBlocks[0] > 16 && matchingFormationBlocks > 16,
                "Rock feature did not produce both biome formations on native terrain: "
                        + placed[0] + "/" + placed[1] + " in " + tested + " chunks");
        helper.succeed();
    }

    private static void complete(ServerLevel level, CompletableFuture<?> future) {
        level.getServer().managedBlock(() -> {
            level.getChunkSource().pollTask();
            return future.isDone();
        });
        future.join();
    }

    /** Real Native Noise And Surface Chunks, Without Requesting Unrelated Neighbor Generation. */
    private static final class IsolatedRegion extends WorldGenRegion {
        private final ServerLevel level;
        private final ChunkAccess chunk;
        private final Holder<Biome> biome;

        IsolatedRegion(ServerLevel level, ChunkAccess chunk) {
            this(level, chunk, null);
        }

        IsolatedRegion(ServerLevel level, ChunkAccess chunk, Holder<Biome> biome) {
            super(level, null, null, chunk);
            this.level = level;
            this.chunk = chunk;
            this.biome = biome;
        }

        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }

        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            if (x == chunk.getPos().x && z == chunk.getPos().z) return chunk;
            if (required) throw new AssertionError("Outcrop requested neighboring mutable chunk " + x + "," + z);
            return null;
        }

        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
            return biome != null ? biome : level.getUncachedNoiseBiome(x, y, z);
        }
    }
}
