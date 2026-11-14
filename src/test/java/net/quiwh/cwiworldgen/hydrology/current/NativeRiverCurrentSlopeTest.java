package net.quiwh.cwiworldgen.hydrology.current;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverColumn;

/** Dry headwater and bend projections must not accelerate real river water. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeRiverCurrentSlopeTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void dryEndpointsUseOnlyActualWetSurfaceHeights(GameTestHelper helper) {
        var current = wet(100);
        var dryHigh = column(1100, 1024, false);
        var dryLow = column(1100, -64, false);
        // A tagged column can also be dry when terrain reaches or exceeds its table.
        var buriedHigh = column(1024, 1024, true);
        var buriedLow = column(-64, -64, true);
        for (var dry : new ReferenceRiverColumn[]{dryHigh, dryLow, buriedHigh, buriedLow}) {
            helper.assertTrue(!dry.hasWater(), "Dry endpoint fixture unexpectedly has planned water");
            helper.assertTrue(CurrentFieldBuilder.downhillSlope(current, dry, wet(98)) == .5,
                    "A dry upstream table affected the one-sided downstream grade");
            helper.assertTrue(CurrentFieldBuilder.downhillSlope(current, wet(102), dry) == .5,
                    "A dry downstream table affected the one-sided upstream grade");
            helper.assertTrue(CurrentFieldBuilder.downhillSlope(current, dry, wet(102)) == 0
                            && CurrentFieldBuilder.downhillSlope(current, wet(98), dry) == 0,
                    "An uphill one-sided sample produced a downhill current grade");
        }
        helper.assertTrue(CurrentFieldBuilder.downhillSlope(current, dryHigh, dryLow) == 0
                        && CurrentFieldBuilder.downhillSlope(current, buriedHigh, buriedLow) == 0,
                "Two dry endpoint tables produced a synthetic current grade");
        double expectedStillGradeSpeed = CurrentFieldBuilder.profileSpeed(5, 9, 0, 0);
        helper.assertTrue(CurrentFieldBuilder.profileSpeed(5, 9,
                        CurrentFieldBuilder.downhillSlope(current, dryHigh, dryLow), 0) == expectedStillGradeSpeed,
                "Dry endpoint tables increased the runtime current speed");
        System.out.println("CWI_CURRENT_DRY_ENDPOINTS=untagged_rejected:true,buried_tagged_rejected:true,one_sided:true,downhill_only:true");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void twoWetEndpointsRetainTheOriginalCentralDifference(GameTestHelper helper) {
        var current = wet(100);
        for (int backwardY : new int[]{-48, 98, 100, 102, 200}) {
            for (int forwardY : new int[]{-48, 98, 100, 102, 200}) {
                double original = Math.max(0, backwardY - forwardY) / 8.;
                double actual = CurrentFieldBuilder.downhillSlope(current, wet(backwardY), wet(forwardY));
                helper.assertTrue(Double.doubleToRawLongBits(actual) == Double.doubleToRawLongBits(original),
                        "Two wet endpoint grades changed their original arithmetic");
                helper.assertTrue(CurrentFieldBuilder.profileSpeed(5, 9, actual, 0)
                                == CurrentFieldBuilder.profileSpeed(5, 9, original, 0),
                        "Two wet endpoints changed the configured current speed profile");
            }
        }
        helper.succeed();
    }

    private static ReferenceRiverColumn wet(int waterY) { return column(waterY - 5, waterY, true); }

    private static ReferenceRiverColumn column(int groundY, int waterY, boolean waterCell) {
        return new ReferenceRiverColumn(groundY, waterY, waterY, groundY, waterY, groundY,
                waterCell, ReferenceRiverColumn.Transition.NONE, groundY + 1, waterY);
    }
}
