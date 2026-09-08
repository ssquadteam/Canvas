package io.canvasmc.canvas.chunk.lod;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Encodes region file NBT straight to packet bytes, never building a
 * {@link net.minecraft.world.level.chunk.LevelChunk} and so never touching the chunk system.
 */
@NullMarked
public final class LodChunkEncoder {

    /** No section is truncated. */
    public static final int NO_CUTOFF = Integer.MIN_VALUE;

    static final int SECTION_HEIGHT = 16;
    private static final int SECTION_VOLUME = SECTION_HEIGHT * SECTION_HEIGHT * SECTION_HEIGHT;
    private static final int MASK_WORDS = SECTION_VOLUME >> 6;
    private static final int LIGHT_LAYER_BYTES = 2048;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    // outside the world below the build limit counts as opaque
    private static final long[] ALL_OPAQUE = new long[MASK_WORDS];
    static {
        java.util.Arrays.fill(ALL_OPAQUE, -1L);
    }

    private static final ThreadLocal<long[][]> OPAQUE_SCRATCH = new ThreadLocal<>();

    private static volatile @Nullable PalettedContainerFactory EMPTY_SECTION_FACTORY;
    private static volatile byte @Nullable [] EMPTY_SECTION_BYTES;

    private LodChunkEncoder() {
    }

    /**
     * Intended to run off the region tick thread. Returns {@code null} when the column has no sendable LOD form,
     * which covers unparseable data, columns that were never lit, and columns left empty by the cutoff.
     */
    public static byte @Nullable [] encode(
        final ServerLevel level,
        final CompoundTag tag,
        final ChunkPos pos,
        final int cutoffY,
        final boolean hollow,
        final boolean sendLight,
        final boolean sendBlockLight
    ) {
        final LodChunkData data;
        try {
            data = LodChunkData.parse(level, tag);
        } catch (final Throwable thrown) {
            // a single corrupt column must not take down the LOD ring
            return null;
        }

        if (data == null || !isSendable(data)) {
            return null;
        }

        final byte[] emptySection = emptySectionBytes(level.palettedContainerFactory());
        final ByteBuf sectionBytes = Unpooled.buffer(256);
        final ByteBuf packetBytes = Unpooled.buffer(512);
        try {
            final FriendlyByteBuf sectionBuf = new FriendlyByteBuf(sectionBytes);
            final LightMasks lights = new LightMasks(level.getSectionsCount() + 2, sendLight, sendBlockLight);

            final Occlusion occlusion = hollow ? Occlusion.forLevel(level) : null;

            if (!writeSections(sectionBuf, level, data, cutoffY, emptySection, lights, occlusion)) {
                return null;
            }

            final RegistryFriendlyByteBuf dataBuf = new RegistryFriendlyByteBuf(packetBytes, level.registryAccess());
            dataBuf.writeInt(pos.x());
            dataBuf.writeInt(pos.z());
            writeHeightmaps(dataBuf, data.heightmaps());
            dataBuf.writeVarInt(sectionBytes.readableBytes());
            dataBuf.writeBytes(sectionBytes);
            dataBuf.writeVarInt(0); // block entities are sent on promotion instead
            lights.write(dataBuf);

            final byte[] encoded = new byte[packetBytes.readableBytes()];
            packetBytes.readBytes(encoded);
            return encoded;
        } finally {
            sectionBytes.release();
            packetBytes.release();
        }
    }

    /**
     * The result is immutable and may be shared between players.
     */
    public static ClientboundLevelChunkWithLightPacket decode(final ServerLevel level, final byte[] encoded) {
        final ByteBuf buffer = Unpooled.wrappedBuffer(encoded);
        try {
            final RegistryFriendlyByteBuf dataBuf = new RegistryFriendlyByteBuf(buffer, level.registryAccess());
            final ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(dataBuf);
            packet.setReady(true); // Anti-Xray gates sends on this, LOD columns are never obfuscated asynchronously
            return packet;
        } finally {
            buffer.release();
        }
    }

    /**
     * Sections are only ever emptied whole, so the cutoff floors to a section boundary.
     */
    public static int alignCutoff(final int cutoffY) {
        return cutoffY <= 0 ? NO_CUTOFF : Math.floorDiv(cutoffY, SECTION_HEIGHT) * SECTION_HEIGHT;
    }

    /**
     * Block light is the larger half of the payload and unreadable at LOD range, so it is only worth sending where
     * there is no sky light to carry the image.
     */
    public static boolean shouldSendBlockLight(final ServerLevel level) {
        return level.canvasConfig().worldChunkSystem.lodSendLight && !level.dimensionType().hasSkyLight();
    }

