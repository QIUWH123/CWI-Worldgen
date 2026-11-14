package net.quiwh.cwiworldgen;

import java.lang.reflect.Field;
import java.util.Random;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.reference.GeneratorContext;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.continent.Continent;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.Rivermap;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.BaseRiverGenerator;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.CFHRiverCarver;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.Network;

/** Wetland generation is disabled for CWI while native river networks remain live. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeWetlandRemovalTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void productionRiverGraphsContainNoRootOrTributaryWetlands(GameTestHelper helper)
            throws Exception {
        int roots = 0, tributaries = 0, wetlands = 0;
        for (long seed : new long[]{0, 42, -987654321}) {
            try (var terrain = new ReferenceTerrain(seed)) {
                GeneratorContext context = context(terrain);
                for (int[] point : new int[][]{{3280, 4260}, {-1600, -7640}, {-2854, 6731}}) {
                    var cell = terrain.sampleRaw(point[0], point[1]);
                    Rivermap map = context.generator.getHeightmap().continent().getRivermap(cell);
                    for (Network root : networks(map)) {
                        roots++;
                        GraphCounts counts = graphCounts(root);
                        tributaries += counts.reaches() - 1;
                        wetlands += counts.wetlands();
                    }
                }
            }
        }
        System.out.println("CWI_WETLAND_GRAPH=roots:" + roots + ",tributaries:" + tributaries
                + ",wetlands:" + wetlands);
        helper.assertTrue(roots > 0 && tributaries > 0,
                "Wetland regression did not exercise live main rivers and tributaries");
        helper.assertTrue(wetlands == 0, "CWI generated " + wetlands
                + " wetland basins in native root and tributary networks");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void zeroChanceSkipsWetlandsOnTheWholeNetwork(GameTestHelper helper) throws Exception {
        try (var terrain = new ReferenceTerrain(0)) {
            GeneratorContext context = context(terrain);
            var cell = terrain.sampleRaw(3280, 4260);
            Network[] roots = networks(context.generator.getHeightmap().continent().getRivermap(cell));
            helper.assertTrue(roots.length > 0, "Native river fixture contains no carver");
            CFHRiverCarver carver = roots[0].riverCarver();

            Network.Builder enabled = branch(carver);
            generator(context, 1F).generateWetlands(enabled, new Random(0));
            helper.assertTrue(enabled.wetlands.size() == 1
                            && enabled.children.getFirst().wetlands.size() == 1,
                    "Enabled control failed to create root and tributary wetlands");

            Network.Builder disabled = branch(carver);
            // Random(0).nextInt(10) is zero: the old chance-to-skip conversion
            // therefore generated a wetland even when the configured chance was zero.
            generator(context, 0F).generateWetlands(disabled, new Random(0));
            helper.assertTrue(disabled.wetlands.isEmpty()
                            && disabled.children.getFirst().wetlands.isEmpty(),
                    "Zero wetland chance still creates root or tributary basins");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void productionRawAndFilteredTerrainContainNoWetlandCells(GameTestHelper helper) {
        int riverCells = 0, rawCells = 0, filteredCells = 0, rawWetlands = 0, filteredWetlands = 0;
        try (var terrain = new ReferenceTerrain(0)) {
            // Before removal, (-7808, 3392) is a wetland and its owning
            // filtered tile contains 3,484 wetland cells.
            for (int x = -7936; x <= -7680; x += 16) {
                for (int z = 3264; z <= 3520; z += 16) {
                    var cell = terrain.sampleRaw(x, z);
                    rawCells++;
                    if (cell.terrain.isWetland()) rawWetlands++;
                }
            }
            var tile = terrain.getTile(-7808 >> ReferenceTerrain.TILE_SHIFT,
                    3392 >> ReferenceTerrain.TILE_SHIFT);
            for (int x = tile.getBlockX(); x < tile.getBlockX() + 128; x++) {
                for (int z = tile.getBlockZ(); z < tile.getBlockZ() + 128; z++) {
                    filteredCells++;
                    if (tile.lookup(x, z).terrain.isWetland()) filteredWetlands++;
                }
            }
            for (int x = 3248; x <= 3312; x += 8) {
                for (int z = 4228; z <= 4292; z += 8) {
                    if (terrain.sampleRaw(x, z).terrain.isRiver()) riverCells++;
                }
            }
        }
        System.out.println("CWI_WETLAND_CELLS=raw:" + rawCells + ",filtered:" + filteredCells
                + ",rivers:" + riverCells + ",raw_wetlands:" + rawWetlands
                + ",filtered_wetlands:" + filteredWetlands);
        helper.assertTrue(rawWetlands == 0 && filteredWetlands == 0,
                "Former seed-0 basin still contains raw/filtered wetlands: "
                        + rawWetlands + "/" + filteredWetlands);
        helper.assertTrue(riverCells > 0, "Removing wetlands also removed the native river fixture");
        helper.succeed();
    }

    private record GraphCounts(int reaches, int wetlands) {}

    private static GraphCounts graphCounts(Network network) {
        int reaches = 1, wetlands = network.wetlands().length;
        for (Network child : network.children()) {
            GraphCounts counts = graphCounts(child);
            reaches += counts.reaches();
            wetlands += counts.wetlands();
        }
        return new GraphCounts(reaches, wetlands);
    }

    private static Network.Builder branch(CFHRiverCarver carver) {
        Network.Builder root = Network.builder(carver);
        root.children.add(Network.builder(carver));
        return root;
    }

    private static BaseRiverGenerator<Continent> generator(GeneratorContext source, float chance) {
        var preset = source.preset.copy();
        preset.rivers().wetlands.chance = chance;
        GeneratorContext context = GeneratorContext.makeUncached(preset, source.noiseLookup,
                source.seed.root(), 3, 1, 6);
        return new BaseRiverGenerator<>(context.generator.getHeightmap().continent(), context) {};
    }

    private static GeneratorContext context(ReferenceTerrain terrain) throws Exception {
        Field field = ReferenceTerrain.class.getDeclaredField("context");
        field.setAccessible(true);
        return (GeneratorContext) field.get(terrain);
    }

    private static Network[] networks(Rivermap map) throws Exception {
        Field field = Rivermap.class.getDeclaredField("networks");
        field.setAccessible(true);
        return (Network[]) field.get(map);
    }
}
