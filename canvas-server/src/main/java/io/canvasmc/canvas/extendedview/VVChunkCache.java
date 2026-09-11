package io.canvasmc.canvas.extendedview;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class VVChunkCache {
    private final ConcurrentHashMap<Long, CompletableFuture<Result>> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> watchers = new ConcurrentHashMap<>();

    public CompletableFuture<Result> computeIfAbsent(final long chunkKey, final Supplier<CompletableFuture<Result>> mapping) {
        return this.cache.computeIfAbsent(chunkKey, ignored -> mapping.get());
    }

    public void remove(final long chunkKey, final CompletableFuture<Result> future) {
        this.cache.remove(chunkKey, future);
    }

    public void watch(final long chunkKey) {
        this.watchers.computeIfAbsent(chunkKey, ignored -> new AtomicInteger()).incrementAndGet();
    }

    public void unwatch(final long chunkKey) {
        final AtomicInteger count = this.watchers.get(chunkKey);
        if (count == null) {
            this.invalidate(chunkKey);
            return;
        }
        if (count.decrementAndGet() <= 0) {
            this.watchers.remove(chunkKey, count);
            this.invalidate(chunkKey);
        }
    }

    public void evictIfUnwatched(final long chunkKey) {
        if (!this.watchers.containsKey(chunkKey)) {
            this.invalidate(chunkKey);
        }
    }

    public void invalidate(final long chunkKey) {
        this.cache.remove(chunkKey);
    }

    public void invalidateAll() {
        this.cache.clear();
    }

    public int size() {
        return this.cache.size();
    }
}
