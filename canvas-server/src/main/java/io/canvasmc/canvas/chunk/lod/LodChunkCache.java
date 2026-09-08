package io.canvasmc.canvas.chunk.lod;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Encoded packets are immutable, so one entry is shared by every player needing the same column.
 */
@NullMarked
public final class LodChunkCache {

    public static final int MAX_ENTRIES = 4096;
    // an LOD column can be tens of kilobytes, so this usually caps before MAX_ENTRIES does
    public static final long MAX_BYTES = 64L * 1024L * 1024L;

    private static final long PRESENT_TTL_NANOS = TimeUnit.MINUTES.toNanos(5L);
    private static final long MISSING_TTL_NANOS = TimeUnit.SECONDS.toNanos(30L);

    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(256, 0.75F, true);
    private long bytes;

    /**
     * {@code null} means nothing usable is cached and the column must be read from disk.
     */
    public synchronized @Nullable Result get(final long chunkKey, final int cutoffY, final boolean hollow) {
        final Key key = new Key(chunkKey, cutoffY, hollow);
        final Entry entry = this.entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAtNanos <= System.nanoTime()) {
            this.entries.remove(key);
            this.bytes -= entry.bytes;
            return null;
        }
        return entry.packet == null ? Result.MISSING : new Result(entry.packet);
    }

    public synchronized void put(final long chunkKey, final int cutoffY, final boolean hollow, final ClientboundLevelChunkWithLightPacket packet, final int encodedBytes) {
        this.store(new Key(chunkKey, cutoffY, hollow), new Entry(packet, encodedBytes, System.nanoTime() + PRESENT_TTL_NANOS));
    }

    /**
     * Keeps a column with no sendable form from being re-read every tick.
     */
    public synchronized void putMissing(final long chunkKey, final int cutoffY, final boolean hollow) {
        this.store(new Key(chunkKey, cutoffY, hollow), new Entry(null, 0, System.nanoTime() + MISSING_TTL_NANOS));
    }

    public synchronized void invalidate(final long chunkKey) {
        final Iterator<Map.Entry<Key, Entry>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<Key, Entry> entry = iterator.next();
            if (entry.getKey().chunkKey == chunkKey) {
                this.bytes -= entry.getValue().bytes;
                iterator.remove();
            }
        }
    }

    public synchronized void invalidateAll() {
        this.entries.clear();
        this.bytes = 0L;
    }

    public synchronized int size() {
        return this.entries.size();
    }

    private void store(final Key key, final Entry entry) {
        final Entry previous = this.entries.put(key, entry);
        if (previous != null) {
            this.bytes -= previous.bytes;
        }
        this.bytes += entry.bytes;

        final Iterator<Map.Entry<Key, Entry>> iterator = this.entries.entrySet().iterator();
        while ((this.entries.size() > MAX_ENTRIES || this.bytes > MAX_BYTES) && iterator.hasNext()) {
            final Map.Entry<Key, Entry> eldest = iterator.next();
            if (eldest.getKey().equals(key)) {
                continue; // never evict what we just stored
            }
            this.bytes -= eldest.getValue().bytes;
            iterator.remove();
        }
    }

    private record Key(long chunkKey, int cutoffY, boolean hollow) {
    }

    private record Entry(@Nullable ClientboundLevelChunkWithLightPacket packet, int bytes, long expireAtNanos) {
    }

    /**
     * @param packet
     *     {@code null} when the column is known to have no sendable form
     */
    public record Result(@Nullable ClientboundLevelChunkWithLightPacket packet) {

        public static final Result MISSING = new Result(null);
    }
}
