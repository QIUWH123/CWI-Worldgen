package net.quiwh.cwiworldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Samples real seeded biome resolution, including the block-level biome zoom. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeBoundedCaveBiomeTest {
    private static final long[] SEEDS = {0L, 42L, 8675309L};
    private static final Map<String, int[]> BANDS = Map.of(
            "sandy_caves", new int[] {-12, 48},
            "glowshroom_caves", new int[] {-32, 40},
            "dripstone_caves", new int[] {-48, 32},
            "spider_caves", new int[] {-40, 16},
            "molten_caves", new int[] {-60, -8},
            "echo_caves", new int[] {-60, -24});

    private static JsonObject sourceJson(GameTestHelper helper) throws Exception {
        var resource = helper.getLevel().getServer().getResourceManager().getResourceOrThrow(
                ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "worldgen/world_preset/wasteland.json"));
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("dimensions")
                    .getAsJsonObject("minecraft:overworld").getAsJsonObject("generator").getAsJsonObject("biome_source");
        }
    }

    private static BiomeSource source(GameTestHelper helper) throws Exception {
        return BiomeSource.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()),
                sourceJson(helper)).getOrThrow();
    }

    private static RandomState state(GameTestHelper helper, long seed) {
        return RandomState.create(helper.getLevel().registryAccess().asGetterLookup(),
                ResourceKey.create(Registries.NOISE_SETTINGS, ResourceLocation.withDefaultNamespace("overworld")), seed);
    }

    private static String biome(BiomeSource source, RandomState state, int x, int y, int z) {
        return source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), state.sampler())
                .unwrapKey().orElseThrow().location().getPath();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void specialCavesRespectActualHeightIncludingBiomeZoom(GameTestHelper helper) throws Exception {
        BiomeSource source = source(helper);
        Set<String> reached = new HashSet<>();
        for (long seed : SEEDS) {
            var state = state(helper, seed);
            var zoomed = new BiomeManager((x, y, z) -> source.getNoiseBiome(x, y, z, state.sampler()),
                    BiomeManager.obfuscateSeed(seed));
            for (int x = -2048; x <= 2048; x += 64) {
                for (int z = -2048; z <= 2048; z += 64) {
                    for (int y = -64; y <= 56; y += 4) {
                        String name = biome(source, state, x, y, z);
                        int[] band = BANDS.get(name);
                        if (band != null) {
                            reached.add(name);
                            helper.assertTrue(y >= band[0] && y <= band[1],
                                    "Noise biome " + name + " escaped height band at " + x + "," + y + "," + z + " seed " + seed);
                        }
                    }
                    for (int y : new int[] {-64, -61, -60, -49, -41, -33, -25, -23, -13, -7, 17, 33, 41, 49}) {
                        String name = zoomed.getBiome(new BlockPos(x, y, z)).unwrapKey().orElseThrow().location().getPath();
                        int[] band = BANDS.get(name);
                        if (band != null) helper.assertTrue(y >= band[0] && y <= band[1],
                                "Zoomed biome " + name + " escaped actual height band at Y=" + y + " seed " + seed);
                    }
                }
            }
        }
        helper.assertTrue(reached.containsAll(BANDS.keySet()), "Height test did not reach every special cave: " + reached);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void caveRegionsStayBoundedAndSeparatedAcrossNegativeCoordinates(GameTestHelper helper) throws Exception {
        BiomeSource source = source(helper);
        Set<String> reached = new HashSet<>();
        Set<Long> fingerprints = new HashSet<>();
        RandomState[] states = new RandomState[SEEDS.length];
        for (int i = 0; i < states.length; i++) states[i] = state(helper, SEEDS[i]);
        int components = 0;
        for (var state : states) {
            long fingerprint = 1;
            for (int y : new int[] {-32, 16}) {
                Map<Long, String> grid = new HashMap<>();
                for (int x = -1792; x <= 1792; x += 16) {
                    for (int z = -1792; z <= 1792; z += 16) {
                        String name = biome(source, state, x, y, z);
                        // Compare only points underground for every seed: surface climate and
                        // terrain-height differences must not make a fixed cave map look seeded.
                        boolean commonUnderground = y == -32;
                        if (commonUnderground) {
                            var point = new DensityFunction.SinglePointContext(x, y, z);
                            for (var candidate : states) if (candidate.sampler().depth().compute(point) <= .2) {
                                commonUnderground = false;
                                break;
                            }
                        }
                        if (commonUnderground) fingerprint = fingerprint * 31 + name.hashCode();
                        if (BANDS.containsKey(name)) {
                            grid.put(key(x, z), name);
                            reached.add(name);
                        }
                        if (Math.floorMod(x, 448) == 0 || Math.floorMod(z, 448) == 0)
                            helper.assertTrue(!BANDS.containsKey(name), "Special cave crossed a separation corridor at " + x + "," + z);
                    }
                }
                while (!grid.isEmpty()) {
                    var first = grid.entrySet().iterator().next();
                    String name = first.getValue();
                    var queue = new ArrayDeque<Long>();
                    queue.add(first.getKey());
                    grid.remove(first.getKey());
                    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                    int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                    while (!queue.isEmpty()) {
                        long point = queue.removeFirst();
                        int x = (int) (point >> 32), z = (int) point;
                        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                        minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                        for (long next : new long[] {key(x - 16, z), key(x + 16, z), key(x, z - 16), key(x, z + 16)}) {
                            if (name.equals(grid.get(next))) { grid.remove(next); queue.addLast(next); }
                        }
                    }
                    int cap = name.equals("spider_caves") || name.equals("echo_caves") ? 192 : 384;
                    helper.assertTrue(maxX - minX <= cap && maxZ - minZ <= cap,
                            "Connected " + name + " region exceeded " + cap + " blocks: " + (maxX - minX) + "x" + (maxZ - minZ));
                    components++;
                }
            }
            fingerprints.add(fingerprint);
        }
        helper.assertTrue(components > 20 && reached.containsAll(BANDS.keySet()),
                "Missing representative disconnected cave regions: " + reached + ", components=" + components);
        helper.assertTrue(fingerprints.size() == SEEDS.length, "Different world seeds reused the same cave geography");
        System.out.println("CWI_BOUNDED_CAVE_COMPONENTS=" + components + ", biomes=" + reached);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void sourceCodecPreservesSurfaceClimateAndSeededResolution(GameTestHelper helper) throws Exception {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        JsonObject json = sourceJson(helper);
        helper.assertTrue(json.has("surface_source"), "Wasteland must use a bounded source with original surface climate");
        BiomeSource source = BiomeSource.CODEC.parse(ops, json).getOrThrow();
        BiomeSource surface = BiomeSource.CODEC.parse(ops, json.get("surface_source")).getOrThrow();
        JsonElement encoded = BiomeSource.CODEC.encodeStart(ops, source).getOrThrow();
        BiomeSource decoded = BiomeSource.CODEC.parse(ops, encoded).getOrThrow();
        helper.assertTrue(source.possibleBiomes().size() == 10 && decoded.possibleBiomes().size() == 10,
                "Codec lost CWI biomes");
        JsonObject oversized = json.deepCopy();
        oversized.getAsJsonArray("regions").get(0).getAsJsonObject().addProperty("max_diameter", 448);
        helper.assertTrue(BiomeSource.CODEC.parse(ops, oversized).error().isPresent(),
                "Codec accepted a patch large enough to merge across cell boundaries");
        JsonObject reversed = json.deepCopy();
        reversed.getAsJsonArray("regions").get(0).getAsJsonObject().addProperty("min_y", 80);
        helper.assertTrue(BiomeSource.CODEC.parse(ops, reversed).error().isPresent(),
                "Codec accepted reversed cave height bounds");
        for (long seed : SEEDS) {
            var state = state(helper, seed);
            var repeated = state(helper, seed);
            for (int x = -4096; x <= 4096; x += 256) {
                for (int z = -4096; z <= 4096; z += 256) {
                    helper.assertTrue(biome(source, state, x, 80, z).equals(biome(surface, state, x, 80, z)),
                            "Bounded cave source changed the original surface climate");
                    for (int y : new int[] {-48, -24, 0, 24, 80}) {
                        String expected = biome(source, state, x, y, z);
                        helper.assertTrue(expected.equals(biome(decoded, state, x, y, z))
                                && expected.equals(biome(source, repeated, x, y, z)),
                                "Codec or repeated RandomState changed seeded biome resolution");
                        if (state.sampler().depth().compute(new DensityFunction.SinglePointContext(x, y, z)) <= .2)
                            helper.assertTrue(expected.equals(biome(surface, state, x, y, z)),
                                    "Cave source replaced surface climate near low terrain at " + x + "," + y + "," + z);
                    }
                }
            }
        }
        helper.succeed();
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    private NativeBoundedCaveBiomeTest() {}
}
