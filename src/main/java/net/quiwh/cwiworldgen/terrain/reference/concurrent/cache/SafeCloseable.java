package net.quiwh.cwiworldgen.terrain.reference.concurrent.cache;

public interface SafeCloseable extends AutoCloseable {
    void close();
}
