package net.quiwh.cwiworldgen.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeBiomeRockTest {
    private static final ResourceLocation BIOME = ResourceLocation.parse("cwi:glowshroom_caves");
    private static final List<BiomeRockRule.Rock> ROCKS = List.of(
            new BiomeRockRule.Rock(Blocks.GRANITE.defaultBlockState(), 35),
            new BiomeRockRule.Rock(Blocks.ANDESITE.defaultBlockState(), 30),
            new BiomeRockRule.Rock(Blocks.DIORITE.defaultBlockState(), 30),
            new BiomeRockRule.Rock(Blocks.TUFF.defaultBlockState(), 5));

    @GameTest(template = "empty")
    public static void biomeRockCodecRoundTripsAndDefaultsTo48BlockMasses(GameTestHelper helper) {
        var rule = rule(-32, 40);
        JsonObject encoded = SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE, rule).getOrThrow().getAsJsonObject();
        helper.assertTrue(encoded.get("type").getAsString().equals("cwi_worldgen:biome_rocks"),
                "Biome rock material rule was not registered");
        helper.assertTrue(SurfaceRules.RuleSource.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(rule),
                "Biome rock codec changed its palette or bounds");
        encoded.remove("cell_size");
        helper.assertTrue(((BiomeRockRule) SurfaceRules.RuleSource.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow()).cellSize() == 48,
                "Omitted mass size must default to 48 blocks");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void biomeRockCodecRejectsInvalidProfilesBeforeWorldgen(GameTestHelper helper) {
        JsonObject valid = SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE, rule(-32, 40)).getOrThrow().getAsJsonObject();
        JsonObject invalid = valid.deepCopy();
        invalid.addProperty("cell_size", 0);
        reject(helper, invalid, "zero cell size");
        invalid = valid.deepCopy();
        invalid.add("profiles", new JsonArray());
        reject(helper, invalid, "empty profile list");
        invalid = valid.deepCopy();
        invalid.getAsJsonArray("profiles").add(invalid.getAsJsonArray("profiles").get(0).deepCopy());
        reject(helper, invalid, "duplicate biome profiles");
        invalid = valid.deepCopy();
        profile(invalid).addProperty("min_y", 41);
        reject(helper, invalid, "inverted height range");
        invalid = valid.deepCopy();
        profile(invalid).addProperty("min_y", -2033);
        reject(helper, invalid, "height outside supported world bounds");
        invalid = valid.deepCopy();
        profile(invalid).add("rocks", new JsonArray());
        reject(helper, invalid, "empty rock palette");
        for (int weight : new int[] {0, -1}) {
            invalid = valid.deepCopy();
            profile(invalid).getAsJsonArray("rocks").get(0).getAsJsonObject().addProperty("weight", weight);
            reject(helper, invalid, "nonpositive weight");
        }
        invalid = valid.deepCopy();
        for (var rock : profile(invalid).getAsJsonArray("rocks")) rock.getAsJsonObject().addProperty("weight", Integer.MAX_VALUE);
        reject(helper, invalid, "overflowing palette weight total");
        for (String state : new String[] {"minecraft:air", "minecraft:water", "minecraft:chest"}) {
            invalid = valid.deepCopy();
            profile(invalid).getAsJsonArray("rocks").get(0).getAsJsonObject().getAsJsonObject("state").addProperty("Name", state);
            reject(helper, invalid, "non-rock target " + state);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void biomeAndInclusiveHeightLimitsApplyToTheActualBlockPosition(GameTestHelper helper) {
        var sampler = rule(-32, 40).sampler(42);
        for (int y : new int[] {-32, -31, -1, 0, 39, 40}) {
            helper.assertTrue(sampler.materialAt(BIOME, -17, y, 16) != null, "Valid actual Y was excluded: " + y);
        }
        for (int y : new int[] {-2032, -33, 41, 2031}) {
            helper.assertTrue(sampler.materialAt(BIOME, -17, y, 16) == null, "Material escaped explicit Y range: " + y);
        }
        for (String biome : new String[] {"cwi:typical_caves", "cwi:spider_caves", "cwi:deep_ocean", "minecraft:plains"}) {
            helper.assertTrue(sampler.materialAt(ResourceLocation.parse(biome), -17, -16, 16) == null,
                    "Ordinary or unrelated biome received special rock: " + biome);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void seededMassesRemainStableAcrossQueryOrderAndNegativeChunkBoundaries(GameTestHelper helper) {
        var rule = rule(-2032, 2031);
        var sampler = rule.sampler(42);
        var repeated = rule.sampler(42);
        var foreign = rule.sampler(43);
        List<int[]> points = new ArrayList<>();
        List<BlockState> expected = new ArrayList<>();
        Set<Long> masses = new HashSet<>();
        int changed = 0;
        for (int i = 0; i < 512; i++) {
            int[] pos = {i * 73 % 1024 - 512, i * 47 % 512 - 256, i * 151 % 1024 - 512};
            points.add(pos);
            BlockState material = sampler.materialAt(BIOME, pos[0], pos[1], pos[2]);
            expected.add(material);
            masses.add(sampler.massId(pos[0], pos[1], pos[2]));
            if (material != foreign.materialAt(BIOME, pos[0], pos[1], pos[2])) changed++;
        }
        for (int i = points.size() - 1; i >= 0; i--) {
            int[] pos = points.get(i);
            helper.assertTrue(expected.get(i) == sampler.materialAt(BIOME, pos[0], pos[1], pos[2])
                            && expected.get(i) == repeated.materialAt(BIOME, pos[0], pos[1], pos[2]),
                    "Sampler depends on instance warmth or query order");
        }
        helper.assertTrue(changed > 180 && masses.size() > 200, "Masses lost seed or spatial variation");
        int crossings = 0;
        int same = 0;
        for (int boundary = -256; boundary <= 256; boundary += 16) for (int y = -128; y <= 128; y += 8) {
            long left = sampler.massId(boundary - 1, y, -17);
            long right = sampler.massId(boundary, y, -17);
            if (left == right) same++;
            crossings++;
        }
        helper.assertTrue(same > crossings * .9, "Chunk edges created material seams");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void weightedPalettesDescribeMassChoicesRatherThanPerBlockSpeckles(GameTestHelper helper) {
        var sampler = rule(-2032, 2031).sampler(42);
        Map<BlockState, Integer> counts = new HashMap<>();
        for (int i = 0; i < 10000; i++) {
            BlockState material = sampler.materialAt(BIOME, i * 137 % 200003 - 100000,
                    i * 61 % 4000 - 2000, i * 307 % 200003 - 100000);
            counts.merge(material, 1, Integer::sum);
        }
        for (var rock : ROCKS) {
            double proportion = counts.getOrDefault(rock.state(), 0) / 10000.0;
            helper.assertTrue(Math.abs(proportion - rock.weight() / 100.0) < .035,
                    "Seeded mass choice ignored palette weight: " + rock + " sampled=" + proportion);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void coherentThreeDimensionalMassesAvoidHorizontalStrataAndSmallNoise(GameTestHelper helper) {
        var sampler = rule(-2032, 2031).sampler(42);
        int[] transitions = new int[3];
        int[] materialTransitions = new int[3];
        int length = 1536;
        int lines = 12;
        for (int axis = 0; axis < 3; axis++) for (int line = 0; line < lines; line++) {
            long previousMass = Long.MIN_VALUE;
            BlockState previousMaterial = null;
            for (int distance = 0; distance < length; distance++) {
                int[] pos = {-711 + line * 71, -768 + line * 37, 417 - line * 89};
                pos[axis] = distance - length / 2;
                long mass = sampler.massId(pos[0], pos[1], pos[2]);
                BlockState material = sampler.materialAt(BIOME, pos[0], pos[1], pos[2]);
                if (distance > 0 && mass != previousMass) transitions[axis]++;
                if (distance > 0 && material != previousMaterial) materialTransitions[axis]++;
                previousMass = mass;
                previousMaterial = material;
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            double meanChord = length * lines / (double) (transitions[axis] + lines);
            helper.assertTrue(meanChord >= 24 && meanChord <= 72,
                    "Typical mass chord left the approved broad scale on axis " + axis + ": " + meanChord);
            helper.assertTrue(materialTransitions[axis] > 100 && materialTransitions[axis] < length * lines * .05,
                    "Material masses were layered or speckled on axis " + axis);
        }
        helper.assertTrue(Math.abs(transitions[0] - transitions[1]) < transitions[0] * .4
                        && Math.abs(transitions[2] - transitions[1]) < transitions[2] * .4,
                "Vertical mass scale differs sharply from the horizontal scale");
        Set<BlockState> horizontal = new HashSet<>();
        for (int z = -192; z <= 192; z += 16) for (int x = -192; x <= 192; x += 16) {
            horizontal.add(sampler.materialAt(BIOME, x, -16, z));
        }
        helper.assertTrue(horizontal.size() == ROCKS.size(), "A horizontal slice collapsed into one global stratum");
        System.out.println("CWI_BIOME_ROCK_MASS_TRANSITIONS=" + java.util.Arrays.toString(transitions));
        System.out.println("CWI_BIOME_ROCK_MATERIAL_TRANSITIONS=" + java.util.Arrays.toString(materialTransitions));
        helper.succeed();
    }

    private static BiomeRockRule rule(int minY, int maxY) {
        return new BiomeRockRule(48, List.of(new BiomeRockRule.Profile(BIOME, minY, maxY, ROCKS)));
    }

    private static JsonObject profile(JsonObject rule) {
        return rule.getAsJsonArray("profiles").get(0).getAsJsonObject();
    }

    private static void reject(GameTestHelper helper, JsonObject encoded, String label) {
        helper.assertTrue(SurfaceRules.RuleSource.CODEC.parse(JsonOps.INSTANCE, encoded).error().isPresent(),
                "Resource loading accepted " + label);
    }
}
