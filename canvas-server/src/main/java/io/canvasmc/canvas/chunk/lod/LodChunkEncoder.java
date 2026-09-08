package io.canvasmc.canvas.chunk.lod;

import ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encodes region file NBT straight to packet bytes, never building a
 * {@link net.minecraft.world.level.chunk.LevelChunk} and so never touching the chunk system.
 */
@NullMarked
public final class LodChunkEncoder {

    /** No section is truncated. */
    public static final int NO_CUTOFF = Integer.MIN_VALUE;

    static final int SECTION_HEIGHT = 16;
    private static final int SECTION_VOLUME = LodSectionCodec.BLOCK_ENTRIES;
    private static final int ROWS = SECTION_VOLUME >> 4;
    private static final int ROW_MASK = 0xFFFF;
    private static final int LIGHT_LAYER_BYTES = 2048;

    private static final Logger LOGGER = LoggerFactory.getLogger("CanvasLOD");

    // starlight save states, see SWMRNibbleArray
    private static final int LIGHT_STATE_UNKNOWN = -1;
    private static final int LIGHT_STATE_UNINIT = 1;
    private static final int LIGHT_STATE_HIDDEN = 3;

    private static final int LIGHT_NONE = 0;
    private static final int LIGHT_EMPTY = 1;
    private static final int LIGHT_DATA = 2;

    // outside the world below the build limit counts as opaque
    private static final int[] ALL_OPAQUE = new int[ROWS];
    static {
        Arrays.fill(ALL_OPAQUE, ROW_MASK);
    }

    private static final VarHandle LIGHT_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private static final ThreadLocal<Section[]> SECTIONS = new ThreadLocal<>();
    private static final ThreadLocal<ByteBuf> SECTION_BYTES = ThreadLocal.withInitial(() -> Unpooled.buffer(4096));
    private static final ThreadLocal<ByteBuf> PACKET_BYTES = ThreadLocal.withInitial(() -> Unpooled.buffer(8192));
    private static final ThreadLocal<LightMasks> LIGHTS = ThreadLocal.withInitial(() -> new LightMasks(32, true, true));

