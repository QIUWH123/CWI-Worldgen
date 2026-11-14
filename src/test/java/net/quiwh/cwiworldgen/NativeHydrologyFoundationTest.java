package net.quiwh.cwiworldgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.hydrology.DirectedReach;
import net.quiwh.cwiworldgen.hydrology.HydrologyQuery;
import net.quiwh.cwiworldgen.hydrology.HydrologySnapshot;
import net.quiwh.cwiworldgen.hydrology.ReachPoint;
import net.quiwh.cwiworldgen.hydrology.ReferenceHydrology;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.ContinentalHydrology;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.Rivermap;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.gen.GenWarp;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.CFHRiverCarver;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.Network;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.River;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.RiverConfig;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.RiverWarp;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.wetland.Wetland;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;
import net.quiwh.cwiworldgen.terrain.reference.noise.domain.Domain;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Line;
import net.quiwh.cwiworldgen.terrain.reference.util.Boundsf;

/** Regression checks for observational, immutable native river metadata. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeHydrologyFoundationTest {
    private static final int[][] EDGES = {{-129, -128}, {-128, -127}, {-17, -16}, {-1, 0},
            {0, -1}, {15, 16}, {127, 128}, {128, 127}, {129, -129}};

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void exportedReachPointsRemainDetached(GameTestHelper helper) {
        var points = new ArrayList<ReachPoint>();
        points.add(new ReachPoint(-16, 0));
        points.add(new ReachPoint(16, 0));
        var reach = new DirectedReach(1, 2, true, .5, points);
        points.clear();
        helper.assertTrue(reach.points().equals(List.of(new ReachPoint(-16, 0), new ReachPoint(16, 0))),
                "Export retained a caller-owned mutable point list");
        boolean rejected = false;
        try {
            reach.points().clear();
        } catch (UnsupportedOperationException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "Exported reach point list remains mutable");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void composedQueryCoordinatesMatchNativeCarvingAndForkAttachments(GameTestHelper helper) {
        var root = new RecordingCarver(new River(0, 0, 1000, 0),
                new RiverWarp(13, .1F, .85F, .002F, 125));
        var left = new RecordingCarver(new River(125, -250, 500, 0),
                new RiverWarp(17, .15F, .75F, .003F, 80));
        var right = new RecordingCarver(new River(500, 300, 750, 0),
                new RiverWarp(23, .15F, .75F, .004F, 70));
        var branch = new Network(root, new Wetland[0], new Network[]{
                new Network(left, new Wetland[0], new Network[0], Boundsf.NONE),
                new Network(right, new Wetland[0], new Network[0], Boundsf.NONE)
        }, new Boundsf(-2000, -2000, 2000, 2000));
        var map = new Rivermap(0, 0, new Network[]{branch}, GenWarp.make(41, 4000));
        var topology = map.topology();
        helper.assertTrue(topology.size() == 3, "Native two-tributary graph lost a reach");
        helper.assertTrue(!topology.get(0).hasParent(), "Root acquired an artificial downstream reach");
        for (int index = 1; index < topology.size(); index++) {
            var child = topology.get(index);
            helper.assertTrue(child.hasParent() && child.parentIndex() == topology.get(0).index(),
                    "Child-to-parent routing link changed");
            double expected = index == 1 ? .5 : .75;
            near(helper, child.downstreamJoinProgress(), expected, 1E-6, "Parent attachment progress");
            near(helper, child.x2(), topology.get(0).x1()
                    + (topology.get(0).x2() - topology.get(0).x1()) * expected, 1E-6,
                    "Child logical endpoint did not attach inside parent");
            near(helper, child.z2(), topology.get(0).z1(), 1E-6,
                    "Child logical endpoint left its parent");
        }

        boolean globalWarpSeen = false, ancestorWarpSeen = false;
        RecordingCarver[] carvers = {root, left, right};
        for (int[] point : new int[][]{{-129, -128}, {-1, 0}, {127, 128}, {350, 60}, {600, -40}, {999, 1}}) {
            map.apply(new Cell(), point[0], point[1]);
            var queries = map.queryGeometry(point[0], point[1]);
            helper.assertTrue(queries.size() == carvers.length, "Forward-query traversal differs from native carving");
            for (int index = 0; index < carvers.length; index++) {
                var carver = carvers[index];
                var query = queries.get(index);
                helper.assertTrue(query.reach().index() == index, "Forward-query preorder changed");
                double error = Math.hypot(query.center().x() - carver.x, query.center().z() - carver.z);
                helper.assertTrue(error <= 1E-4, "Composed query differs from actual native carver by " + error);
                near(helper, query.center().progress(), carver.progress, 1E-6,
                        "Forward-query progress differs from actual native carving");
                var river = carver.river;
                double expectedSigned = river.ndx * (carver.z - river.z1)
                        - river.ndz * (carver.x - river.x1);
                near(helper, query.center().signedDistance(), expectedSigned, .001,
                        "Signed lateral distance differs from actual native carving");
                near(helper, query.center().progress(), Line.distanceOnLine(carver.x, carver.z,
                        river.x1, river.z1, river.x2, river.z2), 1E-6,
                        "Direction progress no longer follows upstream-to-downstream native endpoint order");
            }
            globalWarpSeen |= root.previousX != point[0] || root.previousZ != point[1];
            ancestorWarpSeen |= left.previousX != root.previousX || left.previousZ != root.previousZ;
        }
        helper.assertTrue(globalWarpSeen && ancestorWarpSeen,
                "Warp regression did not exercise both global and parent-reach warps");
        root.river.x1 = -999;
        helper.assertTrue(topology.get(0).x1() == 0, "Numeric topology snapshot retained mutable native endpoints");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void flatTwoTributaryGraphKeepsDownstreamOrientationAndRejectsAmbiguousJunctions(
            GameTestHelper helper) {
        var root = new RecordingCarver(new River(0, 0, 1000, 0), RiverWarp.NONE);
        var left = new RecordingCarver(new River(125, -250, 500, 0), RiverWarp.NONE);
        var right = new RecordingCarver(new River(500, 300, 750, 0), RiverWarp.NONE);
        var branch = new Network(root, new Wetland[0], new Network[]{
                new Network(left, new Wetland[0], new Network[0], Boundsf.NONE),
                new Network(right, new Wetland[0], new Network[0], Boundsf.NONE)
        }, new Boundsf(-2000, -2000, 2000, 2000));
        var map = new Rivermap(0, 0, new Network[]{branch}, GenWarp.EMPTY);
        var flat = new Cell();
        flat.terrain = TerrainType.RIVER;
        flat.waterTable = .5F;
        flat.height = .2F;
        var main = ReferenceHydrology.query(map, flat, 900, 0, 0);
        var first = ReferenceHydrology.query(map, flat, 312.5F, -125, 0);
        var second = ReferenceHydrology.query(map, flat, 625, 150, 0);
        helper.assertTrue(main.validDirection() && first.validDirection() && second.validDirection(),
                "Flat directed graph lost its river directions");
        helper.assertTrue(main.tangentX() > .99 && Math.abs(main.tangentZ()) < 1E-6
                        && first.tangentX() > 0 && first.tangentZ() > 0
                        && second.tangentX() > 0 && second.tangentZ() < 0,
                "Downstream orientation depended on a flat water-height gradient");
        near(helper, first.progress(), .5, 1E-6, "First tributary progress");
        near(helper, second.progress(), .5, 1E-6, "Second tributary progress");
        helper.assertTrue(main.reachId() != first.reachId() && first.reachId() != second.reachId(),
                "Distinct directed branches shared an identity");
        var junction = ReferenceHydrology.query(map, flat, 500, 0, 0);
        helper.assertTrue(!junction.validDirection() && junction.reason().equals("ambiguous_junction"),
                "Equal-distance tributary junction invented a unique current direction");
        finite(helper, junction);
        for (float multiplier : new float[]{-1F, -2F}) {
            Domain fold = new Domain() {
                @Override public float getOffsetX(float x, float z, int seed) { return x * multiplier; }
                @Override public float getOffsetZ(float x, float z, int seed) { return 0; }
                @Override public Domain mapAll(net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise.Visitor visitor) {
                    return this;
                }
                @Override public com.mojang.serialization.MapCodec<? extends Domain> codec() {
                    return GenWarp.EMPTY.river().codec();
                }
            };
            var folded = new Rivermap(0, 0, new Network[]{branch}, new GenWarp(GenWarp.EMPTY.lake(), fold));
            var rejected = ReferenceHydrology.query(folded, flat, -400, 0, 0);
            helper.assertTrue(!rejected.validDirection()
                            && rejected.reason().equals("singular_or_folded_mapping"),
                    "Singular/folded native forward mapping invented a direction");
            finite(helper, rejected);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void frozenNativeLakeDropAndMouthWindowsKeepExplicitDirectionScope(GameTestHelper helper) {
        int stillLake = 0, dropPairs = 0, ocean = 0, mouthRiver = 0, dry = 0;
        for (long[] window : new long[][]{{42, -5979, 290}, {0, -1809, -7393},
                {-987654321, -29, 6493}, {0, 2280, 3120}}) {
            try (var terrain = new ReferenceTerrain(window[0])) {
                for (int dx = -32; dx <= 32; dx += 2) for (int dz = -32; dz <= 32; dz += 2) {
                    int x = (int) window[1] + dx, z = (int) window[2] + dz;
                    var query = terrain.queryHydrology(x, z);
                    if (query.reason().equals("still_lake_expansion")) {
                        helper.assertTrue(!query.validDirection(), "Still lake expansion supplied a current direction");
                        finite(helper, query);
                        stillLake++;
                    }
                }
            }
        }
        try (var terrain = new ReferenceTerrain(0)) {
            for (int x = -971; x <= -907; x++) for (int z = -182; z <= -118; z++) {
                var center = terrain.sampleRaw(x, z);
                if (!center.terrain.isRiver()) continue;
                for (int[] offset : new int[][]{{1, 0}, {0, 1}}) {
                    var adjacent = terrain.sampleRaw(x + offset[0], z + offset[1]);
                    if (!adjacent.terrain.isRiver() || waterY(terrain, center) == waterY(terrain, adjacent)) continue;
                    finite(helper, terrain.queryHydrology(x, z));
                    finite(helper, terrain.queryHydrology(x + offset[0], z + offset[1]));
                    dropPairs++;
                }
            }
        }
        try (var terrain = new ReferenceTerrain(-987654321)) {
            for (int x = -2886; x <= -2822; x += 2) for (int z = 6699; z <= 6763; z += 2) {
                var raw = terrain.sampleRaw(x, z);
                var query = terrain.queryHydrology(x, z);
                finite(helper, query);
                if (raw.terrain.isRiver()) {
                    mouthRiver++;
                } else if (raw.terrain.isDeepOcean() || raw.terrain.isShallowOcean()) {
                    ocean++;
                    helper.assertTrue(!query.validDirection(), "Native river-mouth direction leaked into ocean");
                } else {
                    dry++;
                    helper.assertTrue(!query.validDirection(), "Native river-mouth direction leaked onto dry terrain");
                }
            }
        }
        try (var terrain = new ReferenceTerrain(42)) {
            var junction = terrain.queryHydrology(-1600, -7640);
            helper.assertTrue(terrain.sampleRaw(-1600, -7640).terrain.isRiver(),
                    "Verified native junction fixture no longer intersects a river");
            helper.assertTrue(!junction.validDirection() && junction.reason().equals("ambiguous_junction"),
                    "Verified native tributary junction invented a unique current direction");
            finite(helper, junction);
        }
        helper.assertTrue(stillLake > 0 && dropPairs > 0 && ocean > 0 && mouthRiver > 0 && dry > 0,
                "Frozen native windows missed lake/drop/mouth controls: " + stillLake + "/"
                        + dropPairs + "/" + ocean + "/" + mouthRiver + "/" + dry);
        System.out.println("CWI_HYDROLOGY_SCOPE=lake_expanded:" + stillLake + ",drop_pairs:" + dropPairs
                + ",ocean:" + ocean + ",mouth_river:" + mouthRiver + ",dry:" + dry + ",ambiguous_native_junction:1");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void realRiverDirectionsAdvanceDownstreamOnFlatWaterAndRejectDryColumns(GameTestHelper helper) {
        int valid = 0, flat = 0, invalid = 0;
        boolean variedDirection = false;
        Double firstX = null;
        try (var terrain = new ReferenceTerrain(0)) {
            for (int x = 3215; x <= 3343; x += 4) {
                for (int z = 4196; z <= 4324; z += 4) {
                    var raw = terrain.sampleRaw(x, z);
                    var query = terrain.queryHydrology(x, z);
                    finite(helper, query);
                    if (!query.validDirection()) {
                        invalid++;
                        continue;
                    }
                    helper.assertTrue(raw.terrain.isRiver(), "Direction leaked outside native river classification");
                    helper.assertTrue(query.progress() >= 0 && query.progress() <= 1,
                            "Directed progress escaped reach endpoints");
                    near(helper, Math.hypot(query.tangentX(), query.tangentZ()), 1, 1E-6,
                            "Native downstream tangent is not normalized");
                    HydrologySnapshot snapshot = terrain.hydrologySnapshot(x, z);
                    helper.assertTrue(snapshot.reaches().stream().anyMatch(r -> r.id() == query.reachId()),
                            "Query reach does not belong to its own world's exported graph");
                    int dx = (int) Math.round(query.tangentX() * 2);
                    int dz = (int) Math.round(query.tangentZ() * 2);
                    var downstream = terrain.queryHydrology(x + dx, z + dz);
                    var upstream = terrain.queryHydrology(x - dx, z - dz);
                    if (downstream.validDirection() && upstream.validDirection()
                            && downstream.reachId() == query.reachId() && upstream.reachId() == query.reachId()) {
                        helper.assertTrue(downstream.progress() > upstream.progress(),
                                "Exported tangent points upstream on a real native river");
                        float centerWater = water(raw);
                        if (Float.floatToIntBits(water(terrain.sampleRaw(x + dx, z + dz)))
                                        == Float.floatToIntBits(centerWater)
                                && Float.floatToIntBits(water(terrain.sampleRaw(x - dx, z - dz)))
                                        == Float.floatToIntBits(centerWater)) flat++;
                    }
                    if (firstX == null) firstX = query.tangentX();
                    else variedDirection |= Math.abs(firstX - query.tangentX()) > .02;
                    valid++;
                }
            }
            for (int[] point : EDGES) {
                var raw = terrain.sampleRaw(point[0], point[1]);
                var query = terrain.queryHydrology(point[0], point[1]);
                if (!raw.terrain.isRiver()) helper.assertTrue(!query.validDirection(),
                        "Dry/ocean control acquired a downstream direction at chunk or tile edge");
                finite(helper, query);
            }
        }
        helper.assertTrue(valid >= 8 && flat >= 4 && invalid > 0,
                "Fixed native river window did not exercise valid, flat-water and rejected queries: "
                        + valid + "/" + flat + "/" + invalid);
        helper.assertTrue(variedDirection, "Fixed native river window did not exercise its warped bend");
        System.out.println("CWI_HYDROLOGY_DIRECTIONS=valid:" + valid + ",flat_water:" + flat
                + ",rejected:" + invalid + ",bend_variation:" + variedDirection);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void topologyAndQueriesAreStableAcrossOrderSeedsConcurrencyAndEviction(GameTestHelper helper)
            throws Exception {
        var points = new ArrayList<int[]>();
        points.addAll(List.of(EDGES));
        for (int x = 3247; x <= 3311; x += 16) for (int z = 4228; z <= 4292; z += 16)
            points.add(new int[]{x, z});
        try (var terrain = new ReferenceTerrain(0); var repeated = new ReferenceTerrain(0);
                var highBits = new ReferenceTerrain(1L << 32); var other = new ReferenceTerrain(42)) {
            List<HydrologyQuery> expected = new ArrayList<>();
            List<HydrologySnapshot> snapshots = new ArrayList<>();
            Map<String, String> geometry = new HashMap<>();
            boolean otherSeedChanged = false;
            for (int[] point : points) {
                int x = point[0], z = point[1];
                expected.add(terrain.queryHydrology(x, z));
                snapshots.add(terrain.hydrologySnapshot(x, z));
                geometry.put(x + "," + z, geometry(terrain.sampleRaw(x, z)));
                otherSeedChanged |= !terrain.hydrologySnapshot(x, z).reaches()
                        .equals(other.hydrologySnapshot(x, z).reaches());
            }
            helper.assertTrue(otherSeedChanged, "Distinct worlds reused the same hydrology graph");
            for (int index = points.size() - 1; index >= 0; index--) {
                int[] point = points.get(index);
                int x = point[0], z = point[1];
                helper.assertTrue(expected.get(index).equals(repeated.queryHydrology(x, z)),
                        "Reversed cold-world query changed directed metadata");
                helper.assertTrue(expected.get(index).equals(highBits.queryHydrology(x, z)),
                        "Hydrology changed the reference engine's original low-32-bit seed behavior");
                helper.assertTrue(snapshots.get(index).equals(repeated.hydrologySnapshot(x, z)),
                        "Reversed query changed snapshot IDs or links");
                helper.assertTrue(geometry.get(x + "," + z).equals(geometry(terrain.sampleRaw(x, z))),
                        "Observational metadata query changed native geometry");
            }
            var executor = Executors.newFixedThreadPool(3);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int thread = 0; thread < 3; thread++) {
                    final int offset = thread;
                    futures.add(executor.submit(() -> {
                        for (int index = 0; index < points.size(); index++) {
                            int sample = (index * 7 + offset) % points.size();
                            int[] point = points.get(sample);
                            if (!expected.get(sample).equals(terrain.queryHydrology(point[0], point[1])))
                                throw new AssertionError("Concurrent query changed hydrology metadata");
                        }
                    }));
                }
                for (Future<?> future : futures) future.get();
            } finally {
                executor.shutdownNow();
            }
            var continents = new java.util.HashSet<String>();
            for (int index = 0; index < 48; index++) {
                var snapshot = terrain.hydrologySnapshot(index * 20000 - 480000, index * -17000 + 390000);
                continents.add(snapshot.continentX() + "," + snapshot.continentZ());
            }
            helper.assertTrue(continents.size() > 32, "River-cache regression did not exceed its 32-graph capacity");
            for (int index = 0; index < points.size(); index++) {
                int[] point = points.get(index);
                helper.assertTrue(snapshots.get(index).equals(terrain.hydrologySnapshot(point[0], point[1])),
                        "Graph eviction changed a detached snapshot or regenerated IDs");
                helper.assertTrue(expected.get(index).equals(terrain.queryHydrology(point[0], point[1])),
                        "Graph eviction changed regenerated downstream queries");
                validateLinks(helper, snapshots.get(index));
            }
            System.out.println("CWI_HYDROLOGY_DETERMINISM=sites:" + points.size()
                    + ",parallel_queries:" + (points.size() * 3) + ",eviction_continents:" + continents.size()
                    + ",reversed:true,low32:true,world_isolation:true");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void representableProbeSpansPreserveDirectionAtLargeWorldCoordinates(GameTestHelper helper) {
        int checked = 0;
        for (int[] origin : new int[][]{{0, 0}, {8388608, 0}, {-8388608, 0},
                {0, 8388608}, {0, -8388608}, {29998000, 0}, {-29998000, 0},
                {0, 29998000}, {0, -29998000}, {29998000, -29998000}}) {
            var river = new River(origin[0], origin[1], origin[0] + 1000, origin[1] + 1000);
            var map = new Rivermap(0, 0, new Network[]{new Network(
                    new RecordingCarver(river, RiverWarp.NONE), new Wetland[0], new Network[0],
                    new Boundsf(origin[0] - 2000, origin[1] - 2000,
                            origin[0] + 2000, origin[1] + 2000))}, GenWarp.EMPTY);
            var flat = new Cell();
            flat.terrain = TerrainType.RIVER;
            for (int offset : new int[]{500, 501, 502}) {
                float x = origin[0] + offset;
                float z = origin[1] + offset;
                var query = ReferenceHydrology.query(map, flat, x, z, 0);
                var nativeQuery = map.queryGeometry(x, z).getFirst();
                helper.assertTrue(Double.isFinite(nativeQuery.probeSpanX()) && nativeQuery.probeSpanX() >= 1
                                && Double.isFinite(nativeQuery.probeSpanZ()) && nativeQuery.probeSpanZ() >= 1,
                        "Adaptive float probes collapsed or omitted their representable coordinate spans");
                if (origin[0] == 0 && origin[1] == 0) {
                    near(helper, nativeQuery.probeSpanX(), 1, 0, "Unchanged local X probe span");
                    near(helper, nativeQuery.probeSpanZ(), 1, 0, "Unchanged local Z probe span");
                }
                helper.assertTrue(query.validDirection(),
                        "Representable native probes lost large-coordinate direction at " + x + "," + z);
                near(helper, query.tangentX(), Math.sqrt(.5), 1E-6, "Large-coordinate downstream X");
                near(helper, query.tangentZ(), Math.sqrt(.5), 1E-6, "Large-coordinate downstream Z");
                double expectedSigned = river.ndx * (z - river.z1) - river.ndz * (x - river.x1);
                near(helper, query.signedDistance(), expectedSigned, 1E-4,
                        "Large-coordinate native signed distance");
                var parallel = new River(origin[0], origin[1] + 2,
                        origin[0] + 1000, origin[1] + 1002);
                var parallelMap = new Rivermap(0, 0, new Network[]{
                        new Network(new RecordingCarver(river, RiverWarp.NONE), new Wetland[0],
                                new Network[0], new Boundsf(origin[0] - 2000, origin[1] - 2000,
                                        origin[0] + 2000, origin[1] + 2000)),
                        new Network(new RecordingCarver(parallel, RiverWarp.NONE), new Wetland[0],
                                new Network[0], new Boundsf(origin[0] - 2000, origin[1] - 2000,
                                        origin[0] + 2000, origin[1] + 2000))}, GenWarp.EMPTY);
                float rankX = origin[0] + (offset & ~1);
                float rankZ = origin[1] + (offset & ~1);
                var ranked = ReferenceHydrology.query(parallelMap, flat, rankX, rankZ, 0);
                helper.assertTrue(ranked.validDirection() && ranked.reachId() == query.reachId(),
                        "Float probe spans skewed lateral distance into a false junction ambiguity");
                checked++;
            }
        }
        System.out.println("CWI_HYDROLOGY_LARGE_COORDINATES=queries:" + checked
                + ",positive_negative:true,mixed_axes:true,collapsed_half_step_recovered:true,border:true");
        helper.succeed();
    }

    private static void validateLinks(GameTestHelper helper, HydrologySnapshot snapshot) {
        Map<Long, DirectedReach> byId = new HashMap<>();
        for (var reach : snapshot.reaches()) {
            helper.assertTrue(byId.put(reach.id(), reach) == null, "Snapshot has duplicate reach IDs");
            helper.assertTrue(reach.points().isEmpty(), "Query-field adapter exported guessed world-space centerlines");
        }
        for (var reach : snapshot.reaches()) if (reach.hasDownstream()) {
            helper.assertTrue(reach.id() != reach.downstreamId() && byId.containsKey(reach.downstreamId()),
                    "Snapshot has dangling or self-referential downstream link");
            helper.assertTrue(reach.downstreamJoinProgress() >= 0 && reach.downstreamJoinProgress() <= 1,
                    "Parent attachment progress is outside parent reach");
            var visited = new java.util.HashSet<Long>();
            var cursor = reach;
            while (cursor.hasDownstream()) {
                helper.assertTrue(visited.add(cursor.id()), "Directed graph contains a downstream cycle");
                cursor = byId.get(cursor.downstreamId());
            }
        }
    }

    private static void finite(GameTestHelper helper, HydrologyQuery query) {
        helper.assertTrue(Double.isFinite(query.progress()) && Double.isFinite(query.tangentX())
                        && Double.isFinite(query.tangentZ()) && Double.isFinite(query.signedDistance()),
                "Hydrology query exposed a non-finite numeric result");
        helper.assertTrue(query.reason() != null && !query.reason().isBlank(), "Query omitted its validity reason");
        if (!query.validDirection()) helper.assertTrue(query.tangentX() == 0 && query.tangentZ() == 0,
                "Invalid/ambiguous query still supplied a guessed direction");
    }

    private static float water(Cell cell) {
        return ContinentalHydrology.getComplexWaterHeight(cell.waterTable,
                cell.globalContinentScale, cell.continentSizeModifier);
    }

    private static int waterY(ReferenceTerrain terrain, Cell cell) {
        return terrain.levels().scale(water(cell) + terrain.levels().water);
    }

    private static String geometry(Cell cell) {
        return Float.floatToIntBits(cell.height) + ":" + Float.floatToIntBits(cell.riverMask)
                + ":" + Float.floatToIntBits(cell.waterTable) + ":" + cell.riverZone + ":" + cell.terrain;
    }

    private static void near(GameTestHelper helper, double actual, double expected, double tolerance, String label) {
        helper.assertTrue(Math.abs(actual - expected) <= tolerance,
                label + ": expected " + expected + ", got " + actual);
    }

    private static final class RecordingCarver implements CFHRiverCarver {
        private final River river;
        private final RiverWarp warp;
        private float previousX, previousZ, x, z, progress;

        private RecordingCarver(River river, RiverWarp warp) {
            this.river = river;
            this.warp = warp;
        }

        @Override public boolean isMain() { return true; }
        @Override public River getRiver() { return river; }
        @Override public RiverWarp getWarp() { return warp; }
        @Override public RiverConfig getConfig() { return null; }
        @Override public RiverConfig createForkConfig(float progress, Levels levels) {
            throw new UnsupportedOperationException("Diagnostic network does not generate new forks");
        }
        @Override public void carve(Cell cell, float previousX, float previousZ, float previousProgress,
                float x, float z, float progress) {
            this.previousX = previousX;
            this.previousZ = previousZ;
            this.x = x;
            this.z = z;
            this.progress = progress;
        }
    }

    private NativeHydrologyFoundationTest() {}
}
