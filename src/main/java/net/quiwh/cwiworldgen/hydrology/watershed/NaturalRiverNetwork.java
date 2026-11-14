package net.quiwh.cwiworldgen.hydrology.watershed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.DoubleBinaryOperator;

/**
 * Immutable curved projection of drainage topology. Shared junctions retain one position,
 * tangent and water level; path and bank terrain constrain water before any chunk is generated.
 */
public final class NaturalRiverNetwork {
    public record Vertex(double x, double z, double water) {}
    private record Position(double x, double z) {}
    private record Path(WatershedPlan.Edge edge, List<Position> points, double[] limits, double[] required, double length) {}

    private static final double MAX_INCISION = 30;
    private static final double[] BANK_OFFSETS = {-1, -.5, .5, 1};
    private static final BiPredicate<Integer, Integer> ALL_OWNED = (x, z) -> true;
    private final WatershedPlan plan;
    private final boolean physicalCatchments;
    private final boolean naturalGeometry;
    private final boolean denseProjection;
    private final boolean landscapeMorphology;
    private final boolean compactMountainSupport;
    private final boolean variableSources;
    private final long geometrySeed;
    private final WidthProfile[] widthProfiles;
    private final Map<Integer, WatershedPlan.Edge> edges;
    private final Integer[] edgeKeys;
    private final Map<Integer, List<Vertex>> paths;
    private final Set<Integer> effectiveWet;
    private final Map<Integer, Double> sourceTapers;
    private final Map<Integer, SourceMouthProfile> sourceMouths;
    private final Map<Integer, List<RiverBankContext>> bankContexts;
    private final SamplingProfile[] samplingProfiles;

    /** Published only after admission, trial widths and bank controls are frozen. */
    private record SamplingProfile(WatershedPlan.Edge edge, WidthProfile widths, boolean wet,
            Double sourceLength, SourceMouthProfile mouth, List<RiverBankContext> bankControls) {}

    /** One Bundle's reconstruction-local, read-only inputs; never retained by a published network. */
    static final class RebuildWorkspace {
        private final WatershedPlan plan;
        private final long seed,sea;
        private final DoubleBinaryOperator ground;
        private final int generationVersion;
        private final Position[] junctions;
        private final OrdinaryInputs[] ordinary;

        RebuildWorkspace(WatershedPlan plan,long seed,DoubleBinaryOperator ground,double sea,int generationVersion){
            this.plan=plan;this.seed=seed;this.ground=ground;this.sea=Double.doubleToRawLongBits(sea);
            this.generationVersion=generationVersion;
            junctions=new Position[plan.count()];ordinary=new OrdinaryInputs[plan.count()];
        }
        private void requireInputs(WatershedPlan plan,long seed,DoubleBinaryOperator ground,double sea,int generationVersion){
            if(this.plan!=plan||this.seed!=seed||this.ground!=ground||this.sea!=Double.doubleToRawLongBits(sea)
                    ||this.generationVersion!=generationVersion)
                throw new IllegalArgumentException("River reconstruction workspace inputs changed");
        }
    }
    /** Tangents depend on the current selection, so a changed confluence must miss this cache. */
    private static final class OrdinaryInputs {
        final WatershedPlan.Edge edge;
        final Position start,end,startTangent,endTangent;
        final List<Position> points;
        final WidthProfile widths;
        final SupportedPath support;
        SupportedPath sourceSupport;
        OrdinaryInputs(WatershedPlan.Edge edge,Position start,Position end,Position startTangent,Position endTangent,
                List<Position> points,WidthProfile widths,SupportedPath support){
            this.edge=edge;this.start=start;this.end=end;this.startTangent=startTangent;this.endTangent=endTangent;
            this.points=points;this.widths=widths;this.support=support;
        }
        boolean matches(WatershedPlan.Edge edge,Position start,Position end,Position startTangent,Position endTangent){
            return this.edge.equals(edge)&&this.start.equals(start)&&this.end.equals(end)
                    &&this.startTangent.equals(startTangent)&&this.endTangent.equals(endTangent);
        }
    }

    public NaturalRiverNetwork(WatershedPlan plan, List<WatershedPlan.Edge> selected, long seed,
            DoubleBinaryOperator groundHeight, double sea) {
        this(plan, selected, seed, groundHeight, sea, 4);
    }

    public NaturalRiverNetwork(WatershedPlan plan, List<WatershedPlan.Edge> selected, long seed,
            DoubleBinaryOperator groundHeight, double sea, int generationVersion) {
        this(plan, selected, seed, groundHeight, sea, generationVersion, ALL_OWNED);
    }

    public NaturalRiverNetwork(WatershedPlan plan, List<WatershedPlan.Edge> selected, long seed,
            DoubleBinaryOperator groundHeight, double sea, int generationVersion,
            BiPredicate<Integer, Integer> owned) {
        this(plan,selected,seed,groundHeight,sea,generationVersion,owned,true);
    }

    /** Composite hydrology defers optional mouth publication until lake and split admission is frozen. */
    public NaturalRiverNetwork(WatershedPlan plan, List<WatershedPlan.Edge> selected, long seed,
            DoubleBinaryOperator groundHeight, double sea, int generationVersion,
            BiPredicate<Integer, Integer> owned, boolean publishSourceMouths) {
        this(plan,selected,seed,groundHeight,sea,generationVersion,owned,publishSourceMouths,null);
    }