    /**
     * With this off distant terrain renders black.
     */
    public static boolean shouldSendLight(final ServerLevel level) {
        return level.canvasConfig().worldChunkSystem.lodSendLight;
    }

    /**
     * A block Anti-Xray hides is by definition enclosed, and enclosed blocks are exactly what hollowing drops, so a
     * hollowed column carries nothing Anti-Xray would have obfuscated.
     */
    public static boolean hollows(final ServerLevel level) {
        // forced under Anti-Xray, it is what keeps buried ore out of the packet
        return level.canvasConfig().worldChunkSystem.lodHollowChunks
            || level.paperConfig().anticheat.antiXray.enabled;
    }

    private static boolean writeSections(
        final FriendlyByteBuf out,
        final ServerLevel level,
        final LodChunkData data,
        final int cutoffY,
        final byte[] emptySection,
        final LightMasks lights,
        final @Nullable Occlusion occlusion
    ) {
        final int sectionCount = level.getSectionsCount();
        final LevelChunkSection[] bySectionIndex = data.sections();

        long[][] opaque = null;
        if (occlusion != null) {
            opaque = opaqueScratch(sectionCount);
            for (int sectionIndex = 0; sectionIndex < sectionCount; ++sectionIndex) {
                final int sectionMinY = level.getSectionYFromSectionIndex(sectionIndex) * SECTION_HEIGHT;
                // a truncated section goes out empty, so nothing above it may count it as cover
                final LevelChunkSection chunkSection = sectionMinY + (SECTION_HEIGHT - 1) < cutoffY
                    ? null
                    : bySectionIndex[sectionIndex];
                fillOpaque(chunkSection, opaque[sectionIndex], occlusion);
            }
        }

        boolean anyVisible = false;
        for (int sectionIndex = 0; sectionIndex < sectionCount; ++sectionIndex) {
            final int sectionMinY = level.getSectionYFromSectionIndex(sectionIndex) * SECTION_HEIGHT;
            final int lightIndex = sectionIndex + 1; // light masks start one section below the world

            if (sectionMinY + (SECTION_HEIGHT - 1) < cutoffY) {
                out.writeBytes(emptySection);
                lights.markEmpty(lightIndex);
                continue;
            }

            LevelChunkSection chunkSection = bySectionIndex[sectionIndex];
            if (chunkSection != null && opaque != null) {
                chunkSection = hollowSection(
                    chunkSection,
                    opaque[sectionIndex],
                    sectionIndex > 0 ? opaque[sectionIndex - 1] : ALL_OPAQUE,
                    sectionIndex + 1 < sectionCount ? opaque[sectionIndex + 1] : null
                );
            }

            final boolean empty = chunkSection == null || chunkSection.hasOnlyAir();
            if (empty) {
                out.writeBytes(emptySection);
            } else {
                chunkSection.write(out, null, sectionIndex);
                anyVisible = true;
            }

            // a section hollowing emptied was fully buried, so its light was already zero
            if (bySectionIndex[sectionIndex] == null || (empty && opaque != null)) {
                lights.markEmpty(lightIndex);
            } else {
                lights.add(lightIndex, data.skyLight()[sectionIndex], data.blockLight()[sectionIndex]);
            }
        }

        return anyVisible;
    }

    // one bit per block, set when the block hides whatever is behind it
    private static void fillOpaque(final @Nullable LevelChunkSection section, final long[] mask, final Occlusion occlusion) {
        if (section == null || section.hasOnlyAir()) {
            java.util.Arrays.fill(mask, 0L);
            return;
        }

        // maybeHas only scans the palette, so false here is definitive
        if (!section.maybeHas((final BlockState state) -> !occlusion.occludes(state))) {
            java.util.Arrays.fill(mask, -1L);
            return;
        }

        java.util.Arrays.fill(mask, 0L);
        for (int y = 0; y < SECTION_HEIGHT; ++y) {
            for (int z = 0; z < SECTION_HEIGHT; ++z) {
                for (int x = 0; x < SECTION_HEIGHT; ++x) {
                    if (occlusion.occludes(section.getBlockState(x, y, z))) {
                        final int index = maskIndex(x, y, z);
                        mask[index >> 6] |= 1L << (index & 63);
                    }
                }
            }
        }
    }

    private static int maskIndex(final int x, final int y, final int z) {
        return x | (z << 4) | (y << 8);
    }

    private static boolean opaqueAt(final long @Nullable [] mask, final int index) {
        return mask != null && (mask[index >> 6] & (1L << (index & 63))) != 0L;
    }

