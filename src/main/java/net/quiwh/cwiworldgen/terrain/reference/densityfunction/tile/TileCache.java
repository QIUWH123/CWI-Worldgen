package net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.generation.TileGenerator;
import net.quiwh.cwiworldgen.terrain.reference.util.PosUtil;

/** Bounded completed tiles. Eviction never mutates arrays pinned by chunk readers. */
public class TileCache implements TileFactory {
    private final int tileSize;
    private final TileGenerator generator;
    private final Map<Long,Tile> tiles = new LinkedHashMap<>(16, .75F, true);
    private final Map<Long,CompletableFuture<Tile>> inFlight = new HashMap<>();
    public TileCache(int tileSize, boolean queue, TileGenerator generator) {
        this.tileSize = tileSize;
        this.generator = generator;
    }
    public TileGenerator getGenerator() { return generator; }
    @Nullable public synchronized Tile provideIfPresent(int tileX, int tileZ) {
        return tiles.get(PosUtil.pack(tileX,tileZ));
    }
    @Nullable public Tile provideAtChunkIfPresent(int chunkX, int chunkZ) {
        return provideIfPresent(chunkToTile(chunkX),chunkToTile(chunkZ));
    }
    @Override public Tile provide(int tileX, int tileZ) {
        long key = PosUtil.pack(tileX,tileZ);
        CompletableFuture<Tile> pending;
        boolean start = false;
        synchronized (this) {
            Tile tile = tiles.get(key);
            if (tile != null) return tile;
            pending = inFlight.get(key);
            if (pending == null) {
                pending = new CompletableFuture<>();
                inFlight.put(key,pending);
                start = true;
            }
        }
        if (start) {
            CompletableFuture<Tile> shared = pending;
            try {
                generator.generate(tileX,tileZ).whenComplete((tile,failure) -> complete(key,shared,tile,failure));
            } catch (RuntimeException | Error failure) {
                complete(key,shared,null,failure);
            }
        }
        return pending.join();
    }
    private void complete(long key, CompletableFuture<Tile> pending, @Nullable Tile tile, @Nullable Throwable failure) {
        synchronized (this) {
            // Clear detaches old requests. Their readers still finish, but cannot publish over a new request.
            if (inFlight.get(key) == pending) {
                inFlight.remove(key);
                if (failure == null) {
                    tiles.put(key,tile);
                    if (tiles.size() > 8) tiles.remove(tiles.keySet().iterator().next());
                }
            }
        }
        if (failure == null) pending.complete(tile);
        else pending.completeExceptionally(failure);
    }
    @Override public void queue(int tileX, int tileZ) { }
    @Override public void drop(int tileX, int tileZ) { }
    @Override public int chunkToTile(int coordinate) { return coordinate >> tileSize; }
    public synchronized void clear() { tiles.clear(); inFlight.clear(); }
}
