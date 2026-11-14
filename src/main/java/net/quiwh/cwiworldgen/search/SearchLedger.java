package net.quiwh.cwiworldgen.search;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Server-thread-owned job history. A cancelled or retired job cannot publish a late result. */
public final class SearchLedger<O, R> {
    public enum Status { RUNNING, COMPLETE, CANCELLED }
    public record Entry<O, R>(int id, O owner, Status status, R result) {}

    private final int capacity, retained;
    private final Map<O, Integer> active = new HashMap<>();
    private final LinkedHashMap<Integer, Entry<O, R>> entries = new LinkedHashMap<>();
    private int nextId = 1;

    public SearchLedger(int capacity, int retained) {
        if (capacity < 1 || retained < 1) throw new IllegalArgumentException("Invalid search history limits");
        this.capacity = capacity;
        this.retained = retained;
    }

    public int start(O owner) {
        Objects.requireNonNull(owner);
        if (active.containsKey(owner)) throw new IllegalStateException("A search is already running. Wait for it to finish");
        if (active.size() >= capacity) throw new IllegalStateException("Search queue is full. Try again after a search finishes");
        if (nextId == Integer.MAX_VALUE) throw new IllegalStateException("Search ID limit reached; restart the server");
        int id = nextId++;
        active.put(owner, id);
        entries.put(id, new Entry<>(id, owner, Status.RUNNING, null));
        return id;
    }

    public boolean finish(int id, R result) {
        Objects.requireNonNull(result);
        Entry<O, R> entry = entries.get(id);
        if (entry == null || entry.status() != Status.RUNNING) return false;
        active.remove(entry.owner(), id);
        entries.put(id, new Entry<>(id, entry.owner(), Status.COMPLETE, result));
        trim();
        return true;
    }

    public boolean cancel(int id) {
        Entry<O, R> entry = entries.get(id);
        if (entry == null || entry.status() != Status.RUNNING) return false;
        active.remove(entry.owner(), id);
        entries.put(id, new Entry<>(id, entry.owner(), Status.CANCELLED, null));
        trim();
        return true;
    }

    public Entry<O, R> get(O owner, int id) {
        Entry<O, R> entry = entries.get(id);
        return entry != null && entry.owner().equals(owner) ? entry : null;
    }

    public Integer running(O owner) { return active.get(owner); }
    public int active() { return active.size(); }

    public void forget(O owner) {
        active.remove(owner);
        entries.values().removeIf(entry -> entry.owner().equals(owner));
    }

    public void clear() {
        active.clear();
        entries.clear();
        // IDs are not reused after reload/unload within the same server lifetime.
    }

    private void trim() {
        int completed = entries.size() - active.size();
        var iterator = entries.values().iterator();
        while (completed > retained && iterator.hasNext()) {
            if (iterator.next().status() != Status.RUNNING) {
                iterator.remove();
                completed--;
            }
        }
    }
}