    NaturalRiverNetwork(WatershedPlan plan, List<WatershedPlan.Edge> selected, long seed,
            DoubleBinaryOperator groundHeight, double sea, int generationVersion,
            BiPredicate<Integer, Integer> owned, boolean publishSourceMouths,RebuildWorkspace reuse) {
        if(reuse!=null)reuse.requireInputs(plan,seed,groundHeight,sea,generationVersion);
        this.plan = plan;
        widthProfiles = new WidthProfile[plan.count()];
        physicalCatchments = generationVersion >= 7;
        naturalGeometry = generationVersion >= 8;
        denseProjection = generationVersion >= 9;
        landscapeMorphology = generationVersion >= 10;
        compactMountainSupport = generationVersion >= 11;
        variableSources = generationVersion >= 16;
        geometrySeed = seed;
        if (!Double.isFinite(sea)) throw new IllegalArgumentException("Non-finite sea height");
        Map<Integer, WatershedPlan.Edge> selectedByNode = new HashMap<>();
        for (var edge : selected) {
            if (edge.from() < 0 || edge.from() >= plan.count() || edge.to() != plan.receiver(edge.from()))
                throw new IllegalArgumentException("Channel does not follow drainage topology");
            if (selectedByNode.put(edge.from(), edge) != null)
                throw new IllegalArgumentException("Duplicate drainage channel");
        }
        edges = Collections.unmodifiableMap(new HashMap<>(selectedByNode));
        // Reuse the existing immutable map keys; do not allocate boxes per hot lookup.
        edgeKeys = new Integer[plan.count()];
        for (Integer key : edges.keySet()) edgeKeys[key.intValue()] = key;
        Position[] nodes = new Position[plan.count()];
        boolean[] used = new boolean[plan.count()];
        for (var edge : selected) { used[edge.from()] = true; used[edge.to()] = true; }
        for (int i = 0; i < nodes.length; i++) if (used[i]) {
            if(reuse!=null&&reuse.junctions[i]!=null){nodes[i]=reuse.junctions[i];continue;}
            Position original = new Position(plan.x(i), plan.z(i));
            if (naturalGeometry && !plan.ocean(i)) {
                // One world-space field moves neighbouring junctions together. The terrain
                // search then follows the valley without resetting each edge's bend phase.
                double scale = plan.spacing() * 1.8;
                double amplitude = Math.min(56, plan.spacing() * .55);
                Position warped = new Position(original.x + noise(seed ^ 0x524956455258L, original.x / scale, original.z / scale) * amplitude,
                        original.z + noise(seed ^ 0x52495645525AL, original.x / scale, original.z / scale) * amplitude);
                nodes[i] = naturalJunction(warped, seed, groundHeight, Math.min(32, plan.spacing() * .36));
            } else if (physicalCatchments && !plan.ocean(i)) {
                // A continuous shared displacement breaks the D8 lattice without independent
                // offsets at either end of a reach. Native support still decides admission.
                double scale = plan.spacing() * 3.5;
                double amplitude = plan.spacing() * .18;
                Position warped = new Position(original.x + noise(seed ^ 0x524956455258L,original.x / scale,original.z / scale) * amplitude,
                        original.z + noise(seed ^ 0x52495645525AL,original.x / scale,original.z / scale) * amplitude);
                nodes[i] = lowerGroundPosition(warped,seed ^ i,groundHeight,Math.min(24,plan.spacing() * .20));
            } else nodes[i] = plan.ocean(i) ? original : lowerGroundPosition(original, seed ^ i, groundHeight, Math.min(36, plan.spacing() * .28));
            if(reuse!=null)reuse.junctions[i]=nodes[i];
        }
        int[] mainIncoming = new int[plan.count()];
        Arrays.fill(mainIncoming, -1);
        for (var edge : selected) {
            int previous = mainIncoming[edge.to()];
            if (previous < 0 || plan.area(edge.from()) > plan.area(previous)
                    || plan.area(edge.from()) == plan.area(previous) && edge.from() < previous)
                mainIncoming[edge.to()] = edge.from();
        }
        Position[] tangents = new Position[plan.count()];
        for (int i = 0; i < nodes.length; i++) if (used[i]) {
            Position incoming = mainIncoming[i] < 0 ? null : direction(nodes[mainIncoming[i]], nodes[i]);
            var outgoingEdge = selectedByNode.get(i);
            Position outgoing = outgoingEdge == null ? null : direction(nodes[i], nodes[outgoingEdge.to()]);
            tangents[i] = incoming == null ? outgoing : outgoing == null ? incoming
                    : normalize(incoming.x + outgoing.x, incoming.z + outgoing.z);
            if (tangents[i] == null) tangents[i] = outgoing != null ? outgoing : new Position(1, 0);
        }
        if (generationVersion >= 5) {
            ModernNetwork made = modernNetwork(selected, nodes, tangents, seed, groundHeight, sea,
                    generationVersion >= 9 && owned != ALL_OWNED ? java.util.Objects.requireNonNull(owned) : null,reuse);
            paths = made.paths;
            effectiveWet = made.wet;
            sourceTapers = made.tapers;
            sourceMouths = variableSources && publishSourceMouths ? sourceMouths(selected, groundHeight, owned) : Map.of();
            bankContexts = generationVersion >= 12 ? freezeBankContexts(groundHeight) : Map.of();
            samplingProfiles = naturalGeometry ? freezeSamplingProfiles() : null;
            return;
        }
        bankContexts = Map.of();
        samplingProfiles = null;
        sourceTapers = Map.of();
        sourceMouths = Map.of();
        List<Path> routed = new ArrayList<>();
        double[] water = new double[plan.count()];
        Arrays.fill(water, Double.POSITIVE_INFINITY);
        for (int i = 0; i < nodes.length; i++) if (used[i]) {
            water[i] = Math.max(sea, Math.min(plan.water(i), finiteGround(groundHeight, nodes[i]) - 1));
        }
        for (var edge : selected) {
            var points = curve(nodes[edge.from()], nodes[edge.to()], tangents[edge.from()], tangents[edge.to()],
                    seed, groundHeight);
            double[] limits = new double[points.size()];
            double[] required = new double[points.size()];
            double length = 0;
            for (int i = 0; i < points.size(); i++) {
                if (!edge.wet()) {
                    // Dry washes only use this curve's XY geometry. They neither place water
                    // nor constrain a wet receiver, so dense water-support probes add no value.
                    limits[i] = Double.POSITIVE_INFINITY;
                    required[i] = sea;
                    if (i > 0) length += distance(points.get(i - 1), points.get(i));
                    continue;
                }
                Position before = points.get(Math.max(0, i - 1));
                Position after = points.get(Math.min(points.size() - 1, i + 1));
                Position tangent = direction(before, after);
                Position point = points.get(i);
                double width = width(edge.from(), i / (double) (points.size() - 1));
                double nativeGround = finiteGround(groundHeight, point);
                required[i] = Math.max(sea, nativeGround - MAX_INCISION);
                double limit = nativeGround - 1;
                // Ordinary wet banks constrain water; farther split lanes require a caller support check.
                for (double scale : BANK_OFFSETS) {
                    Position bank = new Position(point.x - tangent.z * width * scale,
                            point.z + tangent.x * width * scale);
                    limit = Math.min(limit, finiteGround(groundHeight, bank) - 1);
                }
                limits[i] = Math.max(sea, limit);
                if (i > 0) length += distance(points.get(i - 1), point);
            }
            // Dry headwaters carry no water and must not lower a downstream wet channel.
            if (edge.wet()) {
                water[edge.from()] = Math.min(water[edge.from()], limits[0]);
                water[edge.to()] = Math.min(water[edge.to()], limits[limits.length - 1]);
                double travelled = 0;
                double startCollar = Math.min(length * .35, width(edge.from(), 0) * 2.3 + 4);
                double endCollar = Math.min(length * .35, width(edge.from(), 1) * 2.3 + 4);
                for (int i = 0; i < points.size(); i++) {
                    if (i > 0) travelled += distance(points.get(i - 1), points.get(i));
                    if (travelled <= startCollar) water[edge.from()] = Math.min(water[edge.from()], limits[i]);
                    if (length - travelled <= endCollar) water[edge.to()] = Math.min(water[edge.to()], limits[i]);
                }
            }
            routed.add(new Path(edge, points, limits, required, length));
        }
        // Drainage area strictly increases along receivers, providing a deterministic topological order.
        routed.sort(Comparator.comparingInt((Path p) -> plan.area(p.edge.from()))
                .thenComparingInt(p -> p.edge.from()));
        // Feasibility runs from the ocean upstream. A route must fit between native bank support
        // and a bounded incision floor, and its downstream route must already be feasible.
        Set<Integer> accepted = new HashSet<>();
        double[] nodeRequired = new double[plan.count()];
        Arrays.fill(nodeRequired, sea);
        for (Path path : routed) if (path.edge.wet()) {
            double travelled = 0;
            double startCollar = Math.min(path.length * .35, width(path.edge.from(), 0) * 2.3 + 4);
            double endCollar = Math.min(path.length * .35, width(path.edge.from(), 1) * 2.3 + 4);
            for (int i = 0; i < path.points.size(); i++) {
                if (i > 0) travelled += distance(path.points.get(i - 1), path.points.get(i));
                if (travelled <= startCollar) nodeRequired[path.edge.from()] = Math.max(nodeRequired[path.edge.from()], path.required[i]);
                if (path.length - travelled <= endCollar) nodeRequired[path.edge.to()] = Math.max(nodeRequired[path.edge.to()], path.required[i]);
            }
        }
        for (int n = routed.size() - 1; n >= 0; n--) {
            Path path = routed.get(n);
            if (!path.edge.wet()) continue;
            int to = path.edge.to(), from = path.edge.from();
            if (!plan.ocean(to) && !accepted.contains(to)) continue;
            double required = nodeRequired[to];
            boolean feasible = required <= water[to] + 1E-8;
            for (int i = path.points.size() - 1; feasible && i >= 0; i--) {
                required = Math.max(required, path.required[i]);
                path.required[i] = required;
                if (required > path.limits[i] + 1E-8) feasible = false;
            }
            required = Math.max(required, nodeRequired[from]);
            if (required > water[from] + 1E-8) feasible = false;
            if (feasible) {
                accepted.add(from);
                nodeRequired[from] = required;
            }
        }
        effectiveWet = Collections.unmodifiableSet(new HashSet<>(accepted));
        // Publish water only through accepted wet routes; rejected candidates cannot drain them.
        for (Path path : routed) {
            if (!effectiveWet.contains(path.edge.from())) continue;
            double minimum = water[path.edge.from()];
            for (double limit : path.limits) minimum = Math.min(minimum, limit);
            water[path.edge.to()] = Math.min(water[path.edge.to()], minimum);
        }
        Map<Integer, List<Vertex>> made = new HashMap<>();
        for (Path path : routed) {
            List<Vertex> points = new ArrayList<>();
            double upstream = water[path.edge.from()], downstream = water[path.edge.to()];
            double current = upstream, travelled = 0;
            double startCollar = Math.min(path.length * .35, width(path.edge.from(), 0) * 2.3 + 4);
            double endCollar = Math.min(path.length * .35, width(path.edge.from(), 1) * 2.3 + 4);
            for (int i = 0; i < path.points.size(); i++) {
                Position point = path.points.get(i);
                if (i > 0) travelled += distance(path.points.get(i - 1), point);
                double progress = Math.clamp((travelled - startCollar) / (path.length - startCollar - endCollar), 0, 1);
                double linear = upstream + (downstream - upstream) * progress;
                double lower = effectiveWet.contains(path.edge.from()) ? path.required[i] : Double.NEGATIVE_INFINITY;
                current = Math.min(current, Math.min(Math.max(linear, lower), path.limits[i]));
                if (i == 0) current = upstream;
                if (i == path.points.size() - 1) current = downstream;
                points.add(new Vertex(point.x, point.z, current));
            }
            made.put(path.edge.from(), List.copyOf(points));
        }
        paths = Map.copyOf(made);
    }

    private NaturalRiverNetwork(NaturalRiverNetwork original, Map<Integer,SourceMouthProfile> mouths,
            DoubleBinaryOperator ground) {
        plan=original.plan;
        physicalCatchments=original.physicalCatchments;
        naturalGeometry=original.naturalGeometry;
        denseProjection=original.denseProjection;
        landscapeMorphology=original.landscapeMorphology;
        compactMountainSupport=original.compactMountainSupport;
        variableSources=original.variableSources;
        geometrySeed=original.geometrySeed;
        widthProfiles=original.widthProfiles;
        edges=original.edges;
        edgeKeys=original.edgeKeys;
        paths=original.paths;
        effectiveWet=original.effectiveWet;
        sourceTapers=original.sourceTapers;
        sourceMouths=mouths;
        bankContexts=freezeBankContexts(ground);
        // Mouth decoration changes cap and depth sampling even though paths are shared.
        samplingProfiles=naturalGeometry?freezeSamplingProfiles():null;
    }

    private SamplingProfile[] freezeSamplingProfiles() {
        SamplingProfile[] profiles = new SamplingProfile[plan.count()];
        for (var entry : edges.entrySet()) {
            Integer key = entry.getKey();
            int from = key.intValue();
            profiles[from] = new SamplingProfile(entry.getValue(), widthProfiles[from], effectiveWet.contains(key),
                    sourceTapers.get(key), sourceMouths.get(key), bankContexts.get(key));
        }
        return profiles;
    }

