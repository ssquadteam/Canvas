package io.canvasmc.canvas.chunk.lod;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    private static final ThreadLocal<Section[]> SECTIONS = new ThreadLocal<>();

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
        if (tag.getString("Status").isEmpty()) {
            return null;
        }

        final ChunkStatus status = tag.read("Status", ChunkStatus.CODEC).orElse(ChunkStatus.EMPTY);
        final int sectionCount = level.getSectionsCount();
        final Section[] sections = sections(sectionCount);

        if (!readSections(level, tag, sections, sectionCount, cutoffY)) {
            return null;
        }
        if (!isSendable(status, sections, sectionCount)) {
            return null;
        }

        final ByteBuf sectionBytes = Unpooled.buffer(4096);
        final ByteBuf packetBytes = Unpooled.buffer(8192);
        try {
            final FriendlyByteBuf sectionBuf = new FriendlyByteBuf(sectionBytes);
            final LightMasks lights = new LightMasks(sectionCount + 2, sendLight, sendBlockLight);

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
        } finally {
            sectionBytes.release();
            packetBytes.release();
        }
    }

    private static boolean readSections(final ServerLevel level, final CompoundTag tag, final Section[] sections, final int sectionCount, final int cutoffY) {
        for (int i = 0; i < sectionCount; ++i) {
            sections[i].reset();
        }

        final PalettedContainerFactory factory = level.palettedContainerFactory();
        final Registry<Biome> biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        final IdMap<Holder<Biome>> biomeIds = factory.biomeStrategy().globalMap();
        final LodSectionCodec codec = LodSectionCodec.get();

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
            section.skyState = sectionTag.getIntOr(
                ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.SKYLIGHT_STATE_TAG, LIGHT_STATE_UNKNOWN
            );
            section.blockLight = sectionTag.getByteArray("BlockLight").orElse(null);
            section.blockState = sectionTag.getIntOr(
                ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.BLOCKLIGHT_STATE_TAG, LIGHT_STATE_UNKNOWN
            );

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
            section.decode();
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
            final boolean lavaOccludes = level.paperConfig().anticheat.antiXray.lavaObscures;
            for (int index = 0; index < sectionCount; ++index) {
                sections[index].fillOpaque(lavaOccludes);
            }

            for (int index = 0; index < sectionCount; ++index) {
                sections[index].hollow(
                    index > 0 ? sections[index - 1].opaque : ALL_OPAQUE,
                    index + 1 < sectionCount ? sections[index + 1].opaque : null
                );
            }
        }

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
                lights.add(lightIndex, section);
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

    // a block is buried when every neighbour hides it, and a neighbour in the next column counts as cover rather than
    // leaking the block. one 16 bit x row at a time, so the six tests are six ands
    static int coveredRow(final int[] opaque, final int row, final int @Nullable [] below, final int @Nullable [] above) {
        final int self = opaque[row];
        if (self == 0) {
            return 0;
        }

        final int z = row & 15;
        final int y = row >> 4;

        return self
            & ((self << 1) | 1) & ((self >>> 1) | 0x8000)
            & (z > 0 ? opaque[row - 1] : ROW_MASK)
            & (z < 15 ? opaque[row + 1] : ROW_MASK)
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

        private void decode() {
            final int paletteSize = this.blocks.paletteSize;
            if (this.paletteFlags.length < paletteSize) {
                this.paletteFlags = new int[paletteSize];
            }
            for (int i = 0; i < paletteSize; ++i) {
                this.paletteFlags[i] = LodSectionCodec.stateFlags(this.blocks.palette[i]);
            }

            this.blocks.unpack(this.values);

            if (paletteSize == 1) {
                final int flags = this.paletteFlags[0];
                this.nonEmpty = (flags & LodSectionCodec.FLAG_AIR) != 0 ? 0 : SECTION_VOLUME;
                this.fluids = (flags & LodSectionCodec.FLAG_FLUID) != 0 ? SECTION_VOLUME : 0;
                return;
            }

            int nonEmpty = 0;
            int fluids = 0;
            for (int i = 0; i < SECTION_VOLUME; ++i) {
                final int flags = this.paletteFlags[this.values[i]];
                if ((flags & LodSectionCodec.FLAG_AIR) == 0) {
                    ++nonEmpty;
                }
                if ((flags & LodSectionCodec.FLAG_FLUID) != 0) {
                    ++fluids;
                }
            }
            this.nonEmpty = nonEmpty;
            this.fluids = fluids;
        }

        // one bit per block, set when the block hides whatever is behind it, packed as a 16 bit x row per (y, z)
        private void fillOpaque(final boolean lavaOccludes) {
            if (this.truncated || !this.present || this.nonEmpty == 0) {
                Arrays.fill(this.opaque, 0);
                return;
            }

            final int paletteSize = this.blocks.paletteSize;
            int occluding = 0;
            for (int i = 0; i < paletteSize; ++i) {
                if (this.occludes(i, lavaOccludes)) {
                    ++occluding;
                }
            }

            if (occluding == 0) {
                Arrays.fill(this.opaque, 0);
                return;
            }
            if (occluding == paletteSize) {
                Arrays.fill(this.opaque, ROW_MASK);
                return;
            }

            Arrays.fill(this.opaque, 0);
            for (int i = 0; i < SECTION_VOLUME; ++i) {
                if (this.occludes(this.values[i], lavaOccludes)) {
                    this.opaque[i >> 4] |= 1 << (i & 15);
                }
            }
        }

        // comparing by identity with the default state matches Anti-Xray, only source lava obscures
        private boolean occludes(final int local, final boolean lavaOccludes) {
            return (this.paletteFlags[local] & LodSectionCodec.FLAG_OCCLUDES) != 0
                || (lavaOccludes && LodSectionCodec.isLava(this.blocks.palette[local]));
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

        private void add(final int index, final Section section) {
            if (!this.sendLight) {
                this.markEmpty(index);
                return;
            }

            switch (lightKind(section.skyLight, section.skyState)) {
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
