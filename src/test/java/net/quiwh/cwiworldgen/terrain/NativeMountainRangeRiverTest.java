package net.quiwh.cwiworldgen.terrain;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;

/** Current-profile real-block and full-height controls for shorter mountain ranges. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeMountainRangeRiverTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void mountainRangeRiverAndMountainBlocksMatchFullHeightAndRetainWater(GameTestHelper helper) {
        RiverMountainTestSupport.verifyRiverAndMountain(helper, "mountain_ranges_wasteland_test", 11);
    }
}