    // returns the section with every buried block replaced by air, or null when nothing in it survives
    private static long[][] opaqueScratch(final int sectionCount) {
        long[][] scratch = OPAQUE_SCRATCH.get();
        if (scratch == null || scratch.length < sectionCount) {
            scratch = new long[sectionCount][MASK_WORDS];
            OPAQUE_SCRATCH.set(scratch);
        }
        return scratch;
    }

    private static @Nullable LevelChunkSection hollowSection(
        final LevelChunkSection self,
        final long[] opaque,
        final long @Nullable [] below,
        final long @Nullable [] above
    ) {
        // safe to edit in place, the parsed section is dropped after this encode and neighbours read the masks
        final PalettedContainer<BlockState> states = self.getStates();
        int buried = 0;

        // only an opaque block can be buried, so the non-opaque bits are skipped outright
        for (int word = 0; word < MASK_WORDS; ++word) {
            long bits = opaque[word];
            while (bits != 0L) {
                final int shift = Long.numberOfTrailingZeros(bits);
                bits &= bits - 1L;

                final int index = (word << 6) | shift;
                if (!isBuried(opaque, below, above, index)) {
                    continue;
                }

                // skips the per block bookkeeping in setBlockState, recalcBlockCounts does it once below
                states.getAndSetUnchecked(index & 15, (index >> 8) & 15, (index >> 4) & 15, AIR);
                ++buried;
            }
        }

        if (buried == 0) {
            return self;
        }
        if (buried == SECTION_VOLUME) {
            return null;
        }

        self.canvas$removeNonEmptyBlocks(buried);
        return self.hasOnlyAir() ? null : self;
    }

    // unknown neighbours in the next column count as opaque, so a border block is culled rather than leaked
    private static boolean isBuried(final long[] opaque, final long @Nullable [] below, final long @Nullable [] above, final int index) {
        final int x = index & 15;
        final int z = (index >> 4) & 15;
        final int y = (index >> 8) & 15;

        if ((x > 0 && !opaqueAt(opaque, index - 1)) || (x < 15 && !opaqueAt(opaque, index + 1))) {
            return false;
        }
        if ((z > 0 && !opaqueAt(opaque, index - 16)) || (z < 15 && !opaqueAt(opaque, index + 16))) {
            return false;
        }

        if (y > 0) {
            if (!opaqueAt(opaque, index - 256)) {
                return false;
            }
        } else if (!opaqueAt(below, index + (15 << 8))) {
            return false;
        }

        if (y < 15) {
            return opaqueAt(opaque, index + 256);
        }
        return opaqueAt(above, index - (15 << 8));
    }

    // one per encode thread, keeps the memo across columns without locking
    private static final class Occlusion {

        private static final ThreadLocal<Occlusion> CACHED = new ThreadLocal<>();

        private final Reference2BooleanOpenHashMap<BlockState> memo = new Reference2BooleanOpenHashMap<>(256);
        private final boolean lavaOccludes;

        private Occlusion(final boolean lavaOccludes) {
            this.lavaOccludes = lavaOccludes;
        }

        private static Occlusion forLevel(final ServerLevel level) {
            final boolean lavaOccludes = level.paperConfig().anticheat.antiXray.lavaObscures;
            final Occlusion cached = CACHED.get();
            if (cached != null && cached.lavaOccludes == lavaOccludes) {
                return cached;
            }

            final Occlusion fresh = new Occlusion(lavaOccludes);
            CACHED.set(fresh);
            return fresh;
        }

        private boolean occludes(final BlockState state) {
            if (state.isAir()) {
                return false;
            }
            if (this.memo.containsKey(state)) {
                return this.memo.getBoolean(state);
            }

            final boolean occludes = this.compute(state);
            this.memo.put(state, occludes);
            return occludes;
        }

        private boolean compute(final BlockState state) {
            // comparing by identity with the default state matches Anti-Xray, only source lava obscures
            if (this.lavaOccludes && state == Blocks.LAVA.defaultBlockState()) {
                return true;
            }
            if (!state.isSolidRender()) {
                return false;
            }

            // mirrors ChunkPacketBlockControllerAntiXray#solidGlobal, the intersection keeps hollowing at least as strict
            return state.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                && !state.is(Blocks.SPAWNER) && !state.is(Blocks.BARRIER) && !state.is(Blocks.SHULKER_BOX)
                && !state.is(Blocks.SLIME_BLOCK) && !state.is(Blocks.MANGROVE_ROOTS);
        }
    }

