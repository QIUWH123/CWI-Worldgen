package net.quiwh.cwiworldgen.hydrology.current;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Tick-owned single work slot. Invalidating never waits or queues a replacement behind it. */
public final class DeferredCurrentWork<T> {
    public record Result<T>(long chunk, T value, Throwable failure, boolean stale) {}
    private final Executor executor;
    private CompletableFuture<T> future;
    private long chunk;
    private boolean obsolete;

    public DeferredCurrentWork(Executor executor) { this.executor = executor; }
    public boolean busy() { return future != null; }
    public boolean preparing(long key) { return future != null && chunk == key && !obsolete; }

    public boolean start(long key, Supplier<T> builder) {
        if (busy()) return false;
        try {
            future = CompletableFuture.supplyAsync(builder, executor);
            chunk = key;
            obsolete = false;
            return true;
        } catch (RejectedExecutionException full) { return false; }
    }

    /** Never calls a blocking get/join, even while the builder is deliberately stalled. */
    public Result<T> poll() {
        if (future == null || !future.isDone()) return null;
        T value = null;
        Throwable failure = null;
        try { value = future.getNow(null); }
        catch (CompletionException failed) { failure = failed.getCause(); }
        var result = new Result<>(chunk, value, failure, obsolete);
        future = null;
        return result;
    }

    public void invalidate() { if (future != null) obsolete = true; }
    public void discard(long key) { if (future != null && chunk == key) obsolete = true; }
}
