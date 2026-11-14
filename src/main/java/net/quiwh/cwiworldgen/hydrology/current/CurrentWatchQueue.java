package net.quiwh.cwiworldgen.hydrology.current;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded subscriptions and deduplicated replay; every retained watcher keeps its newest revision. */
public final class CurrentWatchQueue {
    public record Watch(UUID player, long chunk, long revision) {}
    private record Key(UUID player, long chunk) {}
    private final int limit;
    private final LinkedHashMap<Key, Watch> subscriptions = new LinkedHashMap<>();
    private final LinkedHashMap<Key, Watch> pending = new LinkedHashMap<>();

    public CurrentWatchQueue(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid current watch limit");
        this.limit = limit;
    }

    /** Overflow returns an explicit eviction so the caller can remove that client's old metadata. */
    public Watch watch(UUID player, long chunk, long revision) {
        if (player == null || revision <= 0) throw new IllegalArgumentException("Invalid current watch");
        var key = new Key(player, chunk); var watch = new Watch(player, chunk, revision);
        subscriptions.remove(key); subscriptions.put(key, watch); pending.put(key, watch);
        if (subscriptions.size() <= limit) return null;
        var oldest = subscriptions.entrySet().iterator(); var evicted = oldest.next();
        oldest.remove(); pending.remove(evicted.getKey()); return evicted.getValue();
    }

    public Watch unwatch(UUID player, long chunk) {
        var key = new Key(player, chunk); pending.remove(key); return subscriptions.remove(key);
    }

    public void invalidate(LongSupplier revisions) {
        pending.clear();
        for (var entry : subscriptions.entrySet()) {
            Watch previous = entry.getValue(); long revision = revisions.getAsLong();
            if (revision <= previous.revision()) throw new IllegalArgumentException("Current revisions must increase");
            var refreshed = new Watch(previous.player(), previous.chunk(), revision);
            entry.setValue(refreshed); pending.put(entry.getKey(), refreshed);
        }
    }

    public Watch poll() {
        if (pending.isEmpty()) return null;
        var first = pending.entrySet().iterator(); var watch = first.next().getValue(); first.remove(); return watch;
    }

    /** A busy worker retains the watch; completion replays only current subscriptions. */
    public void retry(Watch watch) {
        if (current(watch)) pending.put(new Key(watch.player(), watch.chunk()), watch);
    }

    public void ready(long chunk) {
        // Deliver completed cached fields before cycling through more cold misses;
        // otherwise a large watch backlog can evict each result before its replay.
        var previous = new LinkedHashMap<>(pending);
        pending.clear();
        for (var entry : subscriptions.entrySet()) {
            if (entry.getValue().chunk() == chunk) pending.put(entry.getKey(), entry.getValue());
        }
        previous.forEach(pending::putIfAbsent);
    }

    /** Failed work yields to other pending chunks instead of monopolizing the build slot. */
    public void retryLater(long chunk) {
        for (var entry : subscriptions.entrySet()) {
            if (entry.getValue().chunk() == chunk) {
                pending.remove(entry.getKey());
                pending.put(entry.getKey(), entry.getValue());
            }
        }
    }

    public boolean current(Watch watch) {
        return watch != null && watch.equals(subscriptions.get(new Key(watch.player(), watch.chunk())));
    }
    public void playerLeft(UUID player) {
        subscriptions.keySet().removeIf(key -> key.player().equals(player));
        pending.keySet().removeIf(key -> key.player().equals(player));
    }
    public int size() { return subscriptions.size(); }
    public int pending() { return pending.size(); }
}
