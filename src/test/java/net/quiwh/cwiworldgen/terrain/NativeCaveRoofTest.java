package net.quiwh.cwiworldgen.terrain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;

/** Recovered saved-world noodle breaches, exercised before surface rules or carvers. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeCaveRoofTest {
    private static final long SAVED_SEED = 7464437938559805472L;
    private static final int[][] COLUMNS = {{-66, -524}, {-67, -524}, {156, -294}, {156, -293}};

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void roofPolicyPreservesLegacyAndDeepNoodlesWhileVaryingItsBedding(GameTestHelper helper) {
        double smallest = Double.POSITIVE_INFINITY, largest = Double.NEGATIVE_INFINITY;
        for (long seed : new long[]{0L, 42L, SAVED_SEED, -7L}) {
            for (int x = -512; x <= 512; x += 64) for (int z = -512; z <= 512; z += 64) {
                double roof = ReferenceCaveRoof.roofDepth(seed, x, z);
                helper.assertTrue(roof >= 18 && roof <= 26 && Double.isFinite(roof),
                        "Noodle roof thickness escaped its bounded terrain reserve");
                helper.assertTrue(Double.doubleToLongBits(roof)
                                == Double.doubleToLongBits(ReferenceCaveRoof.roofDepth(seed, x, z)),
                        "Seed-local noodle roof is not deterministic");
                helper.assertTrue(Math.abs(roof - ReferenceCaveRoof.roofDepth(seed, x + 1, z)) < .2
                                && Math.abs(roof - ReferenceCaveRoof.roofDepth(seed, x, z + 1)) < .2,
                        "Noodle roof acquired a discontinuous column or noise-cell seam");
                smallest = Math.min(smallest, roof); largest = Math.max(largest, roof);
                double surface = 113.375;
                int top = (int) Math.floor(surface);
                for (int depth = 0; depth < 18; depth++) {
                    helper.assertTrue(ReferenceCaveRoof.density(8, seed, x, top - depth, z, surface) > 0,
                            "Profile 8 leaves a noodle breach in its nearest 18 terrain layers");
                }
                for (int depth : new int[]{38, 64, 112}) {
                    double guard = ReferenceCaveRoof.density(8, seed, x, top - depth, z, surface);
                    for (double noodle : new double[]{-.1, -.075, -.001, 0., .3, 64.}) {
                        helper.assertTrue(Double.doubleToLongBits(Math.max(noodle, guard))
                                        == Double.doubleToLongBits(noodle),
                                "The roof reserve changed original deep noodle density bits");
                    }
                }
                for (int version = 1; version <= 7; version++) {
                    for (int y : new int[]{-64, top - 40, top, top + 1, 384}) {
                        helper.assertTrue(ReferenceCaveRoof.density(version, seed, x, y, z, surface) == -64.,
                                "Saved profile " + version + " acquired the profile-8 roof policy");
                    }
                }
            }
        }
        helper.assertTrue(largest - smallest > 3,
                "Noodle roof collapsed to a uniform underground ceiling");
        System.out.println("CWI_NOODLE_ROOF_POLICY=min:" + smallest + ",max:" + largest
                + ",legacy_profiles:7,protected_layers:18,deep_density_bits:unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void recoveredInlandFacesKeepTheirProfileEightNoiseFillRoof(GameTestHelper helper) {
        int protectedBlocks = 0, legacyCavityBlocks = 0;
        for (int version : new int[]{7, 8}) {
            ServerLevel level = level(helper, version == 8
                    ? "natural_geometry_wasteland_test" : "compact_natural_wasteland_test");
            var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
            var settings = generator.generatorSettings().value();
            helper.assertTrue(TerrainGenerationProfile.fromRouter(settings.noiseRouter()) == version,
                    "Recovered cave fixture selected the wrong saved terrain profile");
            RandomState state = RandomState.create(settings, level.registryAccess().lookupOrThrow(Registries.NOISE), SAVED_SEED);
            var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
            try {
                if (version == 8) verifyOnlyNoodlesAreGuarded(helper, level, settings.noiseRouter().finalDensity());
                Map<Long, ProtoChunk> generated = new LinkedHashMap<>();
                for (int[] column : COLUMNS) {
                    int x = column[0], z = column[1];
                    var owner = new ChunkPos(x >> 4, z >> 4);
                    ProtoChunk chunk = generated.get(owner.toLong());
                    if (chunk == null) {
                        chunk = new ProtoChunk(owner, UpgradeData.EMPTY, level,
                                level.registryAccess().registryOrThrow(Registries.BIOME), null);
                        complete(level, generator.createBiomes(state, Blender.empty(), level.structureManager(), chunk));
                        chunk.setPersistedStatus(ChunkStatus.BIOMES);
                        complete(level, generator.fillFromNoise(Blender.empty(), state, level.structureManager(), chunk));
                        chunk.setPersistedStatus(ChunkStatus.NOISE);
                        generated.put(owner.toLong(), chunk);
                    }
                    var cell = terrain.prepareChunk(owner.x, owner.z).getCell(x, z);
                    double surface = cell.height * 256.;
                    int top = (int) Math.floor(surface);
                    helper.assertTrue(!cell.cwiPlannedWater && !cell.cwiBasin
                                    && terrain.landscape().surfaceFormationAt(x, z) == null,
                            "Recovered noodle control was replaced by planned water or a scenic opening");
                    if (version == 8) {
                        for (int depth = 0; depth < 18; depth++) {
                            BlockPos pos = new BlockPos(x, top - depth, z);
                            var block = chunk.getBlockState(pos);
                            helper.assertTrue(!block.isAir() && block.getFluidState().isEmpty(),
                                    "Recovered hillside still loses its completed-terrain roof to noodle density at " + pos);
                            protectedBlocks++;
                        }
                    } else {
                        // The original saved-world voids, before surface materials or later modifications.
                        int low = x == -67 ? 70 : x == 156 && z == -293 ? 93 : x == 156 ? 92 : 0;
                        int high = x == -67 ? 86 : z == -293 ? 103 : x == 156 ? 93 : -1;
                        for (int y = low; y <= high; y++) {
                            helper.assertTrue(chunk.getBlockState(new BlockPos(x, y, z)).isAir(),
                                    "Profile 7 lost its recorded original noodle cavity at " + new BlockPos(x, y, z));
                            legacyCavityBlocks++;
                        }
                        if (x == -66 || z == -294) {
                            helper.assertTrue(!chunk.getBlockState(new BlockPos(x, top, z)).isAir(),
                                    "The saved high column no longer retains its original noise-fill roof");
                        }
                    }
                    System.out.println("CWI_NOODLE_ROOF_BLOCKS=profile:" + version + ",seed:" + SAVED_SEED
                            + ",x:" + x + ",z:" + z + ",completed_surface:" + surface + ",top:" + top);
                }
            } finally {
                if (terrain != null) terrain.close();
            }
        }
        helper.assertTrue(protectedBlocks == 72 && legacyCavityBlocks == 30,
                "Recovered cliff fixture omitted protected roof or original cavity samples");
        System.out.println("CWI_NOODLE_ROOF_RECOVERED=protected_blocks:" + protectedBlocks
                + ",unchanged_legacy_air:" + legacyCavityBlocks + ",stage:NOISE");
        helper.succeed();
    }

    private static void verifyOnlyNoodlesAreGuarded(GameTestHelper helper, ServerLevel level, DensityFunction density) {
        JsonObject root = DensityFunction.HOLDER_HELPER_CODEC.encodeStart(
                RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), density).getOrThrow().getAsJsonObject();
        // The scenic operand remains outside max(noodle, roof). A positive roof may never refill an arch.
        helper.assertTrue(root.get("type").getAsString().equals("minecraft:min"),
                "The noodle roof guard replaced the outer subtractive landscape min");
        JsonObject landscape = expanded(level, root.get("argument2"));
        helper.assertTrue(landscape.get("type").getAsString().equals("cwi_worldgen:landscape"),
                "The noodle roof guard captures scenic surface/cavern density");
        JsonObject noodle = root.getAsJsonObject("argument1").getAsJsonObject("argument2");
        helper.assertTrue(noodle.get("type").getAsString().equals("minecraft:max"),
                "The roof reserve does not guard only the noodle operand");
        JsonObject guard = expanded(level, noodle.get("argument2"));
        helper.assertTrue(guard.get("type").getAsString().equals("cwi_worldgen:reference_terrain")
                        && guard.get("field").getAsString().equals("cave_roof"),
                "The noodle roof reserve is not the seed-bound completed terrain field");
    }

    private static JsonObject expanded(ServerLevel level, JsonElement encoded) {
        if (encoded.isJsonObject()) return encoded.getAsJsonObject();
        DensityFunction function = level.registryAccess().registryOrThrow(Registries.DENSITY_FUNCTION)
                .get(ResourceLocation.parse(encoded.getAsString()));
        if (function == null) throw new AssertionError("Missing density dependency " + encoded);
        return DensityFunction.HOLDER_HELPER_CODEC.encodeStart(
                RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), function).getOrThrow().getAsJsonObject();
    }

    private static ServerLevel level(GameTestHelper helper, String name) {
        ServerLevel result = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, name)));
        helper.assertTrue(result != null, "Missing recovered hillside test dimension " + name);
        return result;
    }

    private static void complete(ServerLevel level, CompletableFuture<?> future) {
        level.getServer().managedBlock(() -> { level.getChunkSource().pollTask(); return future.isDone(); });
        future.join();
    }

    private NativeCaveRoofTest() {}
}
