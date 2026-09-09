package io.canvasmc.canvas.chunk.lod;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Encoded packets are immutable, so one entry is shared by every player needing the same column.
 */
@NullMarked
public final class LodChunkCache {

    public static final int MAX_ENTRIES = 8192;
    public static final long MAX_BYTES = 128L * 1024L * 1024L;

    private static final long PRESENT_TTL_NANOS = TimeUnit.MINUTES.toNanos(5L);
    private static final long MISSING_TTL_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static final long UNWATCHED_TTL_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final int EVICT_ENTRIES = (MAX_ENTRIES * 7) >> 3;
    private static final long EVICT_BYTES = (MAX_BYTES * 7) >> 3;

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>(1024);
    private final ConcurrentHashMap<Key, CompletableFuture<Result>> inflight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> watchers = new ConcurrentHashMap<>();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicBoolean evicting = new AtomicBoolean();

    /**
     * {@code null} means nothing usable is cached and the column must be read from disk.
     */
    public @Nullable Result get(final long chunkKey, final int cutoffY, final boolean hollow) {
        final Key key = new Key(chunkKey, cutoffY, hollow);
        final Entry entry = this.entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAtNanos <= System.nanoTime()) {
            if (this.entries.remove(key, entry)) {
                this.bytes.addAndGet(-entry.bytes);
            }
            return null;
        }
        return entry.result;
    }

    public void request(
        final ServerLevel world,
        final long chunkKey,
        final int cutoffY,
        final boolean hollow,
        final boolean sendLight,
        final boolean sendBlockLight,
        final Consumer<Result> done
    ) {
        final Result cached = this.get(chunkKey, cutoffY, hollow);
        if (cached != null) {
            done.accept(cached);
            return;
        }

        final Key key = new Key(chunkKey, cutoffY, hollow);
        final CompletableFuture<Result> future = this.inflight.computeIfAbsent(
            key,
            ignored -> this.startBuild(world, key, sendLight, sendBlockLight)
        );
        future.whenComplete((result, thrown) -> {
            this.inflight.remove(key, future);
            done.accept(thrown != null || result == null ? Result.MISSING : result);
        });
    }

    public void put(final long chunkKey, final int cutoffY, final boolean hollow, final ClientboundLevelChunkWithLightPacket packet, final int encodedBytes) {
        this.store(new Key(chunkKey, cutoffY, hollow), new Entry(new Result(packet), encodedBytes, System.nanoTime() + PRESENT_TTL_NANOS));
    }

    /**
     * Keeps a column with no sendable form from being re-read every tick.
     */
    public void putMissing(final long chunkKey, final int cutoffY, final boolean hollow) {
        this.store(new Key(chunkKey, cutoffY, hollow), new Entry(Result.MISSING, 0, System.nanoTime() + MISSING_TTL_NANOS));
    }

    public void watch(final long chunkKey) {
        this.watchers.merge(chunkKey, 1, Integer::sum);
        this.touch(chunkKey);
    }

    public void unwatch(final long chunkKey) {
        final Integer left = this.watchers.compute(chunkKey, (ignored, count) -> count == null || count <= 1 ? null : count - 1);
        if (left == null) {
            this.expireSoon(chunkKey);
        }
    }

    public void invalidate(final long chunkKey) {
        this.entries.entrySet().removeIf(mapping -> {
            if (mapping.getKey().chunkKey != chunkKey) {
                return false;
            }
            this.bytes.addAndGet(-mapping.getValue().bytes);
            return true;
        });
    }

    public void invalidateAll() {
        this.entries.clear();
        this.bytes.set(0L);
    }

    public int size() {
        return this.entries.size();
    }

    public long bytes() {
        return this.bytes.get();
    }

    private CompletableFuture<Result> startBuild(
        final ServerLevel world,
        final Key key,
        final boolean sendLight,
        final boolean sendBlockLight
    ) {
        final CompletableFuture<Result> future = new CompletableFuture<>();
        final int chunkX = CoordinateUtils.getChunkX(key.chunkKey);
        final int chunkZ = CoordinateUtils.getChunkZ(key.chunkKey);
        try {
            LodChunkNbt.load(world, chunkX, chunkZ, key.cutoffY, Priority.IDLE, (tag, thrown) -> {
                Result result = Result.MISSING;
                try {
                    if (thrown == null && tag != null) {
                        final byte[] encoded = LodChunkEncoder.encode(
                            world, tag, new ChunkPos(chunkX, chunkZ), key.cutoffY, key.hollow,
                            sendLight, sendBlockLight
                        );
                        if (encoded != null) {
                            final ClientboundLevelChunkWithLightPacket packet = LodChunkEncoder.decode(world, encoded);
                            this.put(key.chunkKey, key.cutoffY, key.hollow, packet, encoded.length);
                            result = new Result(packet);
                        }
                    }
                    if (result.packet() == null) {
                        this.putMissing(key.chunkKey, key.cutoffY, key.hollow);
                    }
                    future.complete(result);
                } catch (final Throwable failed) {
                    this.putMissing(key.chunkKey, key.cutoffY, key.hollow);
                    future.complete(Result.MISSING);
                }
            });
        } catch (final Throwable failed) {
            this.putMissing(key.chunkKey, key.cutoffY, key.hollow);
            future.complete(Result.MISSING);
        }
        return future;
    }

    private void touch(final long chunkKey) {
        final long until = System.nanoTime() + PRESENT_TTL_NANOS;
        this.entries.replaceAll((key, entry) -> key.chunkKey != chunkKey || entry.result.packet() == null
            ? entry
            : new Entry(entry.result, entry.bytes, until));
    }

    private void expireSoon(final long chunkKey) {
        final long until = System.nanoTime() + UNWATCHED_TTL_NANOS;
        this.entries.replaceAll((key, entry) -> key.chunkKey != chunkKey || entry.expireAtNanos <= until
            ? entry
            : new Entry(entry.result, entry.bytes, until));
    }

    private void store(final Key key, final Entry entry) {
        final Entry previous = this.entries.put(key, entry);
        long delta = entry.bytes;
        if (previous != null) {
            delta -= previous.bytes;
        }
        final long now = this.bytes.addAndGet(delta);
        if (this.entries.size() > MAX_ENTRIES || now > MAX_BYTES) {
            this.maybeEvict();
        }
    }

    private void maybeEvict() {
        if (!this.evicting.compareAndSet(false, true)) {
            return;
        }
        try {
            this.evict();
        } finally {
            this.evicting.set(false);
        }
    }

    private void evict() {
        final long now = System.nanoTime();
        for (final Map.Entry<Key, Entry> mapping : this.entries.entrySet()) {
            if (mapping.getValue().expireAtNanos <= now && this.entries.remove(mapping.getKey(), mapping.getValue())) {
                this.bytes.addAndGet(-mapping.getValue().bytes);
            }
        }

        if (this.entries.size() <= MAX_ENTRIES && this.bytes.get() <= MAX_BYTES) {
            return;
        }

        final ArrayList<Map.Entry<Key, Entry>> snapshot = new ArrayList<>(this.entries.entrySet());
        snapshot.sort(Comparator.comparingLong(mapping -> mapping.getValue().expireAtNanos));
        for (final Map.Entry<Key, Entry> mapping : snapshot) {
            if (this.entries.size() <= EVICT_ENTRIES && this.bytes.get() <= EVICT_BYTES) {
                return;
            }
            if (this.entries.remove(mapping.getKey(), mapping.getValue())) {
                this.bytes.addAndGet(-mapping.getValue().bytes);
            }
        }
    }

    private record Key(long chunkKey, int cutoffY, boolean hollow) {
    }

    private record Entry(Result result, int bytes, long expireAtNanos) {
    }

    public record Result(@Nullable ClientboundLevelChunkWithLightPacket packet) {

        public static final Result MISSING = new Result(null);
    }
}
