package net.quiwh.cwiworldgen;

import java.util.EnumSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscape;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscape.Formation;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscapeProfiles;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscapeProfiles.Kind;
import net.quiwh.cwiworldgen.terrain.ReferenceTerrainDensity;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

/** Covers saved-profile dispatch and the live production landscape's selected field. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeOrganicFormationTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void asymmetricArchKeepsItsOriginalRoofReservation(GameTestHelper helper) {
        // Valid production-size descriptor: center is host top(180)-ry(18)-five reserved blocks.
        Formation arch = new Formation(Kind.ARCH, 0, 157, 0, 29.394916711780205, 18,
                12.315126232431961, 0, 20242L, null);
        var policy = ReferenceLandscapeProfiles.select(5);
        helper.assertTrue(arch.density(0, 179, 1, policy) >= 0,
                "Asymmetric aperture consumes the host roof at top-1");
        for (int x = -36; x <= 36; x++) for (int z = -36; z <= 36; z++)
            for (int y = 175; y <= 184; y++)
                helper.assertTrue(arch.density(x, y, z, policy) >= 0,
                        "Organic ARCH cuts above its nominal crown and loses the reserved roof");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void previousProfilesRetainEveryLegacyFormationDensityBit(GameTestHelper helper) {
        int compared = 0;
        long fingerprint = 0xcbf29ce484222325L;
        for (Kind kind : Kind.values()) for (long seed : new long[] {0, 42, -7, 19 + (1L << 40)}) {
            Formation shape = new Formation(kind, -19.25, 120.5, 63.75, 28, 16, 13, .37, seed, null);
            for (int x = -56; x <= 18; x += 3) for (int y = 96; y <= 144; y += 3)
                for (int z = 26; z <= 101; z += 3) {
                    long expected = Double.doubleToLongBits(shape.density(x, y, z));
                    fingerprint = (fingerprint ^ expected) * 0x100000001b3L;
                    for (int version = 1; version <= 4; version++) {
                        helper.assertTrue(expected == Double.doubleToLongBits(shape.density(x, y, z,
                                ReferenceLandscapeProfiles.select(version))),
                                "A saved legacy profile changed its original formation arithmetic");
                        compared++;
                    }
                }
        }
        // Captured from the verified 0.2.9 JAR before this change, over 176,800 density samples.
        helper.assertTrue(fingerprint == 0x7581b536af8543d6L,
                "The original three-argument formation field changed from the previous released candidate");
        System.out.println("CWI_ORGANIC_FORMATION_LEGACY=compared_density_bits=" + compared
                + " fingerprint=" + Long.toHexString(fingerprint));
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void productionProfileFiveUsesVariedFieldForExistingSurfaceKinds(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "refined_wasteland_test")));
        helper.assertTrue(level != null, "Missing profile 5 production verification dimension");
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        RandomState state = RandomState.create(generator.generatorSettings().value(),
                level.registryAccess().lookupOrThrow(Registries.NOISE), 0);
        try (var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain()) {
            helper.assertTrue(terrain.generationVersion() == 5 && terrain.landscape() != null,
                    "Production router did not bind the saved profile 5 landscape");
            EnumSet<Kind> checked = EnumSet.noneOf(Kind.class);
            // Search actual profile-5 descriptors: changed hydrology can reject an older dry-site fixture.
            int size = ReferenceLandscapeProfiles.SURFACE_CELL;
            for (int ring = 0; ring <= 12 && checked.size() < 3; ring++) {
                for (int cx = -ring; cx <= ring && checked.size() < 3; cx++)
                    for (int cz = -ring; cz <= ring && checked.size() < 3; cz++) {
                        if (Math.max(Math.abs(cx), Math.abs(cz)) != ring) continue;
                        Formation shape = terrain.landscape().surfaceFormationAt(cx * size, cz * size);
                        if (shape == null || checked.contains(shape.kind())) continue;
                        boolean accepted = false;
                        int bound = (int) Math.ceil(shape.bound());
                        search: for (int offsetY : new int[] {0, -3, 3, -6, 6})
                            for (int dx = -bound; dx <= bound; dx += 2)
                                for (int dz = -bound; dz <= bound; dz += 2) {
                                    int x = (int) Math.round(shape.x()) + dx;
                                    int y = (int) Math.round(shape.y()) + offsetY;
                                    int z = (int) Math.round(shape.z()) + dz;
                                    double planned = shape.density(x, y, z, ReferenceLandscapeProfiles.select(5));
                                    if (planned >= 0 || planned == shape.density(x, y, z)) continue;
                                    if (terrain.landscape().density(x, y, z, false) != planned
                                            || terrain.landscape().density(x, y, z, true) != planned) continue;
                                    accepted = true;
                                    System.out.println("CWI_ORGANIC_FORMATION_LIVE=kind=" + shape.kind()
                                            + " x=" + x + " y=" + y + " z=" + z + " density=" + planned);
                                    break search;
                                }
                        if (accepted) checked.add(shape.kind());
                    }
            }
            helper.assertTrue(checked.containsAll(EnumSet.of(Kind.ARCH, Kind.OVERHANG, Kind.SPIRE)),
                    "Production profile did not demonstrate every existing surface kind's new field: " + checked);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void profileFiveCavernsRespectFinishedRoofDistrictAndWetRejection(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "refined_wasteland_test")));
        helper.assertTrue(level != null, "Missing profile 5 production verification dimension");
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var caves = (BoundedCaveBiomeSource) generator.getBiomeSource();
        var policy = ReferenceLandscapeProfiles.select(5);
        int active = 0, rejectedWet = 0, candidates = 0;
        for (long seed : new long[] {0, 42}) {
            RandomState state = RandomState.create(generator.generatorSettings().value(),
                    level.registryAccess().lookupOrThrow(Registries.NOISE), seed);
            try (var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain()) {
                helper.assertTrue(terrain.generationVersion() == 5, "Cavern test bound the wrong saved profile");
                for (int ring = 0; ring <= 12 && (active < 2 || rejectedWet < 1); ring++) {
                    for (int cx = -ring; cx <= ring && (active < 2 || rejectedWet < 1); cx++)
                        for (int cz = -ring; cz <= ring && (active < 2 || rejectedWet < 1); cz++) {
                            if (Math.max(Math.abs(cx), Math.abs(cz)) != ring) continue;
                            Formation shape = terrain.landscape().cavernFormationAt(cx * caves.cellSize(), cz * caves.cellSize());
                            if (shape == null) continue;
                            candidates++;
                            helper.assertTrue(shape.kind() == Kind.CAVERN && shape.region() != null
                                            && shape.y() - shape.ry() >= shape.region().minY() + 9
                                            && shape.y() + shape.ry() <= shape.region().maxY() - 9,
                                    "Profile 5 enlarged the cavern descriptor's actual-Y envelope");
                            int x = (int) Math.round(shape.x() + Math.cos(shape.angle()) * shape.rx() * .24);
                            int y = (int) Math.round(shape.y());
                            int z = (int) Math.round(shape.z() + Math.sin(shape.angle()) * shape.rx() * .24);
                            double planned = shape.density(x, y, z, policy);
                            if (planned >= 0) continue;
                            if (!ReferenceLandscape.dry(terrain.sampleRaw(x, z))) {
                                helper.assertTrue(terrain.landscape().density(x, y, z, false) >= 0,
                                        "Profile 5 cavern carved a real wet/ocean column");
                                rejectedWet++;
                                continue;
                            }
                            if (active >= 2 || terrain.landscape().density(x, y, z, false) != planned
                                    || terrain.landscape().density(x, y, z, true) != planned) continue;
                            var actual = terrain.sample(x, z);
                            helper.assertTrue(y + ReferenceLandscapeProfiles.MIN_CAVERN_ROOF < actual.height * 256,
                                    "Live organic cavern breached the 24-block finished-terrain roof");
                            ReferenceTerrainDensity.withAccurateTerrain(() -> {
                                for (int dx : new int[] {-8, 8}) for (int dy : new int[] {-4, 4})
                                    for (int dz : new int[] {-8, 8})
                                        helper.assertTrue(shape.region().equals(caves.caveRegionAtBlock(
                                                        x + dx, y + dy, z + dz, state.sampler())),
                                                "Live organic cavern crossed its buffered actual district");
                                return null;
                            });
                            for (int boundary : new int[] {shape.region().minY(), shape.region().maxY(), caves.caveCeiling() + 1})
                                helper.assertTrue(terrain.landscape().density(x, boundary, z, true) >= 0,
                                        "Organic cavern cut outside the unchanged cave-biome height limits");
                            active++;
                            System.out.println("CWI_ORGANIC_CAVERN_LIVE=seed=" + seed + " biome="
                                    + shape.region().biome().getRegisteredName() + " x=" + x + " y=" + y + " z=" + z
                                    + " roof=" + (actual.height * 256 - y));
                        }
                }
            }
            if (active >= 2 && rejectedWet >= 1) break;
        }
        helper.assertTrue(active >= 2 && rejectedWet >= 1,
                "Missing live profile 5 cavern positives or wet rejection: active=" + active
                        + " wet_rejected=" + rejectedWet + " candidates=" + candidates);
        System.out.println("CWI_ORGANIC_CAVERN_COVERAGE=active=" + active + " wet_rejected="
                + rejectedWet + " candidates=" + candidates);
        helper.succeed();
    }

    private NativeOrganicFormationTest() {}
}
