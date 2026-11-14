package net.quiwh.cwiworldgen.terrain;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeWeatheringTest {
    @GameTest(template = "empty")
    public static void weatheringDistrictsAreSmoothAcrossChunkAndNegativeCellBoundaries(GameTestHelper helper) {
        double min = 1, max = 0, largestStep = 0;
        int changedHighSeed = 0;
        for (int z = -512; z <= 512; z += 16) for (int x = -512; x <= 512; x += 16) {
            double value = ReferenceWeathering.sample(7L, x, z);
            double next = ReferenceWeathering.sample(7L, x + 1, z);
            helper.assertTrue(Double.isFinite(value) && value >= 0 && value <= 1,
                    "Weathering strength escaped its bounded domain");
            helper.assertTrue(value == ReferenceWeathering.sample(7L, x, z),
                    "Weathering districts changed after sampling another coordinate");
            min = Math.min(min, value);
            max = Math.max(max, value);
            largestStep = Math.max(largestStep, Math.abs(value - next));
            if (Math.abs(value - ReferenceWeathering.sample(7L + (1L << 32), x, z)) > .02) changedHighSeed++;
        }
        helper.assertTrue(max - min > .4, "Weathering collapsed to a uniform coating");
        helper.assertTrue(largestStep < .055, "Weathering changed abruptly across a chunk or negative cell boundary");
        helper.assertTrue(changedHighSeed > 1000, "Weathering ignored the upper half of the world seed");
        System.out.println("CWI_WEATHERING_DISTRICTS=range=" + min + ".." + max
                + " largest_adjacent_step=" + largestStep + " high_seed_changes=" + changedHighSeed);
        helper.succeed();
    }
}
