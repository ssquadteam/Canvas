package io.canvasmc.canvas.chunk.lod;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import net.minecraft.util.FastBufferedInputStream;
import sun.misc.Unsafe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.TagType;
import net.minecraft.nbt.visitors.CollectToTag;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class LodChunkNbt {

    private static final ThreadLocal<byte[]> INFLATED = ThreadLocal.withInitial(() -> new byte[1 << 16]);
    private static final ThreadLocal<byte[]> COMPRESSED = ThreadLocal.withInitial(() -> new byte[1 << 15]);
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);
    private static final @Nullable Field FAST_IN = field(FastBufferedInputStream.class, "in");
    private static final @Nullable Unsafe UNSAFE = unsafe();
    private static final long FILTER_IN_OFFSET = filterInOffset();

    private LodChunkNbt() {
    }

    public static void load(
        final ServerLevel world,
        final int chunkX,
        final int chunkZ,
        final int cutoffY,
        final Priority priority,
        final BiConsumer<@Nullable CompoundTag, @Nullable Throwable> done
    ) {
        final MoonriseRegionFileIO.RegionDataController controller = MoonriseRegionFileIO.getControllerFor(
            world, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA
        );
        final PrioritisedExecutor compression = ((ChunkSystemServerLevel) world).moonrise$getChunkTaskScheduler().compressionExecutor;

        controller.createRegionIoTask(chunkX, chunkZ, () -> {
            try {
                final MoonriseRegionFileIO.RegionDataController.ReadData read = controller.readData(chunkX, chunkZ);
                switch (read.result()) {
                    case NO_DATA -> compression.queueTask(() -> done.accept(null, null), priority);
                    case SYNC_READ -> compression.queueTask(() -> done.accept(read.syncRead(), null), priority);
                    case HAS_DATA -> compression.queueTask(() -> {
                        try {
                            done.accept(read(read, chunkX, chunkZ, cutoffY), null);
                        } catch (final Throwable thrown) {
                            done.accept(null, thrown);
                        }
                    }, priority);
                }
            } catch (final Throwable thrown) {
                compression.queueTask(() -> done.accept(null, thrown), priority);
            }
        }, priority).queue();
    }

    static @Nullable CompoundTag read(final DataInput input, final int chunkX, final int chunkZ, final int cutoffY) throws IOException {
        final Collector collector = new Collector(cutoffY);
        NbtIo.parse(input, collector, NbtAccounter.unlimitedHeap());
        if (!(collector.getResult() instanceof final CompoundTag tag)) {
            return null;
        }
        return tag.getIntOr("xPos", Integer.MIN_VALUE) == chunkX && tag.getIntOr("zPos", Integer.MIN_VALUE) == chunkZ
            ? tag : null;
    }

    private static @Nullable CompoundTag read(
        final MoonriseRegionFileIO.RegionDataController.ReadData read,
        final int chunkX,
        final int chunkZ,
        final int cutoffY
    ) throws IOException {
        try {
            return read(drain(read.input()), chunkX, chunkZ, cutoffY);
        } finally {
            if (read.input() != null) {
                read.input().close();
            }
        }
    }

    static @Nullable CompoundTag readInflated(final InputStream inflated, final int chunkX, final int chunkZ, final int cutoffY) throws IOException {
        return read(drain(inflated), chunkX, chunkZ, cutoffY);
    }

    private static @Nullable CompoundTag read(final ByteSlice slice, final int chunkX, final int chunkZ, final int cutoffY) throws IOException {
        return read(new Bytes(slice.bytes, slice.length), chunkX, chunkZ, cutoffY);
    }

    private static ByteSlice drain(final InputStream in) throws IOException {
        final InputStream zlib = findZlib(in);
        if (zlib != null) {
            return inflate(zlib);
        }
        return copy(in, INFLATED);
    }

    private static @Nullable InputStream findZlib(final InputStream in) {
        InputStream cursor = in;
        for (int depth = 0; depth < 4 && cursor != null; ++depth) {
            if (cursor instanceof GZIPInputStream) {
                return null;
            }
            if (cursor instanceof InflaterInputStream inflater) {
                return unwrap(inflater);
            }
            final InputStream next = unwrap(cursor);
            if (next == null || next == cursor) {
                return null;
            }
            cursor = next;
        }
        return null;
    }

    private static ByteSlice inflate(final InputStream compressed) throws IOException {
        final ByteSlice src = copy(compressed, COMPRESSED);
        final Inflater inflater = INFLATER.get();
        inflater.reset();
        inflater.setInput(src.bytes, 0, src.length);
        byte[] out = INFLATED.get();
        int n = 0;
        try {
            while (!inflater.finished()) {
                if (n == out.length) {
                    out = Arrays.copyOf(out, out.length << 1);
                    INFLATED.set(out);
                }
                final int wrote = inflater.inflate(out, n, out.length - n);
                if (wrote == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IOException("Truncated zlib chunk");
                    }
                    break;
                }
                n += wrote;
            }
        } catch (final DataFormatException failed) {
            throw new IOException(failed);
        } finally {
            inflater.reset();
        }
        return new ByteSlice(out, n);
    }

    private static ByteSlice copy(final InputStream in, final ThreadLocal<byte[]> slot) throws IOException {
        byte[] buf = slot.get();
        int n = 0;
        while (true) {
            if (n == buf.length) {
                buf = Arrays.copyOf(buf, buf.length << 1);
                slot.set(buf);
            }
            final int read = in.read(buf, n, buf.length - n);
            if (read < 0) {
                return new ByteSlice(buf, n);
            }
            n += read;
        }
    }

    private static @Nullable InputStream unwrap(final InputStream in) {
        try {
            if (in instanceof FastBufferedInputStream && FAST_IN != null) {
                return (InputStream) FAST_IN.get(in);
            }
            if (in instanceof FilterInputStream && UNSAFE != null && FILTER_IN_OFFSET >= 0L) {
                return (InputStream) UNSAFE.getObject(in, FILTER_IN_OFFSET);
            }
        } catch (final IllegalAccessException ignored) {
        }
        return null;
    }

    private static @Nullable Field field(final Class<?> type, final String name) {
        try {
            final Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static @Nullable Unsafe unsafe() {
        try {
            final Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static long filterInOffset() {
        if (UNSAFE == null) {
            return -1L;
        }
        try {
            return UNSAFE.objectFieldOffset(FilterInputStream.class.getDeclaredField("in"));
        } catch (final Throwable ignored) {
            return -1L;
        }
    }

    // the tag walk reads a byte at a time and ByteArrayInputStream#read is synchronized, so it runs off the array
    private static final class Bytes implements DataInput {

        private static final VarHandle SHORT_VIEW = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
        private static final VarHandle INT_VIEW = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
        private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

        private final byte[] bytes;
        private final int limit;
        private int pos;

        private Bytes(final byte[] bytes, final int limit) {
            this.bytes = bytes;
            this.limit = limit;
        }

        private int take(final int length) throws EOFException {
            final int at = this.pos;
            if (length < 0 || length > this.limit - at) {
                throw new EOFException();
            }
            this.pos = at + length;
            return at;
        }

        @Override
        public void readFully(final byte[] into) throws IOException {
            this.readFully(into, 0, into.length);
        }

        @Override
        public void readFully(final byte[] into, final int offset, final int length) throws IOException {
            System.arraycopy(this.bytes, this.take(length), into, offset, length);
        }

        // a short skip past the end is corrupt data, not a partial read, so it fails rather than misaligning the walk
        @Override
        public int skipBytes(final int length) throws IOException {
            this.take(length);
            return length;
        }

        @Override
        public boolean readBoolean() throws IOException {
            return this.readByte() != 0;
        }

        @Override
        public byte readByte() throws IOException {
            return this.bytes[this.take(1)];
        }

        @Override
        public int readUnsignedByte() throws IOException {
            return this.readByte() & 0xFF;
        }

        @Override
        public short readShort() throws IOException {
            return (short) SHORT_VIEW.get(this.bytes, this.take(2));
        }

        @Override
        public int readUnsignedShort() throws IOException {
            return this.readShort() & 0xFFFF;
        }

        @Override
        public char readChar() throws IOException {
            return (char) this.readShort();
        }

        @Override
        public int readInt() throws IOException {
            return (int) INT_VIEW.get(this.bytes, this.take(4));
        }

        @Override
        public long readLong() throws IOException {
            return (long) LONG_VIEW.get(this.bytes, this.take(8));
        }

        @Override
        public float readFloat() throws IOException {
            return Float.intBitsToFloat(this.readInt());
        }

        @Override
        public double readDouble() throws IOException {
            return Double.longBitsToDouble(this.readLong());
        }

        @Override
        public String readLine() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String readUTF() throws IOException {
            return DataInputStream.readUTF(this);
        }
    }

    private record ByteSlice(byte[] bytes, int length) {
    }

    private static boolean keepRoot(final String id) {
        return switch (id) {
            case "Status", "xPos", "zPos", "Heightmaps", "sections" -> true;
            default -> false;
        };
    }

    private static boolean keepSection(final String id) {
        return switch (id) {
            case "Y", "SkyLight", "BlockLight", "biomes", "block_states" -> true;
            default -> SaveUtil.SKYLIGHT_STATE_TAG.equals(id) || SaveUtil.BLOCKLIGHT_STATE_TAG.equals(id);
        };
    }

    private static final class Collector extends CollectToTag {

        private final int cutoffY;
        private boolean inSections;
        private boolean inSection;
        private boolean awaitingY;
        private boolean skipBlockStates;

        private Collector(final int cutoffY) {
            this.cutoffY = cutoffY;
        }

        @Override
        public StreamTagVisitor.EntryResult visitEntry(final TagType<?> type, final String id) {
            if (this.inSection && this.depth() == 3) {
                if ("Y".equals(id)) {
                    this.awaitingY = true;
                    return super.visitEntry(type, id);
                }
                if ("block_states".equals(id) && this.skipBlockStates) {
                    return StreamTagVisitor.EntryResult.SKIP;
                }
                return keepSection(id) ? super.visitEntry(type, id) : StreamTagVisitor.EntryResult.SKIP;
            }
            if (this.depth() == 1) {
                if (!keepRoot(id)) {
                    return StreamTagVisitor.EntryResult.SKIP;
                }
                if ("sections".equals(id)) {
                    this.inSections = true;
                }
                return super.visitEntry(type, id);
            }
            return super.visitEntry(type, id);
        }

        @Override
        public StreamTagVisitor.EntryResult visitElement(final TagType<?> type, final int index) {
            if (this.inSections && !this.inSection) {
                this.inSection = true;
                this.awaitingY = false;
                this.skipBlockStates = false;
            }
            return super.visitElement(type, index);
        }

        @Override
        public StreamTagVisitor.ValueResult visit(final byte value) {
            this.noteSectionY(value);
            return super.visit(value);
        }

        @Override
        public StreamTagVisitor.ValueResult visit(final int value) {
            this.noteSectionY(value);
            return super.visit(value);
        }

        @Override
        public StreamTagVisitor.ValueResult visitContainerEnd() {
            final int depth = this.depth();
            if (this.inSection && depth == 3) {
                this.inSection = false;
            } else if (this.inSections && depth == 2) {
                this.inSections = false;
            }
            return super.visitContainerEnd();
        }

        private void noteSectionY(final int sectionY) {
            if (this.inSection && this.awaitingY) {
                this.skipBlockStates = (sectionY * LodChunkEncoder.SECTION_HEIGHT) + (LodChunkEncoder.SECTION_HEIGHT - 1) < this.cutoffY;
                this.awaitingY = false;
            }
        }
    }
}