    private static volatile boolean loggedFailure;

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
        try {
            return encodeColumn(level, tag, pos, cutoffY, hollow, sendLight, sendBlockLight);
        } catch (final Throwable thrown) {
            // a single corrupt column must not take down the LOD ring
            if (!loggedFailure) {
                loggedFailure = true;
                LOGGER.warn("Failed to encode LOD column {}, further failures are silent", pos, thrown);
            }
            return null;
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

    private static byte @Nullable [] encodeColumn(
        final ServerLevel level,
        final CompoundTag tag,
        final ChunkPos pos,
        final int cutoffY,
        final boolean hollow,
        final boolean sendLight,
        final boolean sendBlockLight
    ) {
        final String statusName = tag.getStringOr("Status", "");
        if (statusName.isEmpty()) {
            return null;
        }

        final ChunkStatus status = Objects.requireNonNullElse(ChunkStatus.byName(statusName), ChunkStatus.EMPTY);
        final int sectionCount = level.getSectionsCount();
        final Section[] sections = sections(sectionCount);

        if (!readSections(level, tag, sections, sectionCount, cutoffY, hollow)) {
            return null;
        }
        if (!isSendable(status, sections, sectionCount)) {
            return null;
        }

        final ByteBuf sectionBytes = SECTION_BYTES.get().clear();
        final ByteBuf packetBytes = PACKET_BYTES.get().clear();
        final LightMasks lights = LIGHTS.get().reset(sectionCount + 2, sendLight, sendBlockLight);
        final FriendlyByteBuf sectionBuf = new FriendlyByteBuf(sectionBytes);

        if (!writeSections(sectionBuf, level, sections, sectionCount, hollow, lights)) {
            return null;
        }

        final RegistryFriendlyByteBuf dataBuf = new RegistryFriendlyByteBuf(packetBytes, level.registryAccess());
        dataBuf.writeInt(pos.x());
        dataBuf.writeInt(pos.z());
        writeHeightmaps(dataBuf, heightmaps(tag, status));
        dataBuf.writeVarInt(sectionBytes.readableBytes());
        dataBuf.writeBytes(sectionBytes);
        dataBuf.writeVarInt(0); // block entities are sent on promotion instead
        lights.write(dataBuf);

        final byte[] encoded = new byte[packetBytes.readableBytes()];
        packetBytes.readBytes(encoded);
        return encoded;
    }

    private static boolean readSections(
        final ServerLevel level,
        final CompoundTag tag,
        final Section[] sections,
        final int sectionCount,
        final int cutoffY,
        final boolean hollow
    ) {
        for (int i = 0; i < sectionCount; ++i) {
            sections[i].reset();
        }

        final PalettedContainerFactory factory = level.palettedContainerFactory();
        final Registry<Biome> biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        final IdMap<Holder<Biome>> biomeIds = factory.biomeStrategy().globalMap();
        final LodSectionCodec codec = LodSectionCodec.get();
        final boolean lavaOccludes = hollow && level.paperConfig().anticheat.antiXray.lavaObscures;

        final ListTag sectionTags = tag.getListOrEmpty("sections");
        for (int i = 0, len = sectionTags.size(); i < len; ++i) {
            final CompoundTag sectionTag = sectionTags.getCompound(i).orElse(null);
            if (sectionTag == null) {
                continue;
            }

            final int index = level.getSectionIndexFromSectionY(sectionTag.getByteOr("Y", (byte) 0));
            if (index < 0 || index >= sectionCount) {
                continue;
            }

            final Section section = sections[index];
            section.skyLight = sectionTag.getByteArray("SkyLight").orElse(null);
            section.skyState = sectionTag.getIntOr(SaveUtil.SKYLIGHT_STATE_TAG, LIGHT_STATE_UNKNOWN);
            section.blockLight = sectionTag.getByteArray("BlockLight").orElse(null);
            section.blockState = sectionTag.getIntOr(SaveUtil.BLOCKLIGHT_STATE_TAG, LIGHT_STATE_UNKNOWN);

            final CompoundTag biomes = sectionTag.getCompound("biomes").orElse(null);
            section.hasBiomes = biomes != null && codec.readBiomes(biomes, biomeRegistry, biomeIds, section.biomes);

            // a truncated section goes out as air, so its blocks are never read
            section.truncated = (level.getSectionYFromSectionIndex(index) * SECTION_HEIGHT) + (SECTION_HEIGHT - 1) < cutoffY;
            final CompoundTag blockStates = section.truncated ? null : sectionTag.getCompound("block_states").orElse(null);
            if (blockStates == null) {
                continue;
            }
            if (!codec.readBlockStates(blockStates, section.blocks)) {
                return false;
            }

            section.present = true;
            section.decode(hollow, lavaOccludes);
        }

        return true;
    }

    private static boolean writeSections(
        final FriendlyByteBuf out,
        final ServerLevel level,
        final Section[] sections,
        final int sectionCount,
        final boolean hollow,
        final LightMasks lights
    ) {
        final PalettedContainerFactory factory = level.palettedContainerFactory();
        final int defaultBiome = factory.biomeStrategy().globalMap().getId(factory.defaultBiome());

        if (hollow) {
            for (int index = 0; index < sectionCount; ++index) {
                sections[index].hollow(
                    index > 0 ? sections[index - 1].opaque : ALL_OPAQUE,
                    index + 1 < sectionCount ? sections[index + 1].opaque : null
                );
            }
        }

        final int skyOmitFrom = fullSkyRun(sections, sectionCount);

        boolean anyVisible = false;
        for (int index = 0; index < sectionCount; ++index) {
            final Section section = sections[index];
            final int lightIndex = index + 1; // light masks start one section below the world
            final boolean empty = section.truncated || !section.present || section.nonEmpty == 0;

            if (empty) {
                section.writeEmpty(out, defaultBiome);
            } else {
                section.write(out, defaultBiome);
                anyVisible = true;
            }

            // a section hollowing or the cutoff emptied was fully buried, so its light was already zero
            if (section.truncated || (empty && section.present)) {
                lights.markEmpty(lightIndex);
            } else {
                lights.add(lightIndex, section, index >= skyOmitFrom);
            }
        }

        return anyVisible;
    }

    private static boolean isSendable(final ChunkStatus status, final Section[] sections, final int sectionCount) {
        // a column that was never lit renders pitch black, so only accept it if it actually carries light
        if (status.isOrAfter(ChunkStatus.LIGHT)) {
            return true;
        }

        for (int i = 0; i < sectionCount; ++i) {
            if (hasLight(sections[i].skyLight) || hasLight(sections[i].blockLight)) {
                return true;
            }
        }
        return false;
    }

    private static Map<Heightmap.Types, long[]> heightmaps(final CompoundTag tag, final ChunkStatus status) {
        // only the heightmaps the packet carries, the rest are never written to the wire
        final EnumMap<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        tag.getCompound("Heightmaps").ifPresent(compound -> {
            for (final Heightmap.Types type : status.heightmapsAfter()) {
                if (type.sendToClient()) {
                    compound.getLongArray(type.getSerializationKey()).ifPresent(data -> heightmaps.put(type, data));
                }
            }
        });
        return heightmaps;
    }

    // mirrors ClientboundLevelChunkPacketData#HEIGHTMAPS_STREAM_CODEC
    private static void writeHeightmaps(final RegistryFriendlyByteBuf buf, final Map<Heightmap.Types, long[]> heightmaps) {
        buf.writeVarInt(heightmaps.size());
        for (final Map.Entry<Heightmap.Types, long[]> entry : heightmaps.entrySet()) {
            Heightmap.Types.STREAM_CODEC.encode(buf, entry.getKey());
            ByteBufCodecs.LONG_ARRAY.encode(buf, entry.getValue());
        }
    }

    /**
     * Index of the lowest section in the run of full sky light reaching the top of the column, or the section count
     * when there is none.
     * <p>
     * Sky light above terrain is 2048 bytes of {@code 0xFF} per section and is most of an LOD packet. Sending no bit
     * leaves the client's own storage to answer 15 above the highest section it was given data for, which is what
     * vanilla already relies on wherever the light engine has no nibble. The run must be unbroken from the top, or
     * that highest section lands below real terrain and the column goes dark.
     */
    private static int fullSkyRun(final Section[] sections, final int sectionCount) {
        int from = sectionCount;
        for (int index = sectionCount - 1; index >= 0; --index) {
            final Section section = sections[index];
            final byte[] sky = section.skyLight;
            if (sky == null) {
                if (lightKind(null, section.skyState) != LIGHT_NONE) {
                    break;
                }
            } else if (!isFull(sky)) {
                break;
            }
            from = index;
        }
        return from;
    }

    static boolean isFull(final byte[] light) {
        if (light.length != LIGHT_LAYER_BYTES) {
            return false;
        }
        for (int offset = 0; offset < LIGHT_LAYER_BYTES; offset += Long.BYTES) {
            if ((long) LIGHT_VIEW.get(light, offset) != -1L) {
                return false;
            }
        }
        return true;
    }

    // a block is buried when every neighbour hides it. a face on this column's edge is exposed: we do not have
    // the next column, and treating that face as cover is what punched holes in mountain slopes
    static int coveredRow(final int[] opaque, final int row, final int @Nullable [] below, final int @Nullable [] above) {
        final int self = opaque[row];
        if (self == 0) {
            return 0;
        }

        final int z = row & 15;
        final int y = row >> 4;

        return self
            & (self << 1) & (self >>> 1)
            & (z > 0 ? opaque[row - 1] : 0)
            & (z < 15 ? opaque[row + 1] : 0)
            & (y > 0 ? opaque[row - 16] : (below == null ? 0 : below[ROWS - 16 + z]))
            & (y < 15 ? opaque[row + 16] : (above == null ? 0 : above[z]));
    }

    private static Section[] sections(final int sectionCount) {
        Section[] sections = SECTIONS.get();
        if (sections == null || sections.length < sectionCount) {
            sections = new Section[sectionCount];
            for (int i = 0; i < sectionCount; ++i) {
                sections[i] = new Section();
            }
            SECTIONS.set(sections);
        }
        return sections;
    }

    private static boolean hasLight(final byte @Nullable [] data) {
        if (data == null) {
            return false;
        }
        for (final byte value : data) {
            if (value != 0) {
                return true;
            }
        }
        return false;
    }

    private static int lightKind(final byte @Nullable [] data, final int state) {
        // matches SWMRNibbleArray#toVanillaNibble: a hidden or absent nibble sends no bit at all, so the client keeps
        // its own value rather than being told the section is dark
        if (state == LIGHT_STATE_HIDDEN) {
            return LIGHT_NONE;
        }
        if (data == null) {
            return state == LIGHT_STATE_UNKNOWN ? LIGHT_NONE : LIGHT_EMPTY;
        }
        return hasLight(data) ? LIGHT_DATA : LIGHT_EMPTY;
    }

    private static final class Section {

        private final LodSectionCodec.Container blocks = new LodSectionCodec.Container();
        private final LodSectionCodec.Container biomes = new LodSectionCodec.Container();
        private final int[] values = new int[SECTION_VOLUME];
        private final int[] biomeValues = new int[LodSectionCodec.BIOME_ENTRIES];
        private final int[] opaque = new int[ROWS];

        private int[] paletteFlags = new int[16];

        private boolean present;
        private boolean truncated;
        private boolean hasBiomes;
        private boolean modified;
        private int nonEmpty;
        private int fluids;

        private byte @Nullable [] skyLight;
        private byte @Nullable [] blockLight;
        private int skyState;
        private int blockState;

        private void reset() {
            this.present = false;
            this.truncated = false;
            this.hasBiomes = false;
            this.modified = false;
            this.nonEmpty = 0;
            this.fluids = 0;
            this.skyLight = null;
            this.blockLight = null;
            this.skyState = LIGHT_STATE_UNKNOWN;
            this.blockState = LIGHT_STATE_UNKNOWN;
            Arrays.fill(this.opaque, 0);
        }

        private void decode(final boolean hollow, final boolean lavaOccludes) {
            final int paletteSize = this.blocks.paletteSize;
            if (this.paletteFlags.length < paletteSize) {
                this.paletteFlags = new int[paletteSize];
            }

            int occluding = 0;
            int airEntries = 0;
            int fluidEntries = 0;
            for (int i = 0; i < paletteSize; ++i) {
                int flags = LodSectionCodec.stateFlags(this.blocks.palette[i]);
                if (lavaOccludes && LodSectionCodec.isLava(this.blocks.palette[i])) {
                    flags |= LodSectionCodec.FLAG_OCCLUDES;
                }
                this.paletteFlags[i] = flags;
                if ((flags & LodSectionCodec.FLAG_AIR) != 0) {
                    ++airEntries;
                }
                if ((flags & LodSectionCodec.FLAG_FLUID) != 0) {
                    ++fluidEntries;
                }
                if (hollow && (flags & LodSectionCodec.FLAG_OCCLUDES) != 0) {
                    ++occluding;
                }
            }

            if (airEntries == paletteSize) {
                this.nonEmpty = 0;
                this.fluids = fluidEntries == 0 ? 0 : SECTION_VOLUME;
                if (hollow) {
                    Arrays.fill(this.opaque, 0);
                }
                return;
            }

            final boolean countsFromPalette = airEntries == 0 && (fluidEntries == 0 || fluidEntries == paletteSize);
            if (countsFromPalette) {
                this.nonEmpty = SECTION_VOLUME;
                this.fluids = fluidEntries == 0 ? 0 : SECTION_VOLUME;
            }

            if (hollow) {
                Arrays.fill(this.opaque, occluding == 0 ? 0 : (occluding == paletteSize ? ROW_MASK : 0));
            }

            final boolean maskBlocks = hollow && occluding != 0 && occluding != paletteSize;
            // nothing occludes, so nothing can be buried and the values are never read
            final boolean store = this.blocks.globalPalette || (hollow && occluding != 0);
            if (countsFromPalette && !maskBlocks) {
                if (store) {
                    this.blocks.unpack(this.values);
                }
                return;
            }

            final int counted = this.blocks.scan(
                this.values,
                store,
                this.paletteFlags,
                !countsFromPalette,
                maskBlocks ? this.opaque : null
            );
            if (!countsFromPalette) {
                this.nonEmpty = counted >>> 16;
                this.fluids = counted & 0xFFFF;
            }
        }

        private void hollow(final int @Nullable [] below, final int @Nullable [] above) {
            if (!this.present || this.nonEmpty == 0) {
                return;
            }

            final int[] opaque = this.opaque;
            int airLocal = -1;
            int buried = 0;

            for (int row = 0; row < ROWS; ++row) {
                int covered = coveredRow(opaque, row, below, above);

                while (covered != 0) {
                    final int x = Integer.numberOfTrailingZeros(covered);
                    covered &= covered - 1;

                    final int index = (row << 4) | x;
                    if (airLocal < 0) {
                        airLocal = this.blocks.add(LodSectionCodec.airStateId());
                    }
                    if ((this.paletteFlags[this.values[index]] & LodSectionCodec.FLAG_FLUID) != 0) {
                        --this.fluids;
                    }
                    this.values[index] = airLocal;
                    ++buried;
                }
            }

            if (buried == 0) {
                return;
            }

            this.modified = true;
            this.nonEmpty -= buried;
        }

        private void write(final FriendlyByteBuf out, final int defaultBiome) {
            out.writeShort(this.nonEmpty);
            out.writeShort(this.fluids);

            if (this.modified || this.blocks.globalPalette) {
                this.blocks.writeRepacked(out, this.values);
            } else {
                this.blocks.writeVerbatim(out);
            }

            this.writeBiomes(out, defaultBiome);
        }

        private void writeEmpty(final FriendlyByteBuf out, final int defaultBiome) {
            out.writeShort(0);
            out.writeShort(0);
            LodSectionCodec.writeSingleValue(out, LodSectionCodec.airStateId());
            this.writeBiomes(out, defaultBiome);
        }

        // an emptied section still carries its biomes, they drive the client's grass, foliage and water tint
        private void writeBiomes(final FriendlyByteBuf out, final int defaultBiome) {
            if (!this.hasBiomes) {
                LodSectionCodec.writeSingleValue(out, defaultBiome);
                return;
            }

            if (this.biomes.globalPalette) {
                this.biomes.unpack(this.biomeValues);
                this.biomes.writeRepacked(out, this.biomeValues);
            } else {
                this.biomes.writeVerbatim(out);
            }
        }
    }

    private static final class LightMasks {

        private final BitSet skyYMask;
        private final BitSet blockYMask;
        private final BitSet emptySkyYMask;
        private final BitSet emptyBlockYMask;
        private final List<byte[]> skyUpdates = new ArrayList<>();
        private final List<byte[]> blockUpdates = new ArrayList<>();
        private boolean sendLight;
        private boolean sendBlockLight;

        private LightMasks(final int size, final boolean sendLight, final boolean sendBlockLight) {
            this.sendLight = sendLight;
            this.sendBlockLight = sendBlockLight;
            this.skyYMask = new BitSet(size);
            this.blockYMask = new BitSet(size);
            this.emptySkyYMask = new BitSet(size);
            this.emptyBlockYMask = new BitSet(size);
        }

        private LightMasks reset(final int size, final boolean sendLight, final boolean sendBlockLight) {
            this.sendLight = sendLight;
            this.sendBlockLight = sendBlockLight;
            this.skyYMask.clear();
            this.blockYMask.clear();
            this.emptySkyYMask.clear();
            this.emptyBlockYMask.clear();
            this.skyUpdates.clear();
            this.blockUpdates.clear();
            if (this.skyYMask.size() < size) {
                this.skyYMask.set(size, false);
                this.blockYMask.set(size, false);
                this.emptySkyYMask.set(size, false);
                this.emptyBlockYMask.set(size, false);
                this.skyYMask.clear();
                this.blockYMask.clear();
                this.emptySkyYMask.clear();
                this.emptyBlockYMask.clear();
            }
            return this;
        }

        private void markEmpty(final int index) {
            this.emptySkyYMask.set(index);
            this.emptyBlockYMask.set(index);
        }

        private void add(final int index, final Section section, final boolean omitSky) {
            if (!this.sendLight) {
                this.markEmpty(index);
                return;
            }

            switch (omitSky ? LIGHT_NONE : lightKind(section.skyLight, section.skyState)) {
                case LIGHT_DATA -> {
                    this.skyYMask.set(index);
                    this.skyUpdates.add(sized(section.skyLight));
                }
                case LIGHT_EMPTY -> this.emptySkyYMask.set(index);
                default -> {
                }
            }

            if (!this.sendBlockLight) {
                this.emptyBlockYMask.set(index);
                return;
            }

            switch (lightKind(section.blockLight, section.blockState)) {
                case LIGHT_DATA -> {
                    this.blockYMask.set(index);
                    this.blockUpdates.add(sized(section.blockLight));
                }
                case LIGHT_EMPTY -> this.emptyBlockYMask.set(index);
                default -> {
                }
            }
        }

        // no clone, the array is dropped with the section tag once the buffer is written
        private static byte[] sized(final byte[] data) {
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
