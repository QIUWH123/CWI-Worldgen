package net.quiwh.cwiworldgen.terrain.reference.concurrent.cache;

public interface ExpiringEntry {
    long getTimestamp();
    
    default void close() {
    }
}
