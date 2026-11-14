package net.quiwh.cwiworldgen.terrain;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;

/** Production regional terrain with the real source engine, independently of watershed selection. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeRegionalLandformsTest {
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void nativeMountainCirquesAndCoastalCandidatesHaveOpenOutlets(GameTestHelper helper) {
        int mountains = 0, coasts = 0;
        try (var terrain = new ReferenceTerrain(0L, 1)) {
            var shapes = new RegionalLandforms(0L, terrain.levels(), terrain::sampleBaseInto);
            for (int[] point : new int[][]{{-208, -414}, {290, -470}, {-1308, -2704}, {1158, -3902}}) {
                var basin = shapes.basinsNear(point[0], point[1]).stream()
                        .filter(candidate -> candidate.centerX() == point[0] && candidate.centerZ() == point[1])
                        .findFirst().orElseThrow(() -> new AssertionError("Native regional descriptor was lost at " + point[0] + "," + point[1]));
                Cell original = new Cell();
                terrain.sampleBaseInto(original, point[0], point[1]);
                Cell result = new Cell(); result.copyFrom(original);
                shapes.apply(result, point[0], point[1]);
                helper.assertTrue(result.height < original.height - 5 * terrain.levels().unit,
                        "Regional bowl did not alter its real native center");
                helper.assertTrue(Math.abs(result.height * terrain.levels().worldHeight - basin.floorY()) < .001,
                        "Basin metadata disagrees with the terrain floor");
                helper.assertTrue(result.terrain == original.terrain && result.waterTable == original.waterTable
                                && result.riverMask == original.riverMask && result.riverWaterLevel == original.riverWaterLevel,
                        "Regional height shaping independently changed routing or biome metadata");
                Cell outlet = new Cell();
                terrain.sampleBaseInto(outlet, (int) basin.outletX(), (int) basin.outletZ());
                helper.assertTrue(outlet.height * terrain.levels().worldHeight <= basin.spillY(),
                        "Regional outlet does not descend to its proposed spill plane");
                if (basin.kind() == RegionalLandforms.Kind.MOUNTAIN) mountains++;
                else {
                    coasts++;
                    helper.assertTrue(outlet.terrain.isShallowOcean() && basin.spillY() == 62,
                            "Coastal lagoon does not reach native ocean terrain");
                }
            }
            var before = shapes.basinsNear(-208, -414);
            boolean immutable = false;
            try { before.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
            helper.assertTrue(immutable, "Regional descriptor list is mutable");
            shapes.close();
            helper.assertTrue(shapes.basinsNear(-208, -414).equals(before), "Cache eviction changed regional descriptors");
        }
        System.out.println("CWI_REGIONAL_LANDFORMS_NATIVE=mountains:" + mountains + ",coasts:" + coasts + ",metadata_and_outlets:true");
        helper.assertTrue(mountains == 2 && coasts == 2, "Native basin controls did not cover both families");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void flatAndDeepOceanBaseControlsRemainUnchanged(GameTestHelper helper) {
        var levels = new Levels(256, 63);
        for (boolean ocean : new boolean[]{false, true}) {
            RegionalLandforms.Sampler base = (cell, x, z) -> {
                cell.reset(); cell.height = ocean ? 30 / 256F : 90 / 256F;
                cell.continentEdge = ocean ? .1F : 1F;
                cell.terrain = ocean ? TerrainType.DEEP_OCEAN : TerrainType.FLATS;
            };
            var shapes = new RegionalLandforms(42, levels, base);
            for (int x : new int[]{-641, -640, -129, -128, -1, 0, 127, 128, 511, 512, 639, 640}) {
                Cell cell = new Cell(); base.sampleInto(cell, x, -x);
                float original = cell.height;
                shapes.apply(cell, x, -x);
                helper.assertTrue(cell.height == original, "Ineligible control acquired regional landforms");
            }
            shapes.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void naturalRegionalContoursAndBedsFollowNativeOpenOutlets(GameTestHelper helper) {
        int mountains = 0, coasts = 0;
        // A frozen profile-7 base isolates the new policy from river/landscape changes.
        try (var terrain = new ReferenceTerrain(0L, 7)) {
            var shapes = new RegionalLandforms(0L, terrain.levels(), terrain::sampleBaseInto, 8);
            for (int[] point : new int[][]{{-1118, -1221}, {-1859, -2214}, {-3045, -3713}, {-2321, -3662}}) {
                var basin = shapes.basinsNear(point[0], point[1]).stream()
                        .filter(candidate -> candidate.centerX() == point[0] && candidate.centerZ() == point[1])
                        .findFirst().orElseThrow(() -> new AssertionError("Natural native basin fixture disappeared"));
                Cell original = new Cell(); terrain.sampleBaseInto(original, point[0], point[1]);
                Cell carved = new Cell(); carved.copyFrom(original); shapes.apply(carved, point[0], point[1]);
                helper.assertTrue(Math.abs(carved.height * terrain.levels().worldHeight - basin.floorY()) < .001,
                        "Natural basin floor metadata disagrees with the real carved center");
                helper.assertTrue(shapes.basinRadius(basin, point[0], point[1]) == 0
                                && shapes.basinBed(basin, point[0], point[1]) == basin.floorY(),
                        "Natural lake bed and terrain do not share their center");
                helper.assertTrue(original.terrain == carved.terrain && original.waterTable == carved.waterTable
                                && original.riverMask == carved.riverMask && original.riverWaterLevel == carved.riverWaterLevel,
                        "Regional policy changed water ownership or biome metadata");
                Cell outlet = new Cell(); terrain.sampleBaseInto(outlet, (int) basin.outletX(), (int) basin.outletZ());
                shapes.apply(outlet, (int) basin.outletX(), (int) basin.outletZ());
                helper.assertTrue(outlet.height * terrain.levels().worldHeight <= basin.spillY(),
                        "Natural basin filled its downhill outlet");
                double asymmetry = 0;
                for (int i = 0; i < 16; i++) {
                    double angle = i * Math.PI / 8, dx = Math.cos(angle) * basin.radiusX(), dz = Math.sin(angle) * basin.radiusZ();
                    asymmetry += Math.abs(shapes.basinRadius(basin, basin.centerX() + dx, basin.centerZ() + dz)
                            - shapes.basinRadius(basin, basin.centerX() - dx, basin.centerZ() - dz));
                    double bed = shapes.basinBed(basin, basin.centerX() + dx * .4, basin.centerZ() + dz * .4);
                    helper.assertTrue(Double.isFinite(bed) && bed >= basin.floorY() - 2 && bed <= basin.spillY() - 1.8,
                            "Natural lake bed escaped its depth/support bounds");
                }
                helper.assertTrue(asymmetry / 16 > .06, "Natural native basin retained a symmetric ellipse");
                if (basin.kind() == RegionalLandforms.Kind.MOUNTAIN) mountains++; else coasts++;
            }
            shapes.close();
        }
        helper.assertTrue(mountains == 2 && coasts == 2, "Natural native fixtures missed one basin family");
        System.out.println("CWI_NATURAL_REGIONAL_NATIVE=mountains:" + mountains + ",coasts:" + coasts + ",shared_contour_and_bed:true");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void profileTenReliefReachesNativeColumnsWithoutChangingLegacyOrWaterMetadata(GameTestHelper helper) {
        var levels = new Levels(256, 63);
        for (boolean ocean : new boolean[]{false, true}) {
            RegionalLandforms.Sampler base = (cell, x, z) -> {
                cell.reset(); cell.height = (ocean ? 30 : 90) / 256F;
                cell.continentEdge = ocean ? .1F : 1F;
                cell.terrain = ocean ? TerrainType.DEEP_OCEAN : TerrainType.FLATS;
                cell.waterTable = .125F; cell.riverMask = .6F; cell.riverWaterLevel = .25F;
            };
            for (int version = 1; version <= 9; version++) {
                var legacy = new RegionalLandforms(42, levels, base, version);
                for (int x : new int[]{-129, -128, -17, -16, -1, 0, 15, 16, 127, 128}) {
                    Cell cell = new Cell(); base.sampleInto(cell, x, -x);
                    float original = cell.height; legacy.apply(cell, x, -x);
                    helper.assertTrue(cell.height == original, "New relief leaked into saved profile " + version);
                }
                legacy.close();
            }
            var shapes = new RegionalLandforms(42, levels, base, 10);
            var otherSeed = new RegionalLandforms(43, levels, base, 10);
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY, biggestStep = 0;
            int changedSeed = 0;
            for (int x = -192; x <= 192; x += 4) for (int z = -192; z <= 192; z += 4) {
                Cell cell = new Cell(); base.sampleInto(cell, x, z); shapes.apply(cell, x, z);
                Cell neighbor = new Cell(); base.sampleInto(neighbor, x + 1, z); shapes.apply(neighbor, x + 1, z);
                Cell different = new Cell(); base.sampleInto(different, x, z); otherSeed.apply(different, x, z);
                double y = cell.height * 256.; low = Math.min(low, y); high = Math.max(high, y);
                biggestStep = Math.max(biggestStep, Math.abs(cell.height - neighbor.height) * 256.);
                if (cell.height != different.height) changedSeed++;
                helper.assertTrue(cell.terrain == (ocean ? TerrainType.DEEP_OCEAN : TerrainType.FLATS)
                                && cell.waterTable == .125F && cell.riverMask == .6F && cell.riverWaterLevel == .25F,
                        "Local relief independently changed biome or water ownership");
            }
            helper.assertTrue(high - low > (ocean ? 3 : 4) && biggestStep < 1.5 && changedSeed > 100,
                    "Integrated land/ocean relief is uniform, abrupt or repeats between seeds");
            // Real owner-column policy must retain the build envelope after all basin processing.
            for (int boundary : new int[]{-52, 373}) for (int x = -128; x <= 128; x += 8) {
                Cell cell = new Cell(); base.sampleInto(cell, x, -x); cell.height = boundary / 256F;
                shapes.apply(cell, x, -x);
                helper.assertTrue(cell.height * 256. >= -52 && cell.height * 256. <= 373,
                        "Integrated profile-10 relief escaped the terrain build envelope");
            }
            shapes.close(); otherSeed.close();
            System.out.println("CWI_WATER_RELIEF_NATIVE=ocean:" + ocean + ",range:" + (high-low)
                    + ",adjacent:" + biggestStep + ",legacy_profiles:9,water_metadata:unchanged");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void profileTenNativeBasinsHaveDeepUnequalBedsSmoothRimsAndOpenOutlets(GameTestHelper helper) {
        int mountains = 0, coasts = 0;
        int exteriorRimColumns = 0;
        double largestRimStep = 0;
        // Freeze the existing source terrain so this exercises the new basin policy itself.
        try (var terrain = new ReferenceTerrain(0L, 7)) {
            var shapes = new RegionalLandforms(0L, terrain.levels(), terrain::sampleBaseInto, 10);
            var candidates = new java.util.LinkedHashMap<Long, RegionalLandforms.Basin>();
            for (int[] point : new int[][]{{-1118,-1221},{-1859,-2214},{-3045,-3713},{-2321,-3662}})
                for (var basin : shapes.basinsNear(point[0],point[1])) candidates.putIfAbsent(basin.id(),basin);
            for (var kind : RegionalLandforms.Kind.values()) {
                int accepted = 0;
                int familyExteriorRimColumns = 0;
                for (var basin : candidates.values()) {
                    if (basin.kind() != kind || accepted >= 2) continue;
                    accepted++;
                    int cx = (int) basin.centerX(), cz = (int) basin.centerZ();
                    Cell original = new Cell(); terrain.sampleBaseInto(original,cx,cz);
                    Cell center = new Cell(); center.copyFrom(original); shapes.apply(center,cx,cz);
                    helper.assertTrue(basin.spillY()-basin.floorY() >= 9
                                    && Math.abs(center.height*256.-basin.floorY()) < .001,
                            "Profile-10 native pond did not obtain its deep configured center");
                    helper.assertTrue(center.terrain==original.terrain && center.waterTable==original.waterTable
                                    && center.riverMask==original.riverMask && center.riverWaterLevel==original.riverWaterLevel,
                            "Deep pond terrain changed water ownership or biome metadata");
                    double shallow = Double.NEGATIVE_INFINITY, deep = Double.POSITIVE_INFINITY, asymmetry = 0;
                    for (int i=0;i<24;i++) {
                        double angle=i*Math.PI/12, dx=Math.cos(angle)*basin.radiusX(), dz=Math.sin(angle)*basin.radiusZ();
                        asymmetry += Math.abs(shapes.basinRadius(basin,cx+dx,cz+dz)
                                -shapes.basinRadius(basin,cx-dx,cz-dz));
                        for (double fraction : new double[]{0,.3,.55,.8,.95}) {
                            double bed=shapes.basinBed(basin,cx+dx*fraction,cz+dz*fraction);
                            helper.assertTrue(Double.isFinite(bed) && bed>=basin.floorY()-3.001
                                            && bed<=basin.spillY()-1.499,
                                    "Native pond bed left its supported underwater envelope");
                            shallow=Math.max(shallow,bed); deep=Math.min(deep,bed);
                        }
                        // A flat high input isolates the new rim blend from unrelated native cliff slopes.
                        Cell previous=null;
                        for (int distance=0;distance<=basin.radiusX()*1.65;distance++) {
                            int x=cx+(int)Math.round(Math.cos(angle)*distance), z=cz+(int)Math.round(Math.sin(angle)*distance);
                            double r=shapes.basinRadius(basin,x,z);
                            if (r<.75 || r>1.4) { previous=null; continue; }
                            Cell sample=new Cell(); sample.copyFrom(original); sample.height=(float)((basin.spillY()+16)/256.);
                            shapes.apply(sample,x,z);
                            if (r < 1) helper.assertTrue(sample.height * 256. <= shapes.basinBed(basin,x,z) + .001,
                                    "Profile-10 wet-mask edge left a raised terrain ring above the shared lake bed");
                            if (r >= 1 && unsharedDryRim(shapes,basin,x,z)) {
                                helper.assertTrue(sample.height * 256. >= basin.spillY() - .001,
                                        "Profile-10 dry rim carved an unplanned moat below spill at " + x + "," + z
                                                + " r=" + r + " height=" + sample.height * 256. + " spill=" + basin.spillY());
                                familyExteriorRimColumns++;
                            }
                            if(previous!=null) largestRimStep=Math.max(largestRimStep,Math.abs(sample.height-previous.height)*256.);
                            previous=sample;
                        }
                    }
                    helper.assertTrue(shallow-deep>6 && asymmetry/24>.03,
                            "Deep ponds retained flat beds or a repeated symmetric shore ellipse");
                    Cell outlet=new Cell(); terrain.sampleBaseInto(outlet,(int)basin.outletX(),(int)basin.outletZ());
                    shapes.apply(outlet,(int)basin.outletX(),(int)basin.outletZ());
                    helper.assertTrue(outlet.height*256.<=basin.spillY()+.001,
                            "Profile-10 relief raised the accepted downhill outlet above its spill plane");
                    if(kind==RegionalLandforms.Kind.MOUNTAIN)mountains++;else coasts++;
                }
                helper.assertTrue(accepted>0,"Native profile-10 descriptors omitted basin family "+kind);
                helper.assertTrue(familyExteriorRimColumns>100,"Dry-rim regression omitted basin family "+kind);
                exteriorRimColumns+=familyExteriorRimColumns;
            }
            shapes.close();
        }
        helper.assertTrue(largestRimStep<2,"Profile-10 basin rim retained an abrupt repeated contour step: "+largestRimStep);
        System.out.println("CWI_WATER_BASINS_NATIVE=mountains:"+mountains+",coasts:"+coasts
                +",deep_beds:true,rim_adjacent:"+largestRimStep+",dry_rim_columns:"+exteriorRimColumns+",open_outlets:true");
        helper.succeed();
    }

    /** Other basins and open outlet corridors may intentionally descend below this spill plane. */
    private static boolean unsharedDryRim(RegionalLandforms shapes, RegionalLandforms.Basin selected, int x, int z) {
        for (var basin : shapes.basinsNear(x,z)) {
            if (basin.id()!=selected.id() && shapes.basinRadius(basin,x,z)<2.3) return false;
            double vx=basin.outletX()-basin.centerX(), vz=basin.outletZ()-basin.centerZ();
            double dx=x-basin.centerX(), dz=z-basin.centerZ(), length2=vx*vx+vz*vz;
            double progress=(dx*vx+dz*vz)/length2;
            double lateral=Math.abs(dx*vz-dz*vx)/Math.sqrt(length2);
            double width=basin.kind()==RegionalLandforms.Kind.MOUNTAIN ? basin.radiusZ()*.304 : 25.3;
            if (progress>=-.08 && progress<=1.12 && lateral<=width) return false;
        }
        return true;
    }

}
