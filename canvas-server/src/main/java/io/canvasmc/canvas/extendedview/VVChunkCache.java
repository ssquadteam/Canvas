package io.canvasmc.canvas.extendedview;

import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

public final class VVChunkCache {
    private static final long REMOVE_DELAY_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int DRAIN_INTERVAL = 16;
    private static final int MAX_DRAIN_PER_CALL = 5;

    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();
    private final MultiThreadedQueue<Long> order = new MultiThreadedQueue<>();

    private long maybeDrainCounter;

    public CompletableFuture<Result> computeIfAbsent(
        final long chunkKey,
        final LongFunction<? extends CompletableFuture<Result>> builder
    ) {
        this.maybeDrain();
        return this.cache.compute(chunkKey, (key, existing) -> {
            if (existing != null && existing.future != null) {
                return existing;
            }
            final Entry current = existing != null ? existing : new Entry(null);
            current.future = builder.apply(key);
            return current;
        }).future;
    }

    public void remove(final long chunkKey, final CompletableFuture<Result> expect) {
        this.cache.compute(chunkKey, (key, entry) -> {
            if (entry != null && entry.future == expect) {
                return null;
            }
            return entry;
        });
    }

    public void watch(final long chunkKey) {
        this.maybeDrain();
        this.cache.compute(
            chunkKey, (_, entry) -> {
                final Entry current = entry != null ? entry : new Entry(null);
                ++current.watchers;
                current.expireAtNanos = 0L;
                return current;
            }
        );
    }

    public void unwatch(final long chunkKey) {
        this.maybeDrain();
        this.cache.computeIfPresent(
            chunkKey, (_, entry) -> {
                if (--entry.watchers <= 0 && entry.expireAtNanos == 0L) {
                    entry.expireAtNanos = System.nanoTime() + REMOVE_DELAY_NANOS;
                    this.order.add(chunkKey);
                }
                return entry;
            }
        );
    }

    public void evictIfUnwatched(final long chunkKey) {
        this.maybeDrain();
        this.cache.computeIfPresent(
            chunkKey, (_, entry) -> {
                if (entry.watchers <= 0 && entry.expireAtNanos == 0L) {
                    entry.expireAtNanos = System.nanoTime() + REMOVE_DELAY_NANOS;
                    this.order.add(chunkKey);
                }
                return entry;
            }
        );
    }

    private void maybeDrain() {
        if ((this.maybeDrainCounter++ % DRAIN_INTERVAL) == 0) {
            this.drainExpired();
        }
    }

    public void drainExpired() {
        this.drainExpired(MAX_DRAIN_PER_CALL);
    }

    public void tick() {
        this.drainExpired(Integer.MAX_VALUE);
    }

    public void invalidate(final long chunkKey) {
        this.cache.remove(chunkKey);
    }

    public void invalidateAll() {
        this.cache.clear();
        this.order.clear();
    }

    public int size() {
        return this.cache.size();
    }

    private void drainExpired(final int maxToProcess) {
        final long now = System.nanoTime();
        final boolean[] notDue = {false};

        for (int remaining = maxToProcess; remaining > 0; --remaining) {
            final Long chunkKey = this.order.poll();
            if (chunkKey == null) {
                return;
            }

            //noinspection DataFlowIssue - "redundant assignment" - bad IntelliJ
            notDue[0] = false;
            this.cache.computeIfPresent(chunkKey, (_, entry) -> {
                if (entry.expireAtNanos == 0L) {
                    // cancelled, it got rewatched or already processed
                    return entry;
                }

                // check if it's due
                if (now - entry.expireAtNanos < 0L) {
                    notDue[0] = true;
                    return entry;
                }

                // it's due, remove if there are still no watchers
                return entry.watchers <= 0 ? null : entry;
            });

            if (notDue[0]) {
                // not due yet
                this.order.add(chunkKey);
                return;
            }
        }
    }

    private static final class Entry {
        CompletableFuture<Result> future;
        int watchers;
        long expireAtNanos;

        Entry(final CompletableFuture<Result> future) {
            this.future = future;
        }
    }
}
