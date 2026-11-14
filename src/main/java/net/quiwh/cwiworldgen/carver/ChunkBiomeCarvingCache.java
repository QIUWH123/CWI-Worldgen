package net.quiwh.cwiworldgen.carver;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.carver.CarvingContext;

/** One bounded palette summary per worker; no strong world/context/registry references. */
final class ChunkBiomeCarvingCache {
    private static final ThreadLocal<ChunkBiomeCarvingCache> CURRENT = new ThreadLocal<>();
    private final WeakReference<CarvingContext> context;
    private final WeakReference<ChunkAccess> chunk;
    private final Set<ResourceLocation> possibleBiomes;

    private ChunkBiomeCarvingCache(CarvingContext context, ChunkAccess chunk) {
        this.context = new WeakReference<>(context);
        this.chunk = new WeakReference<>(chunk);
        var possible = new HashSet<ResourceLocation>();
        for (var section : chunk.getSections()) {
            section.getBiomes().getAll(biome -> {
                var key = biome.getKey();
                if (key != null) possible.add(key.location());
            });
        }
        this.possibleBiomes = Set.copyOf(possible);
    }

    static ChunkBiomeCarvingCache get(CarvingContext context, ChunkAccess chunk) {
        var cached = CURRENT.get();
        if (cached == null || cached.context.get() != context || cached.chunk.get() != chunk) {
            cached = new ChunkBiomeCarvingCache(context, chunk);
            CURRENT.set(cached);
        }
        return cached;
    }

    boolean mayContain(ResourceLocation biome) {
        return possibleBiomes.contains(biome);
    }
}