    private SamplingProfile samplingProfile(int from) {
        SamplingProfile[] profiles = samplingProfiles;
        return profiles != null && from >= 0 && from < profiles.length ? profiles[from] : null;
    }

    /** Returns an immutable decorated projection; the admitted original remains available as fallback. */
    public NaturalRiverNetwork withSourceMouths(DoubleBinaryOperator ground,BiPredicate<Integer,Integer> owned) {
        return withSourceMouths(ground,owned,Set.of());
    }

    /** Offset split lanes keep the exact bank envelope already screened by split admission. */
    public NaturalRiverNetwork withSourceMouths(DoubleBinaryOperator ground,BiPredicate<Integer,Integer> owned,
            Set<Integer> excludedSources) {
        if(!variableSources||!sourceMouths.isEmpty()&&excludedSources.isEmpty())return this;
        Map<Integer,SourceMouthProfile> mouths=sourceMouths(new ArrayList<>(edges.values()),ground,owned,excludedSources);
        return mouths.equals(sourceMouths)?this:new NaturalRiverNetwork(this,mouths,ground);
    }

    /** Selected IDs reuse existing boxed keys; unknown IDs retain ordinary Map lookup behavior. */
    private Integer lookupKey(int from) {
        if (from >= 0 && from < edgeKeys.length) {
            Integer key = edgeKeys[from];
            if (key != null) return key;
        }
        // Keep this an explicit reference return: an int/Integer ternary would
        // unbox/rebox even a cached selected key through numeric promotion.
        return Integer.valueOf(from);
    }

    /** A connected wet route fits the native terrain without embankments or excessive incision. */
    public boolean wet(int from) { return effectiveWet.contains(lookupKey(from)); }

    public List<Vertex> points(int from) { return paths.getOrDefault(lookupKey(from), List.of()); }

    /** Downstream accumulation changes width gradually rather than resetting it at every grid edge.
     * Construction uses candidate widths until the feasible wet set has been determined. */
    public double width(int from, double progress) {
        SamplingProfile profile = samplingProfile(from);
        if (profile != null) {
            double width;
            if (profile.wet) width = interpolate(profile.widths.width, progress);
            else {
                double t = smooth(Math.clamp(progress, 0, 1));
                double area = physicalArea(plan.area(from) + (plan.area(profile.edge.to()) - plan.area(from)) * t);
                width = 2.2 + Math.min(3, Math.sqrt(area) * .3);
            }
            if (profile.sourceLength != null)
                width = taperedWidth(width, progress, profile.sourceLength);
            if (profile.mouth != null)
                width = profile.mouth.width(interpolate(profile.widths.width, progress),
                        Math.max(0, progress) * profile.sourceLength);
            return width;
        }
        // Constructor-time solving and unknown IDs keep the exact map-based path.
        Integer key = lookupKey(from);
        var edge = edges.get(key);
        if (edge == null) throw new IllegalArgumentException("Unknown drainage edge");
        if (naturalGeometry) {
            boolean wet = effectiveWet == null ? edge.wet() : effectiveWet.contains(key);
            double width = wet ? candidateWidth(from, progress) : dryWidth(from, progress);
            if (sourceTapers != null && sourceTapers.containsKey(key))
                width = taperedWidth(width, progress, sourceTapers.get(key));
            if (sourceMouths != null && sourceMouths.containsKey(key))
                width = sourceMouths.get(key).width(candidateWidth(from, progress),
                        Math.max(0, progress) * sourceTapers.get(key));
            return width;
        }
        double t = Math.clamp(progress, 0, 1);
        t = t * t * (3 - 2 * t);
        double area = plan.area(from) + (plan.area(edge.to()) - plan.area(from)) * t;
        if (physicalCatchments) area = physicalArea(area);
        double width = (effectiveWet == null ? edge.wet() : effectiveWet.contains(key)) ? Math.min(18, 4 + Math.sqrt(area) * .52)
                : 2.2 + Math.min(3, Math.sqrt(area) * .3);
        if (sourceTapers != null && sourceTapers.containsKey(key)) {
            double taper = smooth(Math.min(1, progress * sourceTapers.get(key) / 80));
            width = 1.5 + (width - 1.5) * taper;
        }
        return width;
    }

    /** Conservative construction-time bound for the published width curve, including source caps. */
    public double maximumWidth(int from) {
        SamplingProfile profile = samplingProfile(from);
        if (profile == null || profile.widths == null) return Double.POSITIVE_INFINITY;
        double maximum = Math.max(5.2, SourceMouthProfile.MAX_RADIUS);
        for (double width : profile.widths.width) {
            // Unsupported curves keep the ordinary exact evaluation path.
            if (!Double.isFinite(width) || width <= 0 || width > 64)
                return Double.POSITIVE_INFINITY;
            maximum = Math.max(maximum, width);
        }
        // Interpolation, source taper and mouth blending are convex; split and
        // angular cap factors only narrow them (with a 3.5-block cap minimum).
        // One block of slack dominates rounding throughout these bounded curves.
        return maximum + 1;
    }

    /** Shallow beginnings meet the ordinary downstream depth before the first confluence. */
    public double depthScale(int from, double progress) {
        SamplingProfile profile = samplingProfile(from);
        if (profile != null) {
            Double length = profile.sourceLength;
            double sourceDepth = landscapeMorphology ? .65 : .3;
            double taper = length == null ? 1 : sourceDepth + (1-sourceDepth) * smooth(Math.min(1, Math.max(0, progress) * length / 80));
            if (length != null && profile.mouth != null)
                taper = profile.mouth.depthScale(Math.max(0, progress) * length);
            return taper * interpolate(profile.widths.depth, progress);
        }
        Integer key = lookupKey(from);
        if (!edges.containsKey(key)) throw new IllegalArgumentException("Unknown drainage edge");
        Double length = sourceTapers.get(key);
        double sourceDepth = landscapeMorphology ? .65 : .3;
        double taper = length == null ? 1 : sourceDepth + (1-sourceDepth) * smooth(Math.min(1, Math.max(0, progress) * length / 80));
        if (length != null && sourceMouths.containsKey(key))
            taper = sourceMouths.get(key).depthScale(Math.max(0, progress) * length);
        return naturalGeometry ? taper * depthFactor(from, progress) : taper;
    }

    /** Shared confinement and resistance vary bed depth while retaining one value at junctions. */
    public double depthFactor(int from, double progress) {
        SamplingProfile profile = samplingProfile(from);
        if (profile != null) return interpolate(profile.widths.depth, progress);
        Integer key = lookupKey(from);
        if (!edges.containsKey(key)) throw new IllegalArgumentException("Unknown drainage edge");
        return naturalGeometry ? interpolate(widthProfiles[from].depth, progress) : 1;
    }

    private record WidthProfile(double[] width, double[] depth) {}

    /** An angular cap is expressed as its radius, sharing the ordinary finite-segment distance. */
    public double sourceCapWidth(int from, double progress, double x, double z, double px, double pz,
            double dx, double dz, double width) {
        SamplingProfile profile = samplingProfile(from);
        if (profile != null) {
            if (progress != 0 || profile.mouth == null) return width;
            double length = Math.hypot(dx, dz);
            if (length < 1E-8) return width;
            double rx = x - px, rz = z - pz;
            return profile.mouth.capWidth(width, (rx * dx + rz * dz) / length,
                    (dx * rz - dz * rx) / length);
        }
        return capWidth(sourceMouths, lookupKey(from), progress, x, z, px, pz, dx, dz, width);
    }

    private static double capWidth(Map<Integer, SourceMouthProfile> mouths, Integer from, double progress,
            double x, double z, double px, double pz, double dx, double dz, double width) {
        if (progress != 0 || mouths == null || !mouths.containsKey(from)) return width;
        double length = Math.hypot(dx, dz);
        if (length < 1E-8) return width;
        double rx = x - px, rz = z - pz;
        return mouths.get(from).capWidth(width, (rx * dx + rz * dz) / length,
                (dx * rz - dz * rx) / length);
    }

    /** Optional scenery of a retained source cannot reroute, retire, or lower the accepted network. */
    private Map<Integer,SourceMouthProfile> sourceMouths(List<WatershedPlan.Edge> selected,
            DoubleBinaryOperator ground,BiPredicate<Integer,Integer> owned) {
        return sourceMouths(selected,ground,owned,Set.of());
    }

    private Map<Integer,SourceMouthProfile> sourceMouths(List<WatershedPlan.Edge> selected,
            DoubleBinaryOperator ground,BiPredicate<Integer,Integer> owned,Set<Integer> excludedSources) {
        Map<Integer,SourceMouthProfile> candidates=new HashMap<>();
        for(int from:sourceTapers.keySet().stream().sorted().toList()) {
            if(excludedSources.contains(from))continue;
            List<Vertex> path=paths.get(from);
            Vertex start=path.getFirst();
            SourceMouthProfile made=SourceMouthProfile.make(geometrySeed,start.x,start.z);
            SourceMouthProfile mouth=new SourceMouthProfile(made.radius(),
                    Math.min(made.transition(),sourceTapers.get(from)),made.depth(),made.phase());
            if(sourceSupported(from,path,mouth,ground,owned))candidates.put(from,mouth);
        }
        if(candidates.isEmpty())return Map.of();
        // One extra bounded verification, never a second admission/solve. If these optional
        // mouths interfere with projection, the exact retained geometry remains the fallback.
        List<WatershedPlan.Edge> ordered=new ArrayList<>(selected);
        ordered.sort(Comparator.comparingInt((WatershedPlan.Edge edge)->plan.area(edge.from())).reversed()
                .thenComparingInt(WatershedPlan.Edge::from));
        if(projectedConflict(ordered,effectiveWet,paths,owned==ALL_OWNED?null:owned,ground,candidates)>=0)
            return Map.of();
        return Map.copyOf(candidates);
    }

