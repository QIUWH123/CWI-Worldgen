package net.quiwh.cwiworldgen.search;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.quiwh.cwiworldgen.landscape.LandscapeSearch;
import net.quiwh.cwiworldgen.mixin.StructureSearchChunkAccess;
import net.quiwh.cwiworldgen.mixin.StructureSearchManagerAccess;
import net.quiwh.cwiworldgen.mixin.StructureSearchPresenceAccess;
import net.quiwh.cwiworldgen.mixin.StructureSearchStateAccess;

/** Resumable native placement/presence search, owned exclusively by the server thread. */
public final class StructureSearch {
    private static final int MAX_RADIUS = LandscapeSearch.MAX_RADIUS;
    private static final int MAX_COORDINATE = 29_999_984;
    private static final TicketType<Long> SEARCH_TICKET = TicketType.create("cwi_structure_search", Long::compareTo);
    private static final AtomicLong NEXT_TICKET = new AtomicLong();
    private static final int TICKET_LEVEL = ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS);

    private StructureSearch() {}

    public static Query create(ServerLevel level, HolderSet<Structure> targets, BlockPos origin,
            int radius, BooleanSupplier cancelled) {
        return new Query(level, targets, origin, radius, cancelled);
    }

    public static final class Query {
        private final ServerLevel level;
        private final BooleanSupplier cancellation;
        private final CandidateQueue candidates;
        private final long ticketId = NEXT_TICKET.incrementAndGet();
        private ChunkPos ticket;
        private final List<RingPlan> rings = new ArrayList<>();
        private int ringIndex, ringPosition, holderIndex;
        private boolean done, cancelled;
        private long samples;
        private Candidate current;
        private ChunkAccess chunk;
        private CompletableFuture<ChunkResult<ChunkAccess>> pending;
        private Pair<BlockPos, Holder<Structure>> result;

        private Query(ServerLevel level, HolderSet<Structure> targets, BlockPos origin,
                int radius, BooleanSupplier cancellation) {
            this.level = Objects.requireNonNull(level);
            this.cancellation = Objects.requireNonNull(cancellation);
            checkThread();
            var state = level.getChunkSource().getGeneratorState();
            this.candidates = new CandidateQueue(origin, radius, state.getLevelSeed());
            Map<StructurePlacement, List<Holder<Structure>>> placements = new LinkedHashMap<>();
            for (Holder<Structure> structure : targets) {
                for (StructurePlacement placement : state.getPlacementsForStructure(structure)) {
                    if (placement.getClass() != RandomSpreadStructurePlacement.class
                            && placement.getClass() != ConcentricRingsStructurePlacement.class)
                        throw new IllegalArgumentException("Unsupported structure placement: " + placement.getClass().getSimpleName());
                    var group = placements.computeIfAbsent(placement, ignored -> new ArrayList<>());
                    if (!group.contains(structure)) group.add(structure);
                }
            }
            for (var entry : placements.entrySet()) {
                var placement = entry.getKey();
                var structures = List.copyOf(entry.getValue());
                if (placement instanceof RandomSpreadStructurePlacement spread) {
                    candidates.addRandomSpread(spread, structures);
                } else if (placement instanceof ConcentricRingsStructurePlacement concentric) {
                    var positions = ((StructureSearchStateAccess) (Object) state).cwiWorldgen$ringPositions().get(concentric);
                    if (positions == null) throw new IllegalStateException("Missing native concentric structure positions");
                    rings.add(new RingPlan(concentric, structures, positions));
                }
            }
            this.done = rings.isEmpty() && candidates.done();
        }

        /** A metadata cell, presence check or completed chunk read consumes one step. */
        public boolean step(int maxSamples, long maxNanos) {
            checkThread();
            if (maxSamples < 1 || maxNanos < 1) throw new IllegalArgumentException("Invalid structure search budget");
            if (done) return true;
            long started = System.nanoTime();
            for (int work = 0; work < maxSamples; work++) {
                if (cancellation.getAsBoolean()) {
                    cancelled = true;
                    done = true;
                    close();
                    return true;
                }
                if (System.nanoTime() - started >= maxNanos) return false;
                if (ringIndex < rings.size()) {
                    RingPlan ring = rings.get(ringIndex);
                    if (!ring.positions.isDone()) return false;
                    // getNow propagates native failures instead of converting them into cached absence.
                    List<ChunkPos> positions = ring.positions.getNow(null);
                    if (positions == null) throw new IllegalStateException("Native structure ring positions are unavailable");
                    if (ringPosition < positions.size()) {
                        candidates.add(ring.placement, ring.structures, positions.get(ringPosition++));
                    } else {
                        ringIndex++;
                        ringPosition = 0;
                    }
                    continue;
                }
                if (pending != null) {
                    if (!pending.isDone()) return false;
                    ChunkResult<ChunkAccess> loaded = pending.getNow(null);
                    pending = null;
                    if (loaded == null || !loaded.isSuccess())
                        throw new IllegalStateException("Could not inspect structure candidate chunk"
                                + (loaded == null ? "" : ": " + loaded.getError()));
                    chunk = loaded.orElse(null);
                    if (chunk == null) throw new IllegalStateException("Native structure chunk request returned no chunk");
                    continue;
                }
                if (current == null) {
                    current = candidates.advance();
                    if (current == null) {
                        if (candidates.done()) { done = true; return true; }
                        continue;
                    }
                    samples++;
                    holderIndex = 0;
                    chunk = null;
                }
                if (holderIndex >= current.structures.size()) {
                    releaseTicket();
                    current = null;
                    chunk = null;
                    continue;
                }
                Holder<Structure> holder = current.structures.get(holderIndex);
                var manager = level.structureManager();
                if (chunk != null) {
                    var start = manager.getStartForStructure(SectionPos.bottomOf(chunk), holder.value(), chunk);
                    holderIndex++;
                    if (start != null && start.isValid()) {
                        BlockPos position = current.placement.getLocatePos(start.getChunkPos());
                        if (candidates.contains(position)) return finish(position, holder);
                    }
                    continue;
                }
                var nativeCheck = ((StructureSearchManagerAccess) (Object) manager).cwiWorldgen$structureCheck();
                var cache = (StructureSearchPresenceAccess) (Object) nativeCheck;
                var loaded = cache.cwiWorldgen$loadedStructures().get(current.chunk.toLong());
                if (loaded != null) {
                    holderIndex++;
                    if (loaded.getOrDefault(holder.value(), -1) != -1) return finish(current.position, holder);
                    continue;
                }
                // Native checkStructurePresence joins a disk scan for uncached chunks. Request
                // the native chunk pipeline directly instead; it loads or creates starts asynchronously.
                // Do not reject unknown saved starts using current frequency/biome policy: native
                // presence checks consult disk before those tests, preserving older saved starts.
                // The public getChunkFuture also waits when called on this thread.
                ticket = current.chunk;
                level.getChunkSource().chunkMap.getDistanceManager().addTicket(SEARCH_TICKET, ticket, TICKET_LEVEL, ticketId);
                pending = ((StructureSearchChunkAccess) (Object) level.getChunkSource())
                        .cwiWorldgen$requestStructureChunk(current.chunk.x, current.chunk.z, ChunkStatus.STRUCTURE_STARTS, true);
                return false;
            }
            return done;
        }

        public boolean done() { return done; }
        public boolean cancelled() { return cancelled; }
        public Pair<BlockPos, Holder<Structure>> result() { return result; }
        public long samples() { return samples; }

        /** Retire promptly on unload/logout/reload as well as after a result or failure. */
        public void close() {
            checkThread();
            releaseTicket();
            pending = null; // Never cancel a shared native chunk future.
            current = null;
            chunk = null;
            candidates.clear();
            rings.clear();
            done = true;
        }

        private void releaseTicket() {
            if (ticket != null) {
                level.getChunkSource().chunkMap.getDistanceManager().removeTicket(SEARCH_TICKET, ticket, TICKET_LEVEL, ticketId);
                ticket = null;
            }
        }

        private boolean finish(BlockPos position, Holder<Structure> holder) {
            result = Pair.of(position.immutable(), holder);
            close();
            return true;
        }

        private void checkThread() {
            if (!level.getServer().isSameThread())
                throw new IllegalStateException("Structure queries must run on the server thread");
        }
    }

    private record RingPlan(ConcentricRingsStructurePlacement placement, List<Holder<Structure>> structures,
            CompletableFuture<List<ChunkPos>> positions) {}

    record Candidate(StructurePlacement placement, List<Holder<Structure>> structures,
            ChunkPos chunk, BlockPos position, long distance, long order) {}

    /**
     * Cells are consumed by their closest possible location. Only the active row frontiers and
     * candidates awaiting that lower bound are retained; there is no visited-cell history.
     */
    static final class CandidateQueue {
        private final BlockPos origin;
        private final int radius;
        private final long radiusSquared, seed;
        private long order;
        private final PriorityQueue<CellRow> rows = new PriorityQueue<>(Comparator
                .comparingLong(CellRow::lowerBound).thenComparingInt(row -> row.z)
                .thenComparingInt(row -> row.x).thenComparingLong(row -> row.order));
        private final PriorityQueue<Candidate> ready = new PriorityQueue<>(Comparator
                .comparingLong(Candidate::distance).thenComparingInt(candidate -> candidate.position.getX())
                .thenComparingInt(candidate -> candidate.position.getZ()).thenComparingLong(Candidate::order));

        CandidateQueue(BlockPos origin, int radius, long seed) {
            if (radius < 0 || radius > MAX_RADIUS) throw new IllegalArgumentException("Invalid structure search radius");
            this.origin = Objects.requireNonNull(origin).immutable();
            this.radius = radius;
            this.radiusSquared = (long) radius * radius;
            this.seed = seed;
        }

        void addRandomSpread(RandomSpreadStructurePlacement placement, List<Holder<Structure>> structures) {
            if (placement.spacing() <= placement.separation()) throw new IllegalArgumentException("Invalid structure placement spacing");
            int span = placement.spacing() * 16;
            BlockPos offset = placement.getLocatePos(new ChunkPos(0, 0));
            int minX = (int) Math.floorDiv((long) origin.getX() - radius - offset.getX(), span) - 1;
            int maxX = (int) Math.floorDiv((long) origin.getX() + radius - offset.getX(), span) + 1;
            int minZ = (int) Math.floorDiv((long) origin.getZ() - radius - offset.getZ(), span) - 1;
            int maxZ = (int) Math.floorDiv((long) origin.getZ() + radius - offset.getZ(), span) + 1;
            int pivot = (int) Math.floorDiv((long) origin.getX() - offset.getX(), span);
            for (int z = minZ; z <= maxZ; z++) {
                int left = Math.min(maxX, pivot), right = Math.max(minX, pivot + 1);
                if (left >= minX) addRow(new CellRow(placement, structures, offset, left, z, -1, minX, maxX, order++));
                if (right <= maxX) addRow(new CellRow(placement, structures, offset, right, z, 1, minX, maxX, order++));
            }
        }

        void add(StructurePlacement placement, List<Holder<Structure>> structures, ChunkPos chunk) {
            BlockPos position = placement.getLocatePos(chunk);
            if (contains(position)) ready.add(new Candidate(placement, structures, chunk, position,
                    distance(position), order++));
        }

        /** Return the next exact-distance candidate, or advance one cell of placement metadata. */
        Candidate advance() {
            if (!ready.isEmpty() && (rows.isEmpty() || ready.peek().distance <= rows.peek().lowerBound()))
                return ready.remove();
            if (rows.isEmpty()) return null;
            CellRow row = rows.remove();
            int chunkX = Math.multiplyExact(row.x, row.placement.spacing());
            int chunkZ = Math.multiplyExact(row.z, row.placement.spacing());
            ChunkPos chunk = row.placement.getPotentialStructureChunk(seed, chunkX, chunkZ);
            add(row.placement, row.structures, chunk);
            row.x += row.direction;
            if (row.x >= row.minX && row.x <= row.maxX) addRow(row);
            return null;
        }

        boolean done() { return rows.isEmpty() && ready.isEmpty(); }
        void clear() { rows.clear(); ready.clear(); }

        boolean contains(BlockPos position) {
            return Math.abs((long) position.getX()) <= MAX_COORDINATE
                    && Math.abs((long) position.getZ()) <= MAX_COORDINATE && distance(position) <= radiusSquared;
        }

        private long distance(BlockPos position) {
            long x = (long) position.getX() - origin.getX(), z = (long) position.getZ() - origin.getZ();
            return x * x + z * z;
        }

        private void addRow(CellRow row) {
            if (row.lowerBound() <= radiusSquared) rows.add(row);
        }

        private final class CellRow {
            private final RandomSpreadStructurePlacement placement;
            private final List<Holder<Structure>> structures;
            private final BlockPos offset;
            private final int z, direction, minX, maxX;
            private final long order;
            private int x;

            private CellRow(RandomSpreadStructurePlacement placement, List<Holder<Structure>> structures,
                    BlockPos offset, int x, int z, int direction, int minX, int maxX, long order) {
                this.placement = placement;
                this.structures = structures;
                this.offset = offset;
                this.x = x; this.z = z; this.direction = direction; this.minX = minX; this.maxX = maxX;
                this.order = order;
            }

            private long lowerBound() {
                long span = placement.spacing() * 16L;
                long extent = (placement.spacing() - placement.separation() - 1) * 16L;
                long dx = gap(origin.getX(), x * span + offset.getX(), extent);
                long dz = gap(origin.getZ(), z * span + offset.getZ(), extent);
                return dx * dx + dz * dz;
            }

            private long gap(long value, long start, long extent) {
                return value < start ? start - value : Math.max(0, value - start - extent);
            }
        }
    }
}
