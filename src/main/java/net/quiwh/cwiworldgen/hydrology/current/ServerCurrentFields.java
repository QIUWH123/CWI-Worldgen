package net.quiwh.cwiworldgen.hydrology.current;

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.quiwh.cwiworldgen.config.CurrentConfig;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.neoforged.neoforge.network.PacketDistributor;

/** Tick-owned caches; pure cold preparation runs on a bounded dedicated worker. */
public final class ServerCurrentFields {
    private ServerCurrentFields() {}
    public static final int FIELD_LIMIT = 1024;
    public static final int WATCH_LIMIT = 8192;
    private static final Map<ServerLevel, WorldFields> WORLDS = new IdentityHashMap<>();
    private static final AtomicLong POLICY = new AtomicLong();
    // Never use WORLD_GEN here: tile generation itself submits there and waits.
    private static final ThreadPoolExecutor PREPARER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "CWI Current Preparation");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
    private static long nextRevision;
    private static int worldCursor;

    public static ReferenceTerrain terrain(ServerLevel level) {
        return ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
    }

    private static void publishReady(ServerLevel level, WorldFields world) {
        var result = world.work.poll();
        if (result == null || result.stale() || WORLDS.get(level) != world) return;
        long key = result.chunk();
        if (level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) == null) return;
        if (result.failure() != null) {
            net.quiwh.cwiworldgen.CwiWorldgen.LOGGER.warn("Current preparation failed in {}", new ChunkPos(key), result.failure());
            world.watchers.retryLater(key);
            return;
        }
        CurrentChunk generated = result.value();
        CurrentChunk field;
        try {
            field = CurrentOverrides.apply(level, new ChunkPos(key), generated);
        } catch (IllegalArgumentException overflow) {
            // Runtime composition can fail closed if data was authored under an older
            // generator. Keep natural currents rather than crash a tick or omit its bounds.
            net.quiwh.cwiworldgen.CwiWorldgen.LOGGER.warn("Current overlays rejected in {}: {}",
                    new ChunkPos(key), overflow.getMessage());
            field = generated;
        }
        field = strength(field, CurrentConfig.ENABLED.get() ? CurrentConfig.STRENGTH.get() : 0);
        world.fields.put(key, field);
        while (world.fields.size() > FIELD_LIMIT) world.fields.remove(world.fields.keySet().iterator().next());
        world.watchers.ready(key);
    }

    /** Motion queries are cache-only. Missing fields enqueue bounded work and grant no motion. */
    public static CurrentChunk fieldIfLoaded(ServerLevel level, int chunkX, int chunkZ) {
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) return null;
        ReferenceTerrain terrain = terrain(level);
        if (terrain == null || terrain.generationVersion() < 2) return null;
        var world = world(level);
        long key = ChunkPos.asLong(chunkX, chunkZ);
        CurrentChunk cached = world.fields.get(key);
        if (cached == null && !world.work.preparing(key)) world.pending.request(key);
        return cached;
    }

    /** Diagnostics may read metadata without enqueuing any terrain work. */
    public static CurrentChunk fieldIfPresent(ServerLevel level, int chunkX, int chunkZ) {
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) return null;
        WorldFields world = WORLDS.get(level);
        return world == null ? null : world.fields.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Main-thread metadata edits invalidate revisions and queue only already watched fields. */
    public static void invalidate(ServerLevel level) {
        WorldFields fields = WORLDS.get(level);
        if (fields == null) return;
        fields.policy = -1;
        refreshPolicy(level, fields);
    }

    public static CurrentChunk strength(CurrentChunk field, double multiplier) {
        long[][] layers = new long[field.layerCount()][CurrentChunk.COLUMNS];
        for (int layer = 0; layer < layers.length; layer++) for (int i = 0; i < CurrentChunk.COLUMNS; i++) {
            CurrentColumn c = CurrentColumn.unpack(field.packed(layer,i));
            if (!c.plannedWater()) continue;
            layers[layer][i] = (!Double.isFinite(multiplier) || multiplier <= 0 || !c.active()
                    ? CurrentColumn.still(c.minY(),c.maxY(),c.drop(),c.impactDirection())
                    : CurrentColumn.of(c.minY(),c.maxY(),c.directionX(),c.directionZ(),c.speed()*multiplier,c.drop(),c.impactDirection())).pack();
        }
        return new CurrentChunk(field.chunkPos(),layers);
    }

    public static void sent(ServerPlayer player, ServerLevel level, ChunkPos chunk) {
        ReferenceTerrain terrain = terrain(level);
        if (terrain == null || terrain.generationVersion() < 2) return;
        var world = world(level);
        var evicted = world.watchers.watch(player.getUUID(), chunk.toLong(), ++nextRevision);
        if (evicted != null) {
            var recipient = level.getServer().getPlayerList().getPlayer(evicted.player());
            if (recipient != null && recipient.serverLevel() == level) removePacket(recipient, level, evicted.chunk());
        }
    }

    public static void unwatched(ServerPlayer player, ServerLevel level, ChunkPos chunk) {
        WorldFields world = WORLDS.get(level);
        if (world == null) return;
        if (world.watchers.unwatch(player.getUUID(), chunk.toLong()) != null) {
            removePacket(player, level, chunk.toLong());
        }
    }

    private static void removePacket(ServerPlayer player, ServerLevel level, long key) {
        PacketDistributor.sendToPlayer(player, new CurrentFieldPayload(level.dimension().location(), 2, ++nextRevision, key, null));
    }

    public static void flush() {
        int budget = CurrentConfig.BUILDS_PER_TICK.get();
        int dispatchBudget = 64;
        var levels = new ArrayList<>(WORLDS.keySet());
        if (levels.isEmpty()) return;
        int start = Math.floorMod(worldCursor++, levels.size());
        for (int offset = 0; offset < levels.size(); offset++) {
            var level = levels.get((start + offset) % levels.size());
            var world = WORLDS.get(level);
            refreshPolicy(level, world);
            publishReady(level, world);
            while (dispatchBudget > 0 && (world.pending.size() > 0 || world.watchers.pending() > 0)) {
                dispatchBudget--;
                // Independent queues keep motion pressure from dropping client replays.
                // Alternate when both have work so neither path can starve the other.
                boolean watchTurn = world.watchers.pending() > 0 && (world.pending.size() == 0 || world.watchTurn);
                world.watchTurn = !world.watchTurn;
                var watch = watchTurn ? world.watchers.poll() : null;
                var request = watch == null ? world.pending.poll()
                        : new CurrentWorkQueue.Task(watch.player(), watch.chunk(), watch.revision());
                ServerPlayer player = null;
                if (request.player() != null) {
                    if (!world.watchers.current(watch)) continue;
                    player = level.getServer().getPlayerList().getPlayer(request.player());
                    if (player == null || player.serverLevel() != level) continue;
                }
                int x = ChunkPos.getX(request.chunk()), z = ChunkPos.getZ(request.chunk());
                if (level.getChunkSource().getChunkNow(x, z) == null) continue;
                CurrentChunk field = world.fields.get(request.chunk());
                if (field == null) {
                    if (!world.work.busy() && budget > 0) {
                        long key = request.chunk();
                        WorkerTerrain worker = world.worker;
                        if (world.work.start(key, () -> worker.build(key))) budget--;
                    }
                    if (!world.work.preparing(request.chunk())) {
                        if (watch != null) world.watchers.retry(watch);
                        else world.pending.request(request.chunk());
                        break;
                    }
                    // Completion replays every still-current watcher, including later arrivals.
                    continue;
                }
                if (player != null) PacketDistributor.sendToPlayer(player, new CurrentFieldPayload(level.dimension().location(), 2,
                        request.revision(), request.chunk(), field));
            }
        }
    }

    public static void invalidatePolicy() { POLICY.incrementAndGet(); }
    static long reserveRevision() { return ++nextRevision; }

    private static WorldFields world(ServerLevel level) {
        WorldFields world = WORLDS.computeIfAbsent(level,
                ignored -> new WorldFields(level.getSeed(), terrain(level).generationVersion()));
        refreshPolicy(level, world);
        return world;
    }

    private static void refreshPolicy(ServerLevel level, WorldFields world) {
        long policy = POLICY.get();
        if (world.policy == policy) return;
        world.policy = policy;
        world.fields.clear();
        world.work.invalidate();
        world.pending.clear();
        world.watchers.invalidate(() -> ++nextRevision);
    }

    public static void chunkUnloaded(ServerLevel level, ChunkPos pos) {
        WorldFields world = WORLDS.get(level);
        if (world != null) {
            world.fields.remove(pos.toLong());
            world.work.discard(pos.toLong());
        }
    }

    public static void playerLeft(UUID player) {
        for (WorldFields world : WORLDS.values()) {
            world.watchers.playerLeft(player);
            world.pending.playerLeft(player);
        }
    }
    public static void unloaded(ServerLevel level) {
        WorldFields world = WORLDS.remove(level);
        if (world != null) {
            world.work.invalidate();
            world.worker.retired = true;
        }
    }
    public static int cachedWorlds() { return WORLDS.size(); }
    public static int cachedFields(ServerLevel level) {
        WorldFields world = WORLDS.get(level);
        return world == null ? 0 : world.fields.size();
    }

    private static final class WorldFields {
        long policy = POLICY.get();
        final LinkedHashMap<Long, CurrentChunk> fields = new LinkedHashMap<>(16, .75F, true);
        boolean watchTurn = true;
        final DeferredCurrentWork<CurrentChunk> work = new DeferredCurrentWork<>(PREPARER);
        final WorkerTerrain worker;
        WorldFields(long seed, int generation) { worker = new WorkerTerrain(seed, generation); }
        // Global world limit, rather than a per-player limit feeding a smaller shared queue.
        final CurrentWatchQueue watchers = new CurrentWatchQueue(WATCH_LIMIT);
        final CurrentWorkQueue pending = new CurrentWorkQueue(WATCH_LIMIT);
    }

    /** The worker owns a separate terrain context, avoiding live worldgen cache locks. */
    private static final class WorkerTerrain {
        final long seed;
        final int generation;
        volatile boolean retired;
        private ReferenceTerrain terrain;
        WorkerTerrain(long seed, int generation) { this.seed = seed; this.generation = generation; }
        CurrentChunk build(long key) {
            try {
                if (retired) return new CurrentChunk(key, new long[CurrentChunk.COLUMNS]);
                if (terrain == null) terrain = new ReferenceTerrain(seed, generation);
                return CurrentFieldBuilder.build(terrain, new ChunkPos(key), 1);
            } finally {
                // A level unload never waits for a worker or takes one of its cache locks.
                // Idle retired contexts become unreachable and are collected normally.
                if (retired && terrain != null) { terrain.close(); terrain = null; }
            }
        }
    }
}