    private boolean sourceSupported(int from,List<Vertex> path,SourceMouthProfile mouth,
            DoubleBinaryOperator ground,BiPredicate<Integer,Integer> owned) {
        Vertex start=path.getFirst(),after=path.get(1);
        double radius=mouth.radius(),radius2=radius*radius;
        for(int z=(int)Math.floor(start.z-radius);z<=Math.ceil(start.z+radius);z++)
            for(int x=(int)Math.floor(start.x-radius);x<=Math.ceil(start.x+radius);x++) {
                double distance2=(x-start.x)*(x-start.x)+(z-start.z)*(z-start.z);
                if(distance2>radius2)continue;
                double dx=after.x-start.x,dz=after.z-start.z,length=Math.hypot(dx,dz);
                double width=mouth.capWidth(radius,((x-start.x)*dx+(z-start.z)*dz)/length,
                        (dx*(z-start.z)-dz*(x-start.x))/length);
                if(distance2<=width*width&&!sourceColumn(ground,owned,x,z,start.water,true))return false;
            }
        double taper=sourceTapers.get(from);
        for(int i=1;i<path.size()&&(i-1.)/(path.size()-1)*taper<=mouth.transition();i++) {
            Vertex a=path.get(i-1),b=path.get(i);
            double dx=b.x-a.x,dz=b.z-a.z,length=Math.hypot(dx,dz);
            if(length<1E-8)continue;
            int count=Math.max(2,2*(int)Math.ceil(length));
            for(int n=0;n<=count;n++) {
                double t=n/(double)count,progress=(i-1+t)/(path.size()-1);
                double x=a.x+dx*t,z=a.z+dz*t,water=a.water+(b.water-a.water)*t;
                double width=mouth.width(candidateWidth(from,progress),progress*taper);
                if(!sourceColumn(ground,owned,x,z,water,true))return false;
                for(double side:BANK_OFFSETS)
                    if(!sourceColumn(ground,owned,x-dz/length*width*side,z+dx/length*width*side,water,false))return false;
            }
        }
        return true;
    }

    private static boolean sourceColumn(DoubleBinaryOperator ground,BiPredicate<Integer,Integer> owned,
            double x,double z,double water,boolean incision) {
        if(!owned.test((int)Math.round(x),(int)Math.round(z)))return false;
        double height=ground.applyAsDouble(x,z);
        return Double.isFinite(height)&&height>=water+1-1E-8&&(!incision||height-water<=MAX_INCISION+1E-8);
    }

    private WidthProfile putWidthProfile(int from, WidthProfile profile) {
        WidthProfile previous = widthProfiles[from];
        widthProfiles[from] = profile;
        return previous;
    }

    private record ModernNetwork(Map<Integer, List<Vertex>> paths, Set<Integer> wet, Map<Integer, Double> tapers) {}
    private record SupportedPath(List<Position> points, double[] limits, double[] ground, double[] distance,
            boolean tapered, double startCollar, double endCollar, double startLimit, double endLimit,
            double minimumLimit, double[] grades, double capRequired) {}
    private record RetainedPathSolution(SupportedPath support, long source, long receiver, long sea,
                                        List<Vertex> result) {}

    /** Construction-local scratch; a rejected trial never publishes or retains this mutable state. */
    private static final class RetainedSolveWorkspace {
        private final int count;
        private double[] water;
        private int[] waterEpoch, incomingEpoch;
        private SupportedPath[] active;
        private RetainedPathSolution[] solutions;
        private int epoch;

        RetainedSolveWorkspace(int count) { this.count = count; }

        void reset() {
            // All-dry plans need no scratch arrays. The first actual trial owns allocation.
            if (water == null) {
                water = new double[count];
                waterEpoch = new int[count];
                incomingEpoch = new int[count];
                active = new SupportedPath[count];
                solutions = new RetainedPathSolution[count];
            }
            if (epoch == Integer.MAX_VALUE) {
                Arrays.fill(waterEpoch, 0);
                Arrays.fill(incomingEpoch, 0);
                epoch = 1;
            } else epoch++;
        }

        void receivesWet(int node) { incomingEpoch[node] = epoch; }
        boolean hasWetIncoming(int node) { return incomingEpoch[node] == epoch; }
        double water(int node) { return waterEpoch[node] == epoch ? water[node] : Double.POSITIVE_INFINITY; }
        void water(int node, double value) { water[node] = value; waterEpoch[node] = epoch; }
    }

