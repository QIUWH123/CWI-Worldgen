package net.quiwh.cwiworldgen.terrain.reference.concurrent.cache;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import net.quiwh.cwiworldgen.terrain.reference.concurrent.cache.map.LongMap;
import net.quiwh.cwiworldgen.terrain.reference.concurrent.cache.map.StampedBoundLongMap;

@Deprecated
public class CacheManager {
	private static final List<java.lang.ref.WeakReference<Cache<?>>> CACHES = Collections.synchronizedList(new LinkedList<>());
	
    public static <V extends ExpiringEntry> Cache<V> createCache(long expireTime, long pollInterval, TimeUnit unit) {
    	return createCache(256, expireTime, pollInterval, unit);
    }
	
	public static <V extends ExpiringEntry> Cache<V> createCache(int capacity, long expireTime, long pollInterval, TimeUnit unit) {
		return createCache(capacity, expireTime, pollInterval, unit, StampedBoundLongMap::new);
	}
	
	public static <V extends ExpiringEntry> Cache<V> createCache(int capacity, long expireTime, long pollInterval, TimeUnit unit, IntFunction<LongMap<V>> mapFunc) {
		Cache<V> cache = new Cache<>(capacity, expireTime, pollInterval, unit, mapFunc);
		CACHES.add(new java.lang.ref.WeakReference<>(cache));
		return cache;
	}
	
	public static void clear() throws Exception {
		synchronized (CACHES) {
            for (java.lang.ref.WeakReference<Cache<?>> reference : CACHES) {
                Cache<?> cache = reference.get();
                if (cache != null) cache.close();
            }
            CACHES.clear();
        }
	}
}