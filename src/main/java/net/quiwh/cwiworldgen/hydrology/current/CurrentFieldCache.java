package net.quiwh.cwiworldgen.hydrology.current;

import java.util.LinkedHashMap;

/** Client ordering state is separate from fields so an old build cannot undo an unwatch. */
public final class CurrentFieldCache {
    private final int fieldLimit, revisionLimit;
    private String dimension;
    private final LinkedHashMap<Long, CurrentChunk> fields = new LinkedHashMap<>(16, .75F, true);
    private final LinkedHashMap<Long, Long> revisions = new LinkedHashMap<>(16, .75F, true);

    public CurrentFieldCache(int fieldLimit, int revisionLimit) {
        if (fieldLimit < 1 || revisionLimit < fieldLimit) throw new IllegalArgumentException("Invalid current cache limits");
        this.fieldLimit = fieldLimit;
        this.revisionLimit = revisionLimit;
    }

    public void enter(String nextDimension) {
        if (!java.util.Objects.equals(dimension, nextDimension)) {
            clear();
            dimension = nextDimension;
        }
    }

    public boolean apply(String packetDimension, int generation, long revision, long key, CurrentChunk chunk) {
        if (dimension == null || !dimension.equals(packetDimension) || generation != 2 || revision <= 0
                || revision <= revisions.getOrDefault(key, 0L) || (chunk != null && chunk.chunkPos() != key)) return false;
        revisions.put(key, revision);
        if (chunk == null) fields.remove(key); else fields.put(key, chunk);
        trim(fields, fieldLimit);
        trim(revisions, revisionLimit);
        return true;
    }

    private static <V> void trim(LinkedHashMap<Long, V> map, int limit) {
        while (map.size() > limit) map.remove(map.keySet().iterator().next());
    }
    public CurrentChunk get(long key) { return fields.get(key); }
    public int size() { return fields.size(); }
    public int revisionCount() { return revisions.size(); }
    public String dimension() { return dimension; }
    public void clear() { fields.clear(); revisions.clear(); dimension = null; }
}
