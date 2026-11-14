package net.quiwh.cwiworldgen.terrain;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Saved profile-13 band integration; current shelf-phase surfaces have their own native coverage. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeCliffRockTest {
    @GameTest(template = "empty")
    public static void cliffCodecIsRegisteredAndLoadedBeforeBiomeMasses(GameTestHelper helper) {
        var encoded = SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE, new CliffRockRule())
                .getOrThrow().getAsJsonObject();
        helper.assertTrue(encoded.get("type").getAsString().equals("cwi_worldgen:cliff_rocks"),
                "Local cliff material rule was not registered");
        helper.assertTrue(SurfaceRules.RuleSource.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(new CliffRockRule()),
                "Cliff rule codec did not round-trip");
        var generator = (NoiseBasedChunkGenerator) level(helper).getChunkSource().getGenerator();
        JsonObject production = SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE,
                generator.generatorSettings().value().surfaceRule()).getOrThrow().getAsJsonObject();
        int cliffs = -1, masses = -1, count = 0;
        var sequence = production.getAsJsonArray("sequence");
        for (int i = 0; i < sequence.size(); i++) {
            String type = sequence.get(i).getAsJsonObject().get("type").getAsString();
            if (type.equals("cwi_worldgen:cliff_rocks")) { cliffs = i; count++; }
            if (type.equals("cwi_worldgen:biome_rocks")) masses = i;
        }
        helper.assertTrue(count == 1 && cliffs >= 2 && masses == cliffs + 1,
                "Production rules lost soil-before-cliff-before-biome precedence");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void generatedNativeCliffsExposeBandsAndProtectExistingStates(GameTestHelper helper) {
        ServerLevel level = level(helper);
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var state = level.getChunkSource().randomState();
        var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        var bands = new CliffBands(terrain.seed());
        var levels = terrain.levels();
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        CliffBands.Heights heights = (x, z) -> levels.scale(terrain.sample(x, z).height);
        int built = 0, eligible = 0, bandBlocks = 0, preserved = 0;
        search: for (int tileZ = -8; tileZ <= 8; tileZ++) for (int tileX = -8; tileX <= 8; tileX++) {
            var preview = terrain.sampleRaw(tileX * 128 + 64, tileZ * 128 + 64);
            if (preview.terrain.isSubmerged() || levels.scale(preview.height) < 90) continue;
            for (int dz = 0; dz < 8; dz++) for (int dx = 0; dx < 8; dx++) {
                ChunkPos chunkPos = new ChunkPos(tileX * 8 + dx, tileZ * 8 + dz);
                boolean candidate = false;
                for (int x = 2; x < 16 && !candidate; x += 4) for (int z = 2; z < 16; z += 4) {
                    int gx = chunkPos.getBlockX(x), gz = chunkPos.getBlockZ(z);
                    var exposure = CliffBands.exposureAt(gx, gz, heights);
                    if (!exposure.eligible()) continue;
                    for (int y = exposure.visibleMinY(); y <= exposure.topY() - 3; y++) {
                        var material = bands.materialAt("cwi:forsaken_land", gx, y, gz, exposure);
                        if (material == CliffBands.Material.TUFF || material == CliffBands.Material.ANDESITE) {
                            candidate = true;
                            break;
                        }
                    }
                }
                if (!candidate) continue;
                var chunk = new ProtoChunk(chunkPos, UpgradeData.EMPTY, level, registry, null);
                complete(level, generator.createBiomes(state, Blender.empty(), level.structureManager(), chunk));
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                complete(level, generator.fillFromNoise(Blender.empty(), state, level.structureManager(), chunk));
                chunk.setPersistedStatus(ChunkStatus.NOISE);
                var region = new IsolatedRegion(level, chunk);
                ReferenceRiverSurface.place(chunk, region.getBiomeManager(), terrain);
                List<BlockPos> targets = new ArrayList<>();
                List<BlockState> expected = new ArrayList<>();
                List<BlockState> sentinels = List.of(Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.CHEST.defaultBlockState(),
                        Blocks.STONE_BRICKS.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                        Blocks.LAVA.defaultBlockState(), Blocks.BEDROCK.defaultBlockState());
                List<BlockPos> protectedPositions = new ArrayList<>();
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    int gx = chunkPos.getBlockX(x), gz = chunkPos.getBlockZ(z);
                    var exposure = CliffBands.exposureAt(gx, gz, heights);
                    if (!exposure.eligible()) continue;
                    for (int y = exposure.visibleMinY(); y <= exposure.topY() - 4; y++) {
                        var pos = new BlockPos(gx, y, gz);
                        String biome = chunk.getNoiseBiome(gx >> 2, y >> 2, gz >> 2).unwrapKey().orElseThrow().location().toString();
                        BlockState material = CliffRockRule.material(bands.materialAt(biome, gx, y, gz, exposure));
                        if (material == null || !chunk.getBlockState(pos).is(Blocks.STONE)) continue;
                        eligible++;
                        if (protectedPositions.size() < sentinels.size()) {
                            chunk.setBlockState(pos, sentinels.get(protectedPositions.size()), false);
                            protectedPositions.add(pos);
                        } else {
                            targets.add(pos);
                            expected.add(material);
                        }
                    }
                }
                generator.buildSurface(chunk, new WorldGenerationContext(generator, level), state,
                        level.structureManager(), region.getBiomeManager(), registry, Blender.empty());
                built++;
                for (int i = 0; i < protectedPositions.size(); i++) {
                    helper.assertTrue(chunk.getBlockState(protectedPositions.get(i)) == sentinels.get(i),
                            "Cliff material rule damaged protected state at " + protectedPositions.get(i));
                    preserved++;
                }
                for (int i = 0; i < targets.size(); i++) {
                    BlockState actual = chunk.getBlockState(targets.get(i));
                    // Surface soils have first refusal on any real density ledge.
                    if (!actual.is(Blocks.STONE) && !actual.is(Blocks.TUFF) && !actual.is(Blocks.ANDESITE)) continue;
                    helper.assertTrue(actual == expected.get(i), "Native cliff material differs from local band field at " + targets.get(i));
                    if (actual.is(Blocks.TUFF) || actual.is(Blocks.ANDESITE)) bandBlocks++;
                }
                if (bandBlocks >= 16 && preserved >= 6 || built >= 12) break search;
            }
        }
        System.out.println("CWI_CLIFF_BANDS_NATIVE=chunks=" + built + " eligible=" + eligible
                + " band_blocks=" + bandBlocks + " protected_states=" + preserved);
        helper.assertTrue(bandBlocks >= 16 && preserved >= 6,
                "Production cliff samples did not exercise actual banding and protected states");
        helper.succeed();
    }

    private static ServerLevel level(GameTestHelper helper) {
        return helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:scenery_shapes_wasteland_test")));
    }

    private static void complete(ServerLevel level, CompletableFuture<?> future) {
        level.getServer().managedBlock(() -> { level.getChunkSource().pollTask(); return future.isDone(); });
        future.join();
    }

    private static final class IsolatedRegion extends WorldGenRegion {
        private final ServerLevel level;
        private final ChunkAccess chunk;
        IsolatedRegion(ServerLevel level, ChunkAccess chunk) {
            super(level, null, null, chunk);
            this.level = level;
            this.chunk = chunk;
        }
        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            if (x == chunk.getPos().x && z == chunk.getPos().z) return chunk;
            if (required) throw new AssertionError("Cliff rule requested mutable neighbor " + x + "," + z);
            return null;
        }
        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) { return level.getUncachedNoiseBiome(x, y, z); }
    }
}
