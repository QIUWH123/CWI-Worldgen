package net.quiwh.cwiworldgen.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeLayeredPocketTest {
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void sharedSizeWeightsAreSeededAndFavorOnlyMatchingBiomes(GameTestHelper helper) {
        var biome = helper.getLevel().getBiome(new BlockPos(26000, 96, -26000));
        var biomeId = biome.unwrapKey().orElseThrow().location();
        var normal = new VeinSizeSettings(true, List.of());
        var favored = new VeinSizeSettings(true, List.of(biomeId));
        var unrelated = new VeinSizeSettings(true, List.of(ResourceLocation.parse("cwi:unrelated_biome")));
        var normalRandom = RandomSource.create(0x5645494E53495A45L);
        var replayRandom = RandomSource.create(0x5645494E53495A45L);
        var unrelatedRandom = RandomSource.create(0x5645494E53495A45L);
        var favoredRandom = RandomSource.create(0x4641564F524544L);
        int[] normalCounts = new int[3];
        int[] favoredCounts = new int[3];
        int samples = 50000;
        double normalVolume = 0;
        double favoredVolume = 0;
        for (int attempt = 0; attempt < samples; attempt++) {
            float scale = normal.scale(normalRandom, biome);
            check(scale == normal.scale(replayRandom, biome), "Vein sizes must replay from the same seed");
            check(scale == unrelated.scale(unrelatedRandom, biome), "Unmatched biome IDs must keep neutral size weights");
            normalCounts[sizeCategory(scale)]++;
            normalVolume += scale * scale * scale;
            float favoredScale = favored.scale(favoredRandom, biome);
            favoredCounts[sizeCategory(favoredScale)]++;
            favoredVolume += favoredScale * favoredScale * favoredScale;
        }
        double[] normalWeights = {.6, .3, .1};
        double[] favoredWeights = {.4, .35, .25};
        for (int category = 0; category < 3; category++) {
            check(Math.abs(normalCounts[category] / (double) samples - normalWeights[category]) < .012,
                "Neutral size distribution lost its 60/30/10 weights");
            check(Math.abs(favoredCounts[category] / (double) samples - favoredWeights[category]) < .012,
                "Matching biome size distribution lost its 40/35/25 weights");
        }
        check(normalVolume / samples < .45 && favoredVolume / samples < .6 && favoredVolume > normalVolume,
            "Biome-favored sizes must remain below the former mean volume");
        var fixedRandom = RandomSource.create(556677);
        var fixedControl = RandomSource.create(556677);
        check(VeinSizeSettings.FIXED.scale(fixedRandom, biome) == 1F && fixedRandom.nextLong() == fixedControl.nextLong(),
            "Fixed-size settings must preserve the former geometry and random stream");
        System.out.println("CWI_WEIGHTED_VEIN_SIZES=normal:" + java.util.Arrays.toString(normalCounts)
            + "; favored:" + java.util.Arrays.toString(favoredCounts) + "; mean_volume="
            + normalVolume / samples + "/" + favoredVolume / samples);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void multiChoiceLayersRetainTheirFormerLocalDepositDensity(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var deposit = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:halite_deposit"));
        JsonObject data;
        var resource = level.getServer().getResourceManager().getResourceOrThrow(
            ResourceLocation.parse("cwi:worldgen/configured_feature/striated_ores/halite.json"));
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            data = JsonParser.parseReader(reader).getAsJsonObject();
        }
        data.getAsJsonObject("config").addProperty("pocket_fraction", 1F);
        // Keep this density fixture independent of the production size distribution.
        data.getAsJsonObject("config").remove("size_settings");
        for (JsonElement pattern : data.getAsJsonObject("config").getAsJsonArray("layer_patterns")) {
            for (JsonElement layer : pattern.getAsJsonArray()) {
                // Two original choices retain their weight even when they emit the same host.
                var choices = layer.getAsJsonObject().getAsJsonArray("targets");
                JsonArray hosts = new JsonArray();
                hosts.add(choices.get(0).deepCopy());
                hosts.add(choices.get(0).deepCopy());
                layer.getAsJsonObject().add("targets", hosts);
            }
        }
        var feature = ConfiguredFeature.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), data).getOrThrow();
        BlockPos origin = new BlockPos(24000, 96, -24000);
        int total = 0;
        int deposits = 0;
        int groups = 0;
        int retained = 0;
        for (int attempt = 0; attempt < 12; attempt++) {
            fill(helper, origin);
            check(feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(1700 + attempt), origin),
                "Multi-choice fixture did not place");
            var states = snapshot(helper, origin);
            total += states.size();
            for (var state : states.values()) if (state.is(deposit)) deposits++;
            var config = (LayeredDepositConfiguration) feature.config();
            long pocketSeed = legacyPocketSeed(config, 1700 + attempt, origin);
            List<BlockPos> candidates = new ArrayList<>(states.keySet());
            candidates.sort(Comparator.<BlockPos>comparingInt(pos -> pos.getZ()).thenComparingInt(pos -> pos.getX()).thenComparingInt(pos -> pos.getY()));
            var expectedGroups = legacyPocketGroups(candidates, pocketSeed);
            for (var group : expectedGroups) {
                List<BlockPos> formerDeposits = group.stream().filter(pos -> unit(pocketSeed ^ 0x4445504F534954L, pos) < 1D / 13).toList();
                if (formerDeposits.isEmpty()) continue;
                long actualCount = formerDeposits.stream().filter(pos -> states.get(pos).is(deposit)).count();
                check(actualCount == 0 || actualCount == formerDeposits.size(),
                    "Removing pocket groups must leave every surviving group's original blocks and density unchanged");
                groups++;
                if (actualCount > 0) retained++;
            }
        }
        double fraction = deposits / (double) total;
        check(Math.abs(fraction - .3D / 13) < .006,
            "Retaining 30% of groups must preserve two-choice local density 1/(6*2+1), measured " + fraction);
        check(Math.abs(retained / (double) groups - .3) < .07,
            "Expected 30% of unchanged original groups, measured " + retained + "/" + groups);
        System.out.println("CWI_LAYERED_MULTI_CHOICE_DENSITY=" + deposits + "/" + total + "; fraction=" + fraction);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void weightedLayeredSizesMatchCreateGeometryAndFavorTheMatchingBiome(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        JsonObject data;
        var resource = level.getServer().getResourceManager().getResourceOrThrow(
            ResourceLocation.parse("cwi:worldgen/configured_feature/striated_ores/halite.json"));
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            data = JsonParser.parseReader(reader).getAsJsonObject();
        }
        BlockPos origin = new BlockPos(26000, 96, -26000);
        String currentBiome = level.getBiome(origin).unwrapKey().orElseThrow().location().toString();
        for (boolean favored : List.of(false, true)) {
            JsonObject settings = new JsonObject();
            settings.addProperty("enabled", true);
            JsonArray favoredBiomes = new JsonArray();
            if (favored) favoredBiomes.add(currentBiome);
            settings.add("favored_biomes", favoredBiomes);
            data.getAsJsonObject("config").add("size_settings", settings);
            var production = ConfiguredFeature.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), data).getOrThrow();
            for (int attempt = 0; attempt < 5; attempt++) {
                long seed = 0x9E3779B97F4A7C15L * (attempt + 1);
                var referenceRandom = RandomSource.create(seed);
                float roll = referenceRandom.nextFloat();
                int expectedSize = roll < (favored ? .4 : .6) ? 19 : roll < (favored ? .75 : .9) ? 26 : 32;
                JsonObject reference = data.deepCopy();
                reference.addProperty("type", "create:layered_ore");
                reference.getAsJsonObject("config").addProperty("size", expectedSize);
                var hostOnly = ConfiguredFeature.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), reference).getOrThrow();
                fill(helper, origin);
                check(hostOnly.place(level, level.getChunkSource().getGenerator(), referenceRandom, origin), "Scaled Create reference did not place");
                var expected = snapshot(helper, origin);
                fill(helper, origin);
                check(production.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), origin), "Weighted vein did not place");
                var actual = snapshot(helper, origin);
                check(actual.keySet().equals(expected.keySet()),
                    "Weighted size selection must use the expected scaled Create outline; favored=" + favored + "; size=" + expectedSize);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void smallVeinsMayRetainZeroOrOneWholePocketWithoutRefilling(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        JsonObject data;
        var resource = level.getServer().getResourceManager().getResourceOrThrow(
            ResourceLocation.parse("cwi:worldgen/configured_feature/striated_ores/halite.json"));
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            data = JsonParser.parseReader(reader).getAsJsonObject();
        }
        var jsonConfig = data.getAsJsonObject("config");
        jsonConfig.remove("size_settings");
        jsonConfig.addProperty("size", 10);
        jsonConfig.addProperty("pocket_fraction", 1F);
        jsonConfig.addProperty("group_retention", 1F);
        var baseline = ConfiguredFeature.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), data).getOrThrow();
        jsonConfig.addProperty("group_retention", .3F);
        var reduced = ConfiguredFeature.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), data).getOrThrow();
        var config = (LayeredDepositConfiguration) reduced.config();
        var deposit = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:halite_deposit"));
        BlockPos origin = new BlockPos(27000, 96, -27000);
        boolean sawZero = false;
        boolean sawOne = false;
        int originalGroups = 0;
        int retainedGroups = 0;
        for (int attempt = 0; attempt < 40; attempt++) {
            long seed = 9000 + attempt;
            fill(helper, origin, 6);
            check(baseline.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), origin), "Small baseline host did not place");
            var original = snapshot(helper, origin, 5);
            fill(helper, origin, 6);
            check(reduced.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), origin),
                "Removing all pockets must still report a successfully placed host vein");
            var actual = snapshot(helper, origin, 5);
            check(actual.keySet().equals(original.keySet()), "Pocket reduction changed a small vein's host outline");
            Set<BlockPos> originalDeposits = new HashSet<>();
            Set<BlockPos> actualDeposits = new HashSet<>();
            original.forEach((pos, state) -> { if (state.is(deposit)) originalDeposits.add(pos); });
            actual.forEach((pos, state) -> { if (state.is(deposit)) actualDeposits.add(pos); });
            check(originalDeposits.containsAll(actualDeposits), "Removed pockets refilled their budget into new positions");
            List<BlockPos> candidates = new ArrayList<>(original.keySet());
            candidates.sort(Comparator.<BlockPos>comparingInt(pos -> pos.getZ()).thenComparingInt(pos -> pos.getX()).thenComparingInt(pos -> pos.getY()));
            int retained = 0;
            for (var group : legacyPocketGroups(candidates, legacyPocketSeed(config, seed, origin))) {
                var formerDeposits = group.stream().filter(originalDeposits::contains).toList();
                if (formerDeposits.isEmpty()) continue;
                long actualCount = formerDeposits.stream().filter(actualDeposits::contains).count();
                check(actualCount == 0 || actualCount == formerDeposits.size(), "A surviving small pocket changed its original size or density");
                originalGroups++;
                if (actualCount > 0) retained++;
            }
            retainedGroups += retained;
            sawZero |= retained == 0;
            sawOne |= retained == 1;
        }
        check(sawZero && sawOne, "Small veins must allow both zero and one retained pocket");
        check(Math.abs(retainedGroups / (double) originalGroups - .3) < .12,
            "Small veins forced extra groups or lost retention weighting: " + retainedGroups + "/" + originalGroups);
        System.out.println("CWI_SMALL_POCKET_RETENTION=" + retainedGroups + "/" + originalGroups);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void layeredDepositsReduceQuantityInsideSmallPocketsAndPreserveCreateHostGeometry(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var deposit = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:halite_deposit"));
        JsonObject data;
        var resource = level.getServer().getResourceManager().getResourceOrThrow(
            ResourceLocation.parse("cwi:worldgen/configured_feature/striated_ores/halite.json"));
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            data = JsonParser.parseReader(reader).getAsJsonObject();
        }
        // Fixed-size legacy compatibility must still reproduce Create's host exactly.
        data.getAsJsonObject("config").remove("size_settings");
        var production = ConfiguredFeature.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), data).getOrThrow();
        JsonObject baselineData = data.deepCopy();
        baselineData.getAsJsonObject("config").addProperty("group_retention", 1F);
        var fullPockets = ConfiguredFeature.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), baselineData).getOrThrow();
        // Reconstruct the unchanged, deposit-free Create reference without depending on Create Java APIs.
        JsonObject reference = data.deepCopy();
        reference.addProperty("type", "create:layered_ore");
        for (JsonElement pattern : reference.getAsJsonObject("config").getAsJsonArray("layer_patterns")) {
            for (JsonElement layer : pattern.getAsJsonArray()) {
                JsonArray hosts = new JsonArray();
                Set<String> seen = new HashSet<>();
                for (JsonElement choice : layer.getAsJsonObject().getAsJsonArray("targets")) {
                    if (choice.toString().contains("_deposit")) continue;
                    if (seen.add(choice.toString())) hosts.add(choice.deepCopy());
                }
                layer.getAsJsonObject().add("targets", hosts);
            }
        }
        var hostOnly = ConfiguredFeature.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), reference).getOrThrow();
        BlockPos origin = new BlockPos(23000, 96, -23000);
        int mineralCount = 0;
        int depositCount = 0;
        int closeDeposits = 0;
        for (int attempt = 0; attempt < 20; attempt++) {
            fill(helper, origin);
            check(hostOnly.place(level, level.getChunkSource().getGenerator(), RandomSource.create(1100 + attempt), origin), "Reference vein did not place");
            Map<BlockPos, BlockState> hostStates = snapshot(helper, origin);
            fill(helper, origin);
            check(production.place(level, level.getChunkSource().getGenerator(), RandomSource.create(1100 + attempt), origin), "Compound vein did not place");
            Set<BlockPos> deposits = new HashSet<>();
            for (var entry : snapshot(helper, origin).entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState actual = entry.getValue();
                check(hostStates.containsKey(pos), "Compound generation changed Create's original mineral-body outline");
                if (actual.is(deposit)) deposits.add(pos);
                else check(actual.equals(hostStates.get(pos)), "A host mineral changed outside compound deposit blocks");
            }
            check(snapshot(helper, origin).size() == hostStates.size(), "Compound generation lost original mineral blocks");
            fill(helper, origin);
            check(fullPockets.place(level, level.getChunkSource().getGenerator(), RandomSource.create(1100 + attempt), origin),
                "Unreduced pocket reference did not place");
            Set<BlockPos> originalDeposits = new HashSet<>();
            snapshot(helper, origin).forEach((pos, state) -> { if (state.is(deposit)) originalDeposits.add(pos); });
            check(originalDeposits.containsAll(deposits), "Surviving pockets expanded into positions outside the former eligible mask");
            List<BlockPos> candidates = new ArrayList<>(hostStates.keySet());
            candidates.sort(Comparator.<BlockPos>comparingInt(pos -> pos.getZ()).thenComparingInt(pos -> pos.getX()).thenComparingInt(pos -> pos.getY()));
            var config = (LayeredDepositConfiguration) production.config();
            for (var group : legacyPocketGroups(candidates, legacyPocketSeed(config, 1100 + attempt, origin), config.pocketFraction())) {
                var formerDeposits = group.stream().filter(originalDeposits::contains).toList();
                long actualCount = formerDeposits.stream().filter(deposits::contains).count();
                check(actualCount == 0 || actualCount == formerDeposits.size(),
                    "A surviving pocket lost its original eligible volume or local density");
            }
            for (BlockPos pos : deposits) {
                if (deposits.stream().anyMatch(other -> !other.equals(pos) && other.distSqr(pos) <= 6.25)) closeDeposits++;
            }
            mineralCount += hostStates.size();
            depositCount += deposits.size();
        }
        double fraction = depositCount / (double) mineralCount;
        check(fraction > .0055 && fraction < .012,
            "Layered deposits should retain 30% of existing pocket groups without refilling them; measured " + fraction);
        check(closeDeposits > depositCount * .77,
            "Reduced deposits must occur inside small pockets instead of uniform sparse scattering");
        System.out.println("CWI_LAYERED_POCKETS=" + depositCount + "/" + mineralCount + "; fraction=" + fraction
            + "; close=" + closeDeposits);
        helper.succeed();
    }

    private static void fill(GameTestHelper helper, BlockPos origin) {
        fill(helper, origin, 17);
    }

    private static void fill(GameTestHelper helper, BlockPos origin, int radius) {
        for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) for (int z = -radius; z <= radius; z++)
            helper.getLevel().setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
    }

    // The previous release's RNG setup and group assignment are the regression oracle.
    private static long legacyPocketSeed(LayeredDepositConfiguration config, long worldSeed, BlockPos origin) {
        var random = RandomSource.create(worldSeed);
        var pattern = config.layerPatterns().get(random.nextInt(config.layerPatterns().size()));
        int size = config.size() + 1;
        float total = 0;
        LayeredDepositConfiguration.Layer previous = null;
        List<Float> thicknesses = new ArrayList<>();
        while (total < size) {
            int weight = 0;
            for (var layer : pattern) if (layer != previous) weight += layer.weight();
            int rolled = random.nextInt(weight);
            for (var layer : pattern) {
                if (layer == previous) continue;
                rolled -= layer.weight();
                if (rolled < 0) {
                    float thickness = Mth.randomBetween(random, layer.minSize(), layer.maxSize());
                    thicknesses.add(thickness);
                    total += thickness;
                    previous = layer;
                    break;
                }
            }
        }
        float cumulative = -(total - size) * random.nextFloat();
        for (float thickness : thicknesses) {
            cumulative += thickness;
            if (cumulative >= 0) Mth.randomBetween(random, .5F, 1F);
        }
        Mth.randomBetween(random, -1F, 1F);
        random.nextFloat();
        var displacement = new SimplexNoise(random);
        return Double.doubleToLongBits(displacement.getValue(13.5, 19.25, 29.75)) ^ origin.asLong();
    }

    private static List<List<BlockPos>> legacyPocketGroups(List<BlockPos> candidates, long seed) {
        return legacyPocketGroups(candidates, seed, 1F);
    }

    private static List<List<BlockPos>> legacyPocketGroups(List<BlockPos> candidates, long seed, float fraction) {
        var random = RandomSource.create(seed);
        int count = Math.max(2, Math.round(candidates.size() * fraction / 180F));
        List<LegacyPocket> pockets = new ArrayList<>();
        for (int attempt = 0; attempt < count * 80 && pockets.size() < count; attempt++) {
            BlockPos center = candidates.get(random.nextInt(candidates.size()));
            if (pockets.stream().anyMatch(pocket -> pocket.center.distSqr(center) < 36)) continue;
            pockets.add(new LegacyPocket(center, 2.8 + random.nextDouble() * .8, 2.2 + random.nextDouble() * .8,
                2.8 + random.nextDouble() * .8));
        }
        List<List<BlockPos>> result = new ArrayList<>();
        for (int index = 0; index < pockets.size(); index++) result.add(new ArrayList<>());
        for (BlockPos pos : candidates) {
            int nearest = 0;
            double best = Double.POSITIVE_INFINITY;
            for (int index = 0; index < pockets.size(); index++) {
                var pocket = pockets.get(index);
                double dx = (pos.getX() - pocket.center.getX()) / pocket.rx;
                double dy = (pos.getY() - pocket.center.getY()) / pocket.ry;
                double dz = (pos.getZ() - pocket.center.getZ()) / pocket.rz;
                double distance = dx * dx + dy * dy + dz * dz;
                if (distance < best) { best = distance; nearest = index; }
            }
            result.get(nearest).add(pos);
        }
        return result;
    }

    private static double unit(long seed, BlockPos pos) {
        long mixed = seed ^ (pos.getX() * 0x632BE59BD9B4E019L) ^ (pos.getY() * 0x9E3779B97F4A7C15L)
            ^ (pos.getZ() * 0x85157AF5D66D3E8BL);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return ((mixed ^ (mixed >>> 31)) >>> 11) * 0x1.0p-53;
    }

    private record LegacyPocket(BlockPos center, double rx, double ry, double rz) {}

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper helper, BlockPos origin) {
        return snapshot(helper, origin, 16);
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper helper, BlockPos origin, int radius) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) for (int z = -radius; z <= radius; z++) {
            BlockPos pos = origin.offset(x, y, z);
            BlockState state = helper.getLevel().getBlockState(pos);
            if (!state.is(Blocks.STONE)) result.put(pos, state);
        }
        return result;
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }

    private static int sizeCategory(float scale) {
        if (scale == .6F) return 0;
        if (scale == .8F) return 1;
        if (scale == 1F) return 2;
        throw new AssertionError("Unexpected vein size scale: " + scale);
    }
}
