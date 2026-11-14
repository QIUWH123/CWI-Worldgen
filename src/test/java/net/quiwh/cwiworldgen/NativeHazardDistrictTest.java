package net.quiwh.cwiworldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.placement.HazardDistrictFilter;

/** Shared seed-owned district placement and geometric lake eligibility. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeHazardDistrictTest {
    private static final ResourceLocation NOISE = ResourceLocation.fromNamespaceAndPath("cwi", "districts");

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void districtsRejectWrongNoiseHeightAndSteepSites(GameTestHelper helper) {
        var filter = new HazardDistrictFilter(NOISE, -.7, .05, 45, 90, 6);
        helper.assertTrue(filter.accepts(-.3, 100, 100, (x, z) -> 70 + Math.abs(x - 100) / 2),
                "Shallow basin floor rejected");
        helper.assertTrue(!filter.accepts(.3, 100, 100, (x, z) -> 70), "Wrong district accepted");
        helper.assertTrue(!filter.accepts(-.3, 100, 100, (x, z) -> 120), "High plateau accepted");
        helper.assertTrue(!filter.accepts(-.3, 100, 100, (x, z) -> 70 + x - 100), "Steep site accepted");
        helper.assertTrue(filter.accepts(.05, 100, 100, (x, z) -> 90), "Inclusive noise/height boundary rejected");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void nativeDistrictFilterUsesWorldRandomStateNoise(GameTestHelper helper) {
        var level = helper.getLevel();
        var key = ResourceKey.<NormalNoise.NoiseParameters>create(Registries.NOISE, NOISE);
        var noise = level.getChunkSource().randomState().getOrCreateNoise(key);
        BlockPos pos = helper.absolutePos(new BlockPos(5, 5, 5));
        double sample = noise.getValue(pos.getX(), 0., pos.getZ());
        var context = new PlacementContext(level, level.getChunkSource().getGenerator(), java.util.Optional.empty());
        var matching = new HazardDistrictFilter(NOISE, sample - .00001, sample + .00001, -2032, 2031, 32);
        helper.assertTrue(matching.getPositions(context, RandomSource.create(1), pos).count() == 1,
                "Filter did not share the world's district noise");
        var other = new HazardDistrictFilter(NOISE, sample + .1, sample + .2, -2032, 2031, 32);
        helper.assertTrue(other.getPositions(context, RandomSource.create(1), pos).count() == 0,
                "Native placement ignored its district range");
        helper.succeed();
    }

    private NativeHazardDistrictTest() {}
}
