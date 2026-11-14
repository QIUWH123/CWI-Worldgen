package net.quiwh.cwiworldgen.landscape;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscapeProfiles;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

/** Exercises actual profile-21 formation descriptors, completed cells and climate-bound cavern gates. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeLandscapeOptimizerTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 60000)
    public static void actualFormationsAreFoundByTheirOwningCells(GameTestHelper helper) {
        ReferenceTerrain terrain = terrain(helper);
        var survey = new LandscapeSurvey(terrain, () -> false);
        EnumSet<LandscapeType> reached = EnumSet.noneOf(LandscapeType.class);
        int surfaceSize = ReferenceLandscapeProfiles.SURFACE_CELL;
        for (int ring = 0; ring <= 16 && reached.size() < 3; ring++) {
            for (int cx = -ring; cx <= ring && reached.size() < 3; cx++)
                for (int cz = -ring; cz <= ring && reached.size() < 3; cz++) {
                    if (Math.max(Math.abs(cx), Math.abs(cz)) != ring) continue;
                    var shape = terrain.landscape().surfaceFormationAt(cx * surfaceSize, cz * surfaceSize);
                    if (shape == null) continue;
                    var type = LandscapeType.valueOf(shape.kind().name());
                    if (reached.contains(type)) continue;
                    var accepted = survey.formation(type, shape);
                    if (accepted == null) continue;
                    // The origin is displaced from the anchor. The requested circle crosses owning
                    // cells, while no assumption is made about the old 96-block probe alignment.
                    int originX = accepted.x() + 41, originZ = accepted.z() - 29;
                    var found = survey.search(type, originX, originZ, 64, 128,
                            TimeUnit.SECONDS.toNanos(30), () -> false, n -> {});
                    helper.assertTrue(found.reason() == LandscapeSearch.Reason.FOUND,
                            "Directed owning-cell search missed accepted " + type + ": " + found);
                    var point = found.candidate();
                    helper.assertTrue(survey.here(point.x(), point.y(), point.z()).contains(type),
                            "Located " + type + " does not contain an accepted final cut");
                    reached.add(type);
                    System.out.println("CWI_LANDFORM_OWNER=" + type + ",point:" + point + ",candidates:" + found.samples());
                }
        }
        helper.assertTrue(reached.containsAll(EnumSet.of(LandscapeType.ARCH, LandscapeType.OVERHANG, LandscapeType.SPIRE)),
                "Native fixture did not verify every surface formation kind: " + reached);
        int cavernSize = terrain.landscape().cavernCellSize();
        boolean cavern = false;
        for (int ring = 0; ring <= 12 && !cavern; ring++) {
            for (int cx = -ring; cx <= ring && !cavern; cx++) for (int cz = -ring; cz <= ring && !cavern; cz++) {
                if (Math.max(Math.abs(cx), Math.abs(cz)) != ring) continue;
                var shape = terrain.landscape().cavernFormationAt(cx * cavernSize, cz * cavernSize);
                if (shape == null) continue;
                var accepted = survey.formation(LandscapeType.CAVERN, shape);
                if (accepted == null) continue;
                var found = survey.search(LandscapeType.CAVERN, accepted.x() + 41, accepted.z() - 29,
                        64, 128, TimeUnit.SECONDS.toNanos(30), () -> false, n -> {});
                helper.assertTrue(found.reason() == LandscapeSearch.Reason.FOUND,
                        "Directed owning-cell search missed accepted cavern: " + found);
                var point = found.candidate();
                helper.assertTrue(survey.here(point.x(), point.y(), point.z()).contains(LandscapeType.CAVERN),
                        "Located cavern is outside an accepted final opening");
                cavern = true;
                System.out.println("CWI_LANDFORM_OWNER=CAVERN,point:" + point + ",candidates:" + found.samples());
            }
        }
        helper.assertTrue(cavern, "Native fixture did not demonstrate an accepted cavern");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 60000)
    public static void broadTerrainUsesFinalCellsAndReportsIncompleteBudgets(GameTestHelper helper) {
        ReferenceTerrain terrain = terrain(helper);
        var survey = new LandscapeSurvey(terrain, () -> false);
        var reached = EnumSet.noneOf(LandscapeType.class);
        for (int[] p : new int[][] {{0,0}, {1024,1024}, {-1024,-1024}, {2048,-4096}, {-3508,-2432},
                {4096,4096}, {-4096,4096}, {8192,-8192}}) {
            var cell = terrain.sample(p[0], p[1]);
            var expected = LandscapeType.classify(cell, terrain.levels().waterLevel, terrain.levels().worldHeight);
            for (LandscapeType type : expected) {
                var found = survey.search(type, p[0], p[1], 64, 1,
                        TimeUnit.SECONDS.toNanos(30), () -> false, n -> {});
                helper.assertTrue(found.reason() == LandscapeSearch.Reason.FOUND && found.samples() == 1,
                        "Authoritative origin match lost for " + type + ": " + found);
                helper.assertTrue(found.candidate().x() == p[0] && found.candidate().z() == p[1],
                        "Origin fast path relocated a broad terrain match");
                reached.add(type);
            }
            var absent = expected.contains(LandscapeType.VOLCANO) ? LandscapeType.BADLANDS : LandscapeType.VOLCANO;
            var limited = survey.search(absent, p[0], p[1], 64, 1,
                    TimeUnit.SECONDS.toNanos(30), () -> false, n -> {});
            helper.assertTrue(limited.reason() == LandscapeSearch.Reason.SAMPLE_LIMIT && limited.candidate() == null,
                    "Incomplete single-probe search was reported as absence/success");
            var after = terrain.sample(p[0], p[1]);
            helper.assertTrue(Float.floatToRawIntBits(cell.height) == Float.floatToRawIntBits(after.height)
                    && cell.terrain == after.terrain, "Search mutated completed authoritative terrain");
        }
        helper.assertTrue(reached.size() >= 3, "Broad native fixture has insufficient terrain variety: " + reached);
        System.out.println("CWI_LANDFORM_FINAL_CELL_FAMILIES=" + reached);
        helper.succeed();
    }

    private static ReferenceTerrain terrain(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        helper.assertTrue(level != null && level.getSeed() == 0, "Missing native seed-0 fixture");
        var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null && terrain.generationVersion() == 21 && terrain.landscape() != null,
                "Fixture does not use the current profile and climate-bound landscape");
        return terrain;
    }
}
