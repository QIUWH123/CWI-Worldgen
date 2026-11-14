package net.quiwh.cwiworldgen.terrain.reference.concurrent.cache;

import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import net.quiwh.cwiworldgen.terrain.reference.concurrent.cache.map.LongMap;

/** Per-engine bounded cache; no background task retains an unloaded world. */
public class Cache<V extends ExpiringEntry> implements AutoCloseable {
    private final int capacity;
    private final long lifetimeMS;
    private final LinkedHashMap<Long,V> entries = new LinkedHashMap<>(16, .75F, true);
    public Cache(int capacity, long expireTime, long pollInterval, TimeUnit unit, IntFunction<LongMap<V>> ignored) {
        this.capacity = capacity;
        this.lifetimeMS = unit.toMillis(expireTime);
    }
    public synchronized void remove(long key) {
        V value = entries.remove(key);
        if (value != null) value.close();
    }
    public synchronized V get(long key) { return entries.get(key); }
    public synchronized V computeIfAbsent(long key, LongFunction<V> factory) {
        V found = entries.get(key);
        if (found != null) return found;
        V value = factory.apply(key);
        entries.put(key, value);
        if (entries.size() > capacity) {
            Iterator<V> iterator = entries.values().iterator();
            iterator.next().close();
            iterator.remove();
        }
        return value;
    }
    public synchronized void poll() {
        long timeout = System.currentTimeMillis() - lifetimeMS;
        entries.values().removeIf(value -> {
            if (value.getTimestamp() >= timeout) return false;
            value.close();
            return true;
        });
    }
    @Override public synchronized void close() {
        entries.values().forEach(ExpiringEntry::close);
        entries.clear();
    }
}
