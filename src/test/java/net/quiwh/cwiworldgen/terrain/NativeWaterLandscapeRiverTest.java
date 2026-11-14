package net.quiwh.cwiworldgen.terrain;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;

/** Current-profile entry point for the unchanged native real-block/full-height controls. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeWaterLandscapeRiverTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void waterLandscapeRiverAndMountainBlocksMatchFullHeightAndRetainWater(GameTestHelper helper) {
        RiverMountainTestSupport.verifyRiverAndMountain(helper, "water_landscape_wasteland_test", 10);
    }
}