    // mirrors ClientboundLevelChunkPacketData#HEIGHTMAPS_STREAM_CODEC. LodChunkData already dropped the heightmaps
    // the client never receives, so every entry here goes out
    private static void writeHeightmaps(final RegistryFriendlyByteBuf buf, final Map<Heightmap.Types, long[]> heightmaps) {
        buf.writeVarInt(heightmaps.size());
        for (final Map.Entry<Heightmap.Types, long[]> entry : heightmaps.entrySet()) {
            Heightmap.Types.STREAM_CODEC.encode(buf, entry.getKey());
            ByteBufCodecs.LONG_ARRAY.encode(buf, entry.getValue());
        }
    }

    // a column that was never lit renders pitch black, so only accept it if it actually carries light
    private static boolean isSendable(final LodChunkData data) {
        final ChunkStatus status = data.status();
        if ((status != null && status.isOrAfter(ChunkStatus.LIGHT)) || data.lightCorrect()) {
            return true;
        }

        for (int i = 0, len = data.skyLight().length; i < len; ++i) {
            if (hasLight(data.skyLight()[i]) || hasLight(data.blockLight()[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLight(final @Nullable DataLayer layer) {
        return layer != null && !layer.isEmpty();
    }

    private static byte[] emptySectionBytes(final PalettedContainerFactory factory) {
        final byte[] cached = EMPTY_SECTION_BYTES;
        if (cached != null && EMPTY_SECTION_FACTORY == factory) {
            return cached;
        }

        synchronized (LodChunkEncoder.class) {
            final byte[] recheck = EMPTY_SECTION_BYTES;
            if (recheck != null && EMPTY_SECTION_FACTORY == factory) {
                return recheck;
            }

            final ByteBuf bytes = Unpooled.buffer(32);
            final byte[] encoded;
            try {
                new LevelChunkSection(factory).write(new FriendlyByteBuf(bytes), null, 0);
                encoded = new byte[bytes.readableBytes()];
                bytes.readBytes(encoded);
            } finally {
                bytes.release();
            }

            EMPTY_SECTION_FACTORY = factory;
            EMPTY_SECTION_BYTES = encoded;
            return encoded;
        }
    }

    private static final class LightMasks {

        private final BitSet skyYMask;
        private final BitSet blockYMask;
        private final BitSet emptySkyYMask;
        private final BitSet emptyBlockYMask;
        private final List<byte[]> skyUpdates = new ArrayList<>();
        private final List<byte[]> blockUpdates = new ArrayList<>();
        private final boolean sendLight;
        private final boolean sendBlockLight;

        private LightMasks(final int size, final boolean sendLight, final boolean sendBlockLight) {
            this.sendLight = sendLight;
            this.sendBlockLight = sendBlockLight;
            this.skyYMask = new BitSet(size);
            this.blockYMask = new BitSet(size);
            this.emptySkyYMask = new BitSet(size);
            this.emptyBlockYMask = new BitSet(size);
        }

        private void markEmpty(final int index) {
            this.emptySkyYMask.set(index);
            this.emptyBlockYMask.set(index);
        }

        private void add(final int index, final @Nullable DataLayer sky, final @Nullable DataLayer block) {
            if (!this.sendLight) {
                this.markEmpty(index);
                return;
            }

            if (hasLight(sky)) {
                this.skyYMask.set(index);
                this.skyUpdates.add(sized(sky));
            } else {
                this.emptySkyYMask.set(index);
            }

            if (this.sendBlockLight && hasLight(block)) {
                this.blockYMask.set(index);
                this.blockUpdates.add(sized(block));
            } else {
                this.emptyBlockYMask.set(index);
            }
        }

        // no clone, the layer is dropped with the parsed data once the buffer is written
        private static byte[] sized(final DataLayer layer) {
            final byte[] data = layer.getData();
            if (data.length == LIGHT_LAYER_BYTES) {
                return data;
            }
            final byte[] resized = new byte[LIGHT_LAYER_BYTES];
            System.arraycopy(data, 0, resized, 0, Math.min(LIGHT_LAYER_BYTES, data.length));
            return resized;
        }

        private void write(final FriendlyByteBuf buf) {
            buf.writeBitSet(this.skyYMask);
            buf.writeBitSet(this.blockYMask);
            buf.writeBitSet(this.emptySkyYMask);
            buf.writeBitSet(this.emptyBlockYMask);
            buf.writeCollection(this.skyUpdates, (final FriendlyByteBuf out, final byte[] bytes) -> out.writeByteArray(bytes));
            buf.writeCollection(this.blockUpdates, (final FriendlyByteBuf out, final byte[] bytes) -> out.writeByteArray(bytes));
        }
    }
}
