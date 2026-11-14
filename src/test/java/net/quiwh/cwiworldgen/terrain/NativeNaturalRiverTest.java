package net.quiwh.cwiworldgen.terrain;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.hydrology.watershed.HydraulicChannelProfile;
import net.quiwh.cwiworldgen.hydrology.watershed.WatershedSample;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

/** Profiles 4–7 and 9 exercise real river blocks and generation bounds independently of frozen profiles. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeNaturalRiverTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void naturalRiverAndMountainBlocksMatchFullHeightAndRetainWater(GameTestHelper helper) {
        RiverMountainTestSupport.verifyRiverAndMountain(helper, "natural_wasteland_test", 4);
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void refinedRiverAndMountainBlocksMatchFullHeightAndRetainWater(GameTestHelper helper) {
        RiverMountainTestSupport.verifyRiverAndMountain(helper, "refined_wasteland_test", 5);
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void streamsRiverAndMountainBlocksMatchFullHeightAndRetainWater(GameTestHelper helper) {
        RiverMountainTestSupport.verifyRiverAndMountain(helper, "streams_wasteland_test", 6);
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void compactNaturalRiverAndMountainBlocksMatchFullHeightAndRetainWater(GameTestHelper helper) {
        RiverMountainTestSupport.verifyRiverAndMountain(helper, "compact_natural_wasteland_test", 7);
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void frequentRiversAndMountainBlocksMatchFullHeightAndRetainWater(GameTestHelper helper) {
        RiverMountainTestSupport.verifyRiverAndMountain(helper, "river_frequency_wasteland_test", 9);
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void waterLandscapeRiverAndMountainBlocksMatchFullHeightAndRetainWater(GameTestHelper helper) {
        RiverMountainTestSupport.verifyRiverAndMountain(helper, "water_landscape_wasteland_test", 10);
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void streamsBendsHaveAnOuterThalwegAndPreserveTheInnerShoal(GameTestHelper helper) {
        try (var terrain = new ReferenceTerrain(42L, 6)) {
            boolean positive = false;
            search: for (var reach : terrain.hydrologySnapshot(0, 0).reaches()) {
                if (reach.points().size() < 5) continue;
                var controls = reach.points().stream().map(point -> new HydraulicChannelProfile.Control(
                        point.x(), point.z(), terrain.watershedSample((int) Math.round(point.x()),
                                (int) Math.round(point.z())).waterY())).toList();
                var geometry = new HydraulicChannelProfile.Geometry(controls);
                for (int segment = 2; segment < reach.points().size() - 2; segment++) {
                    var a = reach.points().get(segment - 1); var b = reach.points().get(segment);
                    int x = (int) Math.round((a.x() + b.x()) * .5), z = (int) Math.round((a.z() + b.z()) * .5);
                    var center = terrain.watershedSample(x, z);
                    if (!hydraulicCandidate(center, reach.id()) || center.width() < 6
                            || center.progress() < .2 || center.progress() > .8) continue;
                    var context = geometry.at(segment, .5, center.width());
                    if (Math.abs(context.curvature()) < .08 || context.grade() > .08) continue;
                    double inside = Math.signum(context.curvature()) * center.width() * .45;
                    int ix = (int) Math.round(x - center.tangentZ() * inside);
                    int iz = (int) Math.round(z + center.tangentX() * inside);
                    int ox = (int) Math.round(x + center.tangentZ() * inside);
                    int oz = (int) Math.round(z - center.tangentX() * inside);
                    var inner = terrain.watershedSample(ix, iz); var outer = terrain.watershedSample(ox, oz);
                    if (!hydraulicCandidate(inner, reach.id()) || !hydraulicCandidate(outer, reach.id())
                            || !inner.shoal() || Math.abs(inner.progress() - outer.progress()) > .04
                            || effectiveDepth(outer) - effectiveDepth(inner) < .35) continue;
                    // Exercise the complete filtered tile, where the shallow inner bed must survive.
                    var finished = terrain.sample(ix, iz);
                    helper.assertTrue(finished.cwiShoal && Math.abs(finished.height * 256. - inner.bedY()) < .002,
                            "The real curved-channel inner shoal was lowered during final tile filtering");
                    var innerColumn = ReferenceRiverColumn.sample(terrain, ix, iz);
                    var outerColumn = ReferenceRiverColumn.sample(terrain, ox, oz);
                    helper.assertTrue(innerColumn.hasWater() && outerColumn.hasWater(),
                            "Hydraulic bend shaping closed an accepted real channel");
                    System.out.println("CWI_STREAMS_HYDRAULIC=seed:42,reach:" + reach.id()
                            + ",curvature:" + context.curvature() + ",inner:" + ix + "," + iz
                            + ",outer:" + ox + "," + oz + ",inner_depth:" + effectiveDepth(inner)
                            + ",outer_depth:" + effectiveDepth(outer) + ",preserved_shoal:true");
                    positive = true; break search;
                }
            }
            helper.assertTrue(positive, "No accepted native bend exercised the outside thalweg and surviving inner shoal");
        }
        helper.succeed();
    }

    private static boolean hydraulicCandidate(WatershedSample sample, long reach) {
        return sample.reachId() == reach && sample.wet() && !sample.basin() && !sample.island()
                && !sample.gorge() && !sample.plungePool() && !sample.dryCrest()
                && Math.abs(sample.distance()) < sample.width() * .85
                && sample.originalGroundY() > sample.waterY() + 4;
    }

    /** Remove ordinary symmetric bowl depth so unequal integer side distances cannot fake the result. */
    private static double effectiveDepth(WatershedSample sample) {
        double q = sample.distance() / sample.width();
        return .8 + (sample.waterY() - .8 - sample.bedY()) / Math.pow(1 - q * q, 1.15);
    }

}