    private ModernNetwork modernNetwork(List<WatershedPlan.Edge> selected, Position[] nodes,
            Position[] tangents, long seed, DoubleBinaryOperator ground, double sea,
            BiPredicate<Integer, Integer> owned,RebuildWorkspace reuse) {
        List<WatershedPlan.Edge> ordered = new ArrayList<>(selected);
        ordered.sort(Comparator.comparingInt((WatershedPlan.Edge e) -> plan.area(e.from())).reversed()
                .thenComparingInt(WatershedPlan.Edge::from));
        Map<Integer, List<Position>> geometry = new HashMap<>();
        Map<Integer, SupportedPath> support = new HashMap<>();
        Map<Integer, SupportedPath> sourceSupport = new HashMap<>();
        Map<Integer, List<Vertex>> made = new HashMap<>();
        Set<Integer> accepted = new HashSet<>();
        RetainedSolveWorkspace solveWorkspace = new RetainedSolveWorkspace(plan.count());
        for (var edge : ordered) {
            OrdinaryInputs cached=reuse==null?null:reuse.ordinary[edge.from()];
            if(cached!=null&&cached.matches(edge,nodes[edge.from()],nodes[edge.to()],tangents[edge.from()],tangents[edge.to()])){
                geometry.put(edge.from(),cached.points);
                if(naturalGeometry)putWidthProfile(edge.from(),cached.widths);
                if(edge.wet())support.put(edge.from(),cached.support);
                continue;
            }
            List<Position> ordinary = naturalGeometry
                    ? naturalCurve(nodes[edge.from()], nodes[edge.to()], tangents[edge.from()], tangents[edge.to()], seed, ground)
                    : curve(nodes[edge.from()], nodes[edge.to()], tangents[edge.from()], tangents[edge.to()], seed, ground);
            geometry.put(edge.from(), ordinary);
            if (naturalGeometry) putWidthProfile(edge.from(), profile(edge, ordinary, ground));
            if (edge.wet()) support.put(edge.from(), supported(edge, ordinary, ground, sea, false));
            if(reuse!=null)reuse.ordinary[edge.from()]=new OrdinaryInputs(edge,nodes[edge.from()],nodes[edge.to()],
                    tangents[edge.from()],tangents[edge.to()],ordinary,widthProfiles[edge.from()],support.get(edge.from()));
        }
        Map<Integer, TerrainRiverRoute.Corridor> originals = new HashMap<>();
        geometry.forEach((from, ordinary) -> originals.put(from, new TerrainRiverRoute.Corridor(ordinary.stream()
                .map(p -> new TerrainRiverRoute.Point(p.x, p.z)).toList())));
        Map<Integer, TerrainRiverRoute.Corridor> corridors = Map.copyOf(originals);
        for (var edge : ordered) {
            if (!edge.wet() || !plan.ocean(edge.to()) && !accepted.contains(edge.to())) continue;
            OrdinaryInputs cached=reuse==null?null:reuse.ordinary[edge.from()];
            List<Position> sourcePoints=geometry.get(edge.from());
            if(cached!=null&&cached.points==sourcePoints){
                if(cached.sourceSupport==null)cached.sourceSupport=supported(edge,sourcePoints,ground,sea,true);
                sourceSupport.put(edge.from(),cached.sourceSupport);
            }else sourceSupport.put(edge.from(),supported(edge,sourcePoints,ground,sea,true));
            Set<Integer> trial = new HashSet<>(accepted);
            trial.add(edge.from());
            Map<Integer, List<Vertex>> proposed = solveRetained(ordered, trial, support, sourceSupport, sea, solveWorkspace);
            if (proposed == null) {
                double receiverWater = plan.ocean(edge.to()) ? sea : made.get(edge.to()).getFirst().water;
                List<Position> refined = refined(edge, corridors.get(edge.from()), ground, receiverWater, sea, true);
                WidthProfile originalWidth = naturalGeometry ? putWidthProfile(edge.from(), profile(edge, refined, ground)) : null;
                SupportedPath original = support.put(edge.from(), supported(edge, refined, ground, sea, false));
                SupportedPath originalSource = sourceSupport.put(edge.from(), supported(edge, refined, ground, sea, true));
                proposed = solveRetained(ordered, trial, support, sourceSupport, sea, solveWorkspace);
                // A joining tributary widens a previously tapered receiving source. If that
                // immediate receiver is the obstruction, refine the connected pair together.
                // The entire trial is discarded unless every retained river still fits.
                var receiver = edges.get(edge.to());
                if (proposed == null && receiver != null && accepted.contains(receiver.from())) {
                    List<Vertex> receiverPath = made.get(receiver.from());
                    List<Position> receiverGeometry = refined(receiver, corridors.get(receiver.from()), ground,
                            receiverPath.getLast().water, sea, false);
                    WidthProfile receiverWidth = naturalGeometry ? putWidthProfile(receiver.from(), profile(receiver, receiverGeometry, ground)) : null;
                    SupportedPath receiverOriginal = support.put(receiver.from(),
                            supported(receiver, receiverGeometry, ground, sea, false));
                    SupportedPath receiverSourceOriginal = sourceSupport.put(receiver.from(),
                            supported(receiver, receiverGeometry, ground, sea, true));
                    proposed = solveRetained(ordered, trial, support, sourceSupport, sea, solveWorkspace);
                    if (proposed == null) {
                        support.put(receiver.from(), receiverOriginal);
                        sourceSupport.put(receiver.from(), receiverSourceOriginal);
                        if (naturalGeometry) putWidthProfile(receiver.from(), receiverWidth);
                    } else geometry.put(receiver.from(), receiverGeometry);
                }
                if (proposed == null) {
                    support.put(edge.from(), original); sourceSupport.put(edge.from(), originalSource);
                    if (naturalGeometry) putWidthProfile(edge.from(), originalWidth);
                }
                else geometry.put(edge.from(), refined);
            }
            // A candidate is committed only when the complete retained network still fits.
            // Tiny compatible junction adjustments are allowed; a rejected tributary cannot
            // lower or invalidate any previously accepted downstream route.
            if (proposed != null) { accepted = trial; made = proposed; }
        }
        if (physicalCatchments) {
            // Denser routes can approach a different river at a different water level.
            // Validate the same nearest-core arbitration used by column projection, rather
            // than trusting individually monotone paths to remain monotone when combined.
            // An incompatible branch and all upstream dependents are removed atomically;
            // recomputing the retained water also restores the receiving source taper.
            for (int attempt=0;attempt<=selected.size();attempt++) {
                int conflict=projectedConflict(ordered,accepted,made,owned,ground);
                if(conflict<0)break;
                Set<Integer> removed=new HashSet<>();removed.add(conflict);
                for(var edge:ordered)if(removed.contains(edge.to()))removed.add(edge.from());
                accepted.removeAll(removed);
                made=solveRetained(ordered,accepted,support,sourceSupport,sea,solveWorkspace);
                if(landscapeMorphology&&made==null){
                    // A newly exposed headwater has a wider round cap than the
                    // ordinary joining channel. Its source-only support can fail
                    // after an incoming branch disappears. Retire that source and
                    // retry its receiver; every retry removes one accepted edge.
                    int exposed=edges.get(conflict).to();
                    while(made==null&&accepted.contains(exposed)){
                        boolean incoming=false;
                        for(var edge:ordered)if(accepted.contains(edge.from())&&edge.to()==exposed){incoming=true;break;}
                        if(incoming)break;
                        accepted.remove(exposed);exposed=edges.get(exposed).to();
                        made=solveRetained(ordered,accepted,support,sourceSupport,sea,solveWorkspace);
                    }
                }
                if(made==null)throw new IllegalStateException("Removing an incompatible river invalidated retained water");
            }
        }
        Map<Integer, Double> tapers = new HashMap<>();
        Set<Integer> receivesWet = new HashSet<>();
        for (var edge : ordered) if (accepted.contains(edge.from())) receivesWet.add(edge.to());
        for (var edge : ordered) if (accepted.contains(edge.from()) && !receivesWet.contains(edge.from())) {
            SupportedPath path = sourceSupport.get(edge.from());
            tapers.put(edge.from(), Math.max(80, path.distance[path.distance.length - 1]));
        }
        double[] nodeWater = new double[plan.count()];
        for (int i = 0; i < nodeWater.length; i++) if (nodes[i] != null)
            nodeWater[i] = plan.ocean(i) ? sea : Math.max(sea, finiteGround(ground, nodes[i]) - 1);
        for (var edge : ordered) if (accepted.contains(edge.from())) {
            nodeWater[edge.from()] = made.get(edge.from()).getFirst().water;
            nodeWater[edge.to()] = made.get(edge.from()).getLast().water;
        }
        for (var edge : ordered) if (!made.containsKey(edge.from())) {
            List<Position> points = geometry.get(edge.from());
            List<Vertex> dry = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                double t = i / (double)(points.size() - 1);
                Position p = points.get(i);
                dry.add(new Vertex(p.x, p.z, Math.max(sea,
                        nodeWater[edge.from()] * (1 - t) + nodeWater[edge.to()] * t)));
            }
            made.put(edge.from(), List.copyOf(dry));
        }
        return new ModernNetwork(Collections.unmodifiableMap(new HashMap<>(made)),
                Collections.unmodifiableSet(new HashSet<>(accepted)), Collections.unmodifiableMap(new HashMap<>(tapers)));
    }

    /** Controls are sampled only after acceptance; they never participate in routing or water solving. */
    private Map<Integer,List<RiverBankContext>> freezeBankContexts(DoubleBinaryOperator ground) {
        Map<Integer,List<RiverBankContext>> made=new HashMap<>();
        for(var entry:paths.entrySet()) {
            int from=entry.getKey();
            if(!effectiveWet.contains(from))continue;
            List<Vertex> route=entry.getValue();
            List<RiverBankContext> controls=new ArrayList<>();
            for(int i=0;i<route.size();i++) {
                Vertex before=route.get(Math.max(0,i-1)),point=route.get(i),after=route.get(Math.min(route.size()-1,i+1));
                double dx=after.x-before.x,dz=after.z-before.z,length=Math.max(1E-8,Math.hypot(dx,dz));
                double progress=i/(double)(route.size()-1),width=width(from,progress);
                double offset=width+8;
                double left=Math.max(0,ground.applyAsDouble(point.x-dz/length*offset,point.z+dx/length*offset)-point.water);
                double right=Math.max(0,ground.applyAsDouble(point.x+dz/length*offset,point.z-dx/length*offset)-point.water);
                double relief=Math.max(left,right),grade=Math.max(0,(before.water-after.water)/length);
                double ax=point.x-before.x,az=point.z-before.z,bx=after.x-point.x,bz=after.z-point.z;
                double curvature=Math.hypot(ax,az)<1E-8||Math.hypot(bx,bz)<1E-8?0:
                        Math.clamp(Math.atan2(ax*bz-az*bx,ax*bx+az*bz)*width*4/length,-1,1);
                double leftControl=Math.clamp(.5+.22*noise(geometrySeed^73,point.x/83.,point.z/83.)+(left-right)/160.,0,1);
                double rightControl=Math.clamp(.5+.22*noise(geometrySeed^157,point.x/83.,point.z/83.)+(right-left)/160.,0,1);
                // Shared endpoints keep the former bank; high-relief valleys retain established gorges.
                double collar=bankSmooth(0,.14,progress)*(1-bankSmooth(.86,1,progress));
                double gorge=Math.max(1-collar,bankSmooth(26,55,relief));
                controls.add(new RiverBankContext(relief,grade,.5,curvature,leftControl,rightControl,gorge));
            }
            made.put(from,List.copyOf(controls));
        }
        return Map.copyOf(made);
    }

    private static double bankSmooth(double low,double high,double value) {
        return smooth(Math.clamp((value-low)/(high-low),0,1));
    }

    public RiverBankContext bankContext(int from,int segment,double t) {
        SamplingProfile profile=samplingProfile(from);
        var controls=profile==null?bankContexts.get(lookupKey(from)):profile.bankControls;
        if(controls==null || segment<1 || segment>=controls.size())return null;
        return RiverBankContext.interpolate(controls.get(segment-1),controls.get(segment),t);
    }

    private record ProjectedSegment(int from,int index,int size,Vertex start,Vertex end,double taper) {}
    private record ProjectedWater(int from,double water) {}
    private int projectedConflict(List<WatershedPlan.Edge> ordered,Set<Integer> retained,Map<Integer,List<Vertex>> made,
            BiPredicate<Integer, Integer> owned, DoubleBinaryOperator ground){
        return projectedConflict(ordered,retained,made,owned,ground,Map.of());
    }
    private int projectedConflict(List<WatershedPlan.Edge> ordered,Set<Integer> retained,Map<Integer,List<Vertex>> made,
            BiPredicate<Integer, Integer> owned, DoubleBinaryOperator ground,Map<Integer,SourceMouthProfile> mouths){
        final int bin=32;
        Set<Integer> receives=new HashSet<>();
        for(var edge:ordered)if(retained.contains(edge.from()))receives.add(edge.to());
        Map<Long,List<ProjectedSegment>> index=new HashMap<>();
        for(var edge:ordered)if(retained.contains(edge.from())){
            List<Vertex> path=made.get(edge.from());double length=0;
            for(int i=1;i<path.size();i++)length+=Math.hypot(path.get(i).x-path.get(i-1).x,path.get(i).z-path.get(i-1).z);
            double taper=receives.contains(edge.from())?0:Math.max(80,length);
            if(landscapeMorphology&&taper!=0&&owned!=null&&!ownedSourceCap(edge.from(),path,owned,mouths))return edge.from();
            for(int i=1;i<path.size();i++){
                var a=path.get(i-1);var b=path.get(i);
                // Native ownership can change between owned routing-grid vertices. A
                // foreign curved column selects a different continent's bundle at runtime.
                // Use the same final geometry, rounded columns and source taper before
                // publishing any route; the caller removes its full upstream closure.
                if(owned!=null&&!ownedSegment(edge.from(),i,path.size(),a,b,taper,owned,mouths))return edge.from();
                double w0=projectedWidth(edge.from(),(i-1.)/(path.size()-1),taper,mouths),w1=projectedWidth(edge.from(),i/(double)(path.size()-1),taper,mouths);
                var segment=new ProjectedSegment(edge.from(),i,path.size(),a,b,taper);
                double margin=Math.max(w0,w1)*1.035+1;
                int x0=(int)Math.floor((Math.min(a.x,b.x)-margin)/bin),x1=(int)Math.floor((Math.max(a.x,b.x)+margin)/bin);
                int z0=(int)Math.floor((Math.min(a.z,b.z)-margin)/bin),z1=(int)Math.floor((Math.max(a.z,b.z)+margin)/bin);
                for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++)index.computeIfAbsent(((long)x<<32)^(z&0xFFFFFFFFL),ignored->new ArrayList<>()).add(segment);
            }
        }
        // Column projection visits drainage IDs in ascending node order, then segments.
        index.values().forEach(segments->segments.sort(Comparator.comparingInt(ProjectedSegment::from).thenComparingInt(ProjectedSegment::index)));
        for(var edge:ordered)if(retained.contains(edge.from())){
            ProjectedWater previous=null;var path=made.get(edge.from());
            int lastX=0,lastZ=0;boolean sampledColumn=false;
            for(int i=1;i<path.size();i++){
                // New dense tributaries also validate rounded interior columns; an endpoint-only
                // sweep can miss a one-block rise where curves fold or cross.
                var a=path.get(i-1);var b=path.get(i);
                double length=Math.hypot(b.x-a.x,b.z-a.z);
                int count=landscapeMorphology?Math.max(2,2*(int)Math.ceil(length))
                        :Math.max(1,(int)Math.ceil(length/2))*(denseProjection?2:1);
                for(int n=0;n<=count;n++){
                    int x=(int)Math.round(a.x+(b.x-a.x)*n/count),z=(int)Math.round(a.z+(b.z-a.z)*n/count);
                    if(landscapeMorphology){
                        // The frozen index selects the same winner at the same column.
                        // Preserve every distinct-column transition, including segment
                        // joins, without repeating arbitration for half-block duplicates.
                        if(sampledColumn&&x==lastX&&z==lastZ)continue;
                        lastX=x;lastZ=z;sampledColumn=true;
                    }
                    double score=Double.POSITIVE_INFINITY;ProjectedWater current=null;
                    var nearby=index.get(((long)Math.floorDiv(x,bin)<<32)^(Math.floorDiv(z,bin)&0xFFFFFFFFL));
                    if(nearby==null)continue;
                    for(var segment:nearby){
                        var c=segment.start;var d=segment.end;double dx=d.x-c.x,dz=d.z-c.z,len2=dx*dx+dz*dz;
                        if(len2<1E-8)continue;
                        double t=Math.clamp(((x-c.x)*dx+(z-c.z)*dz)/len2,0,1),px=c.x+dx*t,pz=c.z+dz*t;
                        double progress=(segment.index-1+t)/(segment.size-1);
                        double width=projectedWidth(segment.from,progress,segment.taper,mouths)
                                *(naturalGeometry?1:1+.035*Math.sin(px*.017+pz*.011));
                        width=capWidth(mouths,lookupKey(segment.from),progress,x,z,px,pz,dx,dz,width);
                        double distance=Math.hypot(x-px,z-pz);if(distance>width)continue;
                        double candidate=distance/width-2;
                        if(candidate<score){score=candidate;current=new ProjectedWater(segment.from,c.water+(d.water-c.water)*t);}
                    }
                    if(current==null)continue;
                    // Shorter mountain masks can put a steep native crest between
                    // routing vertices. Validate actual rounded runtime columns after
                    // nearest-channel arbitration, before publishing the wet network.
                    if(compactMountainSupport&&ground.applyAsDouble(x,z)-current.water>MAX_INCISION+1E-8)
                        return current.from;
                    if(previous!=null&&Math.floor(current.water)>Math.floor(previous.water)){
                        // Prune the smallest participating branch; its complete upstream closure
                        // cannot remain wet after losing the only accepted route to the ocean.
                        int remove=edge.from();
                        if(plan.area(current.from)<plan.area(remove))remove=current.from;
                        if(plan.area(previous.from)<plan.area(remove))remove=previous.from;
                        return remove;
                    }
                    previous=current;
                }
            }
        }
        return -1;
    }

    private boolean ownedSegment(int from,int index,int size,Vertex a,Vertex b,double taper,
            BiPredicate<Integer,Integer> owned,Map<Integer,SourceMouthProfile> mouths){
        double dx=b.x-a.x,dz=b.z-a.z,length=Math.hypot(dx,dz);
        if(length<1E-8)return owned.test((int)Math.round(a.x),(int)Math.round(a.z));
        int count=landscapeMorphology?Math.max(2,2*(int)Math.ceil(length)):2*Math.max(1,(int)Math.ceil(length/2));
        for(int n=0;n<=count;n++){
            double t=n/(double)count,x=a.x+dx*t,z=a.z+dz*t;
            double width=projectedWidth(from,(index-1+t)/(size-1),taper,mouths);
            double bankX=-dz/length*width,bankZ=dx/length*width;
            if(!owned.test((int)Math.round(x),(int)Math.round(z))
                    ||!owned.test((int)Math.round(x+bankX),(int)Math.round(z+bankZ))
                    ||!owned.test((int)Math.round(x-bankX),(int)Math.round(z-bankZ)))return false;
        }
        return true;
    }
    private double projectedWidth(int from,double progress,double taper){
        return projectedWidth(from,progress,taper,Map.of());
    }
    private double projectedWidth(int from,double progress,double taper,Map<Integer,SourceMouthProfile> mouths){
        Integer key=lookupKey(from);
        double width=candidateWidth(from,progress);
        SourceMouthProfile mouth=mouths.get(key);
        if(mouth!=null)return mouth.width(width,Math.max(0,progress)*taper);
        return taper==0?width:taperedWidth(width,progress,taper);
    }

    private boolean ownedSourceCap(int from,List<Vertex> path,BiPredicate<Integer,Integer> owned,
            Map<Integer,SourceMouthProfile> mouths){
        Vertex center=path.getFirst(),after=path.get(1);
        double radius=mouths.containsKey(from)?mouths.get(from).radius():taperedWidth(candidateWidth(from,0),0,80),radius2=radius*radius;
        for(int z=(int)Math.floor(center.z-radius);z<=Math.ceil(center.z+radius);z++)
            for(int x=(int)Math.floor(center.x-radius);x<=Math.ceil(center.x+radius);x++)
                if((x-center.x)*(x-center.x)+(z-center.z)*(z-center.z)<=radius2){
                    double width=capWidth(mouths,lookupKey(from),0,x,z,center.x,center.z,after.x-center.x,after.z-center.z,radius);
                    if((x-center.x)*(x-center.x)+(z-center.z)*(z-center.z)<=width*width&&!owned.test(x,z))return false;
                }
        return true;
    }

    private List<Position> refined(WatershedPlan.Edge edge, TerrainRiverRoute.Corridor corridor,
            DoubleBinaryOperator ground, double receiverWater, double sea, boolean source) {
        List<TerrainRiverRoute.Point> geometry = corridor.original();
        double length = 0;
        for (int i = 1; i < geometry.size(); i++) length += Math.hypot(geometry.get(i).x() - geometry.get(i - 1).x(),
                geometry.get(i).z() - geometry.get(i - 1).z());
        final double taperLength = Math.max(80, length);
        return corridor.refine(ground, progress -> {
            double width = candidateWidth(edge.from(), progress);
            return source ? taperedWidth(width,progress,taperLength) : width;
        }, receiverWater, sea).stream().map(p -> new Position(p.x(), p.z())).toList();
    }

    /** Solve only retained routes. Every trial uses local arrays and publishes atomically. */
    private Map<Integer, List<Vertex>> solveRetained(List<WatershedPlan.Edge> ordered, Set<Integer> retained,
            Map<Integer, SupportedPath> support, Map<Integer, SupportedPath> sourceSupport, double sea,
            RetainedSolveWorkspace workspace) {
        workspace.reset();
        for (var edge : ordered) if (retained.contains(edge.from())) workspace.receivesWet(edge.to());
        for (var edge : ordered) if (retained.contains(edge.from()))
            workspace.active[edge.from()] = (workspace.hasWetIncoming(edge.from()) ? support : sourceSupport).get(edge.from());
        for (var edge : ordered) if (retained.contains(edge.from())) {
            SupportedPath path = workspace.active[edge.from()];
            if (plan.ocean(edge.to())) workspace.water(edge.to(), sea);
            workspace.water(edge.from(), Math.min(workspace.water(edge.from()), path.startLimit));
            workspace.water(edge.to(), Math.min(workspace.water(edge.to()), path.endLimit));
        }
        for (int n = ordered.size() - 1; n >= 0; n--) {
            var edge = ordered.get(n);
            if (!retained.contains(edge.from())) continue;
            double cap = Math.min(workspace.water(edge.from()), workspace.active[edge.from()].minimumLimit);
            workspace.water(edge.to(), Math.min(workspace.water(edge.to()), cap));
        }
        Map<Integer, List<Vertex>> made = new HashMap<>();
        for (var edge : ordered) if (retained.contains(edge.from())) {
            SupportedPath supported = workspace.active[edge.from()];
            double sourceWater = workspace.water(edge.from()), receiverWater = workspace.water(edge.to());
            long sourceBits = Double.doubleToRawLongBits(sourceWater);
            long receiverBits = Double.doubleToRawLongBits(receiverWater), seaBits = Double.doubleToRawLongBits(sea);
            RetainedPathSolution cached = workspace.solutions[edge.from()];
            List<Vertex> path;
            if (cached != null && cached.support == supported && cached.source == sourceBits
                    && cached.receiver == receiverBits && cached.sea == seaBits) path = cached.result;
            else {
                // A path's bounds and endpoint levels fully determine its pure water solve.
                // Reuse only an exact match; rejected trials may safely leave cached failures.
                path = supportedWater(edge, supported, sourceWater, receiverWater, sea);
                workspace.solutions[edge.from()] = new RetainedPathSolution(supported, sourceBits, receiverBits, seaBits, path);
            }
            if (path == null) return null;
            workspace.water(edge.from(), path.getFirst().water);
            made.put(edge.from(), path);
        }
        return made;
    }

    private SupportedPath supported(WatershedPlan.Edge edge, List<Position> points,
            DoubleBinaryOperator groundHeight, double sea, boolean tapered) {
        int count = points.size();
        double[] limits = new double[count], ground = new double[count], distance = new double[count];
        for (int i = 1; i < count; i++) distance[i] = distance[i - 1] + distance(points.get(i - 1), points.get(i));
        double taperLength = Math.max(80, distance[count - 1]);
        for (int i = 0; i < count; i++) {
            Position p = points.get(i);
            Position tangent = direction(points.get(Math.max(0, i - 1)), points.get(Math.min(count - 1, i + 1)));
            double progress = i / (double)(count - 1);
            double width = candidateWidth(edge.from(), progress);
            if (tapered) width = taperedWidth(width,progress,taperLength);
            ground[i] = finiteGround(groundHeight, p);
            double limit = ground[i] - 1;
            for (double side : BANK_OFFSETS) {
                double offset = width * side;
                limit = Math.min(limit, finiteGround(groundHeight,
                        new Position(p.x - tangent.z * offset, p.z + tangent.x * offset)) - 1);
            }
            limits[i] = Math.max(sea, limit);
        }
        // Projection clamps to the first vertex and therefore exposes a round wet
        // source cap, including columns behind its tangent. Screen that entire small
        // footprint once, with the same integer sampling used by runtime columns.
        double capRequired=Double.NEGATIVE_INFINITY;
        if(landscapeMorphology&&tapered){
            Position center=points.getFirst();double radius=taperedWidth(candidateWidth(edge.from(),0),0,taperLength),radius2=radius*radius;
            for(int z=(int)Math.floor(center.z-radius);z<=Math.ceil(center.z+radius);z++)
                for(int x=(int)Math.floor(center.x-radius);x<=Math.ceil(center.x+radius);x++){
                    if((x-center.x)*(x-center.x)+(z-center.z)*(z-center.z)>radius2)continue;
                    double host=finiteGround(groundHeight,new Position(x,z));
                    limits[0]=Math.min(limits[0],Math.max(sea,host-1));
                    capRequired=Math.max(capRequired,host-MAX_INCISION);
                }
        }
        double length = distance[count - 1];
        double start = Math.min(length * .25, (tapered ? (landscapeMorphology?3.5:1.5) : candidateWidth(edge.from(), 0)) * 2.3 + 4);
        double end = Math.min(length * .25, candidateWidth(edge.from(), 1) * 2.3 + 4);
        double startLimit = Double.POSITIVE_INFINITY, endLimit = Double.POSITIVE_INFINITY;
        double minimumLimit = Double.POSITIVE_INFINITY;
        // These path-only reductions are reused by every retained-network trial.
        // Keep point order and Math.min, including its NaN and signed-zero behavior.
        for (int i = 0; i < count; i++) {
            if (distance[i] <= start) startLimit = Math.min(startLimit, limits[i]);
            if (length - distance[i] <= end) endLimit = Math.min(endLimit, limits[i]);
            minimumLimit = Math.min(minimumLimit, limits[i]);
        }
        double[] grades = naturalGeometry ? new double[count - 1] : null;
        if (naturalGeometry) for (int i = 0; i < grades.length; i++)
            grades[i] = localGrade(points, ground, distance, i);
        return new SupportedPath(points, limits, ground, distance, tapered, start, end,
                startLimit, endLimit, minimumLimit, grades,capRequired);
    }

    private List<Vertex> supportedWater(WatershedPlan.Edge edge, SupportedPath path,
            double sourceCap, double receiverWater, double sea) {
        int count = path.points.size();
        double length = path.distance[count - 1];
        double startCollar = path.startCollar;
        double endCollar = path.endCollar;
        double[] upper = new double[count];
        upper[count - 1] = receiverWater;
        for (int i = count - 1; i >= 0; i--) {
            double support = path.limits[i];
            if (length - path.distance[i] <= endCollar) support = Math.min(support, receiverWater);
            // Highest terrain-supported envelope that can still descend into the receiver.
            // This postpones a drop until needed instead of cutting a low straight grade
            // through the entire preceding reach.
            upper[i] = i == count - 1 ? Math.min(support, receiverWater)
                    : Math.min(support, upper[i + 1] + (naturalGeometry ? path.grades[i] : .35)
                            * (path.distance[i + 1] - path.distance[i]));
            if (upper[i] < receiverWater - 1E-8) return null;
        }
        double current = Math.min(upper[0], sourceCap);
        for (int i = 0; i < count && path.distance[i] <= startCollar; i++) current = Math.min(current, upper[i]);
        if(current<path.capRequired-1E-8)return null;
        List<Vertex> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            current = Math.min(current, upper[i]);
            if (path.ground[i] - current > MAX_INCISION + 1E-8 || current < sea - 1E-8) return null;
            if (i == count - 1 && Math.abs(current - receiverWater) > 1E-8) return null;
            Position p = path.points.get(i);
            result.add(new Vertex(p.x, p.z, current));
        }
        return List.copyOf(result);
    }

    private double candidateWidth(int from, double progress) {
        if (naturalGeometry) return interpolate(widthProfiles[from].width, progress);
        var edge = edges.get(lookupKey(from));
        double t = smooth(Math.clamp(progress, 0, 1));
        double area = plan.area(from) + (plan.area(edge.to()) - plan.area(from)) * t;
        if (physicalCatchments) area = physicalArea(area);
        return Math.min(18, 4 + Math.sqrt(area) * .52);
    }

    private double dryWidth(int from, double progress) {
        var edge = edges.get(lookupKey(from));
        double t = smooth(Math.clamp(progress, 0, 1));
        double area = physicalArea(plan.area(from) + (plan.area(edge.to()) - plan.area(from)) * t);
        return 2.2 + Math.min(3, Math.sqrt(area) * .3);
    }

    private double taperedWidth(double width, double progress, double length) {
        double source=landscapeMorphology?3.5:1.5;
        return source + (width - source) * smooth(Math.clamp(progress * length / 80, 0, 1));
    }

    private WidthProfile profile(WatershedPlan.Edge edge, List<Position> points, DoubleBinaryOperator ground) {
        // Width controls need at most eight-block intervals; dense two-block flank
        // validation below still constrains every published water vertex.
        int count = Math.max(2, (points.size() + 2) / 4 + 1);
        double[] widths = new double[count], depths = new double[count];
        for (int i = 0; i < count; i++) {
            double progress = i / (double)(count - 1);
            double position = progress * (points.size() - 1);
            int before = Math.min(points.size() - 2, (int)position);
            double blend = position - before;
            Position a = points.get(before), b = points.get(before + 1);
            Position p = i == 0 ? a : i == count - 1 ? b
                    : new Position(a.x + (b.x - a.x) * blend, a.z + (b.z - a.z) * blend);
            double t = smooth(progress);
            double area = physicalArea(plan.area(edge.from()) + (plan.area(edge.to()) - plan.area(edge.from())) * t);
            double host = finiteGround(ground, p), relief = 0;
            // An orientation-independent sample gives every tributary the exact same
            // confinement at its common node, even when its approach tangent differs.
            for (int side = 0; side < 8; side++) {
                double angle = side * Math.PI / 4;
                relief = Math.max(relief, finiteGround(ground,
                        new Position(p.x + Math.cos(angle) * 28, p.z + Math.sin(angle) * 28)) - host);
            }
            double confined = Math.clamp(relief / 18, 0, 1);
            double spatial = noise(geometrySeed ^ 0x57494454484CL, p.x / 145, p.z / 145) * .55
                    + noise(geometrySeed ^ 0x574944544853L, p.x / 62, p.z / 62) * .30
                    + noise(geometrySeed ^ 0x574944544842L, p.x / 340, p.z / 340) * .15;
            // Smooth asymptotic discharge growth avoids a population piled against a cap.
            // Bounds: base <20, spatial <=1.28, confinement <=1.05: width <26.88.
            double base = 4.5 + 15.5 * -Math.expm1(-Math.sqrt(area) / 18);
            widths[i] = base * (.96 + .32 * spatial) * (1.05 - .35 * confined);
            if(landscapeMorphology)widths[i]=Math.max(3.5,widths[i]);
            double resistance = .5 + .5 * noise(geometrySeed ^ 0x44455054484CL, p.x / 116, p.z / 116);
            depths[i] = landscapeMorphology ? .72 + .80 * resistance + .30 * confined
                    : .72 + .46 * resistance + .22 * confined;
        }
        return new WidthProfile(widths, depths);
    }

    private double localGrade(List<Position> points, double[] ground, double[] distance, int index) {
        int before = Math.max(0, index - 6), after = Math.min(points.size() - 1, index + 7);
        double nativeGrade = Math.abs(ground[after] - ground[before])
                / Math.max(1, distance[after] - distance[before]);
        Position p = points.get(index);
        double resistance = .5 + .5 * noise(geometrySeed ^ 0x47524144454CL, p.x / 93, p.z / 93);
        return (.17 + .18 * Math.clamp(nativeGrade / .28, 0, 1)) * (.80 + .20 * resistance);
    }

    private static double interpolate(double[] values, double progress) {
        if (progress <= 0) return values[0];
        if (progress >= 1) return values[values.length - 1];
        double position = Math.clamp(progress, 0, 1) * (values.length - 1);
        int before = Math.min(values.length - 2, (int)position);
        double t = position - before;
        return values[before] + (values[before + 1] - values[before]) * t;
    }
    /** Catchment in the original 128-block cell units, independent of routing resolution. */
    private double physicalArea(double area) { return area * plan.spacing() * plan.spacing() / (128. * 128.); }
    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    private static Position naturalJunction(Position center, long seed, DoubleBinaryOperator ground, double radius) {
        double probe = radius * .5;
        double gx = finiteGround(ground, new Position(center.x + probe, center.z))
                - finiteGround(ground, new Position(center.x - probe, center.z));
        double gz = finiteGround(ground, new Position(center.x, center.z + probe))
                - finiteGround(ground, new Position(center.x, center.z - probe));
        double rotation = Math.atan2(gz, gx) + noise(seed ^ 0x56414C4C4559L,
                center.x / 310, center.z / 310) * Math.PI / 12;
        Position best = center;
        double score = finiteGround(ground, center);
        for (int ring = 1; ring <= 3; ring++) for (int direction = 0; direction < 12; direction++) {
            double r = radius * ring / 3, angle = rotation + direction * Math.PI / 6;
            Position candidate = new Position(center.x + Math.cos(angle) * r, center.z + Math.sin(angle) * r);
            double candidateScore = finiteGround(ground, candidate) + 2 * r * r / (radius * radius);
            if (candidateScore < score) { score = candidateScore; best = candidate; }
        }
        return best;
    }

    private static Position lowerGroundPosition(Position center, long seed, DoubleBinaryOperator ground, double maximumRadius) {
        Position best = center;
        double score = finiteGround(ground, center);
        double rotation = unit(seed) * Math.PI * 2;
        for (int ring = 1; ring <= 3; ring++) {
            double radius = ring * maximumRadius / 3;
            for (int direction = 0; direction < 12; direction++) {
                double angle = rotation + direction * Math.PI / 6;
                Position candidate = new Position(center.x + Math.cos(angle) * radius,
                        center.z + Math.sin(angle) * radius);
                double candidateScore = finiteGround(ground, candidate) + 2 * radius * radius / (maximumRadius * maximumRadius);
                if (candidateScore < score) { score = candidateScore; best = candidate; }
            }
        }
        return best;
    }

    private static List<Position> curve(Position start, Position end, Position startTangent, Position endTangent,
            long seed, DoubleBinaryOperator ground) {
        Position chord = direction(start, end);
        double length = distance(start, end);
        double h0 = length * handle(startTangent, chord);
        double h1 = length * handle(endTangent, chord);
        double slope = Math.abs(finiteGround(ground, start) - finiteGround(ground, end)) / length;
        double amplitude = Math.min(16, length * .09) / (1 + slope * 9);
        int steps = Math.max(8, (int) Math.ceil(length / 2));
        List<Position> points = new ArrayList<>();
        points.add(start);
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps, t2 = t * t, t3 = t2 * t;
            double x = (2 * t3 - 3 * t2 + 1) * start.x + (t3 - 2 * t2 + t) * startTangent.x * h0
                    + (-2 * t3 + 3 * t2) * end.x + (t3 - t2) * endTangent.x * h1;
            double z = (2 * t3 - 3 * t2 + 1) * start.z + (t3 - 2 * t2 + t) * startTangent.z * h0
                    + (-2 * t3 + 3 * t2) * end.z + (t3 - t2) * endTangent.z * h1;
            double noise = noise(seed, x / 190, z / 190) * .72 + noise(seed + 1, x / 65, z / 65) * .28;
            double taper = Math.sin(Math.PI * t);
            double bend = noise * amplitude * taper * taper;
            Position point = i == steps ? end : new Position(x - chord.z * bend, z + chord.x * bend);
            Position previous = points.getLast();
            int divisions = Math.max(1, (int) Math.ceil(distance(previous, point) / 2));
            for (int j = 1; j <= divisions; j++) {
                double u = j / (double) divisions;
                points.add(j == divisions ? point : new Position(previous.x + (point.x - previous.x) * u,
                        previous.z + (point.z - previous.z) * u));
            }
        }
        return List.copyOf(points);
    }

    private static List<Position> naturalCurve(Position start, Position end, Position startTangent,
            Position endTangent, long seed, DoubleBinaryOperator ground) {
        Position chord = direction(start, end);
        double length = distance(start, end);
        // Near-unit handles continue a valley's direction across drainage nodes. The old
        // short handles repeatedly relaxed toward the D8 chord in the middle of each edge.
        double regional = .94 + .10 * noise(seed ^ 0x43555256454CL,
                (start.x + end.x) / 960, (start.z + end.z) / 960);
        double h0 = length * regional * naturalHandle(startTangent, chord);
        double h1 = length * regional * naturalHandle(endTangent, chord);
        double startGradient = crossGround(ground, start, chord);
        double endGradient = crossGround(ground, end, chord);
        int steps = Math.max(8, (int)Math.ceil(length / 2));
        List<Position> points = new ArrayList<>();
        points.add(start);
        for (int i = 1; i <= steps; i++) {
            double t = i / (double)steps, t2 = t * t, t3 = t2 * t;
            double x = (2*t3-3*t2+1)*start.x + (t3-2*t2+t)*startTangent.x*h0
                    + (-2*t3+3*t2)*end.x + (t3-t2)*endTangent.x*h1;
            double z = (2*t3-3*t2+1)*start.z + (t3-2*t2+t)*startTangent.z*h0
                    + (-2*t3+3*t2)*end.z + (t3-t2)*endTangent.z*h1;
            Position raw = new Position(x,z);
            // Only a departure from the shared large-scale valley gradient bends the
            // interior. A planar hillside therefore gains no repeated per-edge wiggle.
            double residual = crossGround(ground, raw, chord) - (startGradient * (1-t) + endGradient*t);
            double shift = Math.clamp(-residual * .8, -16, 16) * 16*t2*(1-t)*(1-t);
            Position point = i == steps ? end : new Position(x - chord.z*shift, z + chord.x*shift);
            Position previous = points.getLast();
            int divisions = Math.max(1, (int)Math.ceil(distance(previous, point) / 2));
            for (int j = 1; j <= divisions; j++) {
                double u = j / (double)divisions;
                points.add(j == divisions ? point : new Position(previous.x + (point.x-previous.x)*u,
                        previous.z + (point.z-previous.z)*u));
            }
        }
        return List.copyOf(points);
    }

    private static double crossGround(DoubleBinaryOperator ground, Position p, Position chord) {
        return finiteGround(ground, new Position(p.x-chord.z*18,p.z+chord.x*18))
                - finiteGround(ground, new Position(p.x+chord.z*18,p.z-chord.x*18));
    }

    private static double naturalHandle(Position tangent, Position chord) {
        return Math.clamp((tangent.x*chord.x+tangent.z*chord.z+.25)/1.25,.20,1);
    }

    private static double handle(Position tangent, Position chord) {
        return .34 * Math.clamp((tangent.x * chord.x + tangent.z * chord.z + .2) / 1.2, .15, 1);
    }
    private static Position direction(Position a, Position b) {
        Position normalized = normalize(b.x - a.x, b.z - a.z);
        return normalized == null ? new Position(1, 0) : normalized;
    }
    private static Position normalize(double x, double z) {
        double length = Math.hypot(x, z);
        return length < 1E-8 ? null : new Position(x / length, z / length);
    }
    private static double distance(Position a, Position b) { return Math.hypot(a.x - b.x, a.z - b.z); }
    private static double finiteGround(DoubleBinaryOperator ground, Position point) {
        double height = ground.applyAsDouble(point.x, point.z);
        if (!Double.isFinite(height)) throw new IllegalArgumentException("Non-finite path ground");
        return height;
    }
    private static double noise(long seed, double x, double z) {
        long ix = (long) Math.floor(x), iz = (long) Math.floor(z);
        double tx = x - ix, tz = z - iz;
        tx = tx * tx * (3 - 2 * tx); tz = tz * tz * (3 - 2 * tz);
        double a = unit(seed ^ mix(ix) ^ mix(iz + 0x1234ABCDL));
        double b = unit(seed ^ mix(ix + 1) ^ mix(iz + 0x1234ABCDL));
        double c = unit(seed ^ mix(ix) ^ mix(iz + 1 + 0x1234ABCDL));
        double d = unit(seed ^ mix(ix + 1) ^ mix(iz + 1 + 0x1234ABCDL));
        return ((a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * tz) * 2 - 1;
    }
    private static double unit(long value) { return (mix(value) >>> 11) * 0x1.0p-53; }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
