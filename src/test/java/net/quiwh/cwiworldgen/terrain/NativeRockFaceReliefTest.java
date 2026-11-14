package net.quiwh.cwiworldgen.terrain;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.Size;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.filter.Filterable;

/** A sloping rock face exercises the production filter without generating Minecraft chunks. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeRockFaceReliefTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void rockFacesCutOnlySteepDryGroundAndRespectProtectedWaterShells(GameTestHelper helper) {
        var old = new Face(0, 0);
        new RockFaceReliefFilter(42, 12, 256, 63).apply(old);
        for (int z = 0; z < old.size.total(); z++) for (int x = 0; x < old.size.total(); x++)
            check(old.at(x, z).height == ground(old.worldX(x), old.worldZ(z)), "Historical profile gained rock relief");

        var face = new Face(0, 0);
        // These are separate production water contracts, including the enclosing dry pond wall.
        face.at(44, 44).cwiPlannedWater = true;
        face.at(76, 44).cwiDryChannel = true;
        face.at(44, 76).cwiPond = true;
        face.at(76, 76).cwiPondProtected = true;
        new RockFaceReliefFilter(42, 13, 256, 63).apply(face);
        int changed = 0, bends = 0;
        double largestCut = 0;
        for (int z = 16; z < face.size.total() - 16; z++) for (int x = 16; x < face.size.total() - 16; x++) {
            double cut = (ground(face.worldX(x), face.worldZ(z)) - face.at(x, z).height) * 256;
            check(cut >= -0.0001 && cut <= 5.5001, "Rock face added peaks or exceeded local amplitude budget");
            largestCut = Math.max(largestCut, cut);
            if (cut > .1) changed++;
            if (Math.abs(face.at(x + 1, z).height - 2 * face.at(x, z).height + face.at(x - 1, z).height) * 256 > .3)
                bends++;
            for (int[] water : new int[][]{{44, 44}, {76, 44}, {44, 76}, {76, 76}})
                if (Math.max(Math.abs(x - water[0]), Math.abs(z - water[1])) <= 8)
                    check(cut == 0, "Rock relief cut a planned river, dry channel or protected pond shell");
        }
        check(changed > 100 && bends > 30 && largestCut > 1, "Steep rock face stayed rounded or gained no coherent relief");

        var clear = new Face(0, 0);
        var waterEdge = new Face(0, 0);
        waterEdge.at(64, 64).cwiPondProtected = true;
        new RockFaceReliefFilter(42, 13, 256, 63).apply(clear);
        new RockFaceReliefFilter(42, 13, 256, 63).apply(waterEdge);
        int feathered = 0;
        for (int z = 52; z <= 76; z++) for (int x = 52; x <= 76; x++) {
            int distance = Math.max(Math.abs(x - 64), Math.abs(z - 64));
            if (distance <= 8 || distance >= 12) continue;
            double original = ground(clear.worldX(x), clear.worldZ(z));
            double fullCut = (original - clear.at(x, z).height) * 256;
            double edgeCut = (original - waterEdge.at(x, z).height) * 256;
            double fraction = (distance - 8) / 4.;
            double expected = fullCut * fraction * fraction * (3 - 2 * fraction);
            check(Math.abs(edgeCut - expected) < .0001,
                    "Rock relief made a hard shelf at the protected water envelope");
            if (fullCut > .1) feathered++;
        }
        check(feathered > 5, "Water-edge fixture did not exercise gradual rock relief");

        var gentle = new Face(0, 0);
        var soft = new Face(0, 0);
        for (Cell cell : gentle.cells) cell.height = 150F / 256;
        for (Cell cell : soft.cells) cell.erosion = 1;
        new RockFaceReliefFilter(42, 13, 256, 63).apply(gentle);
        new RockFaceReliefFilter(42, 13, 256, 63).apply(soft);
        for (int i = 0; i < soft.cells.length; i++) {
            check(gentle.cells[i].height == 150F / 256, "Gentle ground gained angular rock cuts");
            int x = i % soft.size.total(), z = i / soft.size.total();
            check(soft.cells[i].height == ground(soft.worldX(x), soft.worldZ(z)), "Soft soil gained angular rock cuts");
        }
        System.out.println("CWI_ROCK_FACE_FILTER=changed:" + changed + ",sharp_bends:" + bends + ",max_cut:" + largestCut);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void overlappingRockFilterWindowsAgreeAtPositiveAndNegativeTileBorders(GameTestHelper helper) {
        int comparisons = 0;
        var cached = new RockFaceRelief.Field(42, -192, -192, 192, 192);
        for (int z = -192; z <= 192; z += 7) for (int x = -192; x <= 192; x += 7)
            check(cached.cut(x, z, 1, 1) == RockFaceRelief.cut(42, x, z, 1, 1),
                    "Cached rock descriptors omitted or translated a neighboring fracture");
        for (int origin : new int[]{-128, 0}) {
            var a = new Face(origin, -64);
            var b = new Face(origin + 64, -64);
            // Apply the right window first; neither call may read already-mutated neighbors.
            var filter = new RockFaceReliefFilter(42, 13, 256, 63);
            filter.apply(b);
            filter.apply(a);
            for (int wz = -64; wz < 0; wz++) for (int wx = origin + 48; wx < origin + 80; wx++) {
                Cell left = a.at(wx - a.blockX + a.size.border(), wz - a.blockZ + a.size.border());
                Cell right = b.at(wx - b.blockX + b.size.border(), wz - b.blockZ + b.size.border());
                check(Float.floatToRawIntBits(left.height) == Float.floatToRawIntBits(right.height),
                        "Overlapping rock relief changed across independently generated windows at " + wx + "," + wz);
                comparisons++;
            }
        }
        check(comparisons == 4096, "Rock seam fixture missed a tile border");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void actualMountainFacesGainAngularReliefWithoutMovingHydrologyAndIgnoreTileOrder(GameTestHelper helper) {
        // Fixed positives from the existing four-seed mountain survey; no runtime discovery scan.
        final int minX = 1536, minZ = 2176;
        var edgeHeights = new java.util.LinkedHashMap<Long, Integer>();
        int changed = 0, newBends = 0, protectedColumns = 0;
        double maximumCut = 0, beforeCurvature = 0, afterCurvature = 0;
        try (var control = new ReferenceTerrain(42, 12); var candidate = new ReferenceTerrain(42, 13)) {
            var baseA = new Cell(); var baseB = new Cell();
            for (int z = minZ; z <= minZ + 128; z += 32) for (int x = minX; x <= minX + 256; x += 32) {
                control.sampleBaseInto(baseA, x, z); candidate.sampleBaseInto(baseB, x, z);
                check(baseA.height == baseB.height && baseA.terrain == baseB.terrain,
                        "Rock relief moved the mountain footprint or hydrology input");
            }
            for (int tileX = 12; tileX <= 13; tileX++) {
                var before = control.getTile(tileX, 17);
                var after = candidate.getTile(tileX, 17);
                for (int z = minZ; z < minZ + 128; z++) for (int x = tileX * 128; x < (tileX + 1) * 128; x++) {
                    Cell a = before.lookup(x, z), b = after.lookup(x, z);
                    double cut = (a.height - b.height) * 256;
                    check(cut >= -.0001 && cut <= 5.5001, "Actual rock face added a peak or exceeded the local cut budget");
                    check(a.cwiPlannedWater == b.cwiPlannedWater && a.cwiDryChannel == b.cwiDryChannel
                                    && a.cwiPond == b.cwiPond && a.cwiPondProtected == b.cwiPondProtected
                                    && a.cwiWaterHeight == b.cwiWaterHeight && a.riverMask == b.riverMask,
                            "Rock relief changed actual hydrology masks or water height");
                    if (a.cwiPlannedWater || a.cwiDryChannel || a.cwiPondProtected) {
                        check(cut == 0, "Actual river or pond protection was cut"); protectedColumns++;
                    }
                    if (cut > .1) {
                        changed++; maximumCut = Math.max(maximumCut, cut);
                        int ax = x - before.getBlockX() + before.getBlockSize().border();
                        int az = z - before.getBlockZ() + before.getBlockSize().border();
                        int bx = x - after.getBlockX() + after.getBlockSize().border();
                        int bz = z - after.getBlockZ() + after.getBlockSize().border();
                        double ca = Math.abs(before.getCellRaw(ax - 1, az).height - 2 * a.height
                                + before.getCellRaw(ax + 1, az).height) * 256;
                        double cb = Math.abs(after.getCellRaw(bx - 1, bz).height - 2 * b.height
                                + after.getCellRaw(bx + 1, bz).height) * 256;
                        beforeCurvature += ca; afterCurvature += cb;
                        if (cb > ca + .25) newBends++;
                    }
                }
            }
            check(changed > 100 && newBends > 20 && maximumCut > 1,
                    "Actual mountain fixtures gained no substantial angular rock faces");
            // Prepared readers on both sides of a real 128-block tile border must survive reverse cache order.
            for (int x = minX + 126; x <= minX + 129; x++) for (int z = minZ + 16; z < minZ + 112; z += 3) {
                var reader = candidate.prepareChunk(x >> 4, z >> 4);
                edgeHeights.put(((long) x << 32) ^ (z & 0xffffffffL), Float.floatToRawIntBits(reader.getCell(x, z).height));
            }
        }
        try (var reversed = new ReferenceTerrain(42, 13)) {
            var entries = new java.util.ArrayList<>(edgeHeights.entrySet());
            java.util.Collections.reverse(entries);
            for (var entry : entries) {
                int x = (int) (entry.getKey() >> 32), z = (int) (long) entry.getKey();
                var reader = reversed.prepareChunk(x >> 4, z >> 4);
                check(Float.floatToRawIntBits(reader.getCell(x, z).height) == entry.getValue(),
                        "Actual rock face changed when adjacent tiles were generated in reverse order");
            }
            check(reversed.pondColumn(-2607, -3029).wet(), "Known protected pond disappeared from the rock profile");
            Cell pond = reversed.sample(-2607, -3029);
            check(pond.cwiPond && pond.cwiPondProtected, "Completed rock profile discarded native pond protection");
        }
        System.out.println("CWI_ACTUAL_ROCK_FACE=changed:" + changed + ",new_sharp_bends:" + newBends
                + ",max_cut:" + maximumCut + ",mean_old_curvature:" + beforeCurvature / changed
                + ",mean_new_curvature:" + afterCurvature / changed + ",protected_columns:" + protectedColumns
                + ",prepared_border_comparisons:" + edgeHeights.size());
        helper.succeed();
    }

    private static float ground(int x, int z) { return (float) ((150 + .62 * x + .09 * z) / 256); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Face implements Filterable {
        private final int blockX, blockZ;
        private final Size size = Size.make(64, 32);
        private final Cell[] cells = new Cell[size.arraySize()];
        private Face(int blockX, int blockZ) {
            this.blockX = blockX; this.blockZ = blockZ;
            for (int z = 0; z < size.total(); z++) for (int x = 0; x < size.total(); x++) {
                var cell = new Cell();
                cell.height = ground(worldX(x), worldZ(z));
                cell.terrain = TerrainType.MOUNTAINS_1;
                cell.erosion = -.6F;
                cell.riverMask = 1;
                cells[size.indexOf(x, z)] = cell;
            }
        }
        private int worldX(int x) { return blockX + x - size.border(); }
        private int worldZ(int z) { return blockZ + z - size.border(); }
        private Cell at(int x, int z) { return cells[size.indexOf(x, z)]; }
        @Override public int getBlockX() { return blockX; }
        @Override public int getBlockZ() { return blockZ; }
        @Override public Size getBlockSize() { return size; }
        @Override public Cell[] getBacking() { return cells; }
        @Override public Cell getCellRaw(int x, int z) { return at(x, z); }
    }
}
